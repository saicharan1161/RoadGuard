'use strict';
// No npm dependencies. All car traffic is pinned TLS; the dashboard admin API is loopback-only.
const https=require('node:https'), tls=require('node:tls'), fs=require('node:fs'), path=require('node:path');
const crypto=require('node:crypto'), net=require('node:net'), {pipeline}=require('node:stream/promises');
const release=require('./release.json');
const {startMap}=require('./map-server.cjs');
const hash=x=>crypto.createHash('sha256').update(x).digest('hex');
const safeEqual=(a,b)=>{
  if(typeof a!=='string'||typeof b!=='string')return false;
  const left=Buffer.from(a),right=Buffer.from(b);
  return left.length===right.length&&crypto.timingSafeEqual(left,right);
};
function endpoint(value) {
  const [host,rawPort,...rest]=String(value).split(':'); const port=rawPort?Number(rawPort):8943;
  if(rest.length || net.isIP(host)!==4 || ![8943,8944].includes(port) || !allowed(host)) throw Error('Enter a local or private VPN IPv4:8943 for the car');
  return {host,port};
}
class PinnedAgent extends https.Agent {
  constructor(pin) { super({keepAlive:true,maxSockets:6,maxFreeSockets:2,timeout:60000}); this.pin=pin; }
  createConnection(options,callback) {
    const socket=tls.connect({...options,rejectUnauthorized:false,minVersion:'TLSv1.2'});
    let done=false;
    const finish=(error,result)=>{if(!done){done=true;callback(error,result);}};
    socket.once('secureConnect',()=>{
      socket.setTimeout(0);
      if(!safeEqual(hash(socket.getPeerCertificate().raw),this.pin)) { const e=Error('Car certificate changed; pair again');e.code='ROADGUARD_PIN_CHANGED'; finish(e);socket.destroy(); }
      else finish(null,socket);
    });
    socket.once('error',e=>finish(e));
    socket.setTimeout(12000,()=>socket.destroy(Error('Car connection timed out')));
    return undefined;
  }
}
const carAgents=new Map();
function agentFor(car) {
  const key=car.host+'|'+car.pin;
  if(!carAgents.has(key)) {
    if(carAgents.size>=8){const oldest=carAgents.keys().next().value;carAgents.get(oldest).destroy();carAgents.delete(oldest);}
    carAgents.set(key,new PinnedAgent(car.pin));
  }
  return carAgents.get(key);
}
function carRequest(car,route,headers={},method='GET') {
  const ep=endpoint(car.host);
  return new Promise((resolve,reject)=>{
    const req=https.request({...ep,path:route,method,agent:agentFor(car),
      headers:{'X-RoadGuard-App':release.version,...(car.token?{Authorization:`Bearer ${car.token}`} : {}),...headers}},resolve);
    req.setTimeout(12000,()=>req.destroy(Error('Car connection timed out')));
    req.on('error',reject); req.end();
  });
}
async function jsonFrom(response) {
  const data=[];let n=0;
  for await(const chunk of response) { n+=chunk.length; if(n>4*1024*1024) throw Error('Car response too large'); data.push(chunk); }
  if(response.statusCode!==200) throw Object.assign(Error(`Car returned ${response.statusCode}: ${Buffer.concat(data).toString().slice(0,200)}`),{status:response.statusCode});
  return JSON.parse(Buffer.concat(data));
}
async function probe(host) {
  const ep=endpoint(host);
  return new Promise((resolve,reject)=>{
    // Identity probe only: no token/code sent until user compares fingerprint.
    const req=https.get({...ep,path:'/identity',rejectUnauthorized:false,agent:false,timeout:6000},async res=>{
      try { const pin=hash(res.socket.getPeerCertificate().raw); const data=await jsonFrom(res); resolve({...data,pin}); } catch(e) { reject(e); }
    }); req.on('error',reject);req.on('timeout',()=>req.destroy(Error('Car unavailable')));
  });
}
function atomic(file,value) { fs.writeFileSync(file+'.tmp',JSON.stringify(value,null,2)); fs.renameSync(file+'.tmp',file); }
async function body(req) {
  let s='';for await(const b of req){s+=b;if(s.length>8192)throw Error('Request too large');}
  return s?JSON.parse(s):{};
}
function allowed(address) {
  const ip=address.replace(/^::ffff:/,''); if(ip==='::1'||ip==='127.0.0.1') return true;
  if(net.isIP(ip)!==4)return false;
  const a=ip.split('.').map(Number);
  return a[0]===10 || a[0]===192&&a[1]===168 || a[0]===172&&a[1]>=16&&a[1]<=31 || a[0]===100&&a[1]>=64&&a[1]<=127;
}
async function start(dataDir) {
  const configFile=path.join(dataDir,'config.json');
  const config=JSON.parse(fs.readFileSync(configFile));
  const cert=fs.readFileSync(path.join(dataDir,'server.cer'));
  const pin=hash(cert);
  let pairGate=null, busyBackup=false, lastError='', lastSeen=0, location=null, clips=[], pollFlight=null,activeLive=0;
  function prioritizeLive(res) {activeLive++;let closed=false;res.on('close',()=>{if(!closed){closed=true;activeLive--;}});}
  const phoneStreams=new Set();
  const detailCache=new Map();let probes=0;
  const closePhoneStreams=()=>{for(const stream of phoneStreams)stream.destroy();phoneStreams.clear();};
  let backupMessage='Backups are off until enabled';
  const backupRoot=path.join(dataDir,'Recordings');fs.mkdirSync(backupRoot,{recursive:true});
  const indexFile=path.join(dataDir,'backups.json');
  let backups=fs.existsSync(indexFile)?JSON.parse(fs.readFileSync(indexFile)):[];
  const save=()=>atomic(configFile,config);
  const audit=event=>{
    const file=path.join(dataDir,'access.log');
    if(fs.existsSync(file)&&fs.statSync(file).size>1024*1024)fs.renameSync(file,path.join(dataDir,'access.previous.log'));
    fs.appendFileSync(file,`${new Date().toISOString()} ${event}\n`);
  };
  async function pollOnce() {
    if(!config.car)return;
    const car=config.car;
    try {
      const identity=await jsonFrom(await carRequest(car,'/identity'));
      if(identity.app!==release.version)throw Error(`Version mismatch: car ${identity.app}; PC ${release.version}. Update both.`);
      const data=await jsonFrom(await carRequest(car,'/recordings'));
      if(config.car!==car)return;
      clips=data.recordings;config.camera=data.camera;config.carStatus=data.status;lastSeen=Date.now();lastError='';
      try {
        const next=await jsonFrom(await carRequest(car,'/location'));
        if(config.car===car)location={...next,receivedAt:Date.now()};
      } catch(e) { if(config.car===car)location={enabled:false,message:'Location unavailable: '+e.message}; }
    } catch(e) { if(config.car===car)lastError=e.message; }
  }
  async function poll() {
    if(pollFlight)return pollFlight;
    pollFlight=pollOnce();
    try {await pollFlight;} finally {pollFlight=null;}
  }
  async function backup() {
    if(busyBackup||!config.car)return;
    if(activeLive>0){backupMessage='Backup paused to prioritize live video; resumes automatically if enabled';return;}
    busyBackup=true;
    try {
      await poll(); if(lastError)throw Error(lastError);
      const car={...config.car};const dir=path.join(backupRoot,car.pin.slice(0,16));fs.mkdirSync(dir,{recursive:true});
      let used=fs.readdirSync(backupRoot,{recursive:true}).reduce((n,f)=>{const p=path.join(backupRoot,f);return n+(fs.statSync(p).isFile()?fs.statSync(p).size:0);},0);
      const budget=(config.backupGB||10)*1024**3;
      for(const clip of clips.filter(c=>!c.active)) {
        if(activeLive>0){backupMessage='Backup paused to prioritize live video; partial copies retained';return;}
        if(backups.some(x=>x.id===clip.id&&x.carPin===car.pin&&fs.existsSync(path.join(dataDir,x.file))))continue;
        if(!/^[a-f0-9]{64}$/.test(clip.id)||!Number.isSafeInteger(clip.bytes)||clip.bytes<=0)continue;
        const name=String(clip.name).replace(/[^a-zA-Z0-9_.-]/g,'_').slice(0,120);
        const final=path.join(dir,`${clip.id.slice(0,12)}_${name}`), partial=final+'.part';
        let offset=fs.existsSync(partial)?fs.statSync(partial).size:0;
        if(offset>clip.bytes)throw Error('Partial backup larger than source; inspect PC backups');
        if(used+clip.bytes-offset>budget) {backupMessage='Backup size limit reached; existing copies retained';return;}
        const space=fs.statfsSync(dir);if(space.bavail*space.bsize<clip.bytes-offset+1024**3)throw Error('PC disk reserve: less than video size plus 1 GB available');
        if(offset<clip.bytes) {
          const res=await carRequest(car,`/video?id=${clip.id}`,{'X-RoadGuard-Background-Backup':'1',...(offset?{Range:`bytes=${offset}-`}:{})});
          if(res.statusCode!==(offset?206:200) || offset && res.headers['content-range']!==`bytes ${offset}-${clip.bytes-1}/${clip.bytes}`) {res.destroy();throw Error('Backup source changed or range rejected');}
          let received=offset;
          res.on('data',chunk=>{received+=chunk.length;if(activeLive>0)res.destroy(Error('Backup paused for live video; partial copy will resume'));else if(received>clip.bytes)res.destroy(Error('Video exceeds declared size'));});
          backupMessage=`Backing up ${name}`;
          await pipeline(res,fs.createWriteStream(partial,{flags:offset?'a':'w'}));
          if(received!==clip.bytes)throw Error('Backup interrupted; partial copy will resume');
        }
        const fd=fs.openSync(partial,'r+');fs.fsyncSync(fd);fs.closeSync(fd);fs.renameSync(partial,final);
        backups=backups.filter(x=>!(x.id===clip.id&&x.carPin===car.pin));
        backups.push({...clip,carPin:car.pin,file:path.relative(dataDir,final),savedAt:Date.now()});atomic(indexFile,backups);
        used+=clip.bytes-offset;audit('backup completed '+clip.id.slice(0,12));
      }
      backupMessage='Completed clips backed up';
    } catch(e) {backupMessage=e.message;} finally {busyBackup=false;}
  }
  function reply(res,status,value) {res.writeHead(status,{'Content-Type':'application/json','Cache-Control':'no-store'});res.end(JSON.stringify(value));}
  let mapBridge=null,mapBridgeFlight=null,mapFlight=null,mapLastRequest=0,mapLastRefresh=-5000,mapCached=null;
  const monotonic=()=>Number(process.hrtime.bigint()/1000000n);
  async function mapLocation(refresh) {
    if(!config.car)throw Error('Pair the car first');
    if(mapFlight)return mapFlight;
    refresh=refresh&&monotonic()-mapLastRefresh>=5000;
    if(!refresh&&mapCached&&monotonic()-mapLastRequest<5000)return {...mapCached,ageMs:typeof mapCached.ageMs==='number'?mapCached.ageMs+Math.max(0,monotonic()-mapLastRequest):null};
    if(refresh)mapLastRefresh=monotonic();
    const car=config.car;
    mapFlight=(async()=>{
      const identity=await jsonFrom(await carRequest(car,'/identity'));
      if(identity.app!==release.version)throw Object.assign(Error('Install matching car and PC versions'),{status:409});
      const next=await jsonFrom(await carRequest(car,refresh?'/location?refresh=1':'/location'));
      if(config.car!==car)throw Error('Car pairing changed');
      mapCached={...next,testMode:config.testMode===true};mapLastRequest=monotonic();
      location={...next,receivedAt:Date.now()};
      return mapCached;
    })();
    try{return await mapFlight;}finally{mapFlight=null;}
  }
  const server=https.createServer({pfx:fs.readFileSync(path.join(dataDir,'server.pfx')),passphrase:config.pfxPassword,minVersion:'TLSv1.2'},async(req,res)=>{
    try {
      if(!allowed(req.socket.remoteAddress||''))return reply(res,403,{error:'Private network only'});
      const url=new URL(req.url,'https://localhost');const route=url.pathname;
      const bearer=(req.headers.authorization||'').replace(/^Bearer /,'');
      const local=['127.0.0.1','::1','::ffff:127.0.0.1'].includes(req.socket.remoteAddress);
      if(route.startsWith('/admin/')) {
        if(!local||!safeEqual(bearer,config.adminToken))return reply(res,403,{error:'Local dashboard only'});
        if(route==='/admin/live.avc'&&req.method==='GET') {
          if(!config.car)throw Error('Pair the car first');
          prioritizeLive(res);
          const upstream=await carRequest(config.car,'/live.avc');
          if(upstream.statusCode!==200) {upstream.destroy();throw Error('Car live video unavailable');}
          res.writeHead(200,{'Content-Type':'application/x-roadguard-avc','Cache-Control':'no-store'});
          res.on('close',()=>upstream.destroy());await pipeline(upstream,res);return;
        }
        if(route==='/admin/status'&&req.method==='GET')return reply(res,200,{
          testMode:config.testMode===true,
          app:release.version,protocol:release.protocol,pin,car:config.car?{host:config.car.host,pin:config.car.pin}:null,
          online:!!config.car&&Date.now()-lastSeen<20000&&!lastError,lastSeen,error:lastError,location,
          recordings:clips,camera:config.camera,status:config.carStatus,backupMessage,backups,
          backupEnabled:!!config.backupEnabled,backupGB:config.backupGB||10,pairedPhones:(config.phones||[]).length});
        if(req.method!=='POST')return reply(res,405,{error:'POST required'});
        const data=await body(req);
        if(route==='/admin/map-session') {
          if(!mapBridge) {
            if(!mapBridgeFlight)mapBridgeFlight=startMap(mapLocation);
            try {mapBridge=await mapBridgeFlight;}finally{mapBridgeFlight=null;}
          }
          return reply(res,200,{url:mapBridge.session()});
        }
        if(route==='/admin/probe')return reply(res,200,await probe(data.host));
        if(route==='/admin/pair-car') {
          if(busyBackup)throw Error('Wait for the current backup to finish before changing cars');
          if(pollFlight)await pollFlight;
          endpoint(data.host);if(!/^[a-f0-9]{64}$/.test(data.pin)||!/^\d{8}$/.test(data.code))throw Error('Check car address, pin and 8-digit code');
          const car={host:data.host,pin:data.pin};
          const identity=await jsonFrom(await carRequest(car,'/identity'));
          if(identity.app!==release.version||identity.kind!=='car')throw Error('Install matching car APK '+release.version);
          const result=await jsonFrom(await carRequest(car,'/pair',{'X-Pair-Code':data.code,'X-RoadGuard-App':release.version},'POST'));
          if(!/^[a-f0-9]{64}$/.test(result.token))throw Error('Invalid car token');
          if(config.car?.pin!==car.pin) {config.phones=[];closePhoneStreams();}
          if(mapBridge)mapBridge.revoke();mapCached=null;
          config.car={...car,token:result.token};save();lastSeen=0;location=null;clips=[];await poll();audit('car paired');
          return reply(res,200,{ok:true});
        }
        if(route==='/admin/pair-code') {
          if(!config.car)throw Error('Pair the car first');
          pairGate={code:String(crypto.randomInt(0,100000000)).padStart(8,'0'),until:Date.now()+120000,attempts:0};
          const addresses=Object.values(require('node:os').networkInterfaces()).flat().filter(x=>x.family==='IPv4'&&!x.internal&&allowed(x.address)).map(x=>x.address+':8944').join(', ');
          return reply(res,200,{code:pairGate.code,pin,expiresSeconds:120,addresses});
        }
        if(route==='/admin/revoke') {config.phones=[];save();pairGate=null;closePhoneStreams();return reply(res,200,{ok:true});}
        if(route==='/admin/backup-settings') {
          const size=Number(data.gb);if(!Number.isInteger(size)||size<1||size>2000)throw Error('Choose 1–2000 GB');
          config.backupEnabled=data.enabled===true;config.backupGB=size;save();return reply(res,200,{ok:true});
        }
        if(route==='/admin/backup-now') {void backup();return reply(res,200,{ok:true});}
        if(route==='/admin/refresh') {
          await poll();
          const car=config.car;
          if(car&&!lastError)try {
            const next=await jsonFrom(await carRequest(car,'/location?refresh=1'));
            if(config.car===car)location={...next,receivedAt:Date.now()};
          } catch(e) {if(config.car===car)location={enabled:false,message:'Location refresh unavailable: '+e.message};}
          return reply(res,200,{ok:true});
        }
        return reply(res,404,{error:'Not found'});
      }
      if(route==='/identity'&&req.method==='GET')return reply(res,200,{name:'RoadGuard Windows',kind:'server',app:release.version,protocol:release.protocol,carPin:config.car?.pin||''});
      if(route==='/pair'&&req.method==='POST') {
        if(req.headers['x-roadguard-app']!==release.version)return reply(res,409,{error:'Update PC and APK to '+release.version});
        if(!pairGate||Date.now()>pairGate.until||pairGate.attempts++>=5||!safeEqual(req.headers['x-pair-code'],pairGate.code))return reply(res,403,{error:'Wrong or expired code'});
        pairGate=null;config.phones=config.phones||[];
        if(config.phones.length>=8)return reply(res,403,{error:'Revoke old phone access on PC first'});
        const token=crypto.randomBytes(32).toString('hex');config.phones.push(hash(token));save();audit('phone paired');
        return reply(res,200,{token,carPin:config.car?.pin});
      }
      if(!(config.phones||[]).some(x=>safeEqual(hash(bearer),x)))return reply(res,401,{error:'Pair phone with Windows server first'});
      if(req.headers['x-roadguard-app']&&req.headers['x-roadguard-app']!==release.version)return reply(res,409,{error:'Update APK and PC to '+release.version});
      phoneStreams.add(res);res.on('close',()=>phoneStreams.delete(res));
      if(!config.car)return reply(res,503,{error:'No car configured'});
      if(lastError.startsWith('Version mismatch'))return reply(res,409,{error:lastError});
      if(!['/recordings','/location','/video','/details','/live.avc','/live.jpg','/protect'].includes(route))return reply(res,404,{error:'Not found'});
      if(req.method!==(route==='/protect'?'POST':'GET'))return reply(res,405,{error:'Method not allowed'});
      if(route==='/live.avc')prioritizeLive(res);
      const headers={};if(req.headers.range)headers.Range=req.headers.range;
      if(route==='/protect')headers['X-Keep-Recording']=req.headers['x-keep-recording']||'';
      const saved=backups.find(x=>x.carPin===config.car.pin&&x.id===url.searchParams.get('id'));
      if(route==='/details'&&saved) {
        const file=path.resolve(dataDir,saved.file);
        if(!file.startsWith(path.resolve(backupRoot)+path.sep))throw Error('Invalid backup path');
        if(fs.existsSync(file)){
          const stat=fs.statSync(file),key=file+'|'+stat.size+'|'+stat.mtimeMs;
          if(detailCache.has(key))return reply(res,200,detailCache.get(key));
          if(probes>=2)return reply(res,503,{error:'Clip metadata busy; try again'});
          probes++;try{
            const text=await new Promise((ok,fail)=>require('node:child_process').execFile(path.join(__dirname,'ffprobe.exe'),
              ['-v','error','-protocol_whitelist','file','-select_streams','v:0','-show_entries','stream=duration,width,height','-of','json',file],
              {timeout:15000,windowsHide:true,maxBuffer:65536},(e,out)=>e?fail(e):ok(out)));
            const s=JSON.parse(text).streams[0],durationMs=Math.round(Number(s.duration)*1000);
            if(!Number.isSafeInteger(durationMs)||durationMs<1)throw Error('Unknown recording duration');
            const info={durationMs,width:s.width,height:s.height};detailCache.set(key,info);
            if(detailCache.size>1024)detailCache.delete(detailCache.keys().next().value);return reply(res,200,info);
          }finally{probes--;}
        }
      }
      if(route==='/video'&&saved) {
        const file=path.resolve(dataDir,saved.file);
        if(!file.startsWith(path.resolve(backupRoot)+path.sep))throw Error('Invalid backup path');
        if(fs.existsSync(file)) {
          const size=fs.statSync(file).size;
          const match=req.headers.range?/^bytes=(\d+)-(\d*)$/.exec(req.headers.range):null;
          if(req.headers.range&&!match)return reply(res,416,{error:'Invalid range'});
          const begin=match?Number(match[1]):0,end=match&&match[2]?Number(match[2]):size-1;
          if(!Number.isSafeInteger(begin)||!Number.isSafeInteger(end)||begin<0||end<begin||end>=size)return reply(res,416,{error:'Invalid range'});
          res.writeHead(match?206:200,{'Content-Type':'video/mp4','Content-Length':end-begin+1,'Accept-Ranges':'bytes',
            ...(match?{'Content-Range':`bytes ${begin}-${end}/${size}`}:{})});
          audit(`${hash(bearer).slice(0,8)} backup video`);
          await pipeline(fs.createReadStream(file,{start:begin,end}),res);return;
        }
      }
      if(route==='/recordings'&&(lastError||Date.now()-lastSeen>20000)) {
        return reply(res,200,{status:'Car offline — PC backups only',camera:'No live camera',liveAvailable:false,
          recordings:backups.filter(x=>x.carPin===config.car.pin&&fs.existsSync(path.join(dataDir,x.file)))
            .map(({file,carPin,...entry})=>({...entry,active:false,backup:true}))});
      }
      if(route==='/location'&&(lastError||Date.now()-lastSeen>20000))return reply(res,200,location?
        {...location,ageMs:(location.ageMs||0)+Date.now()-(location.receivedAt||Date.now()),fresh:false,message:'Car offline — last known position, not live'}:
        {message:'Car offline; no location available',enabled:false});
      const upstream=await carRequest(config.car,url.pathname+url.search,headers,req.method);
      const responseHeaders={'Cache-Control':'no-store'};
      for(const key of ['content-type','content-length','content-range','accept-ranges','x-roadguard-stream-version'])if(upstream.headers[key])responseHeaders[key]=upstream.headers[key];
      res.writeHead(upstream.statusCode,responseHeaders);
      res.on('close',()=>upstream.destroy());
      if(route==='/live.avc'||route==='/video')audit(`${hash(bearer).slice(0,8)} ${route}`);
      await pipeline(upstream,res);
    } catch(e) { if(!res.headersSent)reply(res,503,{error:e.message});else res.destroy(); }
  });
  server.requestTimeout=15000;server.headersTimeout=10000;server.maxConnections=16;
  await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(config.port||8944,config.bind||'0.0.0.0',resolve);});
  let polling=false;
  // Restore the saved pairing immediately after a PC/server restart, without a UI click.
  void poll();
  const timer=setInterval(async()=>{if(polling)return;polling=true;try{await poll();if(config.backupEnabled)await backup();}finally{polling=false;}},15000);
  return {server,close:()=>{clearInterval(timer);if(mapBridge)mapBridge.close();closePhoneStreams();server.closeAllConnections();server.close();for(const agent of carAgents.values())agent.destroy();carAgents.clear();},pin};
}
module.exports={start,endpoint,allowed,hash,safeEqual,carRequest};
if(require.main===module)start(process.argv[2]||path.join(process.env.ProgramData,'RoadGuard')).catch(e=>{console.error(e.message);process.exit(1);});

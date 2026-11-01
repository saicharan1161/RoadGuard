'use strict';
const assert=require('node:assert/strict'),https=require('node:https'),fs=require('node:fs'),path=require('node:path');
const {start,hash,allowed,endpoint,carRequest}=require('./server.cjs');
const dir=process.argv[2], version=require('./release.json').version;
const pfx=fs.readFileSync(path.join(dir,'server.pfx')),pin=hash(fs.readFileSync(path.join(dir,'server.cer')));
const admin='a'.repeat(64),id='b'.repeat(64),video=process.env.RG_TEST_VIDEO?fs.readFileSync(process.env.RG_TEST_VIDEO):Buffer.from('sample-video-for-range-tests');
let backupHeader='';
let validVersion=true,carAvailable=true,cutBackup=false,lastRange='',videoCalls=0,locationRefreshes=0,holdLive=false,handshakes=0;
let secondClip=false,heldTransfer=null,identityRequests=0;
const json=(res,code,data)=>{res.writeHead(code,{'Content-Type':'application/json'});res.end(JSON.stringify(data));};
async function main() {
 assert(allowed('::ffff:100.80.1.2'));assert(!allowed('8.8.8.8'));assert.throws(()=>endpoint('localhost'));assert.throws(()=>endpoint('8.8.8.8'));
 fs.writeFileSync(path.join(dir,'config.json'),JSON.stringify({testMode:true,pfxPassword:'test-only-password',adminToken:admin,bind:'127.0.0.1',port:18944,phones:[]}));
 const fake=https.createServer({pfx,passphrase:'test-only-password'},(req,res)=>{
  if(!carAvailable)return json(res,503,{error:'Simulated car offline'});
  if(req.url==='/identity'){identityRequests++;return json(res,200,{app:validVersion?version:'wrong',kind:'car'});}
  if(req.url==='/pair')return json(res,200,{token:'c'.repeat(64)});
  if(req.headers.authorization!=='Bearer '+'c'.repeat(64))return json(res,401,{});
  if(req.url==='/recordings'){
    const first={id,name:'2026-10-20_Tuesday_10-00-00-001.mp4',bytes:video.length,modified:1000,active:false};
    return json(res,200,{camera:'Test camera',status:'Recording',recordings:secondClip?[first,{...first,id:'d'.repeat(64),name:'2026-10-21_Wednesday_10-00-00-001.mp4'}]:[first]});
  }
  if(req.url.startsWith('/location')) {
   if(req.url==='/location?refresh=1')locationRefreshes++;
   return json(res,200,{enabled:true,latitude:12.3,longitude:77.5,ageMs:0,fresh:true,accuracyMeters:8,place:'Test district',message:'Current GPS fix'});
  }
  if(req.url==='/live.avc') {res.writeHead(200,{'X-RoadGuard-Stream-Version':'1'});res.write(Buffer.from([0,0,0,3,0,0,0,0]));if(!holdLive)res.end();return;}
  if(req.url.startsWith('/video?')) {
   videoCalls++;lastRange=req.headers.range||'';backupHeader=req.headers['x-roadguard-background-backup']||'';
   let begin=0,end=video.length-1;
   if(lastRange){const m=/bytes=(\d+)-(\d*)/.exec(lastRange);begin=Number(m[1]);if(m[2])end=Number(m[2]);}
   res.writeHead(lastRange?206:200,{'Content-Type':'video/mp4','Content-Length':end-begin+1,...(lastRange?{'Content-Range':`bytes ${begin}-${end}/${video.length}`}:{})});
   if(secondClip&&req.url.endsWith('d'.repeat(64))){res.flushHeaders();heldTransfer=res;return;}
   if(cutBackup){cutBackup=false;res.write(video.subarray(begin,begin+4));setTimeout(()=>res.destroy(),30);return;}
   return res.end(video.subarray(begin,end+1));
  }
  json(res,404,{});
 });
 fake.on('secureConnection',()=>handshakes++);
 await new Promise(r=>fake.listen(8943,'127.0.0.1',r));
 let app=await start(dir);
 async function call(route,data,token=admin,headers={}) {
  const response=await new Promise((resolve,reject)=>{
   const req=https.request({host:'127.0.0.1',port:18944,path:route,method:data?'POST':'GET',rejectUnauthorized:false,agent:false,
    headers:{Authorization:'Bearer '+token,...headers}},resolve);req.on('error',reject);req.end(data?JSON.stringify(data):undefined);
  });
  const chunks=[];for await(const b of response)chunks.push(b);
  return {status:response.statusCode,data:Buffer.concat(chunks),headers:response.headers,json(){return JSON.parse(this.data);}};
 }
 try {
  assert.equal((await call('/admin/status',null,'bad')).status,403);
  assert.equal((await call('/recordings',null,'bad')).status,401);
  await assert.rejects(carRequest({host:'127.0.0.1',pin:'0'.repeat(64)},'/identity'));
  let r=await call('/admin/probe',{host:'127.0.0.1'});assert.equal(r.json().pin,pin);
  r=await call('/admin/pair-car',{host:'127.0.0.1',pin,code:'12345678'});assert.equal(r.status,200);
  r=await call('/admin/status');assert.equal(r.json().online,true);assert.equal(r.json().location.latitude,12.3);
  const beforeReuse=handshakes;
  for(let i=0;i<2;i++){const identity=await carRequest({host:'127.0.0.1',pin},'/identity');for await(const chunk of identity){} }
  assert(handshakes-beforeReuse<=1,'Sequential pinned requests should reuse their TLS connection');
  assert.equal((await call('/admin/map-session',{},'bad')).status,403);
  const mapUrl=new URL((await call('/admin/map-session',{})).json().url);
  const mapResponse=await fetch(mapUrl.origin+'/location',{headers:{Authorization:'Bearer '+mapUrl.hash.slice(1)}});
  assert.equal(mapResponse.status,200);assert.equal((await mapResponse.json()).latitude,12.3);
  const code=(await call('/admin/pair-code',{})).json().code;
  assert.equal((await call('/pair',{},'',{'X-RoadGuard-App':'old','X-Pair-Code':code})).status,409);
  r=await call('/pair',{},'',{'X-RoadGuard-App':version,'X-Pair-Code':code});const token=r.json().token;assert.equal(token.length,64);
  assert.equal((await call('/pair',{},'',{'X-RoadGuard-App':version,'X-Pair-Code':code})).status,403);
  r=await call('/video?id='+id,null,token,{Range:'bytes=3-8'});assert.equal(r.status,206);assert.deepEqual(r.data,video.subarray(3,9));
  r=await call('/live.avc',null,token);assert.equal(r.headers['x-roadguard-stream-version'],'1');assert.equal(r.data.length,8);
  holdLive=true;
  const held=await new Promise((resolve,reject)=>{const request=https.get({host:'127.0.0.1',port:18944,path:'/admin/live.avc',rejectUnauthorized:false,agent:false,headers:{Authorization:'Bearer '+admin}},resolve);request.on('error',reject);});
  held.on('error',()=>{});held.resume();
  const beforeLiveBackup=videoCalls;
  await call('/admin/backup-now',{});
  assert.match((await call('/admin/status')).json().backupMessage,/paused.*live/i);
  assert.equal(videoCalls,beforeLiveBackup,'Live view must prevent backup bandwidth competition');
  cutBackup=true;
  const phoneLive=await new Promise((resolve,reject)=>{https.get({host:'127.0.0.1',port:18944,path:'/live.avc',rejectUnauthorized:false,agent:false,headers:{Authorization:'Bearer '+token}},resolve).on('error',reject);});
  assert.equal(phoneLive.statusCode,200);phoneLive.on('error',()=>{});phoneLive.resume();
  assert.equal((await call('/admin/save-clip',{id})).status,202);
  await new Promise(r=>setTimeout(r,250));
  let status=(await call('/admin/status')).json();assert.equal(status.backups.length,0);
  assert.equal((await call('/admin/save-clip',{id})).status,202);
  await new Promise(r=>setTimeout(r,350));
  status=(await call('/admin/status')).json();assert.equal(status.backups.length,1);assert.equal(lastRange,'bytes=4-');
  assert.equal(backupHeader,'1','Background reads must use the car pacing path');
  assert.deepEqual(fs.readFileSync(path.join(dir,status.backups[0].file)),video);
  held.destroy();phoneLive.destroy();holdLive=false;
  const callsBefore=videoCalls;
  r=await call('/video?id='+id,null,token,{Range:'bytes=5-9'});assert.deepEqual(r.data,video.subarray(5,10));assert.equal(videoCalls,callsBefore);
  assert.equal((await call('/video?id='+id,null,token,{Range:`bytes=${video.length+999}-`})).status,416);
  carAvailable=false;await call('/admin/refresh',{});
  assert.equal((await call('/admin/status')).json().online,false);
  r=await call('/recordings',null,token);assert.equal(r.json().liveAvailable,false);assert.equal(r.json().recordings[0].backup,true);
  r=await call('/location',null,token);assert.equal(r.json().fresh,false);assert.match(r.json().message,/offline/);
  r=await call('/video?id='+id,null,token);assert.deepEqual(r.data,video);assert.equal(videoCalls,callsBefore);
  if(process.env.RG_TEST_VIDEO){
    r=await call('/details?timeline=1&id='+id,null,token);assert.equal(r.status,200);
    assert.equal(r.json().width,640);assert.equal(r.json().height,480);assert.equal(r.json().durationMs,5000);
    assert.deepEqual((await call('/details?timeline=1&id='+id,null,token)).json(),r.json(),'Cached offline metadata must remain available');
  }
  carAvailable=true;await call('/admin/refresh',{});
  assert.equal((await call('/admin/status')).json().online,true);
  const refreshesBefore=locationRefreshes;
  await call('/admin/refresh',{});assert.equal(locationRefreshes,refreshesBefore+1);
  await call('/location?refresh=1',null,token);assert.equal(locationRefreshes,refreshesBefore+2);
  assert.equal((await call('/recordings',null,token,{'X-RoadGuard-App':'old'})).status,409);
  const stopped=new Promise(resolve=>app.server.once('close',resolve));app.close();await stopped;
  const configPath=path.join(dir,'config.json');
  const moved=JSON.parse(fs.readFileSync(configPath));
  const localIp=Object.values(require('node:os').networkInterfaces()).flat().find(n=>n.family==='IPv4'&&!n.internal&&allowed(n.address)&&!n.address.startsWith('100.'));
  moved.car.host=localIp?localIp.address:'127.0.0.1:8944';fs.writeFileSync(configPath,JSON.stringify(moved));
  app=await start(dir,200);
  const beacon=require('node:dgram').createSocket('udp4');
  try {
    for(let attempt=0;attempt<30;attempt++) {
      const info=(await call('/admin/status')).json();
      if(info.online)break;
      await new Promise((done,fail)=>beacon.send(Buffer.from('ROADGUARD-CAR-1'),8944,'127.0.0.1',e=>e?fail(e):done()));
      await new Promise(done=>setTimeout(done,100));
    }
  } finally{beacon.close();}
  assert.equal((await call('/admin/status')).json().car.host,'127.0.0.1','Recover previously pinned car at its new LAN address');
  for(let attempt=0;attempt<30;attempt++) {
   if((await call('/admin/status')).json().online)break;
   await new Promise(resolve=>setTimeout(resolve,100));
  }
  assert.equal((await call('/admin/status')).json().online,true,'Saved car must reconnect after server restart');
  assert.equal((await call('/recordings',null,token)).status,200,'Saved phone token must survive server restart');
  secondClip=true;
  await call('/admin/backup-settings',{enabled:true,gb:1});
  for(let i=0;i<30&&!heldTransfer;i++)await new Promise(done=>setTimeout(done,50));
  assert(heldTransfer,'Automatic backup must be running');
  const pollsDuringTransfer=identityRequests;
  await new Promise(done=>setTimeout(done,700));
  assert(identityRequests>pollsDuringTransfer,'Health checks must continue during an automatic backup');
  heldTransfer.end(video);secondClip=false;
  await call('/admin/backup-settings',{enabled:false,gb:1});
  if(process.env.RG_HOLD_UI_TEST==='1') {
    console.log('UI fixture ready: '+dir);
    await new Promise(()=>{});
  }
  validVersion=false;await call('/admin/refresh',{});assert.equal((await call('/recordings',null,token)).status,409);
  await call('/admin/revoke',{});assert.equal((await call('/location',null,token)).status,401);
  console.log('PASS real TLS pin rejection, admin authentication, car pairing, version gate, single-use phone code, authenticated map location bridge, video/live relay, range reads, interrupted backup resume, offline-copy reads and revocation');
 } finally {app.close();fake.closeAllConnections();fake.close();}
}
main().catch(e=>{console.error(e);process.exitCode=1;});

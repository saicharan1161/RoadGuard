'use strict';
// Read-only map bridge. No car/admin credentials are exposed to the browser.
const http=require('node:http'),crypto=require('node:crypto'),fs=require('node:fs'),path=require('node:path');
const assets=new Map([['/','map.html'],['/map.html','map.html'],['/map.js','map.js'],['/map-state.js','map-state.js'],['/map.css','map.css'],['/leaflet.js','leaflet.js'],['/leaflet.css','leaflet.css']]);
const mime={'.html':'text/html','.js':'application/javascript','.css':'text/css'};
async function startMap(readLocation,root=path.join(__dirname,'live-map')) {
  const sessions=new Map();let origin='';
  const server=http.createServer(async(req,res)=>{
    const send=(code,data,type='application/json')=>{res.writeHead(code,{'Content-Type':type,'Cache-Control':'no-store'});res.end(data);};
    res.setHeader('X-Content-Type-Options','nosniff');res.setHeader('Referrer-Policy','strict-origin-when-cross-origin');
    res.setHeader('Content-Security-Policy',"default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src https://tile.openstreetmap.org data:; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'");
    if(req.headers.host!==origin.slice(7) || req.headers.origin&&req.headers.origin!==origin)return send(403,'{}');
    if(req.method!=='GET')return send(405,'{}');
    const url=new URL(req.url,origin);
    if(url.pathname==='/location') {
      const token=(req.headers.authorization||'').replace(/^Bearer /,'');
      if(!sessions.has(token)||sessions.get(token)<Date.now()){sessions.delete(token);return send(401,JSON.stringify({message:'Reopen Live map from RoadGuard.'}));}
      try {return send(200,JSON.stringify(await readLocation(url.searchParams.get('refresh')==='1')));}
      catch(e){return send(e.code==='ROADGUARD_PIN_CHANGED'?403:[401,403,409].includes(e.status)?e.status:503,JSON.stringify({message:'Car location unavailable'}));}
    }
    const file=assets.get(url.pathname);if(!file)return send(404,'{}');
    try {send(200,fs.readFileSync(path.join(root,file)),mime[path.extname(file)]+'; charset=utf-8');}
    catch(e){send(503,JSON.stringify({message:'Map assets missing; reinstall matching Windows package.'}));}
  });
  server.requestTimeout=10000;server.headersTimeout=5000;server.maxConnections=16;
  await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(0,'127.0.0.1',resolve);});
  origin='http://127.0.0.1:'+server.address().port;
  return {
    session(){
      for(const [key,until] of sessions)if(until<Date.now())sessions.delete(key);
      if(sessions.size>=8)sessions.delete(sessions.keys().next().value);
      const token=crypto.randomBytes(32).toString('hex');sessions.set(token,Date.now()+8*60*60*1000);
      return origin+'/#'+token;
    },
    revoke(){sessions.clear();},
    close(){sessions.clear();server.closeAllConnections();server.close();}
  };
}
module.exports={startMap};

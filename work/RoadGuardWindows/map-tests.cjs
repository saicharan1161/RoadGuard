'use strict';
const assert=require('node:assert/strict'),http=require('node:http'),path=require('node:path'),vm=require('node:vm'),fs=require('node:fs');
const {startMap}=require('./map-server.cjs');
const root=path.join(__dirname,'live-map'),{view}=require(path.join(root,'map-state.js'));
const fix={enabled:true,latitude:12.3,longitude:77.5,ageMs:0,fresh:true,accuracyMeters:8,provider:'gps',place:'Simulated test',testMode:true};
async function main(){
 assert(view(fix,1000,true).live);assert(!view(fix,30001,true).live);assert(!view(fix,0,false).live);
 for(const changed of [{latitude:91},{longitude:181},{latitude:null},{latitude:'12'},{enabled:false}])assert(!view({...fix,...changed},0,true).valid);
 for(const changed of [{ageMs:null},{ageMs:-1},{ageMs:Infinity},{accuracyMeters:-1},{fresh:false}])assert(!view({...fix,...changed},0,true).live);
 assert.equal(view(fix,-1000,true).age,0);
 let refreshes=0;
 const bridge=await startMap(async refresh=>{if(refresh)refreshes++;return fix;},root);
 const url=new URL(bridge.session()),token=url.hash.slice(1),origin=url.origin;
 async function call(route,headers={},method='GET'){
  return new Promise((resolve,reject)=>{
   const req=http.request(origin+route,{headers,method},res=>{let data='';res.on('data',b=>data+=b);res.on('end',()=>resolve({status:res.statusCode,headers:res.headers,data}));});req.on('error',reject);req.end();
  });
 }
 try{
  assert.equal((await call('/location')).status,401);
  assert.equal((await call('/location',{Authorization:'Bearer wrong'})).status,401);
  const auth={Authorization:'Bearer '+token};
  assert.equal((await call('/location',{...auth,Host:'attacker.example'})).status,403);
  assert.equal((await call('/location',{...auth,Origin:'https://attacker.example'})).status,403);
  assert.equal((await call('/location',auth,'POST')).status,405);
  assert.equal((await call('/admin/status',auth)).status,404);
  assert.equal((await call('/../server.cjs',auth)).status,404);
  let result=await call('/location?refresh=1',auth);assert.equal(result.status,200);assert.equal(JSON.parse(result.data).latitude,12.3);assert.equal(refreshes,1);
  result=await call('/');assert.equal(result.status,200);assert.match(result.headers['content-security-policy'],/frame-ancestors 'none'/);assert(!result.data.includes(token));
  for(const asset of ['map.js','map-state.js','leaflet.js','leaflet.css','map.css'])assert.equal((await call('/'+asset)).status,200);
  for(let i=0;i<8;i++)bridge.session();assert.equal((await call('/location',auth)).status,401,'Bound map session count');
  const latest=new URL(bridge.session()).hash.slice(1);bridge.revoke();assert.equal((await call('/location',{Authorization:'Bearer '+latest})).status,401);
 }finally{bridge.close();}
 // Exercise the actual browser controller without real tile requests or private location data.
 const elements={};for(const id of ['status','detail','place','follow','refresh','map','tiles'])elements[id]={textContent:'',style:{}};
 let clock=0,xhr,interval,mapPoint,markerPoint,removed=false,drag,replace;
 const doc={hidden:false,getElementById:id=>elements[id],addEventListener(){}};
 const layer=()=>({on(){return this;},addTo(){return this;},setLatLng(p){markerPoint=p;return this;},setStyle(){return this;},setRadius(){return this;}});
 const fakeL={map:()=>({setView(p){mapPoint=p;return this;},on(event,fn){if(event==='dragstart')drag=fn;return this;},getCenter(){return {equals:p=>JSON.stringify(p)===JSON.stringify(mapPoint)};},panTo(p){mapPoint=p;},invalidateSize(){},remove(){removed=true;}}),latLng:p=>p,tileLayer:layer,circle:layer,circleMarker:layer};
 const context={document:doc,location:{hash:'#secret',pathname:'/'},history:{replaceState(a,b,c){replace=c;}},performance:{now:()=>clock},RoadGuardMapState:{view},L:fakeL,isFinite,JSON,Math,
  XMLHttpRequest:function(){xhr=this;this.open=()=>{};this.setRequestHeader=(k,v)=>{assert.equal(k,'Authorization');assert.equal(v,'Bearer secret');};this.send=()=>{};this.abort=()=>this.onloadend();},
  setTimeout:()=>1,clearTimeout(){},setInterval:fn=>{interval=fn;},addEventListener(){}};
 context.window=context;vm.runInNewContext(fs.readFileSync(path.join(root,'map.js'),'utf8'),context);
 assert.equal(replace,'/');
 function deliver(value){xhr.status=200;xhr.responseText=JSON.stringify(value);xhr.onload();xhr.onloadend();}
 deliver(fix);assert.match(elements.status.textContent,/SIMULATED TEST.*LIVE GPS/);assert.equal(markerPoint[0],12.3);
 deliver({...fix,latitude:12.31});assert.equal(mapPoint[0],12.31);
 drag();deliver({...fix,latitude:12.32});assert.equal(mapPoint[0],12.31);assert.equal(markerPoint[0],12.32);
 elements.follow.onclick();assert.equal(mapPoint[0],12.32);
 clock=31000;interval();assert.match(elements.status.textContent,/NOT LIVE/);
 deliver({...fix,accuracyMeters:200});assert.match(elements.status.textContent,/LOW ACCURACY/);
 xhr.status=503;xhr.onload();xhr.onloadend();assert.match(elements.status.textContent,/NOT LIVE/);
 deliver({enabled:false,message:'Location sharing off'});assert(removed);assert.match(elements.detail.textContent,/sharing off/);
 xhr.status=401;xhr.onload();xhr.onloadend();assert.match(elements.status.textContent,/session ended/);
 console.log('PASS map freshness/validation, follow movement, drag/recenter, stale and disabled GPS, auth failure, loopback Host/Origin/token restrictions, asset allowlist, session bounds and revocation');
}
main().catch(e=>{console.error(e);process.exitCode=1;});

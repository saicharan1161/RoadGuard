'use strict';
// Manual visual QA only. No car connection and no real user coordinates.
const {startMap}=require('./map-server.cjs');
startMap(async()=>({testMode:true,enabled:true,fresh:true,latitude:51.505,longitude:-0.09,accuracyMeters:18,ageMs:1000,place:'SIMULATED TEST • London — not your car'})).then(bridge=>{
 console.log(bridge.session());
 process.on('SIGINT',()=>{bridge.close();process.exit(0);});
});

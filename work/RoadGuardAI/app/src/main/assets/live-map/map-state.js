(function(root){
  'use strict';
  function number(v){return typeof v==='number' && isFinite(v);}
  function view(fix,elapsed,connected){
    fix=fix||{};
    var valid=fix.enabled!==false && number(fix.latitude) && Math.abs(fix.latitude)<=90 && number(fix.longitude) && Math.abs(fix.longitude)<=180;
    var age=number(fix.ageMs)&&fix.ageMs>=0 ? fix.ageMs+Math.max(0,elapsed) : Infinity;
    var accuracy=number(fix.accuracyMeters)&&fix.accuracyMeters>=0?fix.accuracyMeters:null;
    return {valid:valid,age:age,accuracy:accuracy,live:valid&&accuracy!==null&&connected&&fix.fresh===true&&age<=30000};
  }
  if(typeof module==='object'&&module.exports)module.exports={view:view};else root.RoadGuardMapState={view:view};
})(this);

(function(){
  'use strict';
  var token=location.hash.slice(1),last=null,received=0,connected=false,error='',map=null,marker=null,circle=null,following=true,stopped=false,request=null,timer=null,wantRefresh=false,hostPaused=false;
  // The short-lived Windows map credential never enters a request URL or tile referrer.
  if(token)history.replaceState(null,'',location.pathname);
  var status=document.getElementById('status'),detail=document.getElementById('detail'),place=document.getElementById('place'),follow=document.getElementById('follow'),refresh=document.getElementById('refresh');
  function now(){return performance.now();}
  function render(){
    var state=RoadGuardMapState.view(last,now()-received,connected);
    status.textContent=stopped?'Map session ended':state.live?(state.accuracy>50?'● LIVE • LOW ACCURACY':last.provider==='gps'?'● LIVE GPS':'● LIVE LOCATION'):state.valid?'● LAST KNOWN • NOT LIVE':'Waiting for GPS';
    if(last&&last.testMode)status.textContent='SIMULATED TEST • '+status.textContent;
    status.style.color=state.live?'#7ce3a0':'#ffc86b';
    if(!state.valid){
      detail.textContent=error||(last&&last.message)||'Enable location sharing and GPS on the car.';place.textContent='';
      if(map){map.remove();map=null;marker=null;circle=null;document.getElementById('map').innerHTML='<div id="waiting">No GPS position available.</div>';}
      return;
    }
    detail.textContent=last.latitude.toFixed(6)+', '+last.longitude.toFixed(6)+'  •  '+(state.accuracy===null?'Accuracy unknown':'Estimated accuracy ±'+Math.round(state.accuracy)+' m')+'  •  '+(isFinite(state.age)?Math.floor(state.age/1000)+'s old':'Age unknown')+'  •  Source: '+(last.provider||'unknown');
    place.textContent=error||(last.place||last.message||'Place name unavailable');
    var point=[last.latitude,last.longitude],color=state.live?'#149c6a':'#d78a20';
    if(!map){
      document.getElementById('map').textContent='';map=L.map('map').setView(point,16);
      L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap contributors</a>'}).on('tileerror',function(){document.getElementById('tiles').textContent='Map tiles unavailable. GPS coordinates still update; check internet access.';}).addTo(map);
      circle=L.circle(point,{radius:state.accuracy||0,color:color,weight:1,fillOpacity:.12}).addTo(map);
      marker=L.circleMarker(point,{radius:9,color:'#fff',weight:3,fillColor:color,fillOpacity:1}).addTo(map);
      map.on('dragstart',function(){following=false;follow.textContent='Follow car: OFF';});
      map.on('zoomend',function(){map.invalidateSize();});
    }
    marker.setLatLng(point).setStyle({fillColor:color});circle.setLatLng(point).setRadius(state.accuracy||0).setStyle({color:color});
    if(following&&!map.getCenter().equals(L.latLng(point)))map.panTo(point,{animate:false});
  }
  function poll(){
    clearTimeout(timer);if(document.hidden||hostPaused||request||stopped)return;
    var xhr=new XMLHttpRequest();request=xhr;
    xhr.open('GET','location'+(wantRefresh?'?refresh=1':''));wantRefresh=false;xhr.timeout=20000;
    if(token)xhr.setRequestHeader('Authorization','Bearer '+token);
    xhr.onload=function(){
      if(xhr.status===200){try{last=JSON.parse(xhr.responseText);received=now();connected=true;error='';}catch(e){connected=false;error='Invalid location response; retrying.';}}
      else {connected=false;error='Car connection unavailable — retrying automatically.';if(xhr.status===401||xhr.status===403||xhr.status===409){stopped=true;error='Access expired, revoked or versions differ. Return to RoadGuard and reconnect.';}}
    };
    xhr.onerror=xhr.ontimeout=function(){connected=false;error='Connection interrupted — showing last known position.';};
    xhr.onloadend=function(){request=null;refresh.disabled=stopped;render();if(!stopped&&!document.hidden&&!hostPaused)timer=setTimeout(poll,5000);};
    xhr.send();
  }
  follow.onclick=function(){following=!following;follow.textContent='Follow car: '+(following?'ON':'OFF');render();};
  refresh.onclick=function(){wantRefresh=true;refresh.disabled=true;poll();};
  document.addEventListener('visibilitychange',function(){clearTimeout(timer);if(document.hidden){connected=false;if(request)request.abort();}else{render();poll();}});
  window.roadGuardVisible=function(visible){hostPaused=!visible;clearTimeout(timer);if(!visible){connected=false;if(request)request.abort();}else{render();poll();}};
  window.addEventListener('resize',function(){if(map)map.invalidateSize();});
  setInterval(function(){if(!document.hidden)render();},1000);poll();
})();

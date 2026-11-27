"use strict";
const $=id=>document.getElementById(id);
const W=3600,H=1452, KEY="kordon.survival.v1";
const clamp=(v,a,b)=>Math.max(a,Math.min(v,b));
const dist=(a,b)=>Math.hypot(a.x-b.x,a.y-b.y);
const buildings=[
 {x:130,y:284,w:154,h:115,name:"Мастерская",kind:"garage",loot:[1,3,2,2]},
 {x:422,y:302,w:133,h:95,name:"Магазин",kind:"shop",loot:[4,4,0,1]},
 {x:540,y:610,w:135,h:100,name:"Дом лесника",kind:"home",loot:[2,4,1,3]},
 {x:1230,y:132,w:164,h:108,name:"Усадьба",kind:"farm",loot:[1,2,0,3]},...districtBuildings];
const gate={x:1110,y:412},garden={x:1250,y:312},coop={x:1380,y:315},timber={x:1000,y:551};
const breaches=[{x:1180,y:412},{x:1320,y:412}];
const rails=[[960,83,496,9],[960,83,9,330],[1448,83,9,330],[960,407,112,10],[1149,407,15,10],[1197,407,107,10],[1337,407,120,10]];
const enemyStarts=[[75,510],[350,680],[635,450],[745,565],[893,371],[1090,702],[1490,495],[380,180],[760,220],[155,808]];
function initial(){
 return {chapter:0,expanded:false,bridge:false,ending:false,stamina:100,companion:null,diary:"",p:{x:217,y:432,ang:0},car:{x:341,y:467,ang:0,hp:0},hp:100,sat:84,food:1,ammo:2,parts:0,wood:0,extra:[],generator:false,hidden:-1,drive:false,gate:false,walls:[false,false],crop:null,chick:null,hens:0,time:0,looted:[],xp:[0,0,0,0],kills:0,harvest:0,won:false,z:enemyStarts.map(([x,y],i)=>({x,y,ang:i,hp:1})),hurt:0,noise:0};
}
function load(){try{const a=JSON.parse(localStorage.getItem(KEY));if(a&&a.p&&Array.isArray(a.z)&&a.hp>0)return Object.assign(initial(),a)}catch{}return initial()}
let s=load(),paused=true,jx=0,jy=0,keys=new Set(),messageUntil=0,flash=null,fireAfter=0,savedAt=0,stepPhase=0,joyPointer=null;
function populateEast(){
 if(s.expanded)return;
 for(const [x,y] of [[1695,530],[1930,462],[2140,650],[2410,490],[2570,575],[2800,470],[2980,762],[3150,555],[3440,394],[2110,820],[2465,896]])s.z.push({x,y,ang:0,hp:1});
 s.expanded=true;
}
populateEast();
if(s.hidden>=0&&s.p.y>=buildings[s.hidden].y+buildings[s.hidden].h)Object.assign(s.p,roomExit(s.hidden));
const level=i=>1+Math.min(2,Math.floor(s.xp[i]/2));
function save(){try{localStorage.setItem(KEY,JSON.stringify(s))}catch{}}
function say(t){tone(340,.065,"sine",.025);$("toast").textContent=t;messageUntil=performance.now()+2400;$("toast").style.opacity=1}
function doors(i){let b=buildings[i];return {x:b.x+b.w/2,y:b.y+b.h+10}}
function solids(){
 let rects=[...buildings.map(b=>[b.x,b.y,b.w,b.h]),...rails,...chapterWalls()];
 if(s.gate)rects.push([1072,407,77,10]);
 breaches.forEach((v,i)=>{if(s.walls[i])rects.push([v.x-17,407,34,10])});
 for(const b of s.extra)rects.push([b.x-18,b.y-4,36,8]);
 return rects;
}
function blocked(x,y,r,rects){return x<r||y<r||x>W-r||y>H-r||rects.some(([l,t,w,h])=>x+r>l&&x-r<l+w&&y+r>t&&y-r<t+h)}
function move(a,dx,dy,r,rects){
 if(!blocked(a.x+dx,a.y,r,rects))a.x+=dx;
 if(!blocked(a.x,a.y+dy,r,rects))a.y+=dy;
}
function sight(a,b,rects){
 const n=Math.ceil(dist(a,b)/7);
 for(let i=1;i<n;i++)if(blocked(a.x+(b.x-a.x)*i/n,a.y+(b.y-a.y)*i/n,0,rects))return false;
 return true;
}
function route(){
 const objective=chapterRoute();if(objective)return objective;
 if(s.car.hp===0&&s.parts===0)return [doors(0),"Обыскать мастерскую"];
 if(s.car.hp===0)return [s.car,"Починить универсал"];
 if(!s.looted.includes(3))return [doors(3),"Добраться до усадьбы"];
 if(!s.gate)return [gate,"Закрыть ворота"];
 if(!s.walls.every(Boolean))return s.wood<1?[timber,"Набрать доски"]:[breaches[s.walls.indexOf(false)],"Заделать пролом"];
 if(!s.harvest)return [garden,s.crop===null?"Засадить грядку":"Собрать первый урожай"];
 if(!s.hens)return [coop,s.chick===null?"Накормить цыплёнка":"Вырастить курицу"];
 return [doors(3),"Кордон обжит"];
}
function options(){
 if(s.drive)return [{label:"Выйти из машины",fn:()=>{
  if(Math.abs(s.car.speed||0)>20)return say("Сначала останови машину");
  const walls=solids();
  for(let i=0;i<8;i++){let a=s.car.ang+Math.PI/2+i*Math.PI/4,x=s.car.x+Math.cos(a)*32,y=s.car.y+Math.sin(a)*32;
   if(!blocked(x,y,7,walls)){s.drive=false;s.car.speed=0;Object.assign(s.p,{x,y});return}}
  say("Подъедь к свободному месту");
 }}];
 if(s.hidden>=0)return insideActions();
 const list=[];
 function near(o,label,fn,r=46){let d=dist(s.p,o);if(d<=r)list.push({d,label,fn})}
 chapterActions(near);
 buildings.forEach((b,i)=>near(doors(i),"Войти · "+b.name,()=>{
  s.hidden=i;Object.assign(s.p,roomExit(i));say("Стеллаж с припасами у дальней стены");
 }));
 near(s.car,s.car.hp?"За руль":"Починить машину",()=>{
  if(!s.car.hp){if(!s.parts)return say("Нужна деталь из мастерской");s.parts--;s.car.hp=100;s.xp[1]++;say("Двигатель запустился")}else{if(s.companion&&dist(s.companion,s.car)>65)return say("Подожди пассажира");s.drive=true;Object.assign(s.p,s.car);s.noise=2}
 });
 near(gate,s.gate?"Открыть ворота":"Закрыть ворота",()=>{
  if(!s.gate && (carOverRect([1072,407,77,10])||[s.p,...s.z].some(v=>v.x>1065&&v.x<1155&&Math.abs(v.y-412)<13)))return say("Освободи проём ворот");
  s.gate=!s.gate;say(s.gate?"Ворота закрыты":"Ворота открыты")
 });
 breaches.forEach((b,i)=>{if(!s.walls[i])near(b,"Забор · 1 доска",()=>{
  if(!s.wood)return say("Возьми доски у штабеля на дороге");
  if(carOverRect([b.x-17,407,34,10])||[s.p,...s.z].some(v=>Math.abs(v.x-b.x)<23&&Math.abs(v.y-b.y)<13))return say("Отойди на шаг от пролома");
  s.walls[i]=true;s.wood--;s.xp[2]++;say("Пролом заделан");
 },38)});
 near({x:1204,y:223},s.generator?"Осмотреть генератор":"Починить генератор",()=>{
  if(s.generator)return say("Генератор питает освещение фермы");
  if(!s.parts)return say("Нужна одна деталь");
  s.parts--;s.generator=true;s.xp[1]++;say("На ферме появился свет");
 });
 near(timber,"Взять доски",()=>{s.wood+=2;say("Доски +2")});
 near(garden,s.crop===null?"Посадить картофель":s.time-s.crop>=35?"Собрать урожай":"Проверить грядку",()=>{
  if(s.crop===null){s.crop=s.time;say("Картофель посажен")}
  else if(s.time-s.crop>=35){s.crop=null;s.harvest++;s.food+=2+level(3);s.xp[3]++;say("Урожай собран")}
  else say("До урожая "+Math.ceil(35-s.time+s.crop)+" с");
 });
 near(coop,s.hens?"Собрать яйца":s.chick===null?"Покормить цыплёнка":"Проверить курятник",()=>{
  if(s.chick===null){if(!s.food)return say("Для корма нужна одна еда");s.food--;s.chick=s.time;say("Цыплёнок накормлен")}
  else if(s.time-s.chick>=40){s.hens=1;s.food+=2;s.chick=s.time;s.xp[3]++;say("Курица принесла яйца: еда +2")}
  else say("До яиц "+Math.ceil(40-s.time+s.chick)+" с");
 });
 return list.sort((a,b)=>a.d-b.d);
}
function interact(){if(paused)return;let a=options()[0];if(a){a.fn();save();hud()}}
function buildFence(){
 if(paused||s.drive||s.hidden>=0)return;
 if(s.p.x>2180&&s.p.x<2420)return say("На переправе нельзя строить");
 const cost=level(2)>=2?1:2;
 if(s.wood<cost)return say("Для секции нужно досок: "+cost);
 const x=Math.round((s.p.x+Math.cos(s.p.ang)*32)/4)*4,y=Math.round((s.p.y+Math.sin(s.p.ang)*32)/4)*4;
 const spots=[...buildings.map((b,i)=>doors(i)),gate,garden,coop,timber,s.car,{x:1204,y:223},...s.z];
 if(blocked(x,y,21,solids())||spots.some(p=>dist(p,{x,y})<42))return say("Перед тобой мало места для забора");
 s.extra.push({x,y});s.wood-=cost;s.xp[2]++;save();say("Секция забора установлена")
}
$("build").onclick=buildFence;
function eat(){if(paused)return;if(!s.food)return say("Еды нет");if(s.sat>=99&&s.hp>=99)return say("Ты сыт");s.food--;s.sat=Math.min(100,s.sat+36);s.hp=Math.min(100,s.hp+14);say("Еда восстановила силы");save()}
function fire(){
 if(paused||s.drive||s.hidden>=0||s.time<fireAfter)return;
 if(!s.ammo)return say("Патронов нет");
 let z=s.z.filter(v=>dist(v,s.p)<175&&sight(v,s.p,solids())).sort((a,b)=>dist(a,s.p)-dist(b,s.p))[0];
 if(!z)return say("В зоне огня нет цели");
 tone(100,.12,"sawtooth",.055);s.ammo--;s.noise=6;fireAfter=s.time+.45;flash={x:z.x,y:z.y,t:s.time+.13};s.p.ang=Math.atan2(z.y-s.p.y,z.x-s.p.x);s.z.splice(s.z.indexOf(z),1);s.kills++;s.xp[0]++;save()
}
function steer(z,dx,dy,speed,dt,rects){
 let a=Math.atan2(dy,dx);
 for(const offset of [0,.5,-.5,1,-1,1.45,-1.45]){
  let dir=a+offset,vx=Math.cos(dir),vy=Math.sin(dir);
  if(!blocked(z.x+vx*9,z.y+vy*9,6,rects)){move(z,vx*speed*dt,vy*speed*dt,6,rects);z.ang=dir;return}
 }
}
function tick(dt){
 s.time+=dt;s.hurt=Math.max(0,s.hurt-dt);s.noise=Math.max(0,s.noise-dt);
 const walls=solids();let dx=jx+Number(keys.has("KeyD")||keys.has("ArrowRight"))-Number(keys.has("KeyA")||keys.has("ArrowLeft")),dy=jy+Number(keys.has("KeyS")||keys.has("ArrowDown"))-Number(keys.has("KeyW")||keys.has("ArrowUp"));
 let n=Math.hypot(dx,dy);if(n>1){dx/=n;dy/=n}
 s.stepping=false;
 if(s.drive)driveStep(dx,dy,dt,walls);
 else if(n>.05){
  const sprint=s.hidden<0&&!s.companion&&(keys.has("ShiftLeft")||keys.has("ShiftRight")||sprintHeld)&&s.stamina>2&&!s.exhausted;
  const speed=(sprint?1.55:1)*((s.companion&&s.chapter===3?59:74)+level(0)*4),oldX=s.p.x,oldY=s.p.y;s.p.ang=Math.atan2(dy,dx);
  if(s.hidden>=0){const b=buildings[s.hidden];s.p.x=clamp(s.p.x+dx*speed*dt,b.x+9,b.x+b.w-9);s.p.y=clamp(s.p.y+dy*speed*dt,b.y+9,b.y+b.h-9)}
  else move(s.p,dx*speed*dt,dy*speed*dt,7,walls);
  s.stepping=Math.hypot(s.p.x-oldX,s.p.y-oldY)>.02;
  if(s.stepping&&sprint){s.stamina=Math.max(0,s.stamina-20*dt);s.noise=Math.max(s.noise,.2);if(s.stamina<3)s.exhausted=true}else s.stamina=Math.min(100,s.stamina+9*dt);
  stepPhase+=Math.hypot(s.p.x-oldX,s.p.y-oldY)*.18;
 }
 if(!s.stepping)s.stamina=Math.min(100,s.stamina+11*dt);if(s.stamina>25)s.exhausted=false;
 s.sat=Math.max(0,s.sat-dt*(s.hidden>=0?.035:.09));
 if(!s.sat)s.hp-=dt*.35;
 if(s.hidden>=0&&s.sat>10)s.hp=Math.min(100,s.hp+dt*1.6);
 for(let i=0;i<s.z.length;i++){
  const z=s.z[i],zx=z.x,zy=z.y,d=dist(z,s.p),alert=s.hidden<0&&d<(s.noise?370:153)&& (s.noise||sight(z,s.p,walls));
  if(alert){z.lastX=s.p.x;z.lastY=s.p.y;z.until=s.time+4}
  if(s.hidden<0&&z.until>s.time&&Math.hypot(z.lastX-z.x,z.lastY-z.y)>4)chase(z,{x:z.lastX,y:z.lastY},38+3*(i%3),dt,walls);
  else{let a=i*3+Math.floor((s.time+9*i)/12)*1.9;steer(z,Math.cos(a),Math.sin(a),6,dt,walls)}
  z.stride=(z.stride||0)+Math.hypot(z.x-zx,z.y-zy)*.24;
  if(s.hidden<0&&d<(s.drive?29:16)&&s.hurt===0&&sight(z,s.p,walls)){
   s.hurt=1.3;
   if(s.drive){s.car.hp=Math.max(0,s.car.hp-(14-2*level(1)));if(!s.car.hp){s.drive=false;s.car.speed=0;say("Машина заглохла")}}
   else{s.hp-=12;say("Ранение! Уходи в укрытие")}
  }
 }
 footsteps();followCompanion(dt,walls);
 if(!s.won&&s.harvest&&s.hens&&s.walls.every(Boolean)&&s.gate&&s.looted.includes(3)){s.won=true;say("Кордон обжит! Можно продолжать вылазки.")}
 if(s.hp<=0){save();openMenu()}
 if(s.time-savedAt>2){save();savedAt=s.time}
}
function hud(){
 $("sprint").textContent=s.companion?"Эскорт":s.exhausted?"Передышка":"Бег · "+Math.ceil(s.stamina)+"%";$("sprint").disabled=s.hidden>=0||s.drive||!!s.companion;
 $("hp").value=s.hp;$("sat").value=s.sat;$("area").textContent=s.hidden>=0?buildings[s.hidden].name.toUpperCase():s.p.x>3050?"БОЛЬНИЧНЫЙ КВАРТАЛ":s.p.x>2384?"ВОСТОЧНЫЙ БЕРЕГ":s.p.x>1584?"ПРИРЕЧНЫЙ ТРАКТ":s.p.x>960?"ЛЕСНОЙ КОРДОН":"ПРИДОРОЖНЫЙ ПОСЁЛОК";
 let mins=16*60+Math.floor(s.time*3);$("clock").textContent="День "+(1+Math.floor(mins/1440))+" · "+String(Math.floor(mins/60)%24).padStart(2,"0")+":"+String(mins%60).padStart(2,"0");
 let a=options()[0];if(s.drive)$("area").textContent="УНИВЕРСАЛ · "+Math.round(Math.abs(s.car.speed||0)*.30)+" КМ/Ч · "+s.car.hp+"%";$("use").disabled=!a;$("use").textContent=a?a.label:"Нет действия";
 $("fire").textContent="Огонь · "+s.ammo;$("eat").textContent="Еда · "+s.food;
 let [to,task]=route();$("target").textContent=s.hidden>=0?"Укрытие · стеллаж у дальней стены":task+" · "+Math.round(dist(to,s.p)/12)+" м";
}
function resetInput(){sprintHeld=false;jx=jy=0;joyPointer=null;keys.clear();$("stick").firstElementChild.style.transform=""}
function openMenu(){
 paused=true;resetInput();save();$("menu").hidden=false;
 $("entry").textContent=s.hp<=0?"Вылазка окончена. Можно начать новую историю.":s.time<1?"Обыщи мастерскую с синими воротами. На востоке стоит заброшенная ферма — там можно пережить ночь.":"Твоя цель — "+route()[1].toLowerCase()+".";
 if(s.ending&&s.hp>0)$("entry").textContent="Финал · "+s.diary;else if(s.diary&&s.hp>0)$("entry").textContent=s.diary+" Цель: "+route()[1]+".";
 if(typeof journalMap==="function")journalMap();
 $("stats").textContent="Детали "+s.parts+" · Доски "+s.wood+" · Еда "+s.food+" · Патроны "+s.ammo+"\n"+["Выживание","Механика","Стройка","Хозяйство"].map((t,i)=>t+" "+level(i)).join(" · ");
 $("resume").textContent=s.hp<=0?"Начать заново":s.time<1?"Выйти в посёлок":"Продолжить";
}
function resume(){audioReady();if(s.hp<=0){s=initial();populateEast();savedAt=0;fireAfter=0;flash=null;save()}paused=false;$("menu").hidden=true;hud()}
window.suspendGame=()=>{openMenu()};window.toggleMenu=()=>paused?resume():openMenu();
$("use").onclick=interact;$("fire").onclick=fire;$("eat").onclick=eat;$("resume").onclick=resume;$("journal").onclick=openMenu;
window.addEventListener("keydown",e=>{if(["ArrowUp","ArrowLeft","ArrowRight","ArrowDown","Space"].includes(e.code))e.preventDefault();keys.add(e.code);if(e.repeat)return;if(e.code==="Escape")window.toggleMenu();if(e.code==="KeyE")interact();if(e.code==="KeyF"||e.code==="Space")fire();if(e.code==="KeyQ")eat();if(e.code==="KeyB")buildFence()});
window.addEventListener("keyup",e=>keys.delete(e.code));document.addEventListener("visibilitychange",()=>{if(document.hidden)openMenu()});window.addEventListener("blur",()=>{if(!paused)openMenu()});window.addEventListener("pagehide",save);
const stick=$("stick");function joystick(e){let r=stick.getBoundingClientRect(),x=e.clientX-r.left-r.width/2,y=e.clientY-r.top-r.height/2,n=Math.hypot(x,y),range=r.width*.36;jx=x/Math.max(range,n);jy=y/Math.max(range,n);stick.firstElementChild.style.transform="translate("+jx*range+"px,"+jy*range+"px)"}
stick.onpointerdown=e=>{if(joyPointer!==null||paused)return;joyPointer=e.pointerId;stick.setPointerCapture(e.pointerId);joystick(e)};
stick.onpointermove=e=>{if(e.pointerId===joyPointer)joystick(e)};
stick.onlostpointercapture=stick.onpointercancel=stick.onpointerup=e=>{if(e.pointerId===joyPointer)resetInput()};
let sprintHeld=false;const sprintButton=$("sprint");
sprintButton.onpointerdown=e=>{sprintHeld=true;sprintButton.setPointerCapture(e.pointerId)};
sprintButton.onpointerup=sprintButton.onlostpointercapture=sprintButton.onpointercancel=()=>{sprintHeld=false};
let viewZoom=1;$("zoom").onclick=()=>{viewZoom=viewZoom===1?.74:viewZoom===.74?1.28:1;$("zoom").textContent=(viewZoom===1?"1":viewZoom===.74?"0,75":"1,3")+"×"};
hud();openMenu();

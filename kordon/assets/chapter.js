"use strict";
const sites={
 radio:{x:1265,y:269},depot:{x:1840,y:424},winch:{x:2194,y:536},
 walk:{x:2230,y:884},pump:{x:2700,y:446},boat:{x:2396,y:880},
 home:{x:1200,y:292},bus:{x:2200,y:575},clinic:{x:3290,y:386}
};
const river=[[2240,0,144,495],[2240,571,144,302],[2240,895,144,557]];
const districtBuildings=[
 {x:1700,y:284,w:216,h:114,name:"Автобаза",kind:"depot",loot:[3,7,3,3]},
 {x:2650,y:300,w:166,h:117,name:"Насосная",kind:"pump",loot:[2,4,1,1]},
 {x:3190,y:220,w:204,h:132,name:"Районная больница",kind:"clinic",loot:[4,3,1,2]}
];
function chapterRoute(){
 if(s.ending)return null;
 if(!s.looted.includes(0)&&!s.parts&&!s.car.hp)return [doors(0),"Найти детали в мастерской"];
 if(!s.car.hp&&s.parts&&!s.generator)return [s.car,"Починить универсал"];
 if(!s.generator&&!s.parts)return [doors(0),"Снять деталь со стеллажа в мастерской"];
 if(!s.generator)return [{x:1204,y:223},"Запитать рацию на ферме"];
 return [
 [sites.radio,"Ответить на сигнал"],[sites.depot,"Найти трос и карту на АТП"],
 [s.bridge||s.p.x>2384?sites.pump:sites.winch,s.bridge||s.p.x>2384?"Найти механика":"Расчистить мост или пройти по настилу"],
 [s.p.x>2384&&!s.bridge?sites.boat:sites.home,s.p.x>2384&&!s.bridge?"Переправить раненого на лодке":"Доставить механика на ферму"],
 [sites.bus,"Обыскать автобус"],[sites.clinic,"Найти дочь механика"],[sites.radio,"Передать последние координаты"]
 ][s.chapter||0];
}
function chapterWalls(){
 return [...river,...(s.bridge?[]:[[2297,495,22,76]])];
}
function advance(text){
 s.chapter=(s.chapter||0)+1;s.diary=text;say(text);messageUntil=performance.now()+5200;
}
function chapterActions(near){
 const k=s.chapter||0;
 if(s.generator&&k===0)near(sites.radio,"Ответить в эфир",()=>advance("«Вода у пульта. Я не дойду сам». На АТП есть карта и трос."));
 if(k===1)near(sites.depot,"Взять карту и трос",()=>{s.food+=2;advance("Северный мост перекрыт автобусом. Южнее есть пешеходный настил.")});
 if(k>=2&&!s.bridge)near(sites.winch,"Запустить лебёдку",()=>{
  s.bridge=true;s.noise=7;say("Автобус сдвинулся. Мост открыт. Шум привлёк заражённых.")
 });
 if(k===2)near(sites.pump,"Помочь механику",()=>{
  s.noise=5;s.companion={x:sites.pump.x+7,y:sites.pump.y};advance("«Зови меня Ринат. Машина или лодка — только вытащи нас отсюда».")
 });
 if(k===3){
  near(sites.boat,"Переправиться вдвоём",()=>{
   if(dist(s.companion,s.p)>65)return say("Дождись Рината у лодки");
   Object.assign(s.p,{x:2200,y:886});Object.assign(s.companion,s.p);say("Лодка на западном берегу. До фермы можно дойти пешком.")
  });
  near(sites.home,"Уложить Рината",()=>{
   if(dist(s.companion,s.p)>65)return say("Дождись Рината у фермы");
   s.companion=null;s.food+=3;advance("«Моя дочь ехала в том автобусе. Найди путевой лист». Ринат берётся за универсал.")
  });
 }
 if(k>=4 && dist(s.car,sites.home)<135 && s.car.hp<100)near(sites.home,"Попросить ремонт",()=>{s.car.hp=100;say("Ринат отремонтировал универсал")});
 if(k===4)near(sites.bus,"Прочесть путевой лист",()=>advance("Запись водителя: «Пассажиры в больнице. Лена осталась вести эфир»."));
 if(k===5)near(sites.clinic,"Позвать Лену",()=>{
  s.companion={x:s.p.x+7,y:s.p.y};advance("«Папа жив? У меня список уцелевших домов. На ферме решим: дать координаты всем или вызвать эвакуацию с запасной антенны».")
 });
 if(k===6)near({x:1325,y:273},"Вызвать эвакуацию",()=>{
  if(dist(s.companion,s.p)>65)return say("Подожди Лену у передатчика");
  s.ending=true;s.companion=null;s.diary="Вертолёт забрал раненых с поля у реки. Лена осталась вести журнал пропавших, а Ринат — держать кордон.";openMenu()
 });
 if(k===6)near(sites.radio,"Передать координаты",()=>{
  if(dist(s.companion,s.p)>65)return say("Подожди Лену у рации");
  s.ending=true;s.companion=null;s.diary="Ринат узнал голос дочери. В эфире ответили три дома. Кордон стал местом встречи.";say(s.diary);openMenu()
 });
}
function roomShelf(i){const b=buildings[i];return {x:b.x+24,y:b.y+24}}
function roomExit(i){const b=buildings[i];return {x:b.x+b.w/2,y:b.y+b.h-14}}
function insideActions(){
 const i=s.hidden,pos=roomShelf(i),items=[];
 if(dist(s.p,pos)<34)items.push({label:s.looted.includes(i)?"Осмотреть стеллаж":"Обыскать стеллаж",fn:()=>{
  if(!s.looted.includes(i)){const [f,a,p,w]=buildings[i].loot;s.food+=f;s.ammo+=a;s.parts+=p;s.wood+=w;s.looted.push(i);s.xp[0]++;say("Припасы: еда +"+f+" · патроны +"+a+" · детали +"+p)}
  else if(i===0&&!s.parts){s.parts++;say("Разобран старый насос: деталь +1")}else say("Припасы уже собраны")
 }});
 if(dist(s.p,roomExit(i))<36)items.push({label:"Выйти наружу",fn:()=>{Object.assign(s.p,doors(i));s.hidden=-1;s.hurt=1}});
 return items;
}
function followCompanion(dt,walls){
 const c=s.companion;if(!c)return;
 if(s.drive||s.hidden>=0){c.x=s.p.x;c.y=s.p.y;return}
 const d=dist(c,s.p);
 if(d>18)chase(c,s.p,Math.min(96,d*2.7),dt,walls);
}

let audioBus=null,lastFoot=-1;
function audioReady(){
 try{if(!audioBus)audioBus=new (window.AudioContext||window.webkitAudioContext)();if(audioBus.state==="suspended")audioBus.resume().catch(()=>{})}catch{}
}
function tone(hz,seconds,type,volume){
 if(!audioBus||audioBus.state!=="running")return;
 const t=audioBus.currentTime,o=audioBus.createOscillator(),v=audioBus.createGain();o.type=type;
 o.frequency.setValueAtTime(hz,t);o.frequency.exponentialRampToValueAtTime(hz*.45,t+seconds);
 v.gain.setValueAtTime(volume,t);v.gain.exponentialRampToValueAtTime(.0001,t+seconds);
 o.connect(v);v.connect(audioBus.destination);o.start(t);o.stop(t+seconds);
}
function footsteps(){const step=Math.floor(stepPhase/Math.PI);if(s.stepping&&step!==lastFoot){tone(s.p.y>490&&s.p.y<584?105:65,.055,"triangle",.04);lastFoot=step}}

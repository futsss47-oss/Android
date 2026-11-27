"use strict";
const world=$("world"),ctx=world.getContext("2d",{alpha:false});
const land=document.createElement("canvas"),g=land.getContext("2d");
const DETAIL=1.1;land.width=Math.ceil(W*DETAIL);land.height=Math.ceil(H*DETAIL);
const art={};let seed=918273;
function rand(){seed=(Math.imul(seed,1664525)+1013904223)>>>0;return seed/4294967296}
function rect(c,x,y,w,h,color){c.fillStyle=color;c.fillRect(x,y,w,h)}
function oval(c,x,y,rx,ry,col){c.fillStyle=col;c.beginPath();c.ellipse(x,y,rx,ry,0,0,Math.PI*2);c.fill()}
function line(c,x,y,X,Y,col,width=1){c.strokeStyle=col;c.lineWidth=width;c.beginPath();c.moveTo(x,y);c.lineTo(X,Y);c.stroke()}
function poly(c,points,col){c.fillStyle=col;c.beginPath();points.forEach(([x,y],i)=>i?c.lineTo(x,y):c.moveTo(x,y));c.closePath();c.fill()}
function plank(c,x,y,w,h){
 const upright=h>w,len=upright?h:w;
 c.save();c.translate(x,y);if(upright){c.translate(w,0);c.rotate(Math.PI/2)}
 rect(c,2,4,len,8,"#211f1d88");rect(c,0,0,len,6,"#524a39");
 for(let i=0;i<len;i+=5){
  const tone=["#736c4f","#817854","#625e47","#888064"][(Math.floor(i/5)*7)%4];
  rect(c,i,-3+Math.sin(i)*1.3,4,10,tone);rect(c,i,-2,1,7,"#d1bb8255");
 }
 rect(c,0,3,len,1.4,"#332d25b8");for(let i=0;i<len;i+=22)rect(c,i,-5,2.4,13,"#574d38");
 c.restore()
}
const patterns={};
function texture(name,scale){
 const key=name+scale;
 if(!patterns[key]){
  const pattern=g.createPattern(art[name],"repeat");
  pattern.setTransform(new DOMMatrix().scale(scale));
  patterns[key]=pattern;
 }
 return patterns[key];
}
function band(y,width,material){
 const edges=[];
 for(let x=-15;x<W+20;x+=12)edges.push([x,y+Math.sin(x*.022)*2+rand()*2.5]);
 for(let x=W+20;x>=-15;x-=12)edges.push([x,y+width+Math.sin(x*.026)*2+rand()*2.4]);
 poly(g,edges,material);
}
function path(points,width){
 g.beginPath();g.moveTo(...points[0]);points.slice(1).forEach(p=>g.lineTo(...p));g.lineCap="round";g.lineJoin="round";
 g.strokeStyle=texture("earth",.52);g.lineWidth=width;g.stroke();g.lineWidth=width+7;g.globalAlpha=.24;g.stroke();g.globalAlpha=1;
}
function tree(x,y,r){
 const flip=rand()>.5?-1:1,angle=rand()*.45-.23;
 g.save();g.translate(x,y);g.rotate(angle);g.scale(flip,1);
 g.shadowColor="#121b15a8";g.shadowBlur=6;g.shadowOffsetX=5;g.shadowOffsetY=9;
 g.drawImage(art.birch,-r,-r,r*2,r*2);g.restore()
}
function scrap(x,y,type){
 g.save();g.translate(x,y);g.rotate((x%9-4)*.08);
 if(type===0){oval(g,2,3,7,5,"#20231c85");oval(g,0,0,6.6,4.7,"#302f2a");oval(g,0,-1,3.8,2.5,"#787564");oval(g,0,0,2.8,1.7,"#312f28")}
 else if(type===1){rect(g,-4,-6,9,13,"#72503a");oval(g,.5,-6,4.5,2.5,"#ab8460");rect(g,-4,-4,9,1,"#685745");rect(g,-4,3,9,1,"#3d4139")}
 else{for(let i=0;i<4;i++){rect(g,-12,-6+i*3,24-i*2.7,2.5,["#776448","#a08b65","#806e51","#948160"][i])}}
 g.restore()
}
function makeLand(){
 g.setTransform(DETAIL,0,0,DETAIL,0,0);
 rect(g,0,0,W,H,texture("grass",.61));
 for(let i=0;i<195;i++){
  const x=rand()*W,y=rand()*H,r=12+rand()*45;
  g.globalAlpha=.20;oval(g,x,y,r,r*.75,texture("litter",.64));
 }g.globalAlpha=1;
 band(479,116,texture("earth",.64));band(490,92,texture("asphalt",.46));
 for(let x=0;x<W;x+=70){rect(g,x+4,535,24,1.7,"#ccbda075");for(let i=0;i<12;i++)rect(g,x+4+rand()*24,534+rand()*3,1.6,1.1,"#48453e")}
 buildings.forEach(b=>{let x=b.x+b.w/2,y=b.y+b.h;if(b.kind!=="farm")path([[x-1,y],[x+2,(y+535)/2],[x+7,y<500?490:583]],26)});
 path([[1110,489],[1109,396],[1117,310],[1170,261],[1315,245]],31);
 for(let i=0;i<2700;i++){
  const x=rand()*W,y=rand()*H;if(y>495&&y<579&&rand()>.08)continue;
  oval(g,x,y,1.1+rand()*1.7,.45+rand()*.6,["#b18c48a8","#c8a05d70","#54483088","#837f5966"][i%4])
 }
 for(let i=0;i<980;i++){let x=rand()*W,y=rand()*H;if(y>455&&y<612||buildings.some(b=>x>b.x-42&&x<b.x+b.w+35&&y>b.y-55&&y<b.y+b.h+38)||x>931&&x<1480&&y<447)continue;if(x<100||y<110||y>750||x>1480||rand()<.11)tree(x,y,17+rand()*20)}
 [[155,412,0],[168,413,0],[141,402,1],[288,370,1],[294,380,1],[454,411,2],[562,377,1],[1254,255,2],[1194,271,0],[980,556,2],[1011,553,2],[1003,561,2],[577,711,0]].forEach(args=>scrap(...args));
 buildings.forEach(b=>{
  const image=art.roof;
  g.save();g.shadowColor="#1c211abf";g.shadowBlur=5;g.shadowOffsetX=7;g.shadowOffsetY=10;
  if(["farm","home","clinic"].includes(b.kind))g.filter="sepia(.65) brightness(1.08)";
  g.drawImage(image,b.x-3,b.y-3,b.w+6,b.h+6);g.restore()
 });
 riverScene();
 rails.forEach(a=>plank(g,...a));
 for(let i=0;i<3;i++){rect(g,1212,293+i*15,74,11,texture("earth",.44));line(g,1214,300+i*15,1283,300+i*15,"#342b2099",2.5)}
 rect(g,1352,286,60,55,texture("litter",.40));for(let i=0;i<=60;i+=5)line(g,1352+i,286,1352+i,340,"#a0a59b69",.6);for(let i=0;i<=55;i+=5)line(g,1352,286+i,1412,286+i,"#9ea29a6c",.6);plank(g,1351,285,64,4);plank(g,1351,340,64,4);
 g.drawImage(art.roof,1386,269,36,31);
 rect(g,1193,212,24,17,"#47574c");for(let j=0;j<4;j++)rect(g,1196+4*j,217,2,7,"#232e2e");
}
function figure(x,y,a,alive,i){
 const npc=i===-2,pose=alive?stepPhase:(s.z[i].stride||0),stride=npc?0:alive&&!s.stepping?0:Math.sin(pose);
 ctx.save();ctx.translate(x,y);oval(ctx,1.8,2,6.6,4.7,"#19221bb8");ctx.rotate(a);
 ctx.lineCap="round";
 for(const side of [-1,1]){
  const swing=side*stride;
  line(ctx,-1,side*2.7,swing*6-1,side*3.3,"#383d34",3.1);
  line(ctx,swing*6-1,side*3.3,swing*6+1,side*3.3,"#282b27",2.4);
  const reach=alive?1-swing*4:4.7-swing*2.8;
  line(ctx,-1,side*4,reach,side*5,alive?"#ae8349":"#854d42",2.6);
  oval(ctx,reach+1,side*5,1.25,1.05,alive?"#d0ab85":"#b0a998")
 }
 ctx.rotate(stride*(alive?.15:.19));if(npc)ctx.filter="sepia(.65)";
 ctx.drawImage(alive?art.survivor:art.infected,-5.2,-4.8,10.4,9.6);ctx.restore()
}

function car(){
 const c=s.car;ctx.save();ctx.translate(c.x,c.y);oval(ctx,2,2,25,11,"#19221968");ctx.rotate(c.ang);
 ctx.drawImage(art.estate,-27,-12,54,24);
 if(!c.hp){const t=s.time*.8;for(let i=0;i<3;i++){let f=(t+i/3)%1;oval(ctx,17+4*f,-3-10*f,2+3*f,2+3*f,"rgba(95,94,83,"+(.35*(1-f))+")")}}
 ctx.restore()
}
function headlights(){
 if(!s.drive||!s.car.hp)return;
 ctx.save();ctx.translate(s.car.x,s.car.y);ctx.rotate(s.car.ang);
 for(const offset of [-8,8]){
  const light=ctx.createLinearGradient(23,0,121,0);light.addColorStop(0,"#ffdd9e56");light.addColorStop(1,"#ffe6b300");
  poly(ctx,[[24,offset-2],[119,offset-20],[127,offset+20],[24,offset+2]],light)
 }ctx.restore()
}
function label(x,y,t,col="#e6c77e"){
 ctx.font="600 7.7px system-ui";let w=ctx.measureText(t).width+14;rect(ctx,x-w/2,y-8,w,11,"#22332eea");rect(ctx,x-w/2,y-8,1.3,11,col);ctx.textAlign="center";ctx.fillStyle="#ede7cb";ctx.fillText(t,x,y)
}
function chicken(){
 const a=s.time*.8,x=1371+Math.cos(a)*8,y=316+Math.sin(a)*5,r=s.hens?4:2.7;
 oval(ctx,x,y,r*1.4,r,s.hens?"#c4ac87":"#efcf75");oval(ctx,x+r,y-2,r*.65,r*.75,"#e1ba7f");rect(ctx,x+r,y-5,2,2,"#a34e3c");
}
let last=performance.now(),uiAt=0;
function frame(now){
 let dt=Math.min(.05,(now-last)/1000);last=now;if(!paused)tick(dt);
 if(now>messageUntil)$("toast").style.opacity=0;
 if(now-uiAt>150){hud();uiAt=now}
 let cw=world.clientWidth,ch=world.clientHeight,dpr=Math.min(window.devicePixelRatio||1,2);
 if(world.width!==Math.round(cw*dpr)||world.height!==Math.round(ch*dpr)){world.width=Math.round(cw*dpr);world.height=Math.round(ch*dpr)}
 let scale=viewZoom*Math.max(.72,Math.min(3.2,Math.min(cw/430,ch/238)));
 let vw=cw/scale,vh=ch/scale,cx=clamp(s.p.x-vw*.48,0,Math.max(0,W-vw)),cy=clamp(s.p.y-vh*.53,0,Math.max(0,H-vh));
 ctx.setTransform(scale*dpr,0,0,scale*dpr,-cx*scale*dpr,-cy*scale*dpr);ctx.drawImage(land,0,0,W,H);
 if(s.gate)plank(ctx,1072,407,77,10);else{plank(ctx,1073,382,7,30);plank(ctx,1143,382,7,30)}
 breaches.forEach((v,i)=>{if(s.walls[i])plank(ctx,v.x-17,407,34,10);else{line(ctx,v.x-10,408,v.x+3,421,"#a29168",4);line(ctx,v.x,408,v.x+12,416,"#736a4b",4)}});
 s.extra.forEach(b=>plank(ctx,b.x-18,b.y-4,36,8));
 if(s.crop!==null)for(let i=0;i<18;i++){let r=1.5+Math.min(1,(s.time-s.crop)/35)*2.8,x=1218+i%6*12,y=298+Math.floor(i/6)*15;oval(ctx,x,y,r+1,r,"#45633b");oval(ctx,x-1,y-1,r*.7,r*.65,"#88a25c")}
 renderDistrict();chicken();car();headlights();if(s.hidden>=0)roomView(s.hidden);
 for(let i=0;i<s.z.length;i++){let z=s.z[i];figure(z.x,z.y,z.ang,false,i)}
 if(!s.drive){ctx.strokeStyle=s.hurt>0?"#fa8e69":"#f5da977f";ctx.lineWidth=1.2;ctx.beginPath();ctx.ellipse(s.p.x,s.p.y+2,8,7,0,0,Math.PI*2);ctx.stroke();figure(s.p.x,s.p.y,s.p.ang,true,0)}
 if(flash&&s.time<flash.t)line(ctx,s.p.x,s.p.y,flash.x,flash.y,"#ffd76c",2);
 let minutes=(960+s.time*3)%1440,night=Math.max(0,Math.cos(minutes/1440*Math.PI*2));
 if(night)rect(ctx,cx,cy,vw,vh,"rgba(12,26,45,"+(night*.41)+")");
 if(s.generator){let lg=ctx.createRadialGradient(1312,244,2,1312,244,60);lg.addColorStop(0,"#ffcc6d65");lg.addColorStop(1,"#ffcc6d00");oval(ctx,1312,244,60,60,lg)}
 const horizon=ctx.createLinearGradient(cx,cy,cx+vw*.8,cy+vh);
 horizon.addColorStop(0,"#e0ad5521");horizon.addColorStop(.55,"#a2884b00");horizon.addColorStop(1,"#17262226");rect(ctx,cx,cy,vw,vh,horizon);
 buildings.forEach((b,i)=>{let d=doors(i);label(d.x,b.y-8,b.name);if(s.hidden===i)label(d.x,d.y+25,"Укрытие")});
 if(dist(s.p,s.car)<150&&!s.drive)label(s.car.x,s.car.y-23,s.car.hp?"Универсал":s.parts?"Можно починить":"Универсал · поломка");
 if(dist(s.p,garden)<180){label(garden.x,282,"Огород");label(coop.x,271,"Курятник")}
 if(dist(s.p,timber)<140)label(timber.x,timber.y-10,"Штабель досок");
 let [dest]=route();if(s.hidden<0&&!s.ending){let x=clamp(dest.x,cx+22,cx+vw-22),y=clamp(dest.y,cy+58/scale,cy+vh-60/scale);ctx.save();ctx.translate(x,y-2);ctx.rotate(Math.PI/4);ctx.strokeStyle="#f6cf73";ctx.lineWidth=1.5;ctx.strokeRect(-5,-5,10,10);ctx.restore()}
 requestAnimationFrame(frame)
}
const names=["roof","estate","survivor","infected","birch","grass","asphalt","earth","litter"];
const startButton=$("resume");startButton.disabled=true;
Promise.all(names.map(name=>new Promise((resolve,reject)=>{
 const img=new Image();img.onload=()=>{art[name]=img;resolve()};img.onerror=()=>reject(new Error(name));img.src="art/"+name+".webp";
}))).then(()=>{makeLand();startButton.disabled=false;last=performance.now();requestAnimationFrame(frame)}).catch(e=>{startButton.disabled=true;$("entry").textContent="Ошибка загрузки графики: "+e.message});

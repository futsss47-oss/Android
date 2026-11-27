"use strict";
const world=$("world"),ctx=world.getContext("2d",{alpha:false});
const land=document.createElement("canvas");land.width=W;land.height=H;
const g=land.getContext("2d");
let seed=918273;
function rand(){seed=(Math.imul(seed,1664525)+1013904223)>>>0;return seed/4294967296}
function rect(c,x,y,w,h,color){c.fillStyle=color;c.fillRect(x,y,w,h)}
function oval(c,x,y,rx,ry,col){c.fillStyle=col;c.beginPath();c.ellipse(x,y,rx,ry,0,0,Math.PI*2);c.fill()}
function line(c,x,y,X,Y,col,width=1){c.strokeStyle=col;c.lineWidth=width;c.beginPath();c.moveTo(x,y);c.lineTo(X,Y);c.stroke()}
function poly(c,points,col){c.fillStyle=col;c.beginPath();points.forEach(([x,y],i)=>i?c.lineTo(x,y):c.moveTo(x,y));c.closePath();c.fill()}
function plank(c,x,y,w,h){rect(c,x,y+3,w,h,"#382f2999");const horizontal=w>h;rect(c,x,y,w,h,"#675e48");rect(c,x,y,w,2,"#a39871");for(let i=1;i<(horizontal?w:h);i+=7){let xx=x+(horizontal?i:0),yy=y+(horizontal?0:i);rect(c,xx,yy,horizontal?4:w,horizontal?h:4,i%3?"#877d5d":"#6f6850")}}
function tree(x,y,r){
 oval(g,x+8,y+12,r*1.03,r*.83,"#182a2545");rect(g,x-2,y,4,r*.96,"#655343");
 let tint=rand();let c=tint<.22?["#5a5436","#7f7042","#a28a4c"]:tint<.46?["#3b513e","#536848","#728158"]:["#304a3d","#405c47","#5b7150"];
 for(let i=0;i<9;i++){let a=i*2.4,xx=x+Math.cos(a)*r*.43,yy=y+Math.sin(a)*r*.37;oval(g,xx,yy,r*.57,r*.54,c[i%2]);oval(g,xx-2,yy-3,r*.41,r*.38,c[i===8?2:1])}
 for(let i=0;i<13;i++)rect(g,x+(rand()-.5)*r*1.3,y+(rand()-.5)*r*1.1,2+rand()*2,1.4,c[2]);
}
function house(b){
 let {x,y,w,h}=b;rect(g,x+9,y+10,w,h,"#19291d66");rect(g,x,y,w,h,"#665a47");
 for(let j=2;j<h;j+=5){rect(g,x,y+j,w,2,"#84705a");rect(g,x,y+j+2,w,1,"#484636")}
 let roof=b.kind==="garage"?["#687875","#4e6665"]:b.kind==="shop"?["#7b695a","#665143"]:["#6d6652","#575846"];
 poly(g,[[x-5,y+5],[x+w/2,y-14],[x+w/2,y+h-37],[x-5,y+h-17]],roof[0]);
 poly(g,[[x+w/2,y-14],[x+w+5,y+5],[x+w+5,y+h-17],[x+w/2,y+h-37]],roof[1]);
 for(let i=0;i<w/2;i+=9){line(g,x+i,y+4-i*.22,x+i,y+h-19-i*.22,"#abb2a036",2);line(g,x+w-i,y+4-i*.22,x+w-i,y+h-19-i*.22,"#c4b59824",2)}
 line(g,x+w/2,y-14,x+w/2,y+h-37,"#aaa48a",3);
 let d=x+w/2;rect(g,d-11,y+h-22,22,22,b.kind==="garage"?"#518487":"#463e32");rect(g,d-14,y+h,28,5,"#a59979");
 for(let xx of [x+17,x+w-37]){rect(g,xx,y+h-17,21,12,"#3d453e");rect(g,xx+2,y+h-15,7,8,"#bbae83");rect(g,xx+11,y+h-15,8,8,"#798f87")}
}
function makeLand(){
 rect(g,0,0,W,H,"#657054");
 for(let i=0;i<2700;i++)oval(g,rand()*W,rand()*H,5+rand()*26,3+rand()*12,["#5a695146","#7e805448","#85886444","#455b493e"][i%4]);
 rect(g,0,481,W,112,"#91836b");rect(g,0,490,W,94,"#585c57");
 for(let i=0;i<4100;i++){let x=rand()*W,y=490+rand()*94;rect(g,x,y,1+rand()*3,1,"#bcc2a52c")}
 for(let x=0;x<W;x+=75)rect(g,x+9,536,29,2,"#c2b89a88");
 for(let i=0;i<44;i++){let x=rand()*W,y=496+rand()*78;for(let j=0;j<3;j++){let xx=x+rand()*18-5,yy=y+rand()*13-5;line(g,x,y,xx,yy,"#3e4743");x=xx;y=yy}}
 for(let b of buildings){let x=b.x+b.w/2,y=b.y+b.h;line(g,x,y,x,Math.min(590,Math.max(490,y)),"#8c816667",29)}
 line(g,1110,485,1110,278,"#a0936d86",41);line(g,1110,278,1320,258,"#9d906e88",31);
 for(let i=0;i<15000;i++){let x=rand()*W,y=rand()*H;if(y>479&&y<594)continue;line(g,x,y,x+1,y-2-rand()*3,i%4?"#3e594330":"#cdbc7d55")}
 for(let i=0;i<380;i++){let x=rand()*W,y=rand()*H;if(y>445&&y<620||buildings.some(b=>x>b.x-48&&x<b.x+b.w+40&&y>b.y-55&&y<b.y+b.h+70)||x>926&&x<1480&&y<470)continue;if(x<100||y<115||y>755||x>1480||rand()<.10)tree(x,y,10+rand()*19)}
 buildings.forEach(house);rails.forEach(a=>plank(g,...a));
 for(let k=0;k<8;k++){let x=970+k*7;rect(g,x,554-k%2*4,50,5,"#826a49");line(g,x,554-k%2*4,x+50,554-k%2*4,"#bc9d67")}
 for(let i=0;i<3;i++){rect(g,1212,293+i*15,74,11,"#635340");line(g,1215,297+i*15,1283,297+i*15,"#3f4034",2)}
 rect(g,1352,286,60,55,"#a49465");for(let i=0;i<=60;i+=6)line(g,1352+i,286,1352+i,341,"#60695666");for(let i=0;i<=55;i+=6)line(g,1352,286+i,1412,286+i,"#62675255");plank(g,1351,285,64,4);plank(g,1351,340,64,4);
 rect(g,1388,282,30,22,"#79513c");rect(g,1386,278,33,12,"#4b5346");rect(g,1193,212,24,17,"#445552");for(let j=0;j<4;j++)rect(g,1196+4*j,217,2,7,"#232e2e");
}
makeLand();
function figure(x,y,a,alive,i){
 let t=alive?stepPhase:s.time*6+i;
 ctx.save();ctx.translate(x,y);oval(ctx,3,4,11,7,"#14201966");ctx.rotate(a);
 let shirt=alive?"#d39b43":["#776858","#758176","#894f48","#536b70"][i%4];let skin=alive?"#d6ab86":"#a1a48e";
 rect(ctx,-5+Math.sin(t)*2,-4,8,3,"#373c35");rect(ctx,-5-Math.sin(t)*2,2,8,3,"#313831");
 oval(ctx,0,0,6,8,shirt);rect(ctx,alive?-5:1,-8,alive?7:10,3,shirt);rect(ctx,alive?-2:3,5,alive?8:9,3,shirt);
 oval(ctx,2,-1,5,4.7,skin);oval(ctx,.8,-1,4,4,alive?"#544837":"#595b51");if(alive){rect(ctx,-7,-4,5,9,"#716347");rect(ctx,-7,-4,4,2,"#b29a6e")}
 ctx.restore()
}
function car(){
 let c=s.car;ctx.save();ctx.translate(c.x,c.y);ctx.rotate(c.ang);rect(ctx,-24,-10,52,24,"#26352b80");for(let x of [-16,13])for(let y of [-12,8])rect(ctx,x,y,7,5,"#252c29");
 rect(ctx,-23,-10,48,20,"#344b45");rect(ctx,-22,-9,45,17,"#68847a");rect(ctx,-16,-7,7,14,"#384b4b");rect(ctx,-8,-7,15,14,"#59736a");poly(ctx,[[7,-7],[12,-6],[12,6],[7,7]],"#91aca3");line(ctx,13,-8,13,8,"#3a4b41");for(let y of [-8,5])rect(ctx,23,y,2,4,"#eace92");
 if(!c.hp){rect(ctx,13,-7,9,13,"#494a3b");rect(ctx,15,-5,4,6,"#79786b")}ctx.restore()
}
function label(x,y,t,col="#e6c77e"){
 ctx.font="bold 10px system-ui";let w=ctx.measureText(t).width+14;rect(ctx,x-w/2,y-11,w,16,"#22332eea");rect(ctx,x-w/2,y-11,2,16,col);ctx.textAlign="center";ctx.fillStyle="#ede7cb";ctx.fillText(t,x,y)
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
 let scale=Math.max(.72,Math.min(2.15,Math.min(cw/550,ch/310)));
 let vw=cw/scale,vh=ch/scale,cx=clamp(s.p.x-vw*.48,0,Math.max(0,W-vw)),cy=clamp(s.p.y-vh*.53,0,Math.max(0,H-vh));
 ctx.setTransform(scale*dpr,0,0,scale*dpr,-cx*scale*dpr,-cy*scale*dpr);ctx.drawImage(land,0,0);
 if(s.gate)plank(ctx,1072,407,77,10);else{plank(ctx,1073,382,7,30);plank(ctx,1143,382,7,30)}
 breaches.forEach((v,i)=>{if(s.walls[i])plank(ctx,v.x-17,407,34,10);else{line(ctx,v.x-10,408,v.x+3,421,"#a29168",4);line(ctx,v.x,408,v.x+12,416,"#736a4b",4)}});
 s.extra.forEach(b=>plank(ctx,b.x-18,b.y-4,36,8));
 if(s.crop!==null)for(let i=0;i<18;i++){let r=1.5+Math.min(1,(s.time-s.crop)/35)*2.8,x=1218+i%6*12,y=298+Math.floor(i/6)*15;oval(ctx,x,y,r+1,r,"#45633b");oval(ctx,x-1,y-1,r*.7,r*.65,"#88a25c")}
 chicken();car();
 for(let i=0;i<s.z.length;i++){let z=s.z[i];figure(z.x,z.y,z.ang,false,i)}
 if(!s.drive&&s.hidden<0){ctx.strokeStyle=s.hurt>0?"#fa8e69":"#f5da977f";ctx.lineWidth=1.2;ctx.beginPath();ctx.ellipse(s.p.x,s.p.y+2,12,10,0,0,Math.PI*2);ctx.stroke();figure(s.p.x,s.p.y,s.p.ang,true,0)}
 if(flash&&s.time<flash.t)line(ctx,s.p.x,s.p.y,flash.x,flash.y,"#ffd76c",2);
 let minutes=(960+s.time*3)%1440,night=Math.max(0,Math.cos(minutes/1440*Math.PI*2));
 if(night)rect(ctx,cx,cy,vw,vh,"rgba(12,26,45,"+(night*.41)+")");
 if(s.generator){let lg=ctx.createRadialGradient(1312,244,2,1312,244,60);lg.addColorStop(0,"#ffcc6d65");lg.addColorStop(1,"#ffcc6d00");oval(ctx,1312,244,60,60,lg)}
 buildings.forEach((b,i)=>{let d=doors(i);label(d.x,b.y+12,b.name);if(s.hidden===i)label(d.x,d.y+25,"Укрытие")});
 if(dist(s.p,s.car)<150&&!s.drive)label(s.car.x,s.car.y-21,s.car.hp?"Универсал":"Нужна деталь");
 if(dist(s.p,garden)<180){label(garden.x,282,"Огород");label(coop.x,271,"Курятник")}
 if(dist(s.p,timber)<140)label(timber.x,timber.y-10,"Штабель досок");
 let [dest]=route();if(s.hidden<0&&!s.won){let x=clamp(dest.x,cx+22,cx+vw-22),y=clamp(dest.y,cy+58/scale,cy+vh-60/scale);ctx.save();ctx.translate(x,y-2);ctx.rotate(Math.PI/4);ctx.strokeStyle="#f6cf73";ctx.lineWidth=1.5;ctx.strokeRect(-5,-5,10,10);ctx.restore()}
 requestAnimationFrame(frame)
}
requestAnimationFrame(frame);

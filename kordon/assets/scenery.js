"use strict";
function riverScene(){
 rect(g,2235,0,154,H,"#787957");rect(g,2240,0,144,H,"#446b69");
 const water=g.createLinearGradient(2240,0,2384,0);water.addColorStop(0,"#809585");water.addColorStop(.24,"#456d70");water.addColorStop(.6,"#34595f");water.addColorStop(1,"#779283");rect(g,2240,0,144,H,water);
 for(let i=0;i<900;i++){const x=2242+rand()*138,y=rand()*H;line(g,x,y,x+1+rand()*2,y+2+rand()*6,"#d1d9bf35",.7)}
 rect(g,2230,495,164,76,texture("asphalt",.40));for(const y of [491,572]){rect(g,2233,y,157,4,"#9ea098");for(let x=2234;x<2388;x+=17)rect(g,x,y-1,2.4,6,"#525957")}
 for(let x=2232;x<2392;x+=4)rect(g,x,873,3.8,22,x%8?"#8f7658":"#9d8769");
 for(let i=0;i<5;i++){scrap(1735+i*21,437+(i%2)*12,i%3)}
 path([[2401,875],[2490,827],[2570,666],[2700,558]],15);
 path([[2179,884],[2110,803],[2164,657],[2166,590]],15);
}
function busSprite(){
 const x=s.bridge?2190:2308,y=s.bridge?602:533;
 ctx.save();ctx.translate(x,y);ctx.rotate(s.bridge?.12:Math.PI/2);
 rect(ctx,-37,-11,75,25,"#252d2a88");rect(ctx,-39,-13,78,25,"#a7854a");rect(ctx,-37,-11,73,21,"#d1b582");
 for(let i=-31;i<28;i+=10){rect(ctx,i,-11,7,3,"#445857");rect(ctx,i,8,7,3,"#526367")}
 rect(ctx,29,-9,6,18,"#495958");rect(ctx,-11,-5,10,9,"#918777");rect(ctx,6,-5,9,9,"#918777");ctx.restore();
}
function renderDistrict(){
 busSprite();
 for(const [x,y] of [[2194,536],[1840,424]]){rect(ctx,x-8,y-5,16,10,"#43504a");for(let i=-4;i<5;i+=2)line(ctx,x+i,y-4,x+i,y+4,"#afada0")}
 const x=2396,y=880;poly(ctx,[[x-15,y],[x-8,y-5],[x+10,y-4],[x+14,y],[x+10,y+4],[x-8,y+5]],"#b1976d");line(ctx,x-7,y,x+9,y,"#6e5942",4);
 rect(ctx,sites.radio.x-6,sites.radio.y-3,12,7,"#655f49");line(ctx,sites.radio.x+4,sites.radio.y,sites.radio.x+9,sites.radio.y-9,"#c0c2ae");
 if(s.chapter===6&&!s.ending){label(sites.radio.x,sites.radio.y-12,"Общий эфир");label(1325,260,"Канал эвакуации");line(ctx,1320,273,1330,273,"#aba88a",2);line(ctx,1325,269,1325,277,"#b8b59f",2)}
 if(s.chapter===2)figure(sites.pump.x,sites.pump.y,1,true,-2);
 if(s.chapter>=4)figure(sites.home.x,sites.home.y,1,true,-2);
 if(s.ending)figure(sites.radio.x+14,sites.radio.y,2,true,-2);
 if(s.chapter===5)figure(sites.clinic.x,sites.clinic.y,2,true,-2);
 if(s.companion&&!s.drive&&s.hidden<0)figure(s.companion.x,s.companion.y,s.companion.ang||0,true,-2);
}
function roomView(i){
 const b=buildings[i],ex=roomExit(i),sh=roomShelf(i);
 rect(ctx,b.x-2,b.y-2,b.w+4,b.h+4,"#423d32");rect(ctx,b.x+3,b.y+3,b.w-6,b.h-6,"#84745b");
 for(let y=b.y+8;y<b.y+b.h-3;y+=7)line(ctx,b.x+3,y,b.x+b.w-3,y,"#655740",1);
 rect(ctx,sh.x-16,sh.y-11,34,11,"#4c493c");for(let j=0;j<4;j++)rect(ctx,sh.x-13+j*8,sh.y-9,6,6,j%2?"#929573":"#b79967");
 rect(ctx,ex.x-10,b.y+b.h-2,20,4,"#dec684");
 label(sh.x,sh.y-17,s.looted.includes(i)?"Стеллаж":"Припасы");label(ex.x,ex.y+9,"Выход");
}
function journalMap(){
 const c=$("map"),m=c.getContext("2d"),sx=c.width/W,sy=c.height/H;
 m.save();m.fillStyle="#37443a";m.fillRect(0,0,c.width,c.height);m.scale(sx,sy);
 m.fillStyle="#5c8b91";m.fillRect(2240,0,144,H);m.fillStyle="#ab9c75";m.fillRect(0,505,W,56);
 m.fillStyle="#decba0";for(const b of buildings)m.fillRect(b.x,b.y,b.w,b.h);
 m.restore();m.font="10px system-ui";m.fillStyle="#eee3ba";
 for(const [t,x,y] of [["Посёлок",130,210],["Ферма",1180,120],["АТП",1700,220],["Мост",2100,670],["Настил",2080,1020],["Лодка",2420,995],["Насосная",2490,265],["Больница",3060,150]])m.fillText(t,x*sx,y*sy);
 for(const [p,col] of [[route()[0],"#ffbf57"],[s.p,"#bff8e1"]]){m.beginPath();m.arc(p.x*sx,p.y*sy,3,0,Math.PI*2);m.fillStyle=col;m.fill()}
}

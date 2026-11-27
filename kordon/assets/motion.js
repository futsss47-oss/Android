"use strict";
// One oriented hull follows the illustrated car. Keep the game save in world coordinates.
const vec = (x,y) => new SAT.Vector(x,y);
const hull = new SAT.Polygon(vec(0,0),[vec(-25,-11),vec(24,-11),vec(26,-8),vec(26,8),vec(24,11),vec(-25,11)]);
const contact = new SAT.Response();
let staticKey = "", collisionWalls = [], navOpen, navFields = new Map();
const CELL=12,COLS=Math.ceil(W/CELL),ROWS=Math.ceil(H/CELL);
function prepWalls(walls) {
 const key=[s.gate,...s.walls,...s.extra.map(v=>v.x+","+v.y)].join(";");
 if(key===staticKey)return;
 staticKey=key;
 collisionWalls=walls.map(([x,y,w,h])=>({x,y,w,h,body:new SAT.Box(vec(x,y),w,h).toPolygon()}));
 navFields.clear();navOpen=null;
}
function carTouches(x,y,angle,walls) {
 hull.pos.x=x;hull.pos.y=y;hull.setAngle(angle);
 if(hull.calcPoints.some(p=>x+p.x<0||x+p.x>W||y+p.y<0||y+p.y>H))return true;
 for(const wall of walls){
  if(x+29<wall.x||y+29<wall.y||x-29>wall.x+wall.w||y-29>wall.y+wall.h)continue;
  if(SAT.testPolygonPolygon(hull,wall.body,contact.clear()))return true;
 }
 return false;
}
function carOverRect(box) {
 let [x,y,w,h]=box;
 return carTouches(s.car.x,s.car.y,s.car.ang,[{x,y,w,h,body:new SAT.Box(vec(x,y),w,h).toPolygon()}]);
}
const towards=(v,target,delta)=>v+clamp(target-v,-delta,delta);
const wrap=a=>Math.atan2(Math.sin(a),Math.cos(a));
function driveStep(dx,dy,dt,walls){
 const c=s.car;prepWalls(walls);
 const onRoad=c.y>=490&&c.y<=584,throttle=Math.min(1,Math.hypot(dx,dy));
 const active=throttle>.12,heading=active?Math.atan2(dy,dx):c.ang;
 const diff=wrap(heading-c.ang);
 const reverse=active&&Math.abs(diff)>2.35;
 const wanted=active?(reverse?-63:(onRoad?185:117)+4*level(1))*throttle:0;
 const speed=c.speed||0;
 const braking=active&&(speed*wanted<0||Math.abs(wanted)<Math.abs(speed));
 c.speed=towards(speed,wanted,dt*(braking?225:active?(onRoad?84:61):onRoad?58:86));
 const turning=active?clamp(wrap(diff+(reverse?Math.PI:0)),-1,1):0;
 const rotation=turning*Math.min(2.1,Math.abs(c.speed)/32)*dt;
 const steps=Math.max(1,Math.ceil(Math.abs(c.speed)*dt/3));
 for(let k=0;k<steps;k++){
  const a=wrap(c.ang+rotation/steps);
  if(!carTouches(c.x,c.y,a,collisionWalls))c.ang=a;
  const travel=c.speed*dt/steps,x=c.x+Math.cos(c.ang)*travel,y=c.y+Math.sin(c.ang)*travel;
  if(carTouches(x,y,c.ang,collisionWalls)){
   if(Math.abs(c.speed)>52){const damage=Math.round(Math.abs(c.speed)*.085);c.hp=Math.max(0,c.hp-damage);say("Удар · состояние машины "+c.hp+"%")}
   c.speed=0;break;
  }
  c.x=x;c.y=y;
 }
 Object.assign(s.p,{x:c.x,y:c.y,ang:c.ang});
 if(Math.abs(c.speed)>5)s.noise=1;
 if(!c.hp){s.drive=false;c.speed=0;say("Машина заглохла")}
}
function clearPath(a,b,walls) {
 const n=Math.max(1,Math.ceil(dist(a,b)/5));
 for(let i=1;i<=n;i++)if(blocked(a.x+(b.x-a.x)*i/n,a.y+(b.y-a.y)*i/n,6,walls))return false;
 return true;
}
function cellAt(x,y){return clamp(Math.floor(y/CELL),0,ROWS-1)*COLS+clamp(Math.floor(x/CELL),0,COLS-1)}
function center(id){return {x:(id%COLS+.5)*CELL,y:(Math.floor(id/COLS)+.5)*CELL}}
const directions=[1,-1,COLS,-COLS];
function makeField(goal,walls) {
 if(!navOpen){
  navOpen=new Uint8Array(COLS*ROWS);
  for(let id=0;id<navOpen.length;id++){const p=center(id);navOpen[id]=!blocked(p.x,p.y,6,walls)}
 }
 if(!navOpen[goal]){
  const p=center(goal);
  const nearest=directions.map(d=>goal+d).filter(i=>navOpen[i]).sort((a,b)=>dist(p,center(a))-dist(p,center(b)))[0];
  if(nearest===undefined)return null;goal=nearest;
 }
 if(navFields.has(goal))return navFields.get(goal);
 const depth=new Int16Array(navOpen.length);depth.fill(-1);
 const queue=new Int32Array(depth.length);queue[0]=goal;depth[goal]=0;
 let first=0,end=1;
 while(first<end){
  const cur=queue[first++];
  for(const d of directions){
   const next=cur+d;
   if(navOpen[next]&&depth[next]<0){depth[next]=depth[cur]+1;queue[end++]=next}
  }
 }
 if(navFields.size>=6)navFields.delete(navFields.keys().next().value);
 navFields.set(goal,depth);return depth;
}
function chase(z,target,speed,dt,walls){
 if(clearPath(z,target,walls)){steer(z,target.x-z.x,target.y-z.y,speed,dt,walls);return}
 prepWalls(walls);
 const depth=makeField(cellAt(target.x,target.y),walls);
 if(!depth)return;
 const from=cellAt(z.x,z.y);let next=-1;
 for(const d of directions){
  const n=from+d;
  if(depth[n]>=0&&(depth[from]<0||depth[n]<depth[from])&&(next<0||depth[n]<depth[next])&&clearPath(z,center(n),walls))next=n;
 }
 if(next>=0){const p=center(next);steer(z,p.x-z.x,p.y-z.y,speed,dt,walls)}
}

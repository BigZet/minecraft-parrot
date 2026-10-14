"use strict";
(() => {
  const stage = document.getElementById('stage');
  const gallery = document.getElementById('gallery');
  const withParrot = document.getElementById('with-parrot');
  const autoRotate = document.getElementById('auto-rotate');
  const defaults = {yaw: 2.52, pitch: .24, zoom: 1};
  let yaw = defaults.yaw, pitch = defaults.pitch, zoom = defaults.zoom;
  let hats = [], selected = 0, dragging = null, lastFrame = 0;
  const reference = [];
  const cube = (name, from, to, color) => ({name, from, to, color});
  reference.push(
    cube('body', [-1.5,-9.9,-.1], [1.5,-4.0,2.8], '#50ad22'),
    cube('chest', [-1.45,-8.7,-.35], [1.45,-4.8,.2], '#79cb2b'),
    cube('left_wing', [-2.1,-9.5,.25], [-1.5,-4.5,2.6], '#2e8123'),
    cube('right_wing', [1.5,-9.5,.25], [2.1,-4.5,2.6], '#2e8123'),
    cube('tail', [-1.3,-11.3,2.5], [1.3,-8.6,3.25], '#19783d'),
    cube('head', [-1,-4,-1], [1,-1,1], '#6acb28'),
    cube('upper_head', [-1,-1,-2], [1,0,2], '#7bd22c'),
    cube('beak', [-.55,-2.5,-2.8], [.55,-1.2,-1], '#272c20'),
    cube('left_eye', [-1.04,-1.75,-.8], [-.98,-1.05,-.12], '#111c16'),
    cube('right_eye', [.98,-1.75,-.8], [1.04,-1.05,-.12], '#111c16'),
    cube('left_leg', [-.92,-11.0,.6], [-.4,-9.7,1.1], '#777568'),
    cube('right_leg', [.4,-11.0,.6], [.92,-9.7,1.1], '#777568'),
    cube('left_foot', [-1.12,-11.1,-.35], [-.2,-10.8,1.2], '#868373'),
    cube('right_foot', [.2,-11.1,-.35], [1.12,-10.8,1.2], '#868373')
  );
  const faceDefinitions = [
    {n:[1,0,0], i:[1,5,6,2]}, {n:[-1,0,0], i:[4,0,3,7]},
    {n:[0,1,0], i:[3,2,6,7]}, {n:[0,-1,0], i:[4,5,1,0]},
    {n:[0,0,-1], i:[0,1,2,3]}, {n:[0,0,1], i:[5,4,7,6]}
  ];
  function rotated(v, yawValue, pitchValue) {
    const cy=Math.cos(yawValue), sy=Math.sin(yawValue), cp=Math.cos(pitchValue), sp=Math.sin(pitchValue);
    const x=v[0]*cy+v[2]*sy, z=-v[0]*sy+v[2]*cy;
    return [x,v[1]*cp-z*sp,v[1]*sp+z*cp];
  }
  function corners(c) {
    const [x,y,z]=c.from, [X,Y,Z]=c.to;
    return [[x,y,z],[X,y,z],[X,Y,z],[x,Y,z],[x,y,Z],[X,y,Z],[X,Y,Z],[x,Y,Z]];
  }
  function tinted(hex, light) {
    const rgb=[1,3,5].map(n=>parseInt(hex.slice(n,n+2),16));
    return `rgb(${rgb.map(c=>Math.round(Math.min(255,c*light))).join(',')})`;
  }
  function resize(canvas) {
    const ratio=Math.min(window.devicePixelRatio||1,2), rect=canvas.getBoundingClientRect();
    const width=Math.round(rect.width*ratio), height=Math.round(rect.height*ratio);
    if(width!==canvas.width||height!==canvas.height){canvas.width=width;canvas.height=height;}
    return [rect.width,rect.height,ratio];
  }
  function render(canvas, hat, view, bird, thumbnail=false) {
    const [width,height,ratio]=resize(canvas); if(!width||!height)return;
    const ctx=canvas.getContext('2d');ctx.setTransform(ratio,0,0,ratio,0,0);ctx.clearRect(0,0,width,height);
    const parts=bird?reference.concat(hat.cubes):hat.cubes;
    let minY=Infinity,maxY=-Infinity,minX=Infinity,maxX=-Infinity,minZ=Infinity,maxZ=-Infinity;
    for(const c of parts){minY=Math.min(minY,c.from[1]);maxY=Math.max(maxY,c.to[1]);minX=Math.min(minX,c.from[0]);maxX=Math.max(maxX,c.to[0]);minZ=Math.min(minZ,c.from[2]);maxZ=Math.max(maxZ,c.to[2]);}
    const midY=(minY+maxY)/2, midZ=(minZ+maxZ)/2, midX=(minX+maxX)/2;
    const ext=Math.max(maxY-minY,(maxX-minX)*1.3,(maxZ-minZ)*1.3);
    const scale=Math.min(height*(thumbnail ? .80 : .76),width*.82)/ext*view.zoom;
    const distance=40, center=[width*.5,height*.51];
    const shadowY=height*(thumbnail ? .90 : .88);
    ctx.save();ctx.translate(width*.5,shadowY);ctx.scale(1,.25);
    const shadow=ctx.createRadialGradient(0,0,0,0,0,Math.min(width*.23,height*.3));shadow.addColorStop(0,'rgba(0,0,0,.24)');shadow.addColorStop(1,'rgba(0,0,0,0)');
    ctx.fillStyle=shadow;ctx.beginPath();ctx.arc(0,0,Math.min(width*.23,height*.3),0,Math.PI*2);ctx.fill();ctx.restore();
    const faces=[];
    for(const part of parts){
      const vertices=corners(part).map(v=>rotated([v[0]-midX,v[1]-midY,v[2]-midZ],view.yaw,view.pitch));
      for(const def of faceDefinitions){
        const normal=rotated(def.n,view.yaw,view.pitch);if(normal[2]<=.0001)continue;
        const points=def.i.map(i=>{const v=vertices[i],p=distance/(distance-v[2]);return [center[0]+v[0]*scale*p,center[1]-v[1]*scale*p];});
        const depth=def.i.reduce((s,i)=>s+vertices[i][2],0)/4;
        const lit=.67+Math.max(0,def.n[0]*.3+def.n[1]*.78-def.n[2]*.38)*.35;
        faces.push({points,depth,color:tinted(part.color,lit)});
      }
    }
    faces.sort((a,b)=>a.depth-b.depth);
    for(const face of faces){ctx.beginPath();face.points.forEach((p,i)=>i?ctx.lineTo(...p):ctx.moveTo(...p));ctx.closePath();ctx.fillStyle=face.color;ctx.fill();ctx.strokeStyle=face.color;ctx.lineWidth=.4;ctx.stroke();}
  }
  function draw() {
    if(!hats.length)return;
    render(stage,hats[selected],{yaw,pitch,zoom},withParrot.checked);
  }
  function pick(index) {
    selected=index;const hat=hats[index];
    document.getElementById('stage-number').textContent=`${String(index+1).padStart(2,'0')} / ${String(hats.length).padStart(2,'0')}`;
    document.getElementById('hat-id').textContent=`МОДЕЛЬ ${String(index+1).padStart(2,'0')}`;
    document.getElementById('hat-name').textContent=hat.name;
    document.getElementById('hat-description').textContent=hat.description;
    const bb=document.getElementById('download-bb'),glb=document.getElementById('download-glb');
    bb.href=hat.files.bbmodel;glb.href=hat.files.glb||'#';glb.hidden=!hat.files.glb;
    gallery.querySelectorAll('.hat-card').forEach((b,i)=>b.setAttribute('aria-pressed',String(i===index)));
    draw();
  }
  stage.addEventListener('pointerdown',e=>{dragging={x:e.clientX,y:e.clientY};stage.setPointerCapture(e.pointerId);stage.classList.add('dragging');});
  stage.addEventListener('pointermove',e=>{if(!dragging)return;yaw+=(e.clientX-dragging.x)*.012;pitch=Math.max(-.55,Math.min(1.2,pitch+(e.clientY-dragging.y)*.008));dragging={x:e.clientX,y:e.clientY};draw();});
  function release(){dragging=null;stage.classList.remove('dragging');}
  stage.addEventListener('pointerup',release);stage.addEventListener('pointercancel',release);
  stage.addEventListener('wheel',e=>{e.preventDefault();zoom=Math.max(.55,Math.min(2.4,zoom*Math.exp(-e.deltaY*.001)));draw();},{passive:false});
  withParrot.addEventListener('change',draw);
  document.getElementById('reset').addEventListener('click',()=>{yaw=defaults.yaw;pitch=defaults.pitch;zoom=defaults.zoom;draw();});
  const observer=new ResizeObserver(()=>{draw();hats.forEach((hat,i)=>{const canvas=gallery.children[i]?.querySelector('canvas');if(canvas)render(canvas,hat,{...defaults,zoom:1},true,true);});});
  observer.observe(stage);observer.observe(gallery);
  function frame(time){if(autoRotate.checked&&!dragging&&lastFrame){yaw+=(time-lastFrame)*.00035;draw();}lastFrame=time;requestAnimationFrame(frame);}
  requestAnimationFrame(frame);
  fetch('catalog.json').then(response=>{if(!response.ok)throw new Error('Не удалось открыть каталог моделей');return response.json();}).then(catalog=>{
    hats=catalog.hats;
    if(!Array.isArray(hats)||!hats.length)throw new Error('Каталог моделей пуст');
    hats.forEach((hat,index)=>{
      const button=document.createElement('button');button.type='button';button.className='hat-card';button.setAttribute('aria-pressed','false');button.setAttribute('aria-label',`${index+1}. ${hat.name}`);
      const number=document.createElement('span');number.className='number';number.textContent=String(index+1).padStart(2,'0');
      const canvas=document.createElement('canvas');canvas.setAttribute('aria-hidden','true');
      const name=document.createElement('span');name.className='name';name.textContent=hat.name;
      button.append(number,canvas,name);button.addEventListener('click',()=>pick(index));gallery.append(button);
      render(canvas,hat,{...defaults,zoom:1},true,true);
    });
    pick(0);document.documentElement.dataset.ready='true';
  }).catch(error=>{const box=document.getElementById('error');box.textContent=error.message;box.hidden=false;document.getElementById('hat-name').textContent='Не удалось загрузить модели';});
})();

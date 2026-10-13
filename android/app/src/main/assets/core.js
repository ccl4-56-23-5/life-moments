(function(scope){
'use strict';
function normalized(doc,{demo=false}={}){
 if(!doc||doc.schemaVersion!==2||!Array.isArray(doc.photos)||!doc.index||typeof doc.libraryId!=='string')throw Error('索引版本不兼容，请从当前电脑端重新导出。');
 const i=doc.index;
 if(!Array.isArray(i.people)||!Array.isArray(i.albums)||i.people.some(n=>typeof n!=='string')||i.albums.some(a=>!a||typeof a.name!=='string'||!Array.isArray(a.ids)))throw Error('索引人物或相册格式无效。');
 for(const field of ['profiles','assignments','notes','photoLocations'])if(i[field]&&(typeof i[field]!=='object'||Array.isArray(i[field])))throw Error('索引字段格式无效。');
 const seen=new Set(),photos=doc.photos.map(p=>{
  const id=String(p.id);if(!/^\d{1,15}$/.test(id)||!Number.isSafeInteger(Number(id))||seen.has(id))throw Error('照片编号重复或无效。');seen.add(id);
  if(typeof p.cloudPath!=='string'||!p.cloudPath.startsWith('/')||p.cloudPath.split('/').includes('..'))throw Error('照片云端路径无效。');
  const q={...p,id,width:Math.max(1,Number(p.width)||1),height:Math.max(1,Number(p.height)||1)};
  delete q.path;if(!demo){delete q.image;delete q.fullImage;}return q;
 });
 return {...doc,photos,index:{people:Array.isArray(i.people)?i.people.filter(n=>typeof n==='string'):[],profiles:i.profiles||{},assignments:i.assignments||{},notes:i.notes||{},photoLocations:i.photoLocations||{},gpsCrs:i.gpsCrs||'WGS84',albums:Array.isArray(i.albums)?i.albums:[]}};
}
function date(p){return String(p.date||p.time?.slice(0,10)||'未知日期').replace(/^(\d{4}):(\d{2}):(\d{2})/,'$1-$2-$3')}
function people(p,index){return Array.isArray(index.assignments[p.id])?index.assignments[p.id].filter(n=>typeof n==='string'):[]}
const collator=new Intl.Collator('zh-Hans-CN',{numeric:true,sensitivity:'base'});
function filtered(doc,state){
 let photos=doc.photos;
 if(state.view==='person')photos=photos.filter(p=>people(p,doc.index).includes(state.person));
 if(state.view==='album'){const ids=new Set((doc.index.albums[state.album]?.ids||[]).map(String));photos=photos.filter(p=>ids.has(p.id));}
 if(state.mapIds){const ids=new Set(state.mapIds.map(String));photos=photos.filter(p=>ids.has(p.id));}
 const query=state.query.trim().toLocaleLowerCase();if(query)photos=photos.filter(p=>[p.name,date(p),doc.index.notes[p.id],...people(p,doc.index)].join(' ').toLocaleLowerCase().includes(query));
 return photos.slice().sort((a,b)=>state.sort.startsWith('name')?(state.sort==='name-desc'?-1:1)*collator.compare(a.name,b.name):(state.sort==='time-asc'?1:-1)*(String(a.time||date(a)).localeCompare(String(b.time||date(b)))||collator.compare(a.name,b.name)));
}
function groups(photos,unit,sort){
 if(sort.startsWith('name'))return [{key:'全部照片',photos}];
 const grouped=new Map();for(const p of photos){const d=date(p),key=unit==='year'?d.slice(0,4):unit==='day'?d:d.slice(0,7);if(!grouped.has(key))grouped.set(key,[]);grouped.get(key).push(p)}
 return [...grouped].map(([key,photos])=>({key,photos}));
}
function rows(photos,width,target=130,gap=6){
 const result=[];let row=[],sum=0;
 const flush=last=>{if(!row.length)return;const height=Math.min(last?target:target*1.6,(width-gap*(row.length-1))/sum);result.push({height,items:row.map(p=>({photo:p,width:height*p.width/p.height}))});row=[];sum=0;};
 for(const p of photos){row.push(p);sum+=p.width/p.height;if(sum*target+gap*(row.length-1)>=width)flush(false)}flush(true);return result;
}
function valid(c){return Array.isArray(c)&&c.length===2&&c.every(Number.isFinite)&&Math.abs(c[0])<=180&&Math.abs(c[1])<=90}
function forward([lng,lat]){if(lng<72.004||lng>137.8347||lat<.8293||lat>55.8271)return [lng,lat];const x=lng-105,y=lat-35,pi=Math.PI;let a=-100+2*x+3*y+.2*y*y+.1*x*y+.2*Math.sqrt(Math.abs(x)),b=300+x+2*y+.1*x*x+.1*x*y+.1*Math.sqrt(Math.abs(x));const common=(20*Math.sin(6*x*pi)+20*Math.sin(2*x*pi))*2/3;a+=common+(20*Math.sin(y*pi)+40*Math.sin(y/3*pi))*2/3+(160*Math.sin(y/12*pi)+320*Math.sin(y*pi/30))*2/3;b+=common+(20*Math.sin(x*pi)+40*Math.sin(x/3*pi))*2/3+(150*Math.sin(x/12*pi)+300*Math.sin(x/30*pi))*2/3;const rad=lat*pi/180,magic=1-.00669342162296594323*Math.sin(rad)**2,sqrt=Math.sqrt(magic);return [lng+b*180/(6378245/sqrt*Math.cos(rad)*pi),lat+a*180/(6378245*(1-.00669342162296594323)/(magic*sqrt)*pi)]}
function inverse(c){let out=c.slice();for(let i=0;i<8;i++){const f=forward(out),dx=f[0]-c[0],dy=f[1]-c[1];out=[out[0]-dx,out[1]-dy];if(Math.max(Math.abs(dx),Math.abs(dy))<1e-9)break}return out}
function coordinates(p,index){const override=index.photoLocations[p.id]||{};if(valid(override.manual))return override.manual.slice();if(!valid(p.gps))return null;let c=p.gps,crs=override.crs||index.gpsCrs;if(crs==='BD09'){const x=c[0]-.0065,y=c[1]-.006,pi=Math.PI*3000/180,z=Math.sqrt(x*x+y*y)-.00002*Math.sin(y*pi),theta=Math.atan2(y,x)-.000003*Math.cos(x*pi);c=[z*Math.cos(theta),z*Math.sin(theta)];crs='GCJ02'}return crs==='GCJ02'?inverse(c):c.slice()}
const api={normalized,date,people,filtered,groups,rows,coordinates,valid};if(typeof module!=='undefined')module.exports=api;scope.ReaderCore=api;
})(typeof window!=='undefined'?window:globalThis);

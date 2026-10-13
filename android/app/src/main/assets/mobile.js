'use strict';
const C=ReaderCore,content=document.querySelector('#content'),viewer=document.querySelector('#viewer');
const paths={photos:'M4 4h16v16H4z M4 15l4-4 4 4 3-3 5 5 M9 8h.01',map:'M3 6l6-3 6 3 6-3v15l-6 3-6-3-6 3z M9 3v15 M15 6v15',people:'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2 M16 3a4 4 0 0 1 0 8 M22 21v-2a4 4 0 0 0-3-3.87 M13 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0',albums:'M3 5h7l2 3h9v12H3z',refresh:'M20 7v5h-5 M4 17v-5h5 M5.5 8a7 7 0 0 1 11.55-3L20 8 M4 16l2.95 3A7 7 0 0 0 18.5 16',settings:'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8 M9 3h6l1 3 3 1 2 5-2 5-3 1-1 3H9l-1-3-3-1-2-5 2-5 3-1z',search:'M21 21l-5-5 M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0',back:'M15 18l-6-6 6-6',next:'M9 18l6-6-6-6',cloud:'M7 18a5 5 0 1 1 1-10 6 6 0 0 1 11 3 3.5 3.5 0 1 1-1 7z',lock:'M6 10h12v11H6z M8 10V6a4 4 0 0 1 8 0v4',info:'M12 16v-4 M12 8h.01 M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0',close:'M6 6l12 12 M18 6L6 18',download:'M12 3v12 M7 10l5 5 5-5 M4 17v4h16v-4',wifi:'M2 8a16 16 0 0 1 20 0 M5 12a11 11 0 0 1 14 0 M8.5 15.5a5 5 0 0 1 7 0 M12 20h.01',file:'M14 2H6v20h12V6z M14 2v5h5 M9 12h6 M9 16h6',book:'M12 4v17 M3 3c4-1 6 0 9 2 3-2 5-3 9-2v16c-4-1-6 0-9 2-3-2-5-3-9-2z',trash:'M3 6h18 M9 6V3h6v3 M5 6l1 15h12l1-15 M10 10v7 M14 10v7',pin:'M20 10c0 6-8 12-8 12S4 16 4 10a8 8 0 1 1 16 0 M15 10a3 3 0 1 1-6 0 3 3 0 0 1 6 0',zoom:'M12 5v14 M5 12h14',sort:'M8 4v16 M4 16l4 4 4-4 M15 4h6 M15 9h5 M15 14h4'};
const icon=name=>`<svg viewBox="0 0 24 24" aria-hidden="true"><path d="${paths[name]||paths.photos}"/></svg>`;
const esc=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
let doc=null,demo=false,mobileStatus={connected:false,busy:false,text:'尚未连接百度网盘',wifiImages:true,wifi:true},observer,map=null,miniMap=null,mapCamera=null,mapMarkers=[],galleryList=[],viewerIds=[],originals=new Set(),toastTimer,searchTimer;
const preferenceChoices={sort:['time-desc','time-asc','name-asc','name-desc'],group:['day','month','year'],albumSort:['created','name','count']};
function checkedPreferences(value,strict=false){
 if(!value||typeof value!=='object'||Array.isArray(value))throw Error('浏览设置格式无效。');
 const result={};for(const key of Object.keys(value)){if(!Object.prototype.hasOwnProperty.call(preferenceChoices,key)){if(strict)throw Error('包含不支持的浏览设置。');continue;}if(!preferenceChoices[key].includes(value[key])){if(strict)throw Error('浏览设置值无效。');continue;}result[key]=value[key];}return result;
}
let preferences={};try{preferences=checkedPreferences(JSON.parse(localStorage.getItem('reader-preferences')||'{}'))}catch{}
const state={view:'photos',query:'',sort:preferences.sort||'time-desc',group:preferences.group||'month',personTag:'',person:'',album:-1,mapIds:null};
let previousView='photos',returnScroll=0,photoScroll=0,scale=1,panX=0,panY=0,infoOpen=false,loadingOriginal=false;
let photoMap=new Map(),mappedDoc=null,imageQueue=[],activeImages=0,motionKey=null,motionFrame=0,motions=[];
let avatarDoc=null,avatarCache=new Map(),highResTimer,longPressTimer,longPressStart=null,suppressClickUntil=0,peopleLimit=12,peopleQuery='';
let imagesPaused=document.hidden,resumeTimer;const imageRequests=new Map();
const thumbnailMemo=new Map();let fullObjectURL=null;
function clearImageMemory(){for(const url of thumbnailMemo.values())URL.revokeObjectURL(url);thumbnailMemo.clear();if(fullObjectURL){URL.revokeObjectURL(fullObjectURL);fullObjectURL=null;}}
const motionPreference=matchMedia('(prefers-reduced-motion: reduce)'),motionEasing='cubic-bezier(.22,1,.36,1)';
function photoFor(id){if(mappedDoc!==doc){mappedDoc=doc;photoMap=new Map((doc?.photos||[]).map(p=>[p.id,p]));}return photoMap.get(String(id));}
function cancelMotion(){cancelAnimationFrame(motionFrame);motions.forEach(a=>a.cancel());motions=[];}
function pageMotion(key,large=false){
 if(key===motionKey)return;motionKey=key;cancelMotion();if(motionPreference.matches)return;
 motionFrame=requestAnimationFrame(()=>{const root=large?viewer:content,targets=[];
  for(const node of root.querySelectorAll(large?'.viewer-head,.photo-viewport,.viewer-people,.viewer-bar':'.pagehead,.timeline-bar,.backline,.detail-title,.tagfilters,.photo-row,.person-card,.album-card,.welcome,.empty,.settings-section,.map-card')){
   const r=node.getBoundingClientRect();if(r.bottom>0&&r.top<innerHeight){targets.push(node);if(targets.length===14)break;}
  }
  for(const node of targets){const fixed=node.matches('.photo-row,.map-card,.timeline-bar,.photo-viewport');const from={opacity:.35},to={opacity:1};if(!fixed){from.transform='translateY(9px)';to.transform='translateY(0)';}motions.push(node.animate([from,to],{duration:large?240:260,easing:motionEasing}));}
 });
}
motionPreference.addEventListener('change',()=>{if(motionPreference.matches)cancelMotion();});
function native(method,...args){try{if(window.Mobile&&typeof Mobile[method]==='function')return Mobile[method](...args);}catch(error){if(!['releasePreview','mediaState'].includes(method))notice('操作暂未完成，请重试。');console.warn('Native operation failed:',method);return;}if(method==='configure')notice('浏览器预览不连接网盘，请安装 APK 后在设置中连接。');else if(method==='guide')location.href='guide.html';else if(method==='importIndex')browserImport();else if(method==='refresh')notice('浏览器预览不拉取云端数据，请在安卓 App 中刷新索引。');else if(method==='wifiImages'){mobileStatus.wifiImages=args[0];renderSettings();}else if(method==='clearCache')notice('浏览器预览没有云端照片缓存。');else if(['transferDevices','cacheAllThumbnails','cancelThumbnailCache'].includes(method))notice('请在最新版安卓 App 中使用此功能。');}
function notice(text){const toast=document.querySelector('#toast');toast.textContent=text;toast.hidden=false;clearTimeout(toastTimer);toastTimer=setTimeout(()=>toast.hidden=true,4200)}
window.mobileNotice=value=>notice(value.text);
window.cacheCleared=()=>{clearImageMemory();avatarCache.clear();};
function remember(){preferences={...preferences,sort:state.sort,group:state.group};try{localStorage.setItem('reader-preferences',JSON.stringify(preferences))}catch{}}
window.exportLocalSettings=()=>JSON.stringify({schemaVersion:1,preferences:checkedPreferences({...preferences,sort:state.sort,group:state.group})});
window.importLocalSettings=value=>{try{const imported=typeof value==='string'?JSON.parse(value):value;if(!imported||imported.schemaVersion!==1||Object.keys(imported).some(key=>!['schemaVersion','preferences'].includes(key)))throw Error('浏览设置版本或字段无效。');const safe=checkedPreferences(imported.preferences,true);localStorage.setItem('reader-preferences',JSON.stringify(safe));preferences=safe;state.sort=safe.sort||'time-desc';state.group=safe.group||'month';render(false);return JSON.stringify({ok:true});}catch(error){return JSON.stringify({ok:false,error:error.message||'浏览设置无法读取。'});}};
function image(p,full=false){if(demo&&/^data:image\/(jpeg|png|webp);base64,/.test(p.image||''))return p.image;return `https://${window.Mobile?'appassets.androidplatform.net':location.host}/media/${encodeURIComponent(doc.libraryId)}/${p.id}/${full?'full':'thumb'}`;}
function heading(title,count){return `<div class="pagehead"><div><h1>${esc(title)}</h1><p class="subtitle">${esc(count)}</p></div></div>`}
function demoNote(){return demo?'<div class="demo-note"><span>样例浏览 · 人物和相册为演示标注</span><button data-action="exitdemo">退出样例</button></div>':''}
function empty(title,text=''){return `<div class="empty">${icon('photos')}<h2>${esc(title)}</h2><p>${esc(text)}</p></div>`}
function renderNavigation(){document.querySelector('.bottomnav').innerHTML='<div class="nav-brand"><img src="logo.svg" alt=""><span>生活点滴</span></div>'+[['photos','照片'],['map','地点'],['people','人物'],['albums','相册']].map(([name,title])=>`<button data-view="${name}" aria-current="${state.view===name||(name==='people'&&state.view==='person')||(name==='albums'&&state.view==='album')?'page':'false'}">${icon(name)}<span>${title}</span></button>`).join('')+`<button class="nav-settings" data-view="settings" aria-current="${state.view==='settings'?'page':'false'}">${icon('settings')}<span>设置</span></button>`;}
function disposeMap(){if(map){mapCamera={center:map.getCenter().toArray(),zoom:map.getZoom()};map.remove();map=null;}mapMarkers.forEach(m=>m.remove());mapMarkers=[];}
function render(animate=true){
 observer?.disconnect();disposeMap();renderNavigation();
 content.dataset.view=state.view;
 document.querySelector('.searchbox').hidden=state.view==='settings';
 document.querySelector('.searchbox input').value=state.query;
 if(state.view==='settings')renderSettings();else if(!doc)renderWelcome();else if(state.view==='photos')renderPhotos();else if(state.view==='people')renderPeople();else if(state.view==='albums')renderAlbums();else if(state.view==='person'||state.view==='album')renderDetail();else renderMap();
 if(animate)pageMotion(`${state.view}:${state.view==='person'?state.person:state.view==='album'?state.album:''}:${demo}`);
 mountImages();headerSize();
}
function headerSize(){document.documentElement.style.setProperty('--header-height',document.querySelector('.topbar').getBoundingClientRect().height+'px')}
function navigate(view){if(state.view==='photos')photoScroll=window.scrollY;state.view=view;state.mapIds=null;state.query='';state.personTag='';render();window.scrollTo(0,view==='photos'?photoScroll:0)}
function renderWelcome(){content.innerHTML=`<div class="welcome"><img src="logo.svg" alt="生活点滴"><h1>把生活，带在身边</h1><p>连接百度网盘，浏览电脑整理好的照片、人物和相册。</p><button class="primary" data-action="configure">${icon('cloud')}连接百度网盘</button><button class="textbutton" data-action="demo">先看看样例</button><div class="welcome-features"><div>${icon('photos')}<section><h3>沿着时间，看见生活</h3><p>日、月、年分组，完整照片连续浏览</p></section></div><div>${icon('people')}<section><h3>熟悉的人，熟悉的回忆</h3><p>电脑标注的人物、头像与批注同步可见</p></section></div><div>${icon('lock')}<section><h3>安心浏览</h3><p>照片长按可下载到手机</p></section></div></div><button class="textbutton small" data-action="guide">查看数据拉取教程</button></div>`;}
function grid(photos){
 const padding=getComputedStyle(content),width=Math.max(240,content.clientWidth-parseFloat(padding.paddingLeft)-parseFloat(padding.paddingRight)),target=width<500?128:180;
 return C.rows(photos,width,target).map(row=>`<div class="photo-row" style="height:${row.height.toFixed(2)}px">${row.items.map(({photo:p,width})=>`<button class="photo-tile" data-photo="${p.id}" aria-label="查看 ${esc(p.name)}" style="width:${width.toFixed(2)}px"><img data-photo-src="${p.id}" alt="${esc(p.name)}" decoding="async"></button>`).join('')}</div>`).join('');
}
function titleFor(key){if(/^\d{4}-\d{2}-\d{2}$/.test(key))return key.replace(/^(\d{4})-(\d{2})-(\d{2})$/,'$1年$2月$3日');if(/^\d{4}-\d{2}$/.test(key))return key.replace(/^(\d{4})-(\d{2})$/,'$1年$2月');return /^\d{4}$/.test(key)?key+'年':key;}
function sortSelect(){return `<select id="photo-sort" aria-label="照片排序">${[['time-desc','时间 ↓'],['time-asc','时间 ↑'],['name-asc','名称 A–Z'],['name-desc','名称 Z–A']].map(([v,t])=>`<option value="${v}" ${state.sort===v?'selected':''}>${t}</option>`).join('')}</select>`}
function renderPhotos(){
 galleryList=C.filtered(doc,state);const groups=C.groups(galleryList,state.group,state.sort);
 content.innerHTML=demoNote()+heading('照片',`${galleryList.length.toLocaleString()} 张照片${state.query?' · 搜索结果':''}`)+`<div class="timeline-bar"><div class="segments" aria-label="时间分组">${[['day','日'],['month','月'],['year','年']].map(([v,t])=>`<button data-group="${v}" aria-pressed="${state.group===v}">${t}</button>`).join('')}</div><select id="date-jump" aria-label="跳转日期"><option value="">跳转日期</option>${groups.map((g,i)=>`<option value="g${i}">${esc(titleFor(g.key))}</option>`).join('')}</select>${sortSelect()}</div>`+(galleryList.length?groups.map((g,i)=>`<section class="timeline-section" id="g${i}"><div class="grouphead"><h2>${esc(titleFor(g.key))}</h2><small>${g.photos.length} 张</small></div>${grid(g.photos)}</section>`).join('')+'<p class="end">所有照片都在这里 · 随时回看生活</p>':empty('没有找到照片','试试其他名字、日期或批注。'));
}
function profile(name){return doc.index.profiles[name]||{}}
function avatar(name,small=false){const a=profile(name).avatar,size=small?48:166;return `<span class="avatar${small?' avatar-small':''}">${a?`<canvas width="${size}" height="${size}" data-avatar="${esc(name)}" role="img" aria-label="${esc(name)}的头像"></canvas>`:esc(name.slice(0,1))}</span>`}
function tags(name){return `<span class="chips">${(Array.isArray(profile(name).tags)?profile(name).tags:[]).map(t=>`<span class="chip">${esc(t)}</span>`).join('')}</span>`}
function renderPeople(){
 const index=doc.index,allTags=[...new Set(index.people.flatMap(n=>profile(n).tags||[]))].filter(t=>typeof t==='string');
 const query=state.query.toLowerCase(),names=index.people.filter(n=>(!state.personTag||(profile(n).tags||[]).includes(state.personTag))&&(!query||(n+' '+(profile(n).tags||[]).join(' ')).toLowerCase().includes(query)));
 content.innerHTML=demoNote()+heading('人物',`${index.people.length} 位人物`)+(allTags.length?`<div class="tagfilters">${['',...allTags].map(t=>`<button data-tag="${esc(t)}" aria-pressed="${state.personTag===t}">${esc(t||'全部')}</button>`).join('')}</div>`:'')+(names.length?`<div class="cards">${names.map(n=>`<button class="person-card" data-person="${esc(n)}">${avatar(n)}<strong>${esc(n)}</strong><small>${doc.photos.filter(p=>C.people(p,index).includes(n)).length} 张照片</small>${tags(n)}</button>`).join('')}</div>`:empty('暂无符合条件的人物','人物和标签在电脑端整理，刷新索引后显示。'));
}
function renderAlbums(){
 const query=state.query.toLowerCase(),albums=doc.index.albums.map((a,i)=>({a,i})).filter(({a})=>!query||(a.name+' '+(a.note||'')).toLowerCase().includes(query));
 if(preferences.albumSort==='name')albums.sort((x,y)=>String(x.a.name).localeCompare(String(y.a.name),'zh-Hans-CN'));
 if(preferences.albumSort==='count')albums.sort((x,y)=>(y.a.ids||[]).length-(x.a.ids||[]).length);
 content.innerHTML=demoNote()+heading('相册',`${doc.index.albums.length} 本相册`)+`<div class="pagehead"><select id="album-sort" aria-label="相册排序">${[['created','创建顺序'],['name','名称'],['count','照片数量']].map(([v,t])=>`<option value="${v}" ${(preferences.albumSort||'created')===v?'selected':''}>${t}</option>`).join('')}</select></div>`+(albums.length?`<div class="cards">${albums.map(({a,i})=>{const ids=new Set((a.ids||[]).map(String)),photos=doc.photos.filter(p=>ids.has(p.id)),p=photos[0];return `<button class="album-card" data-album="${i}">${p?`<img class="album-cover" data-photo-src="${p.id}" alt="${esc(a.name)}">`:'<span class="album-cover"></span>'}<span class="title">${esc(a.name)}</span><small>${photos.length} 张照片${a.note?' · 有批注':''}</small></button>`}).join('')}</div>`:empty('暂无相册','在电脑端建立相册后，手机会显示相同的整理结果。'));
}
function renderDetail(){
 galleryList=C.filtered(doc,state);const person=state.view==='person',album=doc.index.albums[state.album];
 if(!person&&!album){navigate('albums');return;}
 content.innerHTML=demoNote()+`<button class="backline" data-action="parent">${icon('back')}返回${person?'人物':'相册'}</button><div class="detail-title">${person?avatar(state.person):''}<div><h1>${esc(person?state.person:album.name)}</h1><p class="small muted">${galleryList.length} 张照片</p>${person?tags(state.person):''}</div></div>${!person&&album.note?`<p class="note">${esc(album.note)}</p>`:''}<div class="pagehead">${sortSelect()}</div>`+(galleryList.length?grid(galleryList):empty('没有符合条件的照片'));
}
function settingsRow(action,name,detail,ic,extra='',tail=''){const key=['cacheAllThumbnails','cancelThumbnailCache'].includes(action)?'thumbnail-warmup':action;return `<button class="setting-row" data-setting-key="${key}" data-action="${action}">${icon(ic)}<span class="setting-label"><span class="setting-title">${esc(name)}</span>${detail?`<small>${esc(detail)}</small>`:''}${tail}</span>${extra||icon('next')}</button>`}
function warmupRow(){
 const w=mobileStatus.thumbnailWarmup||{},automatic=mobileStatus.thumbnailWarmupAuto!==false,running=automatic&&['running','waiting','paused'].includes(w.state);
 const labels={running:'正在缓存全部缩略图',waiting:'等待网络后继续缓存',paused:automatic?'等待条件恢复后继续缓存':'全图库缓存已暂停',complete:w.failed?'缩略图扫描完成':'全部缩略图缓存完成',cancelled:'已暂停全图库缓存',error:'缩略图缓存待重试',limited:'缓存空间达到上限',capacitylimit:'缓存空间达到上限',capacity_limit:'缓存空间达到上限'},reason=w.reason||w.text;
 const total=Math.max(0,Number(w.total)||0),done=Math.min(total,Math.max(0,Number(w.done??w.cached)||0));
 const progress=total?`<span class="setting-progress" role="progressbar" aria-label="缩略图缓存进度" aria-valuemin="0" aria-valuemax="${total}" aria-valuenow="${done}"><span style="width:${(done/total*100).toFixed(1)}%"></span></span>`:'';
 return settingsRow(running?'cancelThumbnailCache':'cacheAllThumbnails',labels[w.state]||'缓存全部缩略图',total?`${done} / ${total} 张${w.failed?' · '+Number(w.failed)+' 张待重试':''}${reason&&w.state!=='running'&&w.state!=='complete'?' · '+String(reason):''} · ${running?'点击暂停':'点击继续或重试'}`:'启动后自动缓存图库缩略图；可手动重试','photos','',progress);
}
function renderSettings(statusOnly=false){
 const s=mobileStatus;
 const markup=heading('设置','生活点滴 · 0.1.0-beta.5')+demoNote()+`<section class="settings-section" data-settings-section="cloud"><p class="section-label">百度网盘</p><div class="settings-card">${settingsRow('configure',s.connected?'已连接百度网盘':'连接百度网盘',s.connected?s.folder:'复用电脑端现有应用','cloud')}${settingsRow('refresh','刷新索引',s.busy?s.text:s.lastSync?'上次更新：'+s.lastSync:'下载最新完整的整理信息','refresh')}<div class="setting-row static" data-setting-key="connection-status">${icon('info')}<span class="setting-label small">${esc(s.text)}${s.connected&&!s.bound?'<small>连接后需刷新或导入索引，再读取云端照片。</small>':''}</span></div>${s.connected?settingsRow('disconnect','断开连接','保留手机端索引','lock'):''}</div><p class="settings-footnote">整理信息从电脑同步。照片长按可保存到手机相册。</p></section><section class="settings-section" data-settings-section="cache"><p class="section-label">浏览与缓存</p><div class="settings-card">${settingsRow('wifi','仅在 Wi-Fi 下自动加载照片','缩略图缓存 · 高清图仅在浏览内存中显示','wifi',`<span class="switch" role="switch" aria-label="仅在 Wi-Fi 下自动加载照片" aria-checked="${s.wifiImages}"><span></span></span>`)}${warmupRow()}${settingsRow('clearCache','清理照片缓存',`缩略图 ${((s.thumbnailCacheBytes||0)/1048576).toFixed(1)} MiB · 总缓存 ${(s.cacheBytes||0)/1048576>=1?((s.cacheBytes||0)/1048576).toFixed(1):'0'} MiB · 索引保留`,'trash')}${settingsRow('importIndex','导入电脑端索引','选择电脑导出的 JSON 文件','file')}</div></section><section class="settings-section" data-settings-section="transfer"><p class="section-label">设备迁移</p><div class="settings-card">${settingsRow('transferDevices','扫描局域网设备','在同一 Wi-Fi 下复制网盘连接与全部应用设置','wifi')}</div><p class="settings-footnote">双方确认配对后传输；连接信息在新设备上加密保存。</p></section><section class="settings-section" data-settings-section="help"><p class="section-label">帮助</p><div class="settings-card">${settingsRow('guide','手机端拉取数据教程','从电脑发布到手机读取，逐步操作','book')}${settingsRow('demo','浏览样例','35 张照片 · 演示人物与相册','photos')}</div></section><div class="credits"><img src="logo.svg" alt="">生活点滴 Beta<br>电脑整理，手机随时浏览<br><a href="https://www.maptiler.com/copyright/">© MapTiler</a> · <a href="https://www.openstreetmap.org/copyright">© OpenStreetMap 贡献者</a><br>地图使用电脑端相同的 MapTiler 样式<br>地图引擎：MapLibre · 应用图标沿用电脑端</div>`;
 if(!statusOnly||!content.querySelector('[data-settings-section]')){content.innerHTML=markup;return;}
 // Preserve the row buttons and their focus/pointer targets while native progress updates.
 const next=document.createElement('template');next.innerHTML=markup;const present=new Map([...content.querySelectorAll('[data-setting-key]')].map(row=>[row.dataset.settingKey,row]));
 for(const row of next.content.querySelectorAll('[data-setting-key]')){const old=present.get(row.dataset.settingKey);if(!old){const section=content.querySelector(`[data-settings-section="${row.closest('[data-settings-section]').dataset.settingsSection}"] .settings-card`);section?.append(row.cloneNode(true));continue;}present.delete(row.dataset.settingKey);if(row.dataset.action)old.dataset.action=row.dataset.action;const label=old.querySelector('.setting-label'),value=row.querySelector('.setting-label');if(label.innerHTML!==value.innerHTML)label.innerHTML=value.innerHTML;const toggle=old.querySelector('.switch');if(toggle)toggle.setAttribute('aria-checked',row.querySelector('.switch').getAttribute('aria-checked'));}
 for(const row of present.values())row.remove();
}
const MAP_STYLE=window.lifeMomentsConfig?.mapStyleUrl||{version:8,sources:{},layers:[{id:'background',type:'background',paint:{'background-color':'#eef1f5'}}]};
let heat=true;
function features(){return doc.photos.map(p=>({p,c:C.coordinates(p,doc.index)})).filter(({c})=>c).map(({p,c})=>({type:'Feature',geometry:{type:'Point',coordinates:c},properties:{id:p.id}}));}
function renderMap(){
 const points=features();galleryList=C.filtered(doc,state);
 content.innerHTML=demoNote()+heading('地点',`${points.length} 张照片有位置信息`)+(points.length?'<div class="map-card"><div id="photo-map" class="map"></div><div class="map-message" id="map-message">正在加载地图…</div></div><div class="map-control"><button data-action="heat" aria-pressed="true">热力图</button><button data-action="fitmap">查看全部地点</button></div><div id="map-results" class="map-results"></div>':empty('暂无位置信息','电脑端读取或校准的位置会随索引同步。'));
 if(points.length)requestAnimationFrame(()=>setupMap(points));
}
function setupMap(points){
 if(!document.querySelector('#photo-map'))return;
 try{
 map=new maplibregl.Map({container:'photo-map',style:MAP_STYLE,center:mapCamera?.center||points[0].geometry.coordinates,zoom:mapCamera?.zoom||10,attributionControl:false});
 map.addControl(new maplibregl.NavigationControl({showCompass:false}),'bottom-right');
 map.on('error',()=>{const msg=document.querySelector('#map-message');if(msg){msg.hidden=false;msg.textContent='地图暂时无法加载，请检查网络；照片和索引仍可浏览。'}});
 map.on('load',()=>{
  if(!map)return;map.addSource('photos',{type:'geojson',data:{type:'FeatureCollection',features:points},cluster:true,clusterMaxZoom:13,clusterRadius:48});
  map.addLayer({id:'heat',type:'heatmap',source:'photos',maxzoom:14,paint:{'heatmap-weight':['case',['has','point_count'],['get','point_count'],1],'heatmap-intensity':1.2,'heatmap-radius':28,'heatmap-opacity':heat?.55:0}});
  map.addLayer({id:'clusters',type:'circle',source:'photos',filter:['has','point_count'],paint:{'circle-color':'#007aff','circle-radius':['step',['get','point_count'],19,30,24,100,28],'circle-stroke-width':3,'circle-stroke-color':'#fff'}});
  if(typeof MAP_STYLE==='string'||MAP_STYLE.glyphs)map.addLayer({id:'counts',type:'symbol',source:'photos',filter:['has','point_count'],layout:{'text-field':'{point_count_abbreviated}','text-size':12},paint:{'text-color':'white'}});
  map.addLayer({id:'points',type:'circle',source:'photos',filter:['!',['has','point_count']],paint:{'circle-color':'#007aff','circle-radius':7,'circle-stroke-width':2,'circle-stroke-color':'white'}});
  const message=document.querySelector('#map-message');message.hidden=!!window.lifeMomentsConfig?.mapStyleUrl;if(!message.hidden)message.textContent='地图背景未配置，仍可查看照片位置';document.querySelector('#photo-map').dataset.loaded='true';
  map.on('click','clusters',async e=>{const f=e.features[0],source=map.getSource('photos');const zoom=await source.getClusterExpansionZoom(f.properties.cluster_id);map.easeTo({center:f.geometry.coordinates,zoom});});
  map.on('click','points',e=>showLocation(e.features.map(f=>String(f.properties.id))));
  map.on('moveend',mapThumbnails);if(!mapCamera)fitMap();mapThumbnails();
 });
 }catch{notice('地图引擎无法启动，请更新 Android System WebView。');}
}
function fitMap(){if(!map)return;const fs=features(),bounds=new maplibregl.LngLatBounds();fs.forEach(f=>bounds.extend(f.geometry.coordinates));map.fitBounds(bounds,{padding:45,maxZoom:14,duration:500});}
function mapThumbnails(){
 if(!map||!map.getLayer('points'))return;mapMarkers.forEach(m=>m.remove());mapMarkers=[];
 if(map.getZoom()<14)return;const groups=new Map();
 for(const f of map.queryRenderedFeatures({layers:['points']})){const c=f.geometry.coordinates,key=c.map(v=>v.toFixed(4)).join(',');if(!groups.has(key))groups.set(key,{c,ids:[]});const g=groups.get(key),id=String(f.properties.id);if(!g.ids.includes(id))g.ids.push(id);}
 for(const g of [...groups.values()].slice(0,24)){const p=doc.photos.find(p=>p.id===g.ids[0]);if(!p)continue;const el=document.createElement('button');el.className='photo-tile';el.style.cssText='width:46px;height:46px;border:2px solid white;box-shadow:0 2px 8px #0003';el.setAttribute('aria-label',`${g.ids.length} 张地点照片`);const im=document.createElement('img');im.alt=p.name;el.append(im);el.onclick=()=>showLocation(g.ids);mapMarkers.push(new maplibregl.Marker({element:el}).setLngLat(g.c).addTo(map));bindImage(im,p);}
}
function showLocation(ids){const seed=doc.photos.find(p=>p.id===ids[0]),c=seed&&C.coordinates(seed,doc.index);if(c)ids=doc.photos.filter(p=>{const point=C.coordinates(p,doc.index);return point&&Math.abs(point[0]-c[0])<.0001&&Math.abs(point[1]-c[1])<.0001}).map(p=>p.id);state.mapIds=ids;galleryList=C.filtered(doc,state);const target=document.querySelector('#map-results');if(target){target.innerHTML=`<div class="grouphead"><h2>这里的照片</h2><small>${galleryList.length} 张</small></div>`+grid(galleryList);mountImages();target.scrollIntoView({behavior:'smooth',block:'start'});}}
function mountImages(){
 observer?.disconnect();imageQueue=imageQueue.filter(job=>job.img.isConnected);
 for(const [img,request] of imageRequests)if(!img.isConnected)request.controller.abort();
 observer=new IntersectionObserver(entries=>{for(const entry of entries){if(!entry.isIntersecting)continue;const img=entry.target;if(img.dataset.avatar)drawAvatar(img);else{const p=photoFor(img.dataset.photoSrc);if(p&&!img.hasAttribute('src'))queueImage(img,p);}observer.unobserve(img);}}, {rootMargin:'900px'});
 document.querySelectorAll('img[data-photo-src]:not([src]),canvas[data-avatar]:not([data-avatar-ready])').forEach(im=>observer.observe(im));
}
function drawAvatar(canvas){
 if(avatarDoc!==doc){avatarDoc=doc;avatarCache=new Map();}const name=canvas.dataset.avatar;
 if(!avatarCache.has(name))avatarCache.set(name,new Promise(resolve=>{
  const a=profile(name).avatar,p=photoFor(a?.photoId);if(!p){resolve(null);return;}const img=new Image();let finished=false;
  const finish=value=>{if(finished)return;finished=true;clearTimeout(timer);resolve(value);},timer=setTimeout(()=>finish(null),25000);
  img.onerror=()=>finish(null);img.onload=()=>{try{const crop=document.createElement('canvas');crop.width=crop.height=166;crop.getContext('2d').drawImage(img,Math.max(0,(Number(a.x)||0)*img.naturalWidth),Math.max(0,(Number(a.y)||0)*img.naturalHeight),Math.max(1,(Number(a.width)||1)*img.naturalWidth),Math.max(1,(Number(a.height)||1)*img.naturalHeight),0,0,166,166);finish(crop);}catch(error){finish(null);}};img.src=image(p);
 }));
 avatarCache.get(name).then(crop=>{if(!canvas.isConnected)return;try{if(crop){canvas.getContext('2d').drawImage(crop,0,0,canvas.width,canvas.height);canvas.dataset.avatarReady='true';}else canvas.parentElement.textContent=name.slice(0,1);}catch(error){canvas.parentElement.textContent=name.slice(0,1);}});
}
function queueImage(img,p){if(img.dataset.queued==='true')return;img.dataset.queued='true';imageQueue.push({img,p});pumpImages();}
function pumpImages(){
 if(imagesPaused)return;
 imageQueue=imageQueue.filter(job=>job.img.isConnected);
 const distance=job=>{const r=job.img.getBoundingClientRect();return Math.max(0,r.top-innerHeight,-r.bottom);};
 imageQueue.sort((a,b)=>distance(a)-distance(b));
 while(activeImages<2&&imageQueue.length){const {img,p}=imageQueue.shift();const r=img.getBoundingClientRect();
  if(r.top>innerHeight+1200||r.bottom<-1200){img.dataset.queued='false';observer?.observe(img);continue;}
  activeImages++;bindImage(img,p,false,false,()=>{img.dataset.queued='false';activeImages--;pumpImages();});
 }
}
const mediaLabels={missing:'原片未备份',mismatch:'备份版本不一致',wifi:'等待 Wi-Fi',connection:'尚未连接',binding:'请刷新索引',thumbnail:'暂无缩略图',original:'原图未加载',paused:'预览等待恢复',network:'预览读取失败'};
async function bindImage(img,p,full=false,retry=false,complete=()=>{}){
 if(!img||!p){complete();return;}
 imageRequests.get(img)?.controller.abort();
 const serial=(img._loadSerial||0)+1;img._loadSerial=serial;
 let settled=false;const done=()=>{if(!settled){settled=true;complete();}};
 img.dataset.photoSrc=p.id;img.dataset.full=String(full);img.dataset.mediaError='';img.style.visibility='';img.classList.remove('loaded');
 const host=img.closest('.photo-tile,.album-card,.photo-viewport');host?.querySelector('.media-error')?.remove();
 img.onload=()=>{done();if(!img.isConnected||img._loadSerial!==serial)return;img.dataset.mediaError='';img.style.visibility='';img.classList.add('loaded');host?.querySelector('.media-error')?.remove();updateMediaSummary();};
 img.onerror=()=>{
  done();if(!img.isConnected||img._loadSerial!==serial)return;let result={};try{const value=JSON.parse(native('mediaState',p.id,full)||'{}');if(value&&typeof value==='object')result=value;}catch{}
  const code=result.code||'network',message=result.message||'预览读取失败，请检查网络后重试。';
  img.dataset.mediaError=code;img.style.visibility='hidden';host?.querySelector('.media-error')?.remove();
  if(host){const error=document.createElement('div');error.className='media-error';error.title=message;
   const label=document.createElement('span');label.textContent=mediaLabels[code]||mediaLabels.network;error.append(label);
   if(host.classList.contains('photo-viewport')){const detail=document.createElement('p');detail.textContent=message;error.append(detail);const button=document.createElement('button');button.dataset.action='retrypreview';button.textContent='重试预览';error.append(button);}
   else {const hint=document.createElement('small');hint.textContent='点击查看';error.append(hint);}host.append(error);
  }updateMediaSummary();
 };
 const source=image(p,full),key=[doc.libraryId,p.cloudPath,p.size,p.time].join('|');const controller=new AbortController(),request={controller,serial};imageRequests.set(img,request);let expired=false;
 const timeout=setTimeout(()=>{expired=true;controller.abort();},30000);
 try {
  if(imagesPaused){img.dataset.mediaError='paused';return;}
  if(source.startsWith('data:')){img.src=source;return;}
  let url=!full&&!retry?thumbnailMemo.get(key):null;
  if(url&&!full){thumbnailMemo.delete(key);thumbnailMemo.set(key,url);}
  if(!url){
   const response=await fetch(source+(retry?'?retry='+Date.now():''),{cache:'no-store',signal:controller.signal});if(!response.ok)throw Error('media');
   const blob=await response.blob();if(!blob.size||blob.size>64*1048576||!blob.type.startsWith('image/'))throw Error('Invalid image response');if(!img.isConnected||img._loadSerial!==serial||controller.signal.aborted)return;url=URL.createObjectURL(blob);
   if(full){if(fullObjectURL)URL.revokeObjectURL(fullObjectURL);fullObjectURL=url;}
   else {const previous=thumbnailMemo.get(key);if(previous)URL.revokeObjectURL(previous);thumbnailMemo.set(key,url);while(thumbnailMemo.size>96){const oldest=thumbnailMemo.keys().next().value;URL.revokeObjectURL(thumbnailMemo.get(oldest));thumbnailMemo.delete(oldest);}}
  }
  if(img.isConnected&&img._loadSerial===serial)img.src=url;
 }catch(error){if(img.isConnected&&img._loadSerial===serial){if(controller.signal.aborted&&!expired)img.dataset.mediaError='paused';else img.onerror();}}finally{clearTimeout(timeout);if(imageRequests.get(img)===request)imageRequests.delete(img);done();}
}
function updateMediaSummary(){
 const failed=[...content.querySelectorAll('img[data-media-error]')].filter(img=>img.dataset.mediaError),missing=failed.filter(img=>img.dataset.mediaError==='missing').length;
 let summary=content.querySelector('.media-summary');if(!failed.length){summary?.remove();return;}
 if(!summary){summary=document.createElement('div');summary.className='media-summary';summary.setAttribute('role','status');summary.innerHTML='<span></span><button data-action="retryimages">重试预览</button>';content.querySelector('.pagehead')?.after(summary);}
 summary.querySelector('span').textContent=missing?`当前已尝试的照片中，${missing} 张原片尚未在网盘找到。可继续浏览其他日期。`:`${failed.length} 张预览暂未加载，点击照片可查看原因。`;
}
function retryImages(automatic=false,skipViewer=false){
 if(!automatic){native('retryPreviews');avatarCache.clear();}
 document.querySelectorAll('img[data-media-error]').forEach(img=>{if(!img.dataset.mediaError||(automatic&&!['wifi','connection','binding','network','paused'].includes(img.dataset.mediaError)))return;
  if(skipViewer&&img.closest('.photo-viewport'))return;
  const rect=img.getBoundingClientRect();if(rect.bottom<-250||rect.top>innerHeight+250)return;
  const p=photoFor(img.dataset.photoSrc);if(p)queueRetry(img,p,img.dataset.full==='true');
 });
}
function queueRetry(img,p,full=false){if(full||img.closest('.photo-viewport'))bindImage(img,p,full,true);else{img.removeAttribute('src');img.dataset.queued='false';queueImage(img,p);}}
function pauseImages(){imagesPaused=true;clearTimeout(resumeTimer);clearTimeout(highResTimer);cancelLongPress();for(const [img,request] of imageRequests){if(img.isConnected)img.dataset.mediaError='paused';request.controller.abort();}if(state.viewer){native('releasePreview');loadingOriginal=false;originals.clear();if(fullObjectURL){URL.revokeObjectURL(fullObjectURL);fullObjectURL=null;}const img=viewer.querySelector('#large-photo');if(img){img.removeAttribute('src');img.dataset.mediaError='paused';}}}
function resumeImages(value){if(value&&typeof value==='object')window.setMobileStatus(value);if(document.hidden)return;imagesPaused=true;clearTimeout(resumeTimer);clearTimeout(highResTimer);for(const [img,request] of imageRequests){if(img.isConnected)img.dataset.mediaError='paused';request.controller.abort();}resumeTimer=setTimeout(()=>{resumeTimer=null;window.previewMemoryReleased();imagesPaused=false;for(const job of imageQueue)job.img.dataset.queued='false';imageQueue=[];avatarCache.clear();mountImages();retryImages(true,true);pumpImages();if(state.viewer&&!demo){const p=currentPhoto(),img=viewer.querySelector('#large-photo');if(p&&img)bindImage(img,p,false,true);highResTimer=setTimeout(()=>requestHighRes(true),180);}},120);}
window.appResumed=value=>resumeImages(value);
window.previewMemoryReleased=()=>{originals.clear();loadingOriginal=false;if(fullObjectURL){URL.revokeObjectURL(fullObjectURL);fullObjectURL=null;}const img=viewer.querySelector('#large-photo'),p=currentPhoto(),button=viewer.querySelector('[data-action=original]');if(img&&img.dataset.full==='true'){img.removeAttribute('src');img.dataset.mediaError='paused';if(!imagesPaused&&!document.hidden&&p)bindImage(img,p);}if(button){button.disabled=false;button.innerHTML=icon('download')+'读取高清图';}};
document.addEventListener('visibilitychange',()=>document.hidden?pauseImages():resumeImages());
function openViewer(id){id=String(id);if(!photoFor(id)){notice('照片已不在当前索引中，请刷新后重试。');return;}viewerIds=galleryList.map(p=>p.id);if(!viewerIds.includes(id))viewerIds=doc.photos.map(p=>p.id);returnScroll=window.scrollY;state.viewer=id;document.body.style.overflow='hidden';viewer.hidden=false;renderViewer();}
function currentPhoto(){return state.viewer?photoFor(state.viewer):null;}
function renderViewer(){
 if(fullObjectURL){URL.revokeObjectURL(fullObjectURL);fullObjectURL=null;}
 clearTimeout(highResTimer);native('releasePreview');originals.clear();
 miniMap?.remove();miniMap=null;scale=1;panX=panY=0;infoOpen=false;loadingOriginal=false;peopleLimit=12;peopleQuery='';const p=currentPhoto();if(!p){closeViewer();return;}
 const pos=viewerIds.indexOf(p.id);viewer.className='viewer';
 viewer.innerHTML=`<div class="viewer-head"><button class="iconbutton" data-action="closeviewer" aria-label="返回照片">${icon('back')}</button><div class="viewer-title">${esc(titleFor(C.date(p)))}<small>${esc(p.name)}</small></div><button class="iconbutton" data-action="info" aria-label="照片信息">${icon('info')}</button></div><div class="photo-viewport"><img id="large-photo" src="${esc(image(p,originals.has(p.id)))}" alt="${esc(p.name)}" draggable="false"></div><div class="viewer-bar"><button class="iconbutton" data-action="prev" aria-label="上一张" ${pos<=0?'disabled':''}>${icon('back')}</button><button class="original" data-action="original" ${demo?'disabled':''}>${icon('download')}${demo?'示例预览':originals.has(p.id)?'高清图已显示':'读取高清图'}</button><span class="counter">${pos+1} / ${viewerIds.length}</span><button class="iconbutton" data-action="zoom" aria-label="放大照片">${icon('zoom')}</button><button class="iconbutton" data-action="next" aria-label="下一张" ${pos>=viewerIds.length-1?'disabled':''}>${icon('next')}</button></div>`;
 const names=C.people(p,doc.index);if(names.length)viewer.querySelector('.viewer-bar').insertAdjacentHTML('beforebegin',`<div class="viewer-people" aria-label="照片中的人物">${names.slice(0,6).map(name=>`<button data-person="${esc(name)}" title="${esc(name)}">${avatar(name,true)}<span>${esc(name)}</span></button>`).join('')}${names.length>6?`<button class="all-people" data-action="allpeople">查看全部 ${names.length} 位</button>`:''}</div>`);
 bindImage(viewer.querySelector('#large-photo'),p);bindGestures();mountImages();pageMotion('viewer:'+p.id,true);
 if(!demo&&window.Mobile)highResTimer=setTimeout(()=>requestHighRes(true),180);
}
function closeViewer(){clearTimeout(highResTimer);native('releasePreview');originals.clear();if(fullObjectURL){URL.revokeObjectURL(fullObjectURL);fullObjectURL=null;}miniMap?.remove();miniMap=null;state.viewer=null;viewer.hidden=true;viewer.innerHTML='';document.body.style.overflow='';if(layoutDirty){layoutDirty=false;if(doc&&['photos','person','album'].includes(state.view))render(false);}window.scrollTo(0,returnScroll);pageMotion(`${state.view}:return`);}
function moveViewer(direction){const i=viewerIds.indexOf(state.viewer)+direction;if(i<0||i>=viewerIds.length)return;state.viewer=viewerIds[i];renderViewer();}
function infoPeople(names){return `<div class="people-heading"><h3>人物 · ${names.length} 位</h3>${names.length>12?'<input id="photo-people-search" type="search" placeholder="搜索照片中的人物" aria-label="搜索照片中的人物" autocomplete="off">':''}</div><div id="photo-people-list" class="peoplechips"></div><div id="photo-people-more"></div>`;}
function updateInfoPeople(){const p=currentPhoto(),target=viewer.querySelector('#photo-people-list'),more=viewer.querySelector('#photo-people-more');if(!p||!target||!more)return;const all=C.people(p,doc.index),query=peopleQuery.trim().toLocaleLowerCase(),names=all.filter(name=>!query||name.toLocaleLowerCase().includes(query));target.innerHTML=names.length?names.slice(0,peopleLimit).map(n=>`<button data-person="${esc(n)}" title="${esc(n)}">${avatar(n,true)}<span>${esc(n)}</span></button>`).join(''):'<p class="muted">没有匹配的人物</p>';more.innerHTML=names.length>peopleLimit?`<button class="people-more" data-action="morepeople">再显示 ${Math.min(12,names.length-peopleLimit)} 位 · 共 ${names.length} 位</button>`:'';mountImages();}
function showInfo(focusPeople=false){
 if(infoOpen){if(focusPeople){viewer.querySelector('.infosheet').scrollTop=0;return;}miniMap?.remove();miniMap=null;viewer.querySelector('.infosheet')?.remove();viewer.classList.remove('has-info');infoOpen=false;return;}
 const p=currentPhoto();if(!p)return;const index=doc.index,names=C.people(p,index),albums=index.albums.map((a,i)=>({a,i})).filter(({a})=>(a.ids||[]).map(String).includes(p.id)),coords=C.coordinates(p,index);
 viewer.insertAdjacentHTML('beforeend',`<section class="infosheet"><div class="sheet-handle"></div><div class="sheet-head"><h2>照片信息</h2><button class="iconbutton" data-action="info" aria-label="关闭照片信息">${icon('close')}</button></div><div class="info-section info-people">${names.length?infoPeople(names):'<h3>人物</h3><p class="muted">暂无人物标注</p>'}</div><div class="info-section"><h3>批注</h3><p>${esc(index.notes[p.id]||'暂无批注')}</p></div><div class="info-section"><h3>相册</h3>${albums.length?albums.map(({a,i})=>`<button class="albumchip" data-album="${i}">${esc(a.name)}</button>`).join(''):'<p class="muted">未加入相册</p>'}</div><div class="info-section"><h3>拍摄信息</h3><p>${esc(p.name)}<br>${esc(p.time||p.date)}<br>${p.width} × ${p.height} · ${(Number(p.size||0)/1048576).toFixed(1)} MiB${p.camera?'<br>'+esc(p.camera):''}</p></div>${coords?`<div class="info-section"><h3>拍摄地点</h3><div id="mini-map" class="mini-map"></div><button class="location-link" data-action="location">${icon('pin')}在地点页查看</button><p class="small muted">${coords[0].toFixed(6)}, ${coords[1].toFixed(6)}${index.photoLocations[p.id]?.manual?' · 电脑已校准':''}</p></div>`:''}<div class="info-section"><h3>云端原片</h3><p class="small muted">${esc(p.cloudPath)}</p></div><div class="info-section"><p class="small muted">${demo?'样例图片用于预览，不代表已下载云端原图。':'可双指缩放、拖动，左右轻扫切换照片。高清图从云端读取，仅在当前浏览内存中显示，关闭照片后释放。长按可以将原片下载到手机相册。'}<br>人物、相册与批注跟随电脑同步。</p></div></section>`);infoOpen=true;viewer.classList.add('has-info');updateInfoPeople();mountImages();
 if(coords){try{miniMap=new maplibregl.Map({container:'mini-map',style:MAP_STYLE,center:coords,zoom:15,interactive:false,attributionControl:false});new maplibregl.Marker({color:'#007aff'}).setLngLat(coords).addTo(miniMap);}catch{}}
}
function updateTransform(){const img=viewer.querySelector('#large-photo');if(img)img.style.transform=`translate(${panX}px,${panY}px) scale(${scale})`;}
function bindGestures(){const port=viewer.querySelector('.photo-viewport'),pointers=new Map();let startX=0,startY=0,startPanX=0,startPanY=0,initialDistance=0,startScale=1,hadPinch=false;
 port.onpointerdown=e=>{viewer.querySelector('#large-photo').style.transition='none';port.setPointerCapture(e.pointerId);pointers.set(e.pointerId,{x:e.clientX,y:e.clientY});if(pointers.size===1){startX=e.clientX;startY=e.clientY;startPanX=panX;startPanY=panY;hadPinch=false;}if(pointers.size===2){const [a,b]=[...pointers.values()];initialDistance=Math.hypot(a.x-b.x,a.y-b.y);startScale=scale;hadPinch=true;}};
 port.onpointermove=e=>{if(!pointers.has(e.pointerId))return;pointers.set(e.pointerId,{x:e.clientX,y:e.clientY});if(pointers.size===2){const [a,b]=[...pointers.values()];scale=Math.max(1,Math.min(6,startScale*Math.hypot(a.x-b.x,a.y-b.y)/Math.max(1,initialDistance)));}else if(scale>1){const bound=port.clientWidth*(scale-1)/2;panX=Math.max(-bound,Math.min(bound,startPanX+e.clientX-startX));const ybound=port.clientHeight*(scale-1)/2;panY=Math.max(-ybound,Math.min(ybound,startPanY+e.clientY-startY));}updateTransform();};
 port.onpointerup=e=>{viewer.querySelector("#large-photo").style.transition="";pointers.delete(e.pointerId);if(!hadPinch&&scale===1&&Math.abs(e.clientX-startX)>65&&Math.abs(e.clientY-startY)<70)moveViewer(e.clientX<startX?1:-1);};port.onpointercancel=e=>pointers.delete(e.pointerId);
 port.ondblclick=()=>{scale=scale>1?1:2;panX=panY=0;updateTransform()};
}
window.originalReady=result=>{if(!result||imagesPaused||state.viewer!==String(result.id))return;const button=viewer.querySelector('[data-action=original]'),p=currentPhoto();if(!button||!p)return;if(result.ok)originals.add(String(result.id));loadingOriginal=false;viewer.querySelector('.viewer-loading')?.remove();if(result.ok){bindImage(viewer.querySelector('#large-photo'),p,true,true);button.innerHTML=icon('download')+'高清图已显示';}else{if(!result.automatic)notice(result.error||'高清图读取失败');button.innerHTML=icon('download')+'重试高清图';}button.disabled=false;};
function requestHighRes(automatic=false){if(!state.viewer||demo||imagesPaused||loadingOriginal)return;const button=viewer.querySelector('[data-action=original]');if(!button)return;loadingOriginal=true;button.disabled=true;button.innerHTML='<span class="spinner"></span> 读取高清图…';native(automatic?'preview':'original',state.viewer);}
window.showPhotoInfo=value=>{if(state.viewer!==String(value.id))openViewer(String(value.id));if(!infoOpen)showInfo();};
window.setMobileStatus=value=>{if(!value||typeof value!=='object')return;const previous=mobileStatus;mobileStatus={...mobileStatus,...value};document.querySelector('[data-action=refresh]')?.classList.toggle('spin',!!mobileStatus.busy);if(state.view==='settings')renderSettings(true);if((!previous.wifi&&mobileStatus.wifi)||(previous.wifiImages&&!mobileStatus.wifiImages)||(!previous.connected&&mobileStatus.connected)||(!previous.bound&&mobileStatus.bound))retryImages(true);};
async function readSnapshot(){const response=await fetch(window.Mobile?'/snapshot.json':'preview-snapshot.json',{cache:'no-store'});if(!response.ok)return null;const value=await response.json();return value?C.normalized(value):null;}
window.snapshotChanged=async()=>{try{const snapshot=await readSnapshot();if(!snapshot)throw Error('没有可读取的索引。');if(state.viewer)closeViewer();clearImageMemory();doc=snapshot;demo=false;originals.clear();state.view='photos';state.query='';state.mapIds=null;state.personTag='';render();window.scrollTo(0,0);notice('已更新手机端图库');}catch(e){notice(e.message)}};
async function loadDemo(){try{const response=await fetch('demo.json');if(!response.ok)throw Error('样例无法读取');doc=C.normalized(await response.json(),{demo:true});demo=true;state.view='photos';state.query='';state.mapIds=null;render();window.scrollTo(0,0);}catch(e){notice(e.message)}}
async function exitDemo(){if(state.viewer)closeViewer();try{doc=await readSnapshot()}catch{doc=null}demo=false;state.view='photos';render();window.scrollTo(0,0)}
function browserImport(){const input=document.createElement('input');input.type='file';input.accept='.json,application/json';input.onchange=async()=>{try{const file=input.files[0];if(!file)return;if(file.size>64*1024*1024)throw Error('索引超过 64 MiB。');doc=C.normalized(JSON.parse(await file.text()));demo=false;state.view='photos';render();notice('已导入索引；云端照片请在安卓 App 中读取。');}catch(e){notice(e.message)}};input.click()}
document.addEventListener('click',e=>{
 const button=e.target.closest('button');if(!button||button.disabled)return;
 if(button.dataset.view){navigate(button.dataset.view);return;}
 if(button.dataset.photo){openViewer(button.dataset.photo);return;}
 if(button.dataset.person){if(state.viewer)closeViewer();previousView=state.view;state.view='person';state.person=button.dataset.person;state.query='';state.mapIds=null;render();window.scrollTo(0,0);return;}
 if(button.dataset.album){if(state.viewer)closeViewer();previousView=state.view;state.view='album';state.album=Number(button.dataset.album);state.query='';state.mapIds=null;render();window.scrollTo(0,0);return;}
 if(button.dataset.group){state.group=button.dataset.group;remember();render(false);return;}
 if(button.hasAttribute('data-tag')){state.personTag=button.dataset.tag;render(false);return;}
 const action=button.dataset.action;
 if(action==='retryimages'||action==='retrypreview'){retryImages();return;}
 if(['configure','refresh','guide','importIndex','clearCache','disconnect','transferDevices','cacheAllThumbnails','cancelThumbnailCache'].includes(action)){native(action);return;}
 if(action==='settings'){navigate('settings');return;}
 if(action==='demo'){loadDemo();return;}if(action==='exitdemo'){exitDemo();return;}
 if(action==='parent'){navigate(state.view==='person'?'people':'albums');return;}
 if(action==='wifi'){native('wifiImages',!mobileStatus.wifiImages);return;}
 if(action==='closeviewer'){closeViewer();return;}if(action==='prev'){moveViewer(-1);return;}if(action==='next'){moveViewer(1);return;}
 if(action==='info'){showInfo();return;}if(action==='zoom'){scale=scale>=4?1:Math.min(4,scale*2);panX=panY=0;updateTransform();return;}
 if(action==='allpeople'){showInfo(true);return;}if(action==='morepeople'){peopleLimit+=12;updateInfoPeople();return;}
 if(action==='original'){requestHighRes();return;}
 if(action==='location'){const id=state.viewer;closeViewer();navigate('map');setTimeout(()=>showLocation([id]),400);return;}
 if(action==='heat'){heat=!heat;button.setAttribute('aria-pressed',String(heat));if(map?.getLayer('heat'))map.setPaintProperty('heat','heatmap-opacity',heat?.55:0);return;}
 if(action==='fitmap'){fitMap();return;}
});
function menuPhoto(target){const tile=target.closest('[data-photo]');return tile?.dataset.photo||(target.closest('.photo-viewport')?state.viewer:null);}
function cancelLongPress(){clearTimeout(longPressTimer);longPressStart=null;}
function showPhotoMenu(id){if(!id)return;suppressClickUntil=Date.now()+700;cancelLongPress();if(window.Mobile&&!demo)native('photoMenu',id);else notice('安装安卓 App 后可长按下载云端照片。');}
document.addEventListener('pointerdown',e=>{const id=menuPhoto(e.target);if(!id||e.button>0)return;cancelLongPress();longPressStart={id,x:e.clientX,y:e.clientY};longPressTimer=setTimeout(()=>showPhotoMenu(id),550);});
document.addEventListener('pointermove',e=>{if(longPressStart&&Math.hypot(e.clientX-longPressStart.x,e.clientY-longPressStart.y)>12)cancelLongPress();});
document.addEventListener('pointerup',cancelLongPress);document.addEventListener('pointercancel',cancelLongPress);
document.addEventListener('contextmenu',e=>{const id=menuPhoto(e.target);if(id){e.preventDefault();if(Date.now()>=suppressClickUntil)showPhotoMenu(id);}});
document.addEventListener('click',e=>{if(Date.now()<suppressClickUntil&&menuPhoto(e.target)){e.preventDefault();e.stopImmediatePropagation();}},true);
document.querySelector('.brand').onclick=e=>{e.preventDefault();if(state.viewer)closeViewer();navigate('photos')};
document.addEventListener('change',e=>{if(e.target.id==='photo-sort'){state.sort=e.target.value;remember();render(false);}if(e.target.id==='date-jump'){document.getElementById(e.target.value)?.scrollIntoView({behavior:'smooth',block:'start'});}if(e.target.id==='album-sort'){preferences.albumSort=e.target.value;try{localStorage.setItem('reader-preferences',JSON.stringify({...preferences,sort:state.sort,group:state.group}))}catch{}renderAlbums();mountImages();}});
document.querySelector('#search').oninput=e=>{state.query=e.target.value;clearTimeout(searchTimer);searchTimer=setTimeout(()=>{if(state.view==='map')return;render(false);},180)};
document.addEventListener('input',e=>{if(e.target.id==='photo-people-search'){peopleQuery=e.target.value;peopleLimit=12;updateInfoPeople();}});
window.mobileBack=()=>{if(state.viewer){if(infoOpen)showInfo();else closeViewer();return true;}if(state.view==='person'||state.view==='album'){navigate(state.view==='person'?'people':'albums');return true;}if(state.view!=='photos'){navigate('photos');return true;}return false;};
document.addEventListener('keydown',e=>{if(e.key==='Escape')window.mobileBack();if(state.viewer&&e.key==='ArrowRight')moveViewer(1);if(state.viewer&&e.key==='ArrowLeft')moveViewer(-1)});
let resizeTimer,layoutWidth=innerWidth,layoutDirty=false;new ResizeObserver(()=>{headerSize();if(layoutWidth===innerWidth)return;layoutWidth=innerWidth;if(state.viewer){layoutDirty=true;return;}if(!doc||!['photos','person','album'].includes(state.view))return;clearTimeout(resizeTimer);resizeTimer=setTimeout(()=>{const scroll=window.scrollY;render(false);window.scrollTo(0,scroll)},120)}).observe(document.body);
document.querySelector('[data-action=refresh]').innerHTML=icon('refresh');document.querySelector('[data-action=settings]').innerHTML=icon('settings');document.querySelector('#search-icon').innerHTML=icon('search');
(async()=>{try{doc=await readSnapshot()}catch{}render();native('ready');})();

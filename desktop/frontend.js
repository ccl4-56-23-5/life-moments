let desktopStatus=window.desktopStatus||{},scanText=data.scanComplete?'':`正在整理拍摄时间与位置 · ${data.pending} 张`,refreshPending=false;
function desktopSend(type,extra={}){window.chrome?.webview?.postMessage({type,session:window.desktopSession,...extra})}
function syncControls(){return `<div class="sync-controls"><button type="button" class="sync-toggle" role="switch" aria-checked="${!!desktopStatus.syncEnabled}" data-action="sync-toggle" title="将相册索引同步到百度网盘"><span class="switch-track"><span></span></span>索引同步</button><button type="button" class="iconbutton" data-action="sync-now" aria-label="立即同步索引" ${!desktopStatus.syncEnabled||desktopStatus.syncBusy?'disabled':''}>${icon(desktopStatus.syncBusy?'loader-circle':'cloud-upload')}</button><small class="sync-status" title="${esc(desktopStatus.lastSync?`上次同步：${desktopStatus.lastSync}`:'')}">${esc(desktopStatus.syncText||'同步已关闭')}</small></div>`}
const originalRender=render;
const originalRenderViewer=renderViewer;
renderViewer=function(){stashPhotoPage();originalRenderViewer();const back=app.querySelector('[data-action=closeviewer]');back?.setAttribute('aria-label',`返回${state.view==='album'?'相册':state.view==='person'?'人物':state.view==='map'?'地点':'照片'}`);const image=app.querySelector('#large-photo'),port=app.querySelector('.photo-viewport');if(!image||!port)return;port.insertAdjacentHTML('beforeend','<div class="photo-loading" role="status"><span class="scan-spinner"></span><span>正在加载照片…</span></div>');const status=port.querySelector('.photo-loading');const done=()=>{if(image.naturalWidth>0)status.remove();else status.innerHTML='<span>照片预览无法加载</span>'};image.addEventListener('load',done,{once:true});image.addEventListener('error',done,{once:true});if(image.complete)done()};
render=function(){if(state.viewer===null&&app.querySelector('.workspace')){disposeMiniMap();renderContent();for(const b of app.querySelectorAll('.navigation [data-view]'))b.setAttribute('aria-current',state.view===b.dataset.view||b.dataset.view==='albums'&&state.view==='album'||b.dataset.view==='people'&&state.view==='person'?'page':'false');const search=app.querySelector('#photo-search');if(search)search.value=state.query;icons()}else originalRender();if(state.viewer!==null)return;const actions=app.querySelector('.topactions');if(actions&&!actions.querySelector('[data-action=refresh]')){actions.insertAdjacentHTML('afterbegin',`${btn('refresh','刷新','refresh-cw','textbutton')}<div class="sync-slot">${syncControls()}</div>`);icons()}updateScanStatus()};
const originalRenderContent=renderContent;
const photoSortOptions=[['time-desc','拍摄时间 · 从新到旧'],['time-asc','拍摄时间 · 从旧到新'],['name-asc','文件名 · A → Z'],['name-desc','文件名 · Z → A']];
const albumSortOptions=[['created','创建顺序'],['name-asc','相册名称 · A → Z'],['name-desc','相册名称 · Z → A'],['count-desc','照片数量 · 从多到少'],['recent','最近拍摄时间']];
store.uiPreferences||={};
if(!photoSortOptions.some(([v])=>v===store.uiPreferences.photoSort))store.uiPreferences.photoSort='time-desc';
if(!albumSortOptions.some(([v])=>v===store.uiPreferences.albumSort))store.uiPreferences.albumSort='created';
const nameOrder=new Intl.Collator('zh-Hans-CN',{numeric:true,sensitivity:'base'});
const sourceFiltered=filtered;
filtered=function(){if(state.view==='album'&&!store.albums[state.album])return [];const ps=sourceFiltered();const mode=store.uiPreferences.photoSort;if(mode==='time-desc')return ps;if(mode==='time-asc')return ps.slice().sort((a,b)=>String(a.time||a.date).localeCompare(String(b.time||b.date)));const direction=mode==='name-desc'?-1:1;return ps.slice().sort((a,b)=>direction*(nameOrder.compare(a.name,b.name)||nameOrder.compare(a.relativePath||a.name,b.relativePath||b.name)))};
let managingAlbums=false;const parentContexts={};
let photoPageCache=null,photoRevision=0;
function photoPageKey(){return JSON.stringify([photoRevision,state.group,state.query,store.uiPreferences.photoSort,Math.round(galleryWidth())])}
function stashPhotoPage(){const main=app.querySelector('#content');if(!main?.dataset.photoKey)return;const fragment=document.createDocumentFragment();while(main.firstChild)fragment.append(main.firstChild);photoPageCache={key:main.dataset.photoKey,fragment,photos:timelinePhotos,tiles:galleryTiles};delete main.dataset.photoKey}
const originalPersist=persist;
persist=function(){photoRevision++;photoPageCache=null;const main=app.querySelector('#content');if(main)delete main.dataset.photoKey;originalPersist()};
const originalCaptureNote=captureNote;
captureNote=function(){const input=app.querySelector('#photo-note'),changed=input&&state.viewer!==null&&input.value!==(store.notes[state.viewer]||'');originalCaptureNote();if(changed){photoRevision++;photoPageCache=null;const main=app.querySelector('#content');if(main)delete main.dataset.photoKey}};
// Replacing every existing SVG and redecoding full avatar photos on each refresh is avoidable.
icons=function(){for(const svg of root.querySelectorAll('svg[data-lucide]'))svg.removeAttribute('data-lucide');if(globalThis.lucide)lucide.createIcons({attrs:{width:20,height:20}});renderAvatars()};
const avatarCanvases=new Map(),avatarPending=new Map();
renderAvatars=function(){for(const canvas of root.querySelectorAll('canvas[data-avatar]')){const a=profile(canvas.dataset.avatar).avatar,p=samples.find(p=>p.id===a?.photoId);if(!p)continue;const key=JSON.stringify([p.id,p.stamp,a.x,a.y,a.width,a.height]);if(canvas.dataset.drawnAvatar===key)continue;canvas.dataset.drawnAvatar=key;const draw=source=>{if(canvas.isConnected&&canvas.dataset.drawnAvatar===key)canvas.getContext('2d').drawImage(source,0,0,canvas.width,canvas.height)};const cached=avatarCanvases.get(key);if(cached){draw(cached);continue}let pending=avatarPending.get(key);if(!pending){pending=new Promise(resolve=>{const im=new Image();im.onload=()=>{const small=document.createElement('canvas');small.width=small.height=166;paintCrop(small,im,a);avatarCanvases.set(key,small);while(avatarCanvases.size>64)avatarCanvases.delete(avatarCanvases.keys().next().value);avatarPending.delete(key);resolve(small)};im.onerror=()=>{avatarPending.delete(key);resolve(null)};im.src=p.fullImage||p.image});avatarPending.set(key,pending)}pending.then(small=>{if(small)draw(small);else delete canvas.dataset.drawnAvatar})}};
function sortButton(albums=false){const choices=albums?albumSortOptions:photoSortOptions,current=store.uiPreferences[albums?'albumSort':'photoSort'];return `<button type="button" class="sort-button textbutton" data-action="sort" aria-label="切换排序方式" title="${esc(choices.find(([v])=>v===current)?.[1]||'排序')}">${icon('arrow-down-up')}<span>排序</span></button>`}
function parentButton(view){return `<button type="button" class="page-back textbutton" data-action="backparent">${icon('chevron-left')}<span>返回${view==='albums'?'相册':'人物'}</span></button>`}
function albumEntries(){const photos=new Map(samples.map(p=>[p.id,p]));const q=state.query.trim().toLowerCase();const entries=store.albums.map((album,index)=>{const visible=album.ids.map(id=>photos.get(id)).filter(Boolean);return {album,index,count:visible.length,cover:visible[0],recent:visible.reduce((last,p)=>String(p.time||p.date)>last?String(p.time||p.date):last,'')}}).filter(({album})=>!q||(album.name+' '+(album.note||'')).toLowerCase().includes(q));const mode=store.uiPreferences.albumSort;entries.sort((a,b)=>mode==='name-asc'?nameOrder.compare(a.album.name,b.album.name)||a.index-b.index:mode==='name-desc'?nameOrder.compare(b.album.name,a.album.name)||a.index-b.index:mode==='count-desc'?b.count-a.count||a.index-b.index:mode==='recent'?b.recent.localeCompare(a.recent)||a.index-b.index:a.index-b.index);return entries}
function renderAlbums(){const main=app.querySelector('#content');disposeMap();const entries=albumEntries();main.innerHTML=heading('相册',`<div class="page-actions">${sortButton(true)}<button type="button" class="textbutton manage-button" data-action="managealbums" aria-pressed="${managingAlbums}">${icon(managingAlbums?'check':'sliders-horizontal')}<span>${managingAlbums?'完成':'管理'}</span></button>${btn('newalbum','新建相册','plus','primary')}</div>`)+(entries.length?`<div class="album-grid ${managingAlbums?'is-managing':''}">${entries.map(({album,index,count,cover})=>`<div class="album-item"><button type="button" class="album-card" data-album="${index}" aria-label="打开相册 ${esc(album.name)}">${cover?`<img src="${esc(cover.image)}" alt="${esc(album.name)}" loading="lazy">`:`<div class="album-empty-cover">${icon('album')}</div>`}<div class="title">${esc(album.name)}</div><div class="summary">${count} 张照片</div></button>${managingAlbums?`<button type="button" class="album-remove iconbutton" data-delete-album="${index}" aria-label="删除相册 ${esc(album.name)}" title="删除相册">${icon('trash-2')}</button>`:''}</div>`).join('')}</div>`:empty(state.query?'没有符合条件的相册':'还没有相册',state.query?'':btn('newalbum','新建相册','plus','textbutton')))}
function openSort(){const albums=state.view==='albums',key=albums?'albumSort':'photoSort',choices=albums?albumSortOptions:photoSortOptions;modal.classList.remove('wide-dialog');modal.dataset.kind='sort';modal.innerHTML=`<form><h2>排序方式</h2><div class="sort-options">${choices.map(([value,title])=>`<label><input type="radio" name="sort" value="${value}" ${store.uiPreferences[key]===value?'checked':''}><span>${title}</span></label>`).join('')}</div><div class="dialogactions"><button type="button" class="textbutton" data-dismiss>取消</button><button type="button" class="primary" data-save>应用</button></div></form>`;modal.querySelector('[data-dismiss]').onclick=()=>modal.close();bindDialogSave(()=>{store.uiPreferences[key]=modal.querySelector('[name=sort]:checked').value;persist();modal.close();renderContent();icons();window.scrollTo(0,0)});icons();modal.showModal()}
function openAlbumDelete(index){const album=store.albums[index];if(!album)return;modal.classList.remove('wide-dialog');modal.dataset.kind='deletealbum';modal.innerHTML=`<form><h2>删除相册</h2><p class="delete-album-name">${esc(album.name)}</p><p class="dialoghint">删除此相册及其批注。照片、人物标注和照片批注会保留。</p><div class="dialogactions"><button type="button" class="textbutton" data-dismiss>取消</button><button type="button" class="primary danger-button" data-save>删除相册</button></div></form>`;modal.querySelector('[data-dismiss]').onclick=()=>modal.close();bindDialogSave(()=>{const current=store.albums.indexOf(album);if(current<0){modal.close();return}store.albums.splice(current,1);if(state.album===current){state.album=null;if(state.view==='album')state.view='albums'}else if(state.album>current)state.album--;persist();modal.close();renderContent();icons();flash('已删除相册')});icons();modal.showModal()}
function returnToParent(){const view=state.view==='album'?'albums':'people',context=parentContexts[view]||{query:'',scroll:0};captureNote();state.view=view;state.query=context.query;state.album=null;state.person=null;render();window.scrollTo(0,context.scroll)}
let timelinePhotos=[],layoutWidth=0,resizeTimer=0,scrollFrame=0,galleryTiles=[],viewerAnchor=null,pendingContent=false;
const waitingImages=new Set();
const galleryObserver=new IntersectionObserver(entries=>{for(const entry of entries)if(entry.isIntersecting){const img=entry.target;galleryObserver.unobserve(img);waitingImages.delete(img);img.addEventListener('load',()=>img.classList.add('is-ready'),{once:true});img.src=img.dataset.src}},{rootMargin:'400px 0px'});
function prepareGalleryImages(){for(const img of waitingImages)if(!img.isConnected){galleryObserver.unobserve(img);waitingImages.delete(img);delete img.dataset.observed}for(const img of app.querySelectorAll('.photo-tile img[data-src]:not([data-observed])')){img.dataset.observed='true';waitingImages.add(img);galleryObserver.observe(img)}}
let imageFrame=0;new MutationObserver(()=>{if(!imageFrame)imageFrame=requestAnimationFrame(()=>{imageFrame=0;prepareGalleryImages()})}).observe(app,{childList:true,subtree:true});
function galleryWidth(){const main=app.querySelector('#content');if(!main)return 960;const css=getComputedStyle(main);return Math.max(240,main.clientWidth-parseFloat(css.paddingLeft)-parseFloat(css.paddingRight))}
function photoRatio(p){return p.width>0&&p.height>0?p.width/p.height:4/3}
// Justified rows preserve chronological order and the complete image, including portraits.
grid=function(ps){
    const width=galleryWidth(),gap=10,target=width<650?155:205,rows=[];let row=[],sum=0;
    function flush(last=false){if(!row.length)return;const height=Math.min(last?target:target*1.38,(width-gap*(row.length-1))/sum);rows.push(`<div class="photo-row" style="height:${height.toFixed(3)}px">${row.map(p=>{const ratio=photoRatio(p),w=height*ratio;return `<button type="button" class="photo-tile" data-photo="${p.id}" data-ratio="${ratio}" style="width:${w.toFixed(3)}px;flex:0 0 ${w.toFixed(3)}px;height:${height.toFixed(3)}px" aria-label="打开 ${esc(p.name)}"><img data-src="${esc(p.image)}" alt="${esc(label(p))}" width="${p.width||400}" height="${p.height||300}" decoding="async"></button>`}).join('')}</div>`);row=[];sum=0}
    for(const p of ps){const r=photoRatio(p);if(row.length&&Math.abs((width-gap*(row.length-1))/sum-target)<Math.abs((width-gap*row.length)/(sum+r)-target)&&sum*target+gap*(row.length-1)>width*.72)flush();row.push(p);sum+=r;if(sum*target+gap*(row.length-1)>=width)flush()}
    flush(true);return `<div class="photo-grid natural-grid">${rows.join('')}</div>`;
};
function timelineInset(){const top=app.querySelector('.topbar')?.getBoundingClientRect().height||82,bar=app.querySelector('.timeline-toolbar');return top+12+(bar?.offsetHeight||0)+14}
function timelineAnchor(){if(state.viewer!==null)return null;const tiles=galleryTiles[0]?.isConnected?galleryTiles:app.querySelectorAll('.photo-tile'),inset=timelineInset();let lo=0,hi=tiles.length-1,found=null;while(lo<=hi){const mid=(lo+hi)>>1,rect=tiles[mid].getBoundingClientRect();if(rect.bottom>inset){found={id:tiles[mid].dataset.photo,offset:rect.top};hi=mid-1}else lo=mid+1}return found}
function restoreAnchor(anchor){if(!anchor)return;const tile=app.querySelector(`[data-photo="${anchor.id}"]`);if(tile)window.scrollBy(0,tile.getBoundingClientRect().top-anchor.offset)}
function timelineYears(){return [...new Set(timelinePhotos.map(p=>p.date.slice(0,4)))].sort().reverse()}
function updateTimelinePickers(){
    const y=app.querySelector('#timeline-year'),m=app.querySelector('#timeline-month');if(!y||!m)return;
    y.value=state.month.slice(0,4);const available=[...new Set(timelinePhotos.filter(p=>p.month.startsWith(y.value+'-')).map(p=>p.month))].sort().reverse();
    if(m.dataset.year!==y.value){m.innerHTML=available.map(v=>`<option value="${v}">${Number(v.slice(5))}月</option>`).join('');m.dataset.year=y.value}
    m.value=state.month;
}
function jumpTimeline(value){
    const p=timelinePhotos.find(p=>p.date.startsWith(value));if(!p)return;state.month=p.month;updateTimelinePickers();
    const tile=app.querySelector(`[data-photo="${p.id}"]`);if(tile){const section=tile.closest('.timeline-section');const node=section&&section.querySelector('.photo-tile')===tile?section:tile;window.scrollTo({top:window.scrollY+node.getBoundingClientRect().top-timelineInset(),behavior:'instant'})}
}
renderContent=function(){
    if(state.view!=='photos')stashPhotoPage();
    if(state.view==='albums'){renderAlbums();return}
    if(state.view!=='photos'){originalRenderContent();const main=app.querySelector('#content');if(state.view==='album'||state.view==='person'){main.insertAdjacentHTML('afterbegin',parentButton(state.view==='album'?'albums':'people'));const actions=main.querySelector(state.view==='album'?'.pagehead':'.person-actions');if(actions){if(state.view==='album'){const note=actions.querySelector('[data-action=albumnote]');if(note){const tools=document.createElement('div');tools.className='page-actions';note.replaceWith(tools);tools.append(note);tools.insertAdjacentHTML('beforeend',sortButton())}}else actions.insertAdjacentHTML('beforeend',sortButton())}}prepareGalleryImages();return}
    const main=app.querySelector('#content');if(!main)return;disposeMap();
    const key=photoPageKey();if(main.dataset.photoKey===key){updateScanStatus();return}
    if(photoPageCache?.key===key){main.replaceChildren(photoPageCache.fragment);timelinePhotos=photoPageCache.photos;galleryTiles=photoPageCache.tiles;photoPageCache=null;main.dataset.photoKey=key;layoutWidth=galleryWidth();prepareGalleryImages();updateTimelinePickers();updateScanStatus();return}
    photoPageCache=null;
    const ps=filtered();timelinePhotos=ps;layoutWidth=galleryWidth();const years=timelineYears();
    if(!ps.some(p=>p.month===state.month))state.month=ps[0]?.month||'';
    const namedSort=store.uiPreferences.photoSort.startsWith('name-');
    const toolbar=`<div class="timeline-toolbar"><div class="timeline-count"><span>${ps.length.toLocaleString()} 张照片</span><div class="scan-line" role="status" aria-live="polite"></div></div><div class="timeline-nav"><label class="timeline-jump"><select id="timeline-year" aria-label="跳转到年份">${years.map(y=>`<option value="${y}" ${state.month.startsWith(y)?'selected':''}>${y}年</option>`).join('')}</select></label><label class="timeline-jump"><select id="timeline-month" aria-label="跳转到月份"></select></label><div class="segments" aria-label="时间分组">${[['day','日'],['month','月'],['year','年']].map(([v,n])=>`<button type="button" data-group="${v}" aria-pressed="${!namedSort&&state.group===v}" ${namedSort?'disabled title="按拍摄时间排序时可切换分组"':''}>${n}</button>`).join('')}</div></div></div>`;
    let body=heading('照片',sortButton())+toolbar;const groups=new Map();
    if(namedSort&&ps.length)body+=`<section class="timeline-section" data-time="all">${grid(ps)}</section>`;
    if(!namedSort)for(const p of ps){const key=state.group==='day'?p.date:state.group==='month'?p.month:p.date.slice(0,4);if(!groups.has(key))groups.set(key,[]);groups.get(key).push(p)}
    for(const [key,photos] of groups){const first=photos[0];const title=state.group==='year'?`${key}年`:state.group==='month'?`${key.slice(0,4)}年 ${Number(key.slice(5))}月`:`${Number(key.slice(5,7))}月${Number(key.slice(8))}日`;const subtitle=state.group==='day'?`${key.slice(0,4)}年 · ${['星期日','星期一','星期二','星期三','星期四','星期五','星期六'][new Date(key+'T12:00:00').getDay()]}`:`${photos.length} 张照片`;body+=`<section class="timeline-section" data-time="${key}" data-date="${first.date}"><div class="grouphead"><h2>${title}</h2><small>${subtitle}</small></div>${grid(photos)}</section>`}
    if(!ps.length)body+=empty('这个目录中暂无符合条件的照片');
    else body+='<div class="timeline-end">已浏览全部照片</div>';
    main.innerHTML=body;
    main.dataset.photoKey=key;
    galleryTiles=[...main.querySelectorAll('.photo-tile')];
    prepareGalleryImages();
    updateTimelinePickers();
    main.querySelector('#timeline-year')?.addEventListener('change',e=>jumpTimeline(e.target.value));
    main.querySelector('#timeline-month')?.addEventListener('change',e=>jumpTimeline(e.target.value));
    updateScanStatus();
};
const originalOpenViewer=openViewer,originalCloseViewer=closeViewer;
openViewer=function(id){const tile=app.querySelector(`[data-photo="${id}"]`);viewerAnchor=tile?{id:String(id),offset:tile.getBoundingClientRect().top}:timelineAnchor();originalOpenViewer(id)};
closeViewer=function(){originalCloseViewer();restoreAnchor(viewerAnchor);viewerAnchor=null};
function updateScanStatus(){const line=app.querySelector('.scan-line');if(line){line.innerHTML=scanText?`<span class="scan-spinner"></span><span>${esc(scanText)}</span>`:data.errors?.length?`<span>${data.errors.length} 项读取问题</span>`:'';line.classList.toggle('is-loading',!!scanText);line.hidden=!line.textContent.trim()}const refresh=app.querySelector('[data-action=refresh]');if(refresh){refresh.disabled=refreshPending;refresh.classList.toggle('is-loading',refreshPending)}}
window.desktopScanProgress=text=>{scanText=text;refreshPending=!text.includes('失败');updateScanStatus()};
window.applyDesktopPhotos=updated=>{
    captureNote();const scroll=app.querySelector('.infopanel')?.scrollTop||0,anchor=timelineAnchor();
    const fields=['id','stamp','time','width','height'];if(all.length!==updated.photos.length||all.some((p,i)=>fields.some(k=>p[k]!==updated.photos[i]?.[k]))){photoRevision++;photoPageCache=null}
    Object.assign(data,updated);all.splice(0,all.length,...updated.photos);samples.splice(0,samples.length,...all.filter(p=>p.image));months.splice(0,months.length,...[...new Set(samples.map(p=>p.month))].sort());
    if(!months.includes(state.month))state.month=months.at(-1)||'';
    window.desktopPhotoData=data;scanText=updated.scanComplete?'':`正在整理拍摄时间与位置 · ${updated.pending} 张`;refreshPending=false;
    if(state.viewer!==null){const p=samples.find(p=>p.id===state.viewer);if(!p){persist();state.viewer=null;render();flash('这张照片已不在当前目录中')}else{renderInfo();app.querySelector('.infopanel').scrollTop=scroll}}else if(!modal.open){renderContent();icons();restoreAnchor(anchor)}else pendingContent=true;
    updateScanStatus();
};
modal.addEventListener('close',()=>{if(!pendingContent||state.viewer!==null)return;pendingContent=false;const anchor=timelineAnchor();renderContent();icons();restoreAnchor(anchor)});
window.setDesktopStatus=status=>{desktopStatus=status;window.desktopStatus=status;const slot=app.querySelector('.sync-slot');if(slot){slot.innerHTML=syncControls();icons()}const settingsSlot=modal.querySelector('.settings-sync-slot');if(settingsSlot){settingsSlot.innerHTML=syncControls();icons()}};
window.desktopNotice=text=>flash(text);
const originalDialog=openDialog;
openDialog=function(kind){originalDialog(kind);modal.classList.toggle('settings-dialog',kind==='mapping');if(kind==='mapping'){modal.querySelector('h2').textContent='设置';const form=modal.querySelector('form'),actions=form.querySelector('.dialogactions'),directory=document.createElement('section');directory.className='settings-group';directory.innerHTML='<h3>照片目录</h3>';for(const node of [...form.children])if(!node.matches('h2,.dialogactions'))directory.append(node);actions.before(directory);actions.insertAdjacentHTML('beforebegin',`<section class="settings-group"><h3>百度网盘索引同步</h3><div class="settings-sync-slot">${syncControls()}</div><button type="button" id="cloud-connect" class="settings-connect">${icon('cloud')}${desktopStatus.connected?'连接配置':'连接百度网盘'}</button><p class="dialoghint">电脑发布索引，手机同步浏览。${desktopStatus.cloudFolder?`索引目录：${esc(desktopStatus.cloudFolder)}`:'授权连接后可开启同步。'}</p></section>`);modal.querySelector('#cloud-connect').onclick=()=>desktopSend('configure-sync');icons()}};

const expandedPeopleInfo=renderInfo;
renderInfo=function(){expandedPeopleInfo();const chips=app.querySelector('.personchips');if(!chips)return;for(const button of chips.querySelectorAll('.avatar-edit')){const name=button.dataset.avatarPerson||button.textContent;for(const node of [...button.childNodes])if(node.nodeType===Node.TEXT_NODE)node.remove();const label=document.createElement('span');label.className='person-chip-name';label.textContent=name;button.append(label);button.title=name;}const heading=chips.closest('.infosection')?.querySelector('.sectionheading h3');if(heading)heading.textContent=`人物 · ${chips.children.length} 位`;};
root.addEventListener('click',e=>{
    const action=e.target.closest('button')?.dataset.action;
    if(action==='refresh'){captureNote();persist();scanText='正在检查目录变化…';refreshPending=true;updateScanStatus();desktopSend('refresh',{store})}
    if(action==='sync-toggle'){captureNote();persist();desktopSend('toggle-sync',{enabled:!desktopStatus.syncEnabled,store})}
    if(action==='sync-now'){captureNote();persist();desktopSend('sync-now',{store})}
});
app.addEventListener('error',e=>{if(e.target.matches('.photo-tile img')){e.target.closest('.photo-tile').classList.add('unavailable');e.target.alt='预览暂不可用 · 点击查看原图'}},true);
// Capture these controls before the legacy month-filter handlers.
root.addEventListener('click',e=>{const b=e.target.closest('button');if(!b||modal.open)return;if(state.viewer!==null){if(b.dataset.avatarPerson)parentContexts.people={query:'',scroll:0};return}const action=b.dataset.action;if(action==='backparent'){e.stopImmediatePropagation();returnToParent();return}if(action==='sort'){e.stopImmediatePropagation();openSort();return}if(action==='managealbums'){e.stopImmediatePropagation();managingAlbums=!managingAlbums;renderContent();icons();return}if(b.dataset.deleteAlbum!==undefined){e.stopImmediatePropagation();openAlbumDelete(Number(b.dataset.deleteAlbum));return}if(b.dataset.album!==undefined||b.dataset.person){e.stopImmediatePropagation();const parent=b.dataset.album!==undefined?'albums':'people';parentContexts[parent]={query:state.query,scroll:window.scrollY};state.view=parent==='albums'?'album':'person';if(parent==='albums')state.album=Number(b.dataset.album);else state.person=b.dataset.person;state.query='';render();window.scrollTo(0,0);return}if(b.dataset.view){managingAlbums=false;requestAnimationFrame(()=>window.scrollTo(0,0))}if(b.dataset.group){e.stopImmediatePropagation();if(b.disabled)return;const anchor=timelineAnchor();state.group=b.dataset.group;renderContent();icons();restoreAnchor(anchor)}else if(b.dataset.month){e.stopImmediatePropagation();jumpTimeline(b.dataset.month)}},true);
window.addEventListener('scroll',()=>{if(scrollFrame||state.view!=='photos'||state.viewer!==null)return;scrollFrame=requestAnimationFrame(()=>{scrollFrame=0;const anchor=timelineAnchor(),p=anchor&&timelinePhotos.find(p=>String(p.id)===anchor.id);if(p&&state.month!==p.month){state.month=p.month;updateTimelinePickers()}})},{passive:true});
window.addEventListener('resize',()=>{clearTimeout(resizeTimer);resizeTimer=setTimeout(()=>{if(state.viewer!==null||state.view==='map'||modal.open)return;const width=galleryWidth();if(Math.abs(width-layoutWidth)<2)return;const anchor=timelineAnchor();renderContent();icons();layoutWidth=width;restoreAnchor(anchor)},120)});

// Only animate bounded, visible elements. The long timeline and its sticky ancestors
// keep their layout coordinates, so navigation stays immediate and anchors stay stable.
const motionPreference=matchMedia('(prefers-reduced-motion: reduce)'),motionEasing='cubic-bezier(.22,1,.36,1)';
let pageMotionFrame=0,pageMotionAnimations=[],motionPageKey=null,dialogMotion=null;
function cancelPageMotion(){cancelAnimationFrame(pageMotionFrame);pageMotionFrame=0;for(const animation of pageMotionAnimations)animation.cancel();pageMotionAnimations=[]}
function requestPageMotion(key,viewer=false){
    if(key===motionPageKey)return;const previous=motionPageKey;motionPageKey=key;cancelPageMotion();if(motionPreference.matches)return;
    pageMotionFrame=requestAnimationFrame(()=>{
        pageMotionFrame=0;const targets=new Set(),main=app.querySelector('#content'),visible=node=>{const r=node.getBoundingClientRect();return r.bottom>0&&r.top<innerHeight};
        if(viewer){const port=app.querySelector('.photo-viewport');if(port)targets.add(port);if(!previous?.startsWith('viewer:'))for(const node of app.querySelectorAll('.viewerbar,.infopanel'))if(visible(node))targets.add(node)}
        else if(main){
            for(const node of main.querySelectorAll('.pagehead,.page-back,.person-header,.tagfilters,.timeline-toolbar,.empty,.mapbox,.places'))if(visible(node)&&targets.size<14)targets.add(node);
            if(state.view==='photos'){
                const rows=main.querySelectorAll('.photo-row');let lo=0,hi=rows.length-1;
                while(lo<=hi){const mid=(lo+hi)>>1;if(rows[mid].getBoundingClientRect().bottom>timelineInset())hi=mid-1;else lo=mid+1}
                for(let i=lo;i<rows.length&&targets.size<14;i++){if(rows[i].getBoundingClientRect().top>=innerHeight)break;targets.add(rows[i])}
            }else for(const node of main.querySelectorAll('.album-item,.person-card,.photo-row'))if(visible(node)&&targets.size<14)targets.add(node);
        }
        for(const node of targets){
            const fixed=node.matches('.photo-row,.mapbox'),port=node.matches('.photo-viewport');
            const from={opacity:port ? .65 : .35},to={opacity:1};
            if(!fixed){from.transform=port?'scale(.985)':'translateY(9px)';to.transform=port?'scale(1)':'translateY(0)'}
            pageMotionAnimations.push(node.animate([from,to],{duration:viewer?240:260,easing:motionEasing}));
        }
    });
}
const motionRenderContent=renderContent;
renderContent=function(){motionRenderContent();const top=app.querySelector('.topbar');if(top)root.style.setProperty('--topbar-height',top.getBoundingClientRect().height+'px');requestPageMotion(`${state.view}:${state.view==='album'?state.album:state.view==='person'?state.person:''}`)};
const motionRenderViewer=renderViewer;
renderViewer=function(){motionRenderViewer();requestPageMotion(`viewer:${state.viewer}`,true)};
window.desktopReveal=()=>{motionPageKey=null;if(state.viewer!==null)requestPageMotion(`viewer:${state.viewer}`,true);else requestPageMotion(`${state.view}:${state.view==='album'?state.album:state.view==='person'?state.person:''}`)};
new MutationObserver(()=>{dialogMotion?.cancel();dialogMotion=null;if(modal.open&&!motionPreference.matches)dialogMotion=modal.animate([{opacity:.4,transform:'translateY(10px) scale(.98)'},{opacity:1,transform:'translateY(0) scale(1)'}],{duration:230,easing:motionEasing})}).observe(modal,{attributes:true,attributeFilter:['open']});
motionPreference.addEventListener('change',()=>{if(motionPreference.matches){cancelPageMotion();dialogMotion?.cancel();dialogMotion=null}});

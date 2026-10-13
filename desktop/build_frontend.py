from pathlib import Path
import re
import runpy
import shutil
import sys

root=Path(__file__).resolve().parent.parent
sys.path.insert(0, str(root / 'scripts'))
from build_config import load_config, script
out=root/'desktop/app'
out.mkdir(parents=True,exist_ok=True)
template=(root/'preview/desktop-v2-template.html').read_text(encoding='utf-8')
enhancements=(root/'preview/enhancements-v3.js').read_text(encoding='utf-8')
template=template.replace('__ENHANCEMENTS__',enhancements)
template=template.replace('__PHOTO_DATA__','{"photos":[],"source":"","count":0,"samples":0}')
template=template.replace('__GEO_DATA__',(root/'preview/simplified-geo.json').read_text(encoding='utf-8'))
template=template.replace('__MAP_CSS__',(root/'preview/maplibre-gl.css').read_text(encoding='utf-8'))
template=template.replace('__D3_CODE__','')
template=template.replace('__MAP_CODE__',(root/'preview/maplibre-gl.js').read_text(encoding='utf-8'))
template=template.replace('__LUCIDE_CODE__',(root/'desktop/vendor/lucide.min.js').read_text(encoding='utf-8'))
template=template.replace('</head>', '<script>'+script(load_config(root))+'</script></head>')
template=template.replace("const data=JSON.parse(document.getElementById('pi-source-data').textContent)","const data=window.desktopPhotoData||JSON.parse(document.getElementById('pi-source-data').textContent)")
template=template.replace("const saved=window.openai?.widgetState?.privateContent?.previewStore||JSON.parse(localStorage.getItem('photoindex.preview.v2')||'null')", "const saved=window.desktopIndex??null")
template=template.replace("function persist(){", "function persist(){if(window.chrome?.webview){desktopSend('save-index',{store});return}")
template=template.replace("window.addEventListener('resize',", "window.addEventListener('desktop:save',()=>{captureNote();persist()});window.addEventListener('resize',")
template=template.replace("store.cloudRoot+'/'+p.name", "store.cloudRoot+'/'+(p.relativePath||p.name)")
template=template.replace("if(b.dataset.avatarPerson){openAvatar(b.dataset.avatarPerson,state.viewer);return}","if(b.dataset.avatarPerson){captureNote();persist();state.person=b.dataset.avatarPerson;state.viewer=null;state.view='person';state.query='';render();window.scrollTo(0,0);return}")
template=template.replace('aria-label="截取 ${esc(n)} 的头像"','aria-label="查看 ${esc(n)} 的所有照片"')
template=template.replace('attributionControl:true','attributionControl:false')
template=template.replace("map.on('error',e=>{", "map.on('error',e=>{(window.desktopMapErrors||=[]).push(String(e.error?.message||'map error').replace(/key=[^& ]+/g,'key=REDACTED'));")
template=template.replace("m.on('error',e=>{", "m.on('error',e=>{(window.desktopMapErrors||=[]).push(String(e.error?.message||'map error').replace(/key=[^& ]+/g,'key=REDACTED'));")
template=template.replace("center:c,zoom:16,localIdeographFontFamily", "center:c,zoom:16,attributionControl:false,localIdeographFontFamily")
credits='<div class="map-credits"><a href="https://www.maptiler.com" target="_blank" rel="noopener"><img src="maptiler-logo.svg" alt="MapTiler"></a><span><a href="https://www.maptiler.com/copyright/" target="_blank" rel="noopener">© MapTiler</a> · <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">© OpenStreetMap 贡献者</a></span></div>'
template=template.replace('<p class="dialoghint">保留相对目录结构，用于手机定位已备份的原片。</p>', '<p class="dialoghint">保留相对目录结构，用于手机定位已备份的原片。</p><label>地图来源</label>'+credits)
template=template.replace('<strong>生活点滴 / 2022</strong>','<strong>${esc(data.label||"照片目录")}</strong>')
template=template.replace('<div class="samplelabel">界面样例 · 35 张照片</div>', '<div class="samplelabel">'+"${data.count} 张照片"+'</div>')
template=template.replace("body+='<div class=\"samplelabel\">${data.count} 张照片</div>'", "body+=`<div class=\"samplelabel\">${data.count} 张照片</div>`")
template=template.replace("month:'2022-06'", "month:months.at(-1)||''")
# Date controls locate the continuous library; they never filter photo membership.
month_filter="else if(state.view==='photos'&&state.group!=='year')ps=ps.filter(p=>p.month===state.month);"
assert month_filter in template, 'Timeline filter changed upstream'
template=template.replace(month_filter,';')
template=template.replace("state.query=e.target.value;renderContent();icons()", "state.query=e.target.value;renderContent();icons();window.scrollTo(0,0)")
template=template.replace("${esc(data.source||'')}\"><label for=\"cloud-path\">", '${esc(data.source)}"><button type="button" id="choose-source" class="textbutton">选择照片目录</button><label for="cloud-path">')
template=template.replace("icons();modal.showModal();}","modal.querySelector('#choose-source')?.addEventListener('click',()=>{captureNote();persist();modal.close();desktopSend('choose-folder',{store})});if(kind==='publish'){modal.querySelector('.dialoghint').textContent='导出人物、相册、批注和位置校准的索引快照。';modal.querySelectorAll('.dialoghint')[1]?.remove();const b=document.createElement('button');b.type='button';b.className='primary';b.textContent='导出索引';b.onclick=()=>{captureNote();persist();desktopSend('export-index',{store});modal.close()};modal.querySelector('.dialogactions').append(b)}icons();modal.showModal();}")
# Group headings and navigation derive from the actual dates, including other years.
template=template.replace('<small>2022年</small>', '<small>${d.slice(0,4)}年</small>')
template=template.replace('2022年${Number(state.month.slice(5))}月','${state.month.slice(0,4)}年${Number(state.month.slice(5))}月')
template=template.replace("else body+=`<div class=\"grouphead\"><h2>2022年</h2></div>`+grid(ps)","else [...new Set(ps.map(p=>p.date.slice(0,4)))].forEach(y=>{body+=`<div class=\"grouphead\"><h2>${y}年</h2></div>`+grid(ps.filter(p=>p.date.startsWith(y)))})")
template=template.replace("${Number(m.slice(5))}月", "${m.slice(0,4)}年${Number(m.slice(5))}月")
css='''
#photoindex-desktop .map-credits{display:flex;align-items:center;justify-content:space-between;gap:5px;flex-wrap:wrap;margin:2px 0 12px;font-size:10px;color:var(--muted)}
#photoindex-desktop .map-credits a{color:inherit;text-decoration:none}
#photoindex-desktop .map-credits img{width:53px;height:auto;display:block}
#photoindex-desktop .location-preview{margin-bottom:3px}
'''
template=template.replace('</head>','<style>'+css+(root/'desktop/frontend.css').read_text(encoding='utf-8')+(root/'desktop/tag_editor.css').read_text(encoding='utf-8')+'</style></head>')
template=template.replace("const all=data.photos,samples=all.filter(p=>p.image)", "const all=data.photos,samples=all.filter(p=>p.image)")
template=template.replace("Object.values(store.assignments).filter(a=>a.includes(n)).length", "samples.filter(p=>(store.assignments[p.id]||[]).includes(n)).length")
template=template.replace("${a.ids.length} 张照片", "${samples.filter(p=>a.ids.includes(p.id)).length} 张照片")
template=template.replace("cloudRoot:'/生活点滴/2022'", "cloudRoot:'/'+(data.label||'生活点滴')")
template=template.replace("__DESKTOP_EXTENSIONS__",'')
template=template.replace("window.addEventListener('resize',",(root/'desktop/frontend.js').read_text(encoding='utf-8')+'\n'+(root/'desktop/tag_editor.js').read_text(encoding='utf-8')+"\nwindow.addEventListener('resize',")
# Parent folders may contain locations beyond the original sample's two cities.
template=template.replace("${['西安','武汉'].map(n=>", "${[].map(n=>")
template=template.replace('<small>本地原片</small>', '<small>${esc(p.relativePath||p.name)}</small>')
template=template.replace('<span>生活点滴</span></div><label class="searchbox">','<span>生活点滴</span><small class="beta-badge">Beta</small></div><label class="searchbox">')
template=template.replace('<span class="brandmark">${icon(\'images\')}</span>','<span class="brandmark"><img src="brand/logo.svg" alt="" width="34" height="34"></span>')
(out/'index.html').write_text(template,encoding='utf-8')
runpy.run_path(str(root/'desktop/create_brand_assets.py'),run_name='__main__')
shutil.copyfile(out/'app.ico',out/'brand/logo.ico')
print('Desktop frontend prepared; photographs remain external and read-only.')

using System;
using System.IO;
using System.Threading.Tasks;

internal sealed partial class PhotoWindow
{
    async Task TestPerformanceAsync()
    {
        string script = @"(async()=>{
            // A note edited without the Save button must invalidate a cached search result on return.
            const first=window.desktopPhotoData.photos[0],previous=window.desktopIndex.notes[first.id]||'';
            const click=s=>{const b=document.querySelector(s);if(!b)throw Error('missing '+s);b.click()};
            const search=text=>{const i=document.querySelector('#photo-search');i.value=text;i.dispatchEvent(new Event('input'))};
            click('[data-view=photos]');search('');click('[data-photo=""'+first.id+'""]');
            document.querySelector('#photo-note').value='缓存回归旧检索词';click('[data-action=savenote]');click('[data-action=closeviewer]');search('缓存回归旧检索词');
            if(document.querySelectorAll('.photo-tile').length!==1)throw Error('note search missing initial result');
            click('[data-photo=""'+first.id+'""]');document.querySelector('#photo-note').value='缓存回归新检索词';click('[data-action=closeviewer]');
            if(document.querySelectorAll('.photo-tile').length!==0)throw Error('unsaved note left stale cached result');
            search('缓存回归新检索词');if(document.querySelectorAll('.photo-tile').length!==1)throw Error('updated note not searchable');
            click('[data-photo=""'+first.id+'""]');document.querySelector('#photo-note').value=previous;click('[data-action=closeviewer]');window.dispatchEvent(new Event('desktop:save'));search('');
            const person=window.__albumQa.person;click('[data-view=people]');search(person);click('[data-person=""'+person+'""]');click('[data-action=backparent]');
            click('[data-view=photos]');click('[data-photo=""'+first.id+'""]');click('[data-avatar-person=""'+person+'""]');click('[data-action=backparent]');
            if(document.querySelector('#photo-search').value!==''||document.querySelectorAll('.person-card').length!==window.desktopIndex.people.length)throw Error('viewer person return restored unrelated old filter');
            const records=[];for(let round=0;round<3;round++)for(const view of ['albums','photos','people','photos']){
                const started=performance.now();document.querySelector('[data-view='+view+']').click();const rendered=performance.now();
                await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));
                records.push({view,renderMs:Math.round((rendered-started)*10)/10,paintMs:Math.round((performance.now()-started)*10)/10,nodes:document.querySelectorAll('*').length,photos:document.querySelectorAll('.photo-tile').length});
            }return {ok:true,count:window.desktopPhotoData.count,unsavedNoteCache:true,personParentFilter:true,records};
        })()";
        string response = await web.CoreWebView2.CallDevToolsProtocolMethodAsync("Runtime.evaluate", json.Serialize(new { expression = script, awaitPromise = true, returnByValue = true }));
        Assert(response.Contains("\"ok\":true") || response.Contains("\"ok\": true"), "Performance navigation failed: " + response);
        File.WriteAllText(Path.Combine(dataRoot, "performance.json"), response);
    }
}

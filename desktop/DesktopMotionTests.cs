using System;
using System.IO;
using System.Threading.Tasks;
using Microsoft.Web.WebView2.Core;

internal sealed partial class PhotoWindow
{
    async Task TestMotionAsync()
    {
        string normal = "", reduced = "";
        Exception failure = null;
        try
        {
            await web.CoreWebView2.CallDevToolsProtocolMethodAsync("Emulation.setEmulatedMedia", "{\"features\":[{\"name\":\"prefers-reduced-motion\",\"value\":\"no-preference\"}]}");
            string script = @"(async()=>{
                const fixture=window.__albumQa;if(!fixture)throw Error('album fixture missing');
                const q=window.__motionQa={records:[],total:window.desktopPhotoData.photos.filter(p=>p.image).length};
                const frames=()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));
                const click=s=>{const b=document.querySelector(s);if(!b)throw Error('missing motion control '+s);b.click();return b};
                const live=()=>document.getAnimations().filter(a=>a.effect?.target?.isConnected);
                const expectedEase='cubic-bezier(0.22,1,0.36,1)';
                const easing=e=>String(e).replace(/\s/g,'').replace(/([,(])\./g,(_,lead)=>lead+'0.');
                q.frames=frames;q.click=click;q.live=live;
                q.motion=()=>live().filter(a=>easing(a.effect.getTiming().easing)===expectedEase);
                async function step(selector,label,heading){
                    const start=performance.now();click(selector);
                    const rendered=performance.now()-start;
                    if(heading&&document.querySelector('h1')?.textContent!==heading)throw Error(label+' rendered wrong page');
                    await frames();
                    const animations=q.motion();
                    if(!animations.length)throw Error(label+' has no nonlinear page motion');
                    for(const animation of animations){
                        const timing=animation.effect.getTiming(),target=animation.effect.target;
                        if(!Number.isFinite(Number(timing.duration))||Number(timing.duration)<=0||Number(timing.duration)>280)throw Error(label+' motion duration outside bounds');
                        if(target.matches('.photo-row')&&animation.effect.getKeyframes().some(k=>k.transform&&k.transform!=='none'))throw Error('photo-row motion changes viewport anchors');
                    }
                    if(label.startsWith('nav-')&&animations.length>14)throw Error('too many navigation animation targets');
                    q.records.push({step:label,renderMs:Math.round(rendered*10)/10,paintMs:Math.round((performance.now()-start)*10)/10,animations:animations.map(a=>({target:a.effect.target.className,duration:a.effect.getTiming().duration,easing:a.effect.getTiming().easing}))});
                    return animations;
                }
                click('[data-view=photos]');
                const search=document.querySelector('#photo-search');search.value='';search.dispatchEvent(new Event('input'));
                await frames();
                await step('[data-view=albums]','nav-albums','相册');
                await step('[data-view=photos]','nav-photos','照片');
                if(document.querySelectorAll('.photo-tile').length!==q.total)throw Error('motion lost continuous timeline photos');
                const cachedFirst=document.querySelector('.photo-tile');
                await step('[data-view=people]','nav-people','人物');
                await step('[data-person=""'+fixture.person+'""]','person-detail',fixture.person);
                await step('.page-back[data-action=backparent]','person-back','人物');
                const mapMotion=await step('[data-view=map]','nav-map','地点');
                const mapBox=document.querySelector('.mapbox'),mapRect=mapBox.getBoundingClientRect();
                if(mapRect.width<200||mapRect.height<200||!mapBox.querySelector('canvas'))throw Error('map layout missing after animated navigation');
                if(mapMotion.find(a=>a.effect.target===mapBox)?.effect.getKeyframes().some(frame=>frame.transform))throw Error('map transition transforms canvas geometry');
                await step('[data-view=albums]','nav-albums-before-detail','相册');
                const card=[...document.querySelectorAll('.album-card')].find(b=>b.querySelector('.title')?.textContent===fixture.names.a);
                if(!card)throw Error('motion album fixture not visible');
                await step('[data-album=""'+card.dataset.album+'""]','album-detail',fixture.names.a);
                await step('.page-back[data-action=backparent]','album-back','相册');
                await step('[data-view=photos]','nav-photos-before-viewer','照片');
                if(document.querySelector('.photo-tile')!==cachedFirst)throw Error('animated return rebuilt cached photo DOM');
                const tiles=[...document.querySelectorAll('.photo-tile')],first=tiles[0],second=tiles[1];
                const byId=new Map(window.desktopPhotoData.photos.map(p=>[String(p.id),p]));
                if(!first||!second)throw Error('viewer motion needs two photos');
                const originalTop=first.getBoundingClientRect().top;
                await step('[data-photo=""'+first.dataset.photo+'""]','viewer-open');
                if(!document.querySelector('#large-photo'))throw Error('viewer did not render');
                await step('[data-action=next]','viewer-next');
                if(document.querySelector('#large-photo').alt!==byId.get(second.dataset.photo).name)throw Error('viewer motion changed queue order');
                await step('[data-action=closeviewer]','viewer-close','照片');
                if(Math.abs(document.querySelector('[data-photo=""'+first.dataset.photo+'""]').getBoundingClientRect().top-originalTop)>2)throw Error('active motion changed viewer return anchor');
                // Cancel running page motion by immediately requesting another navigation.
                const previous=await step('[data-view=albums]','nav-albums-before-fast-switch','相册');
                const started=performance.now();click('[data-view=photos]');click('[data-view=people]');click('[data-view=albums]');
                if(document.querySelector('h1')?.textContent!=='相册')throw Error('rapid navigation left a stale page');
                await frames();
                if(previous.some(a=>a.playState==='running'||a.playState==='paused'))throw Error('rapid navigation did not cancel previous animations');
                q.rapid={cancelled:previous.length,renderMs:Math.round((performance.now()-started)*10)/10,finalPage:'相册'};
                await Promise.all(q.motion().map(a=>a.finished.catch(()=>{})));
                if(q.motion().some(a=>a.playState==='running'))throw Error('rapid navigation left unfinished page motion');
                q.cachedPhotoDom=true;q.animatedViewerAnchor=true;
                await step('[data-view=photos]','nav-photos-before-toolbar','照片');
                window.scrollTo(0,0);await frames();
                const main=document.querySelector('#content'),toolbar=document.querySelector('.timeline-toolbar'),css=getComputedStyle(main);
                const inner=main.clientWidth-parseFloat(css.paddingLeft)-parseFloat(css.paddingRight),initial=toolbar.getBoundingClientRect();
                if(initial.width>=inner-1)throw Error('floating toolbar still spans full main width');
                if(initial.height>52.5)throw Error('floating toolbar is taller than 52px');
                const positions=[];
                for(const scroll of [900,1600]){
                    window.scrollTo(0,scroll);await frames();
                    const bar=toolbar.getBoundingClientRect(),header=document.querySelector('.topbar').getBoundingClientRect(),gap=bar.top-header.bottom;
                    if(gap<7.5||gap>20.5)throw Error('floating toolbar sticky offset incorrect: '+gap);
                    if(Math.abs(bar.height-initial.height)>1||Math.abs(bar.width-initial.width)>1)throw Error('floating toolbar changed size while scrolling');
                    if(document.querySelectorAll('.photo-tile').length!==q.total)throw Error('scrolling removed timeline photos');
                    positions.push({scroll:window.scrollY,top:bar.top,headerBottom:header.bottom,gap});
                }
                q.toolbar={width:initial.width,mainInnerWidth:inner,height:initial.height,positions};
                await step('[data-action=sort]','sort-dialog');
                if(!q.motion().some(a=>a.effect.target===document.querySelector('dialog')))throw Error('dialog opening has no nonlinear motion');
                click('dialog [data-dismiss]');await frames();
                return {ok:true,total:q.total,records:q.records,rapid:q.rapid,toolbar:q.toolbar,cachedPhotoDom:q.cachedPhotoDom,animatedViewerAnchor:q.animatedViewerAnchor};
            })()";
            normal = await web.CoreWebView2.CallDevToolsProtocolMethodAsync("Runtime.evaluate", json.Serialize(new { expression = script, awaitPromise = true, returnByValue = true }));
            Assert(normal.Contains("\"ok\":true") || normal.Contains("\"ok\": true"), "Page motion/floating toolbar failed: " + normal);
            using (var stream = File.Create(Path.Combine(dataRoot, "floating-toolbar.png"))) await web.CoreWebView2.CapturePreviewAsync(CoreWebView2CapturePreviewImageFormat.Png, stream);
            await web.CoreWebView2.CallDevToolsProtocolMethodAsync("Emulation.setEmulatedMedia", "{\"features\":[{\"name\":\"prefers-reduced-motion\",\"value\":\"reduce\"}]}");
            script = @"(async()=>{
                const q=window.__motionQa,fixture=window.__albumQa,records=[];
                await q.frames();
                if(!matchMedia('(prefers-reduced-motion: reduce)').matches)throw Error('reduced motion emulation inactive');
                async function step(selector,label,heading){
                    q.click(selector);
                    if(heading&&document.querySelector('h1')?.textContent!==heading)throw Error(label+' reduced-motion page incorrect');
                    await q.frames();
                    const animations=q.motion();
                    if(animations.length)throw Error(label+' ignores reduced motion: '+animations.map(a=>a.effect.target.className).join(','));
                    records.push({step:label,animations:0});
                }
                await step('[data-view=albums]','albums','相册');
                const card=[...document.querySelectorAll('.album-card')].find(b=>b.querySelector('.title')?.textContent===fixture.names.a);
                if(!card)throw Error('reduced motion album fixture missing');
                await step('[data-album=""'+card.dataset.album+'""]','album-detail',fixture.names.a);
                await step('.page-back[data-action=backparent]','album-back','相册');
                await step('[data-view=people]','people','人物');
                await step('[data-person=""'+fixture.person+'""]','person-detail',fixture.person);
                await step('.page-back[data-action=backparent]','person-back','人物');
                await step('[data-view=map]','map','地点');
                await step('[data-view=photos]','photos','照片');
                const first=document.querySelector('.photo-tile');
                await step('[data-photo=""'+first.dataset.photo+'""]','viewer-open');
                await step('[data-action=next]','viewer-next');
                await step('[data-action=closeviewer]','viewer-close','照片');
                if(document.querySelectorAll('.photo-tile').length!==q.total)throw Error('reduced motion changed photo membership');
                await step('[data-action=sort]','sort-dialog');
                q.click('dialog [data-dismiss]');await q.frames();
                window.scrollTo(0,0);
                return {ok:true,total:q.total,reducedMotion:true,records};
            })()";
            reduced = await web.CoreWebView2.CallDevToolsProtocolMethodAsync("Runtime.evaluate", json.Serialize(new { expression = script, awaitPromise = true, returnByValue = true }));
            Assert(reduced.Contains("\"ok\":true") || reduced.Contains("\"ok\": true"), "Reduced motion failed: " + reduced);
        }
        catch (Exception error) { failure = error; }
        // C# 5 cannot await inside finally; restore emulation before propagating failures.
        await web.CoreWebView2.CallDevToolsProtocolMethodAsync("Emulation.setEmulatedMedia", "{\"features\":[]}");
        if (failure != null) throw failure;
        File.WriteAllText(Path.Combine(dataRoot, "motion.json"), "{\"normal\":" + normal + ",\"reduced\":" + reduced + "}");
    }
}

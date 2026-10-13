using System;
using System.Collections;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Web.WebView2.Core;

internal sealed partial class PhotoWindow
{
    static void Assert(bool condition, string message) { if (!condition) throw new InvalidOperationException(message); }
    async Task TestAsync()
    {
        try
        {
            long firstPaint = startup.ElapsedMilliseconds;
            await Task.Delay(1500);
            string first = await web.CoreWebView2.ExecuteScriptAsync(@"(()=>({count:window.desktopPhotoData.count,source:window.desktopPhotoData.source,years:[...document.querySelector('#timeline-year').options].map(o=>o.value),hasRefresh:!!document.querySelector('[data-action=refresh]'),hasSync:!!document.querySelector('[role=switch]'),loading:document.querySelector('.scan-line')?.textContent,visibleImages:[...document.images].filter(i=>i.complete&&i.naturalWidth>0).length}))()");
            Assert(!loading.Visible, "Splash did not reveal ready main page");
            File.WriteAllText(Path.Combine(dataRoot, "first-paint.json"), "{\"nativeMs\":" + firstPaint + ",\"pageReadyMs\":" + pageReadyMs + ",\"mainShownMs\":" + mainShownMs + ",\"page\":" + first + "}");
            using (var stream = File.Create(Path.Combine(dataRoot, "first-paint.png"))) await web.CoreWebView2.CapturePreviewAsync(CoreWebView2CapturePreviewImageFormat.Png, stream);
            await scanTask; await Task.Delay(500);
            File.WriteAllText(Path.Combine(dataRoot, "scan.json"), json.Serialize(photoData));
            await TestTimelineAsync();
            await TestAlbumNavigationAsync();
            await TestTagEditorAsync();
            await TestPerformanceAsync();
            await TestMotionAsync();
            await TestHeifAsync();
            string test = await web.CoreWebView2.ExecuteScriptAsync(@"(()=>{const year=document.querySelector('#timeline-year');year.value=[...year.options].some(o=>o.value==='2022')?'2022':year.value;year.dispatchEvent(new Event('change'));const month=document.querySelector('#timeline-month');if([...month.options].some(o=>o.value==='2022-06')){month.value='2022-06';month.dispatchEvent(new Event('change'))}const photo=window.desktopPhotoData.photos.find(p=>p.month===month.value);const first=photo?document.querySelector('[data-photo=""'+photo.id+'""]'):document.querySelector('[data-photo]');if(!first)throw Error('no photo');first.click();const viewer=!!document.querySelector('#large-photo');document.querySelector('[data-action=tagperson]').click();document.querySelector('#person-name').value='安装验证人物';document.querySelector('[data-save]').click();document.querySelector('[data-avatar-person]').click();const person=document.querySelector('h1').textContent;return {ok:viewer&&person==='安装验证人物'&&document.querySelectorAll('.photo-tile').length>0,viewer,person,photos:document.querySelectorAll('.photo-tile').length,attribution:document.querySelectorAll('.maplibregl-ctrl-attrib').length}})()");
            Assert(test.Contains("\"ok\":true"), "UI interaction failed: " + test);
            File.WriteAllText(Path.Combine(dataRoot, "interaction.json"), test);
            await web.CoreWebView2.ExecuteScriptAsync("document.querySelector('[data-view=photos]').click();document.querySelector('[data-action=refresh]').click()");
            await Task.Delay(700); await scanTask; await Task.Delay(300);
            File.WriteAllText(Path.Combine(dataRoot, "refresh.json"), json.Serialize(new { reads = photoData["metadataReads"], count = photoData["count"], time = photoData["scanMs"] }));
            Assert(Convert.ToInt32(photoData["metadataReads"]) == 0, "Warm refresh re-read unchanged EXIF");
            string parentRoot = sourceRoot; var selected = PhotoLibrary.Photos(photoData).FirstOrDefault(p => Convert.ToString(p["relativePath"]).StartsWith("2022/"));
            if (selected != null)
            {
                long stable = Convert.ToInt64(selected["id"]); string previousSession = session;
                await LoadFolderAsync(Path.Combine(parentRoot, "2022")); for (int i = 0; !ready && i < 50; i++) await Task.Delay(100); await scanTask;
                Assert(PhotoLibrary.Photos(photoData).Any(p => Convert.ToInt64(p["id"]) == stable), "Native child switch changed photo identity");
                Assert(((IEnumerable)index["people"]).Cast<object>().Select(Convert.ToString).Contains("安装验证人物"), "Native child switch lost person");
                var preferences = (Dictionary<string, object>)index["uiPreferences"];
                Assert(Convert.ToString(preferences["photoSort"]) == "time-desc" && Convert.ToString(preferences["albumSort"]) == "created", "Directory switch lost sort preferences");
                Assert(session != previousSession, "Folder switch reused stale session");
                var warm = System.Diagnostics.Stopwatch.StartNew(); await LoadFolderAsync(parentRoot); for (int i = 0; !ready && i < 50; i++) await Task.Delay(100); await scanTask;
                File.WriteAllText(Path.Combine(dataRoot, "directory-switch.json"), json.Serialize(new { parentCount = photoData["count"], warmMs = warm.ElapsedMilliseconds, stableIds = true, retainedPeople = true }));
            }
            string before = File.ReadAllText(Path.Combine(dataRoot, "library-index-v2.json"));
            await web.CoreWebView2.ExecuteScriptAsync("window.chrome.webview.postMessage({type:'save-index',session:'previous-directory-session',store:{albums:[],people:['STALE']}})");
            await Task.Delay(150); Assert(before == File.ReadAllText(Path.Combine(dataRoot, "library-index-v2.json")), "Stale page overwrote index");
            await web.CoreWebView2.ExecuteScriptAsync("document.querySelector('[data-action=mapping]').click()");
            File.WriteAllText(Path.Combine(dataRoot, "settings.json.qa"), await web.CoreWebView2.ExecuteScriptAsync("({credits:!!document.querySelector('dialog .map-credits'),picker:!!document.querySelector('#choose-source'),syncSwitch:!!document.querySelector('dialog [role=switch]')})"));
            using (var stream = File.Create(Path.Combine(dataRoot, "settings.png"))) await web.CoreWebView2.CapturePreviewAsync(CoreWebView2CapturePreviewImageFormat.Png, stream);
            using (var d = new CloudSettingsDialog(cloud)) { d.Show(); await Task.Delay(150); using (var capture = new Bitmap(d.Width, d.Height)) { d.DrawToBitmap(capture, new Rectangle(0, 0, d.Width, d.Height)); capture.Save(Path.Combine(dataRoot, "cloud-settings.png")); } d.Close(); }
            await RegressionTestsAsync();
            File.WriteAllText(Path.Combine(dataRoot, "PASS.txt"), "Native branded splash reveals ready page; existing-tag clicks/keyboard/deduplication/cancel/safe text/persistence/filter; continuous all-photo timeline; native aspect ratios; floating toolbar; eased page/viewer/dialog transitions and rapid cancellation; reduced motion; cached photo DOM and viewport anchors; map layout; cross-month viewer; HEIF; parent directory UI; stable IDs and migration; refresh cache; stale saves; sync protocol and cancellation verified.");
        }
        catch (Exception e) { File.WriteAllText(Path.Combine(dataRoot, "self-test-error.txt"), e.ToString()); }
        Close();
    }
    async Task TestTimelineAsync()
    {
        var available = PhotoLibrary.Photos(photoData).ToList();
        foreach (var predicate in new Func<double, bool>[] { r => r < .8, r => r > 1.3 && r < 2, r => r > 4 })
        {
            var photo = available.FirstOrDefault(p => predicate(Convert.ToDouble(p["width"]) / Convert.ToDouble(p["height"]))); if (photo == null) continue;
            byte[] bytes = await Task.Run(() => library.Thumbnail(Convert.ToString(photo["path"]), Convert.ToString(photo["stamp"])));
            using (var input = new MemoryStream(bytes)) using (var thumb = Image.FromStream(input)) Assert(Math.Abs(((double)thumb.Width / thumb.Height) / (Convert.ToDouble(photo["width"]) / Convert.ToDouble(photo["height"])) - 1) < .01, "Thumbnail changed original aspect ratio: " + photo["name"]);
        }
        string result = await web.CoreWebView2.ExecuteScriptAsync(@"(()=>{
            const ps=window.desktopPhotoData.photos.filter(p=>p.image),ids=ps.map(p=>String(p.id));const groups=[];
            for(const g of ['day','month','year']){document.querySelector('[data-group='+g+']').click();const got=[...document.querySelectorAll('.photo-tile')].map(t=>t.dataset.photo);if(got.length!==ids.length||got.some((id,i)=>id!==ids[i]))throw Error(g+' lost or reordered photos');groups.push({group:g,count:got.length})}
            const ratios=[ps.find(p=>p.width/p.height<.8),ps.find(p=>p.width/p.height>1.3&&p.width/p.height<2),ps.find(p=>p.width/p.height>4)].filter(Boolean).map(p=>{const tile=document.querySelector('[data-photo=""'+p.id+'""]'),r=tile.getBoundingClientRect(),actual=r.width/r.height,expected=p.width/p.height;if(Math.abs(actual/expected-1)>.01||getComputedStyle(tile.querySelector('img')).objectFit!=='contain')throw Error('photo cropped: '+p.name);return {name:p.name,expected,actual}});
            if(document.documentElement.scrollWidth>innerWidth+1)throw Error('horizontal overflow');document.querySelector('[data-group=day]').click();return {ok:true,groups,ratios,count:ids.length}
        })()");
        Assert(result.Contains("\"ok\":true"), "Continuous timeline/aspect ratio failed: " + result); File.WriteAllText(Path.Combine(dataRoot, "timeline.json"), result);
        await web.CoreWebView2.ExecuteScriptAsync(@"(()=>{const y=document.querySelector('#timeline-year');if([...y.options].some(o=>o.value==='2022'))y.value='2022';y.dispatchEvent(new Event('change'));const m=document.querySelector('#timeline-month');if([...m.options].some(o=>o.value==='2022-06'))m.value='2022-06';m.dispatchEvent(new Event('change'))})()");
        bool galleryLoaded = false;
        for (int i = 0; i < 80; i++) { await Task.Delay(150); if (await web.CoreWebView2.ExecuteScriptAsync("(()=>{const visible=[...document.querySelectorAll('.photo-tile img')].filter(i=>{const r=i.getBoundingClientRect();return r.bottom>190&&r.top<innerHeight});return visible.length>0&&visible.every(i=>i.complete&&i.naturalWidth>0)})()") == "true") { galleryLoaded = true; break; } }
        Assert(galleryLoaded, "Date jump thumbnails did not load");
        using (var stream = File.Create(Path.Combine(dataRoot, "ios-timeline.png"))) await web.CoreWebView2.CapturePreviewAsync(CoreWebView2CapturePreviewImageFormat.Png, stream);
        result = await web.CoreWebView2.ExecuteScriptAsync(@"(()=>{
            const ps=window.desktopPhotoData.photos,month=document.querySelector('#timeline-month').value,p=ps.find(p=>p.month===month),tile=document.querySelector('[data-photo=""'+p.id+'""]');const before=tile.getBoundingClientRect().top;
            if(before<150||before>500||document.querySelectorAll('.photo-tile').length!==ps.length)throw Error('date jump filtered or misplaced photos');
            document.querySelector('[data-group=month]').click();let after=document.querySelector('[data-photo=""'+p.id+'""]').getBoundingClientRect().top;if(Math.abs(before-after)>2)throw Error('grouping lost viewport');
            document.querySelector('[data-photo=""'+p.id+'""]').click();document.querySelector('[data-action=closeviewer]').click();after=document.querySelector('[data-photo=""'+p.id+'""]').getBoundingClientRect().top;if(Math.abs(before-after)>2)throw Error('viewer return lost viewport');
            const boundary=ps.findIndex((p,i)=>i<ps.length-1&&p.month!==ps[i+1].month);if(boundary>=0){document.querySelector('[data-photo=""'+ps[boundary].id+'""]').click();document.querySelector('[data-action=next]').click();if(document.querySelector('#large-photo').alt!==ps[boundary+1].name)throw Error('viewer stopped at month boundary');document.querySelector('[data-action=closeviewer]').click()}
            const m=document.querySelector('#timeline-month');m.value=month;m.dispatchEvent(new Event('change'));window.__timelineQa={id:String(p.id),top:document.querySelector('[data-photo=""'+p.id+'""]').getBoundingClientRect().top};return {ok:true,month,before,after,crossMonth:true}
        })()");
        Assert(result.Contains("\"ok\":true"), "Timeline navigation failed: " + result); File.WriteAllText(Path.Combine(dataRoot, "timeline-navigation.json"), result);
        ClientSize = new Size(1024, 760); await Task.Delay(700);
        result = await web.CoreWebView2.ExecuteScriptAsync(@"(()=>{const q=window.__timelineQa,t=document.querySelector('[data-photo=""'+q.id+'""]'),r=t.getBoundingClientRect();if(Math.abs(r.top-q.top)>3||document.documentElement.scrollWidth>innerWidth+1)throw Error('resize lost anchor or overflowed');const before=r.top;window.applyDesktopPhotos(window.desktopPhotoData);const after=document.querySelector('[data-photo=""'+q.id+'""]').getBoundingClientRect().top;if(Math.abs(before-after)>3)throw Error('metadata update lost anchor');window.scrollTo(0,document.documentElement.scrollHeight);const last=[...document.querySelectorAll('.photo-tile')].at(-1),rect=last.getBoundingClientRect();if(rect.bottom>innerHeight+2||rect.bottom<0)throw Error('oldest photo unreachable');return {ok:true,resizeTop:r.top,metadataUpdateTop:after,lastPhoto:last.dataset.photo,total:document.querySelectorAll('.photo-tile').length}})()");
        Assert(result.Contains("\"ok\":true"), "Timeline resize/end failed: " + result); File.WriteAllText(Path.Combine(dataRoot, "timeline-scroll.json"), result);
        ClientSize = new Size(1280, 820); await Task.Delay(400); await web.CoreWebView2.ExecuteScriptAsync("window.scrollTo(0,0);document.querySelector('[data-group=day]').click()");
    }
    async Task TestAlbumNavigationAsync()
    {
        string result = await web.CoreWebView2.ExecuteScriptAsync(@"(()=>{
            const q=window.__albumQa={
                names:{a:'排序验证A · UI测试',z:'排序验证Z · UI测试',empty:'空相册验证 · UI测试'},
                person:'相册管理验证人物',note:'相册删除后保留的照片批注',albumNote:'排序后写入 A 相册的批注',
                photos:window.desktopPhotoData.photos.filter(p=>p.image)
            };
            function click(selector){const el=document.querySelector(selector);if(!el)throw Error('missing control: '+selector);el.click();return el}
            q.click=click;
            q.cards=()=>[...document.querySelectorAll('.album-card[data-album]')];
            q.card=name=>q.cards().find(c=>c.querySelector('.title')?.textContent===name);
            q.sort=value=>{
                click('[data-action=sort]');
                if(document.querySelector('dialog').dataset.kind!=='sort')throw Error('wrong sort dialog');
                click('dialog [name=sort][value=""'+value+'""]');
                click('dialog [data-save]');
            };
            if(q.photos.length<2)throw Error('album regression requires two photos');
            q.first=q.photos[0];q.last=q.photos.at(-1);
            click('[data-view=albums]');
            q.originalCount=q.cards().length;
            // Create Z first so deleting it must shift A's original array index.
            for(const name of [q.names.z,q.names.a,q.names.empty]){
                click('[data-action=newalbum]');document.querySelector('#album-name').value=name;click('dialog [data-save]');
                if(!q.card(name))throw Error('created album missing: '+name);
            }
            q.aIndex=Number(q.card(q.names.a).dataset.album);q.zIndex=Number(q.card(q.names.z).dataset.album);
            if(q.zIndex>=q.aIndex)throw Error('fixture must exercise index shift');
            click('[data-view=photos]');click('[data-photo=""'+q.first.id+'""]');
            click('[data-action=tagperson]');document.querySelector('#person-name').value=q.person;click('dialog [data-save]');
            document.querySelector('#photo-note').value=q.note;click('[data-action=savenote]');
            click('[data-action=addalbum]');
            for(const index of [q.aIndex,q.zIndex])document.querySelector('dialog [name=album][value=""'+index+'""]').checked=true;
            click('dialog [data-save]');click('[data-action=closeviewer]');
            click('[data-photo=""'+q.last.id+'""]');click('[data-action=addalbum]');
            document.querySelector('dialog [name=album][value=""'+q.aIndex+'""]').checked=true;
            click('dialog [data-save]');click('[data-action=closeviewer]');
            click('[data-view=albums]');q.sort('name-asc');
            const collator=new Intl.Collator('zh-CN',{numeric:true,sensitivity:'base'}),names=q.cards().map(c=>c.querySelector('.title').textContent);
            if(names.some((n,i)=>i&&collator.compare(names[i-1],n)>0))throw Error('album name ascending order incorrect');
            const a=q.card(q.names.a);if(Number(a.dataset.album)!==q.aIndex)throw Error('sorted display changed original album index');
            a.click();
            if(document.querySelector('h1')?.textContent!==q.names.a)throw Error('sorted card opened wrong album');
            const ids=[...document.querySelectorAll('.photo-tile')].map(t=>t.dataset.photo);
            if(ids.length!==2||!ids.includes(String(q.first.id))||!ids.includes(String(q.last.id)))throw Error('sorted card opened wrong album membership');
            if(!document.querySelector('.page-back[data-action=backparent]'))throw Error('album detail has no parent navigation');
            click('[data-action=albumnote]');document.querySelector('#album-note').value=q.albumNote;click('dialog [data-save]');
            if(document.querySelector('.album-note')?.textContent!==q.albumNote)throw Error('album note not displayed');
            q.sort('time-asc');
            const ordered=[...document.querySelectorAll('.photo-tile')].map(t=>t.dataset.photo);
            if(ordered[0]!==String(q.last.id)||ordered[1]!==String(q.first.id))throw Error('album ascending photo sort incorrect');
            click('[data-photo=""'+ordered[0]+'""]');click('[data-action=next]');
            if(document.querySelector('#large-photo')?.alt!==q.first.name)throw Error('album viewer next differs from visible order');
            click('[data-action=closeviewer]');window.scrollTo(0,0);
            return {ok:true,created:names.filter(n=>Object.values(q.names).includes(n)),originalIndices:{a:q.aIndex,z:q.zIndex},albumPhotoOrder:ordered}
        })()");
        Assert(result.Contains("\"ok\":true"), "Album creation/sort/navigation failed: " + result);
        File.WriteAllText(Path.Combine(dataRoot, "album-details.json"), result);
        await Task.Delay(500);
        using (var stream = File.Create(Path.Combine(dataRoot, "album-details.png"))) await web.CoreWebView2.CapturePreviewAsync(CoreWebView2CapturePreviewImageFormat.Png, stream);
        result = await web.CoreWebView2.ExecuteScriptAsync(@"(()=>{
            const q=window.__albumQa; q.click('.page-back[data-action=backparent]');
            if(document.querySelector('h1')?.textContent!=='相册')throw Error('album parent return failed');
            q.sort('name-desc');const names=q.cards().map(c=>c.querySelector('.title').textContent),c=new Intl.Collator('zh-CN',{numeric:true,sensitivity:'base'});
            if(names.some((n,i)=>i&&c.compare(names[i-1],n)<0))throw Error('album name descending order incorrect');
            q.click('[data-action=managealbums]');
            if(document.querySelectorAll('.album-item [data-delete-album]').length!==q.cards().length)throw Error('management actions missing');
            if(!q.card(q.names.a)||!q.card(q.names.z)||!q.card(q.names.empty))throw Error('management lost fixture album');
            window.scrollTo(0,0);return {ok:true,count:q.cards().length}
        })()");
        Assert(result.Contains("\"ok\":true"), "Album management failed: " + result);
        await Task.Delay(200);
        using (var stream = File.Create(Path.Combine(dataRoot, "album-management.png"))) await web.CoreWebView2.CapturePreviewAsync(CoreWebView2CapturePreviewImageFormat.Png, stream);
        string beforeCancel = File.ReadAllText(Path.Combine(dataRoot, "library-index-v2.json"));
        result = await web.CoreWebView2.ExecuteScriptAsync(@"(()=>{
            const q=window.__albumQa,idx=q.card(q.names.a).dataset.album;
            q.click('[data-delete-album=""'+idx+'""]');
            const d=document.querySelector('dialog');
            if(d.dataset.kind!=='deletealbum'||!d.textContent.includes(q.names.a))throw Error('delete confirmation lacks intended album');
            q.click('dialog [data-dismiss]');
            if(!q.card(q.names.a)||q.cards().length!==q.originalCount+3)throw Error('cancel deleted album');
            return {ok:true,cancelled:q.names.a}
        })()");
        Assert(result.Contains("\"ok\":true"), "Album delete cancellation failed: " + result);
        await Task.Delay(200);
        Assert(beforeCancel == File.ReadAllText(Path.Combine(dataRoot, "library-index-v2.json")), "Cancelling album deletion changed persisted index");
        result = await web.CoreWebView2.ExecuteScriptAsync(@"(()=>{
            const q=window.__albumQa,idx=q.card(q.names.z).dataset.album;
            q.click('[data-delete-album=""'+idx+'""]');
            if(!document.querySelector('dialog').textContent.includes(q.names.z))throw Error('wrong album delete confirmation');
            q.click('dialog [data-save]');
            if(q.card(q.names.z)||!q.card(q.names.a)||!q.card(q.names.empty)||q.cards().length!==q.originalCount+2)throw Error('wrong album deleted');
            const a=q.card(q.names.a);
            if(Number(a.dataset.album)!==q.aIndex-1)throw Error('delete did not refresh shifted album index');
            a.click();
            if(document.querySelector('h1')?.textContent!==q.names.a||document.querySelector('.album-note')?.textContent!==q.albumNote||document.querySelectorAll('.photo-tile').length!==2)throw Error('remaining album or its note corrupted');
            q.click('.page-back[data-action=backparent]');q.card(q.names.empty).click();
            if(document.querySelectorAll('.photo-tile').length!==0||!document.querySelector('.page-back'))throw Error('empty album detail failed');
            q.click('.page-back[data-action=backparent]');q.click('[data-view=photos]');
            q.click('[data-photo=""'+q.first.id+'""]');
            if(document.querySelector('#photo-note')?.value!==q.note||!document.querySelector('[data-avatar-person=""'+q.person+'""]'))throw Error('album deletion changed photo annotations');
            q.click('[data-avatar-person=""'+q.person+'""]');
            if(document.querySelector('h1')?.textContent!==q.person||!document.querySelector('.page-back[data-action=backparent]'))throw Error('person detail navigation unavailable');
            q.click('.page-back[data-action=backparent]');
            if(document.querySelector('h1')?.textContent!=='人物')throw Error('person parent return failed');
            q.click('[data-view=photos]');
            q.sort('time-desc');
            return {ok:true,deleted:q.names.z,remaining:q.names.a,indexShift:true,photoNotePreserved:true,personPreserved:true,emptyAlbum:true}
        })()");
        Assert(result.Contains("\"ok\":true"), "Album deletion/parent navigation failed: " + result);
        File.WriteAllText(Path.Combine(dataRoot, "album-deletion.json"), result);
        await Task.Delay(200);
        var persisted = json.Deserialize<Dictionary<string, object>>(File.ReadAllText(Path.Combine(dataRoot, "library-index-v2.json")));
        var albums = ((IEnumerable)persisted["albums"]).Cast<Dictionary<string, object>>().ToList();
        var retained = albums.First(a => Convert.ToString(a["name"]) == "排序验证A · UI测试");
        Assert(!albums.Any(a => Convert.ToString(a["name"]) == "排序验证Z · UI测试"), "Deleted album persisted");
        Assert(Convert.ToString(retained["note"]) == "排序后写入 A 相册的批注" && ((IEnumerable)retained["ids"]).Cast<object>().Count() == 2, "Sorted album annotation saved to wrong index");
        result = await web.CoreWebView2.ExecuteScriptAsync(@"(()=>{
            const q=window.__albumQa,byId=new Map(q.photos.map(p=>[String(p.id),p])),collator=new Intl.Collator('zh-CN',{numeric:true,sensitivity:'base'}),report=[];
            for(const sort of ['time-asc','time-desc','name-asc','name-desc']){
                q.sort(sort);window.scrollTo(0,0);
                const got=[...document.querySelectorAll('.photo-tile')].map(t=>t.dataset.photo);
                if(got.length!==q.photos.length||new Set(got).size!==got.length||got.some(id=>!byId.has(id)))throw Error(sort+' lost photos');
                for(let i=1;i<got.length;i++){
                    const a=byId.get(got[i-1]),b=byId.get(got[i]);
                    const cmp=sort.startsWith('time')?String(a.time||a.date).localeCompare(String(b.time||b.date)):collator.compare(a.name,b.name);
                    if((sort.endsWith('asc')&&cmp>0)||(sort.endsWith('desc')&&cmp<0))throw Error(sort+' order incorrect at '+i);
                }
                q.click('[data-photo=""'+got[0]+'""]');q.click('[data-action=next]');
                if(document.querySelector('#large-photo')?.alt!==byId.get(got[1]).name)throw Error(sort+' viewer queue differs from page');
                q.click('[data-action=closeviewer]');
                report.push({sort,count:got.length,viewerQueue:true});
            }
            q.sort('time-desc');q.click('[data-view=albums]');q.sort('created');q.click('[data-view=photos]');
            document.querySelector('[data-group=day]').click();window.scrollTo(0,0);
            return {ok:true,photoSorts:report,restored:{photoSort:'time-desc',albumSort:'created'},total:document.querySelectorAll('.photo-tile').length}
        })()");
        Assert(result.Contains("\"ok\":true"), "Photo ordering/queue failed: " + result);
        File.WriteAllText(Path.Combine(dataRoot, "sort-orders.json"), result);
        await Task.Delay(200);
        persisted = json.Deserialize<Dictionary<string, object>>(File.ReadAllText(Path.Combine(dataRoot, "library-index-v2.json")));
        var preferences = (Dictionary<string, object>)persisted["uiPreferences"];
        Assert(Convert.ToString(preferences["photoSort"]) == "time-desc" && Convert.ToString(preferences["albumSort"]) == "created", "Sort preferences did not persist");
    }
    async Task TestHeifAsync()
    {
        var photos = PhotoLibrary.Photos(photoData).Where(p => HeifDecoder.Supports(Convert.ToString(p["path"]))).ToList();
        var report = new List<object>();
        foreach (var p in photos)
        {
            Assert(!p.ContainsKey("readError"), "HEIF metadata failed: " + p["name"]);
            string path = Convert.ToString(p["path"]), stamp = Convert.ToString(p["stamp"]);
            var watch = System.Diagnostics.Stopwatch.StartNew();
            byte[] thumb = await Task.Run(() => library.Thumbnail(path, stamp)); byte[] full = await Task.Run(() => library.RenderHeif(path, stamp, false));
            using (var t = new MemoryStream(thumb)) using (var preview = Image.FromStream(t)) using (var f = new MemoryStream(full)) using (var image = Image.FromStream(f))
            {
                Assert(Math.Max(preview.Width, preview.Height) <= 480, "HEIF thumbnail too large");
                Assert(image.Width == Convert.ToInt32(p["width"]) && image.Height == Convert.ToInt32(p["height"]), "HEIF orientation/dimensions mismatch: " + p["name"]);
                report.Add(new { name = p["name"], width = image.Width, height = image.Height, date = p["date"], gps = p["gps"], dateSource = p["dateSource"], decodeMs = watch.ElapsedMilliseconds });
            }
        }
        File.WriteAllText(Path.Combine(dataRoot, "heif-decode.json"), json.Serialize(report));
        if (photos.Count == 0) return;
        var chosen = photos.First();
        await web.CoreWebView2.ExecuteScriptAsync("document.querySelector('#photo-search').value=" + json.Serialize(chosen["name"]) + ";document.querySelector('#photo-search').dispatchEvent(new Event('input'));document.querySelector('[data-group=year]').click();document.querySelector('[data-photo]').click()");
        bool loaded = false;
        for (int i = 0; i < 100; i++) { await Task.Delay(100); if (await web.CoreWebView2.ExecuteScriptAsync("!!document.querySelector('#large-photo')?.naturalWidth") == "true") { loaded = true; break; } }
        Assert(loaded, "HEIF large image did not load in WebView2");
        await web.CoreWebView2.ExecuteScriptAsync("document.querySelector('[data-action=tagperson]').click();document.querySelector('#person-name').value='HEIF验证人物';document.querySelector('[data-save]').click();document.querySelector('[data-avatar-person]').click();document.querySelector('[data-action=avatar]').click()");
        for (int i = 0; i < 100; i++) { await Task.Delay(100); if (await web.CoreWebView2.ExecuteScriptAsync("!!document.querySelector('#crop-source')?.naturalWidth") == "true") break; }
        Assert(await web.CoreWebView2.ExecuteScriptAsync("!!document.querySelector('#crop-source')?.naturalWidth") == "true", "HEIF avatar source failed");
        await web.CoreWebView2.ExecuteScriptAsync("document.querySelector('dialog [data-save]').click()"); await Task.Delay(250);
        using (var stream = File.Create(Path.Combine(dataRoot, "heif-person.png"))) await web.CoreWebView2.CapturePreviewAsync(CoreWebView2CapturePreviewImageFormat.Png, stream);
        await web.CoreWebView2.ExecuteScriptAsync("document.querySelector('.photo-tile').click()"); await Task.Delay(300);
        using (var stream = File.Create(Path.Combine(dataRoot, "heif-viewer.png"))) await web.CoreWebView2.CapturePreviewAsync(CoreWebView2CapturePreviewImageFormat.Png, stream);
        await web.CoreWebView2.ExecuteScriptAsync("document.querySelector('[data-action=closeviewer]').click();document.querySelector('[data-view=photos]').click();document.querySelector('[data-group=day]').click()");
        File.WriteAllText(Path.Combine(dataRoot, "heif-ui.json"), json.Serialize(new { supported = photos.Count, largeImage = loaded, avatarCrop = true, beta = Text.Contains("Beta") }));
    }
    async Task RegressionTestsAsync()
    {
        string fixtures = Path.Combine(dataRoot, "fixtures"), child = Path.Combine(fixtures, "2022"); Directory.CreateDirectory(child);
        string photo = Path.Combine(child, "2022-06-03-test.jpg"); using (var im = new Bitmap(32, 24)) im.Save(photo, System.Drawing.Imaging.ImageFormat.Jpeg);
        long oldId = PhotoLibrary.Hash(child.ToLowerInvariant() + "/2022-06-03-test.jpg");
        string legacy = Path.Combine(dataRoot, "indexes", PhotoLibrary.Hash(child.ToLowerInvariant()).ToString("x") + ".json"); Directory.CreateDirectory(Path.GetDirectoryName(legacy));
        SaveAtomic(legacy, json.Serialize(new { people = new[] { "迁移测试人物" }, assignments = new Dictionary<string, object> { { oldId.ToString(), new[] { "迁移测试人物" } } }, notes = new Dictionary<string, object> { { oldId.ToString(), "保留批注" } }, profiles = new Dictionary<string, object> { { "迁移测试人物", new { tags = new[] { "测试" }, avatar = new { photoId = oldId, x = .1, y = .1, width = .2, height = .2 } } } }, photoLocations = new Dictionary<string, object> { { oldId.ToString(), new { manual = new[] { 108.9, 34.2 } } } }, cloudRoot = "/生活点滴/2022", albums = new[] { new { name = "迁移测试相册", ids = new[] { oldId }, note = "相册批注" } } }));
        var childData = library.Discover(child); var parentData = library.Discover(fixtures); long id = Convert.ToInt64(PhotoLibrary.Photos(childData).First()["id"]);
        Assert(id == Convert.ToInt64(PhotoLibrary.Photos(parentData).First()["id"]), "Parent/child IDs differ");
        var migrated = library.LoadIndex(fixtures);
        Assert(((Dictionary<string, object>)migrated["assignments"]).ContainsKey(id.ToString()), "Person migration failed");
        Assert(Convert.ToString(((Dictionary<string, object>)migrated["notes"])[id.ToString()]) == "保留批注", "Note migration failed");
        var album = ((IEnumerable)migrated["albums"]).Cast<Dictionary<string, object>>().First(a => Convert.ToString(a["name"]) == "迁移测试相册");
        Assert(((IEnumerable)album["ids"]).Cast<object>().Select(Convert.ToInt64).Contains(id), "Album migration failed");
        var person = (Dictionary<string, object>)((Dictionary<string, object>)migrated["profiles"])["迁移测试人物"];
        Assert(Convert.ToInt64(((Dictionary<string, object>)person["avatar"])["photoId"]) == id, "Avatar migration failed");
        Assert(((Dictionary<string, object>)migrated["photoLocations"]).ContainsKey(id.ToString()), "Location migration failed");
        ((Dictionary<string, object>)migrated["notes"])[id.ToString()] = "更新后的批注"; library.SaveIndex(fixtures, migrated);
        var again = library.LoadIndex(child); Assert(Convert.ToString(((Dictionary<string, object>)again["notes"])[id.ToString()]) == "更新后的批注", "Directory change lost annotation");
        var handler = new MockBaiduHandler(); using (var sync = new BaiduSync(Path.Combine(dataRoot, "cloud-tests"), handler))
        {
            Directory.CreateDirectory(Path.Combine(dataRoot, "cloud-tests")); sync.SetTestCredentials(); var bytes = new byte[4 * 1024 * 1024 + 101]; bytes[100] = 1;
            await sync.UploadAsync(bytes, CancellationToken.None); Assert(handler.parts == 2 && handler.finalized == 1 && sync.LastSync.Length > 0, "Multi-part publication failed");
            bool duplicate = await sync.UploadAsync(bytes, CancellationToken.None); Assert(!duplicate && handler.finalized == 1, "Unchanged index published twice");
            sync.UpdateFolder("/apps/test/另一索引目录"); bool relocated = await sync.UploadAsync(bytes, CancellationToken.None); Assert(relocated && handler.finalized == 2, "New cloud directory skipped initial index");
            handler.fail = true; int published = handler.finalized; bool rejected = false;
            try { await sync.UploadAsync(Encoding.UTF8.GetBytes("{}"), CancellationToken.None); } catch (InvalidOperationException) { rejected = true; }
            Assert(rejected && handler.finalized == published, "API error reported success");
            handler.fail = false; var cancelled = new CancellationTokenSource(); cancelled.Cancel();
            try { await sync.UploadAsync(bytes, cancelled.Token); } catch (OperationCanceledException) { }
            Assert(handler.finalized == published, "Disabled sync still published");
        }
        await BaiduAuthorizationTests.RunAsync(Path.Combine(dataRoot, "authorization-tests"));
        await BaiduUploadResponseTests.RunAsync(Path.Combine(dataRoot, "upload-response-tests"));
        File.WriteAllText(Path.Combine(dataRoot, "protocol-tests.json"), "{\"stableId\":true,\"migration\":true,\"multiPart\":true,\"apiErrors\":true,\"cancellation\":true,\"oauthHttp400\":true}");
    }
    sealed class MockBaiduHandler : HttpMessageHandler
    {
        internal int parts, finalized; internal bool fail;
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken token)
        {
            token.ThrowIfCancellationRequested(); string reply = "{\"errno\":0}";
            if (fail) reply = "{\"errno\":-6}";
            else if (request.RequestUri.AbsolutePath.EndsWith("superfile2")) { parts++; var multi = (MultipartFormDataContent)request.Content; byte[] data = await multi.First().ReadAsByteArrayAsync(); reply = "{\"md5\":\"" + BaiduSync.Md5(data) + "\"}"; }
            else if (request.RequestUri.Query.Contains("method=precreate")) reply = "{\"errno\":0,\"return_type\":1,\"uploadid\":\"mock-upload\"}";
            else if ((await request.Content.ReadAsStringAsync()).Contains("isdir=0")) { finalized++; reply = "{\"errno\":0,\"fs_id\":123}"; }
            return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(reply) };
        }
    }
}

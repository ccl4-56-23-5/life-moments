using System;
using System.Collections;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using Microsoft.Web.WebView2.Core;

internal sealed partial class PhotoWindow
{
    async Task TestTagEditorAsync()
    {
        string response = await web.CoreWebView2.ExecuteScriptAsync(@"(()=>{
            const fixture=window.__albumQa;if(!fixture)throw Error('tag editor requires album fixture');
            const q=window.__tagQa={person:fixture.person,other:'标签点击验证人物',special:'标签 ""朋友"" <img data-tag-injection src=x onerror=globalThis.__tagInjected=true>',click:fixture.click};
            q.tags=name=>(window.desktopIndex.profiles[name]?.tags||[]).slice();
            q.original=q.tags(q.person);q.otherOriginal=q.tags(q.other);
            q.chip=tag=>[...document.querySelectorAll('dialog [data-existing-tag]')].find(button=>button.dataset.existingTag===tag);
            q.clearPeople=()=>{q.click('[data-view=people]');const search=document.querySelector('#photo-search');search.value='';search.dispatchEvent(new Event('input'));document.querySelector('[data-person-tag=""""]')?.click()};
            q.personCard=name=>[...document.querySelectorAll('[data-person]')].find(button=>button.dataset.person===name);
            q.open=name=>{q.clearPeople();const card=q.personCard(name);if(!card)throw Error('missing tag fixture person '+name);card.click();q.click('[data-action=personlabels]')};
            q.parse=value=>[...new Set(value.split(/[,，、;；\n]/).map(tag=>tag.trim()).filter(Boolean))];
            q.open(q.person);document.querySelector('#person-labels').value=['旅行','家人','大学',q.special,'旅行'].join('，');q.click('dialog [data-save]');
            if(q.tags(q.person).length!==4)throw Error('typed tags were not deduplicated');
            q.click('[data-view=photos]');q.click('[data-photo=""'+fixture.first.id+'""]');q.click('[data-action=tagperson]');document.querySelector('#person-name').value=q.other;q.click('dialog [data-save]');
            q.click('[data-avatar-person=""'+q.other+'""]');q.click('[data-action=personlabels]');
            if(!q.chip('旅行')||!q.chip('家人')||!q.chip('大学')||!q.chip(q.special))throw Error('existing tags missing');
            if(document.querySelectorAll('dialog [data-existing-tag]').length!==new Set([...document.querySelectorAll('dialog [data-existing-tag]')].map(button=>button.dataset.existingTag)).size)throw Error('existing tag choices duplicated');
            const input=document.querySelector('#person-labels');input.value='手写新标签，旅行';input.dispatchEvent(new Event('input',{bubbles:true}));
            if(q.chip('旅行').getAttribute('aria-pressed')!=='true')throw Error('typing did not update selected tag');
            q.chip('家人').click();q.chip('家人').click();q.chip(q.special).click();
            let tags=q.parse(input.value);if(tags.length!==4||!tags.includes('手写新标签')||!tags.includes('旅行')||!tags.includes('家人')||!tags.includes(q.special))throw Error('clicking tags lost manual input or duplicated choice');
            input.value=['手写新标签','家人',q.special].join('，');input.dispatchEvent(new Event('input',{bubbles:true}));
            if(q.chip('旅行').getAttribute('aria-pressed')!=='false'||q.chip('家人').getAttribute('aria-pressed')!=='true')throw Error('manual edits left stale selected states');
            q.chip('旅行').click();
            if(document.querySelector('dialog [data-tag-injection]')||globalThis.__tagInjected||!q.chip(q.special).textContent.includes(q.special))throw Error('tag text was interpreted as HTML');
            if(q.chip('大学').type!=='button'||q.chip('大学').tabIndex<0)throw Error('existing tag is not keyboard accessible');
            q.chip('大学').focus();
            if(document.activeElement!==q.chip('大学'))throw Error('existing tag cannot receive keyboard focus');
            q.beforeKeyboard=input.value;
            return {ok:true,existingTags:true,manualInputPreserved:true,deduplicated:true,typingUpdatesSelection:true,safeText:true}
        })()");
        Assert(response.Contains("\"ok\":true"), "Existing tag editor failed: " + response);
        await Task.Delay(200);
        string beforeCancel = File.ReadAllText(Path.Combine(dataRoot, "library-index-v2.json"));
        await web.CoreWebView2.ExecuteScriptAsync("(()=>{const q=window.__tagQa,button=q.chip('大学');button.focus();button.addEventListener('keydown',e=>{q.keyboardKeyTrusted=e.isTrusted&&e.key==='Enter'},{once:true});button.addEventListener('click',e=>{q.keyboardClickTrusted=e.isTrusted},{once:true})})()");
        // Chromium activates Enter on a button through its character phase. A keyDown
        // without text dispatches only keydown and cannot exercise native activation.
        await web.CoreWebView2.CallDevToolsProtocolMethodAsync("Input.dispatchKeyEvent", json.Serialize(new { type = "keyDown", text = "\r", unmodifiedText = "\r", key = "Enter", code = "Enter", windowsVirtualKeyCode = 13, nativeVirtualKeyCode = 13 }));
        await web.CoreWebView2.CallDevToolsProtocolMethodAsync("Input.dispatchKeyEvent", "{\"type\":\"keyUp\",\"key\":\"Enter\",\"code\":\"Enter\",\"windowsVirtualKeyCode\":13,\"nativeVirtualKeyCode\":13}");
        response = await web.CoreWebView2.ExecuteScriptAsync(@"(()=>{
            try{
            const q=window.__tagQa,input=document.querySelector('#person-labels');
            if(!document.querySelector('dialog').open||!q.parse(input.value).includes('大学')||input.value===q.beforeKeyboard)throw Error('Enter did not add existing tag or saved the form');
            if(!q.keyboardKeyTrusted||!q.keyboardClickTrusted)throw Error('Enter did not produce trusted native button activation');
            if(q.chip('大学').getAttribute('aria-pressed')!=='true')throw Error('keyboard choice not marked selected');
            q.keyboard=true;
            const buttons=[...document.querySelectorAll('dialog [data-existing-tag]')],bounds=document.querySelector('dialog').getBoundingClientRect();
            if(buttons.some(button=>{const r=button.getBoundingClientRect();return r.left<bounds.left||r.right>bounds.right+1}))throw Error('existing tag choices overflow dialog');
            return {ok:true,keyboard:true,trustedActivation:true,tags:q.parse(input.value)}
            }catch(error){return {ok:false,error:String(error),focus:document.activeElement?.outerHTML,dialogOpen:document.querySelector('dialog')?.open,url:location.href}}
        })()");
        File.WriteAllText(Path.Combine(dataRoot, "tag-keyboard.json"), response);
        Assert(response.Contains("\"ok\":true"), "Keyboard tag addition failed: " + response);
        await Task.Delay(300);
        using (var stream = File.Create(Path.Combine(dataRoot, "tag-editor.png"))) await web.CoreWebView2.CapturePreviewAsync(CoreWebView2CapturePreviewImageFormat.Png, stream);
        response = await web.CoreWebView2.ExecuteScriptAsync(@"(()=>{const q=window.__tagQa,before=JSON.stringify(q.tags(q.other));q.click('dialog [data-dismiss]');if(document.querySelector('dialog').open||JSON.stringify(q.tags(q.other))!==before)throw Error('cancel saved tags');return {ok:true,cancelled:true}})()");
        Assert(response.Contains("\"ok\":true"), "Tag editor cancellation failed: " + response);
        await Task.Delay(200);
        Assert(beforeCancel == File.ReadAllText(Path.Combine(dataRoot, "library-index-v2.json")), "Cancelling tag editing changed persisted index");
        response = await web.CoreWebView2.ExecuteScriptAsync(@"(()=>{
            const q=window.__tagQa;q.click('[data-action=personlabels]');const input=document.querySelector('#person-labels');input.value='手写新标签';input.dispatchEvent(new Event('input',{bubbles:true}));
            for(const tag of ['旅行','家人','大学',q.special]){q.chip(tag).click();q.chip(tag).click()}
            q.click('dialog [data-save]');q.expected=['手写新标签','旅行','家人','大学',q.special];
            const saved=q.tags(q.other);if(saved.length!==q.expected.length||q.expected.some(tag=>!saved.includes(tag)))throw Error('existing and typed tags not saved');
            q.clearPeople();const filter=[...document.querySelectorAll('[data-person-tag]')].find(button=>button.dataset.personTag===q.special);if(!filter)throw Error('saved tag missing from filters');filter.click();
            const people=[...document.querySelectorAll('.person-card[data-person]')].map(button=>button.dataset.person);
            if(people.length!==2||!people.includes(q.person)||!people.includes(q.other))throw Error('existing tag filter returned wrong people');
            if(document.querySelector('[data-tag-injection]')||globalThis.__tagInjected)throw Error('saved tag created HTML');
            return {ok:true,existingTags:true,manualInputPreserved:true,deduplicated:true,typingUpdatesSelection:true,keyboard:q.keyboard,cancelDidNotSave:true,safeText:true,savedTags:saved,filteredPeople:people}
        })()");
        Assert(response.Contains("\"ok\":true"), "Tag save/filter failed: " + response);
        File.WriteAllText(Path.Combine(dataRoot, "tag-editor.json"), response);
        await Task.Delay(200);
        var persisted = json.Deserialize<Dictionary<string, object>>(File.ReadAllText(Path.Combine(dataRoot, "library-index-v2.json")));
        var profiles = (Dictionary<string, object>)persisted["profiles"];
        var savedProfile = (Dictionary<string, object>)profiles["标签点击验证人物"];
        var savedTags = ((IEnumerable)savedProfile["tags"]).Cast<object>().Select(Convert.ToString).ToList();
        Assert(savedTags.Count == 5 && savedTags.Distinct().Count() == 5 && savedTags.Contains("手写新标签") && savedTags.Contains("旅行") && savedTags.Contains("家人") && savedTags.Contains("大学"), "Tag additions did not persist");
        response = await web.CoreWebView2.ExecuteScriptAsync(@"(()=>{const q=window.__tagQa;for(const pair of [[q.person,q.original],[q.other,q.otherOriginal]]){q.open(pair[0]);document.querySelector('#person-labels').value=pair[1].join('，');q.click('dialog [data-save]')}q.click('[data-view=photos]');window.scrollTo(0,0);return {ok:true,restored:true}})()");
        Assert(response.Contains("\"ok\":true"), "Restoring tag editor fixture failed: " + response);
    }
}

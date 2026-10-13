// Reuse tags already assigned to people while keeping the free-form editor.
function parsePersonLabels(value){return [...new Set(String(value).split(/[,，、;；\n]/).map(tag=>tag.trim()).filter(Boolean))]}
openPersonTags=function(){
    const person=state.person;
    const existing=[...new Set(store.people.flatMap(name=>profile(name).tags||[]).filter(tag=>typeof tag==='string'&&tag.trim()).map(tag=>tag.trim()))].sort(nameOrder.compare);
    const chips=existing.length?`<div class="person-existing-tags" role="group" aria-label="已有标签">${existing.map(tag=>`<button type="button" class="person-existing-tag" data-existing-tag="${esc(tag)}" aria-pressed="false"><span class="person-existing-tag-check" aria-hidden="true">${icon('check')}</span><span>${esc(tag)}</span></button>`).join('')}</div>`:'<p class="person-existing-tags-empty">暂无已有标签</p>';
    showFeatureDialog('personlabels',`${person} · 标签`,
        `<label for="person-labels">人物标签</label><input id="person-labels" value="${esc((profile(person).tags||[]).join('，'))}" maxlength="200" placeholder="例如：家人，同学，大学" autofocus><p class="dialoghint">使用逗号分隔多个标签。</p><section class="person-existing-tags-section"><h3>已有标签</h3>${chips}</section>`,
        ()=>{
            const tags=parsePersonLabels(modal.querySelector('#person-labels').value);
            store.profiles[person]={...profile(person),tags};
            if(state.personTag&&!store.people.some(name=>(profile(name).tags||[]).includes(state.personTag)))state.personTag=null;
        });
    const input=modal.querySelector('#person-labels'),buttons=[...modal.querySelectorAll('[data-existing-tag]')];
    const update=()=>{const selected=new Set(parsePersonLabels(input.value));for(const button of buttons)button.setAttribute('aria-pressed',String(selected.has(button.dataset.existingTag)))};
    input.addEventListener('input',update);
    for(const button of buttons)button.addEventListener('click',()=>{
        const tag=button.dataset.existingTag;
        if(!parsePersonLabels(input.value).includes(tag)){
            const current=input.value.replace(/\s+$/,'');
            input.value=current+(current&&!/[,，、;；\n]$/.test(current)?'，':'')+tag;
            input.dispatchEvent(new Event('input',{bubbles:true}));
        }
        update();
    });
    update();
};

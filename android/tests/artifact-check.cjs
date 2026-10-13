const fs=require('fs'),path=require('path'),assert=require('assert/strict');
const {pathToFileURL}=require('url');
const {chromium,browserOptions}=require('./browser-support.cjs');
const base=path.resolve(__dirname,'..'),out=path.join(base,'artifacts/qa');
(async()=>{
 const browser=await chromium.launch({...browserOptions,headless:true});
 const page=await browser.newPage();const errors=[];page.on('pageerror',e=>errors.push(String(e)));
 for(const filename of ['手机端拉取数据教程.html','artifacts/手机端拉取数据教程.html','app/src/main/assets/guide.html']){
  await page.goto(pathToFileURL(path.join(base,filename)).href);await page.locator('h1').waitFor();
  assert.equal(await page.locator('h1').innerText(),'手机端拉取数据教程');
  assert.deepEqual(await page.evaluate(()=>[...document.images].filter(img=>!img.complete||img.naturalWidth===0).map(img=>img.src)),[]);
  for(const width of [320,390,900]){
   await page.setViewportSize({width,height:844});
   assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'Tutorial fits '+width);
  }
  for(const href of await page.locator('a[href^="#"]').evaluateAll(a=>a.map(x=>x.getAttribute('href'))))assert.equal(await page.locator(href).count(),1);
 }
 await page.goto(pathToFileURL(path.join(base,'artifacts/手机端拉取数据教程.html')).href);
 await page.setViewportSize({width:1080,height:1000});await page.screenshot({path:path.join(out,'tutorial.png')});
 assert.equal(await page.locator('img:not([src^="data:"])').count(),0);
 assert.equal(await page.locator('a[href^="app/"],a[href="index.html"]').count(),0);
 assert.deepEqual(errors,[]);await browser.close();
 const result={status:'PASS',checks:['tutorial images','320–900px layout','section navigation','portable HTML has no local dependencies'],pageErrors:errors};
 fs.writeFileSync(path.join(out,'artifact-results.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));
})().catch(error=>{console.error(error);process.exit(1)});

import {after, before, test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {chromium} from 'playwright-core';
const script=await readFile(new URL('../shared/PronoteAutomation.js',import.meta.url),'utf8');
const image='data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO2pM6sAAAAASUVORK5CYII=';
let browser;
before(async()=>{browser=await chromium.launch({executablePath:process.env.CHROME_PATH??'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',headless:true});});
after(async()=>{await browser?.close();});
const fixture=`<!doctype html><html><body><span id="qr-label">QR code de l’application</span><button aria-labelledby="qr-label" id="qr">QR</button><script>
 window.clicks={open:0,generate:0};
 function openDialog(){window.clicks.open++;document.getElementById('dialog')?.remove();const d=document.createElement('div');d.id='dialog';d.role='dialog';d.innerHTML='<h1>QR Code d’ajout de compte sur l’application mobile</h1><span id="code-label">Code de vérification à 4 chiffres :</span><div role="textbox" contenteditable="true" aria-labelledby="code-label" id="code"></div><button id="generate" disabled>Générer le QR Code</button><button id="close" aria-label="Fermer">Fermer</button>';document.body.append(d);d.querySelector('#close').onclick=()=>d.remove();d.querySelector('#code').oninput=e=>d.querySelector('#generate').disabled=e.currentTarget.textContent.length!==4;d.querySelector('#generate').onclick=()=>{window.clicks.generate++;const img=document.createElement('img');img.width=50;img.height=50;img.src=${JSON.stringify(image)};d.replaceChildren(Object.assign(document.createElement('h1'),{textContent:'QR Code d’ajout de compte sur l’application mobile'}),img);};}
 document.getElementById('qr').onclick=openDialog;
 </script></body></html>`;
async function pageFor(html=fixture,origin='https://school.index-education.net',demo=false,credentials){
 const context=await browser.newContext();const page=await context.newPage();
 await context.addInitScript(()=>{window.messages=[];window.JMethodNative={postMessage:(raw)=>window.messages.push(JSON.parse(raw))};});
 await page.route('**/*',r=>r.fulfill({contentType:'text/html; charset=utf-8',body:html}));
 await page.goto(origin+'/pronote/eleve.html');
 await page.evaluate(cfg=>window.__JM_CONFIG=cfg,{version:1,nonce:'synthetic-nonce-123456789',pin:'1234',allowedOrigin:origin,demo,...(credentials?{credentials}:{})});
 return {context,page};
}
test('authenticated page generates exactly one QR with matching synthetic PIN',async()=>{
 const {context,page}=await pageFor();await page.addScriptTag({content:script});await page.addScriptTag({content:script});
 await page.waitForFunction(()=>window.messages.some(m=>m.type==='qr'));
 const result=await page.evaluate(()=>({messages:window.messages,clicks:window.clicks,configCleared:!window.__JM_CONFIG}));
 assert.equal(result.messages.filter(m=>m.type==='authenticated').length,1);assert.equal(result.messages.filter(m=>m.type==='qr').length,1);
 assert.equal(result.messages.find(m=>m.type==='qr').pin,'1234');assert.equal(result.messages.find(m=>m.type==='qr').image,image);
 assert.deepEqual(result.clicks,{open:1,generate:1});assert.ok(result.configCleared);await context.close();
});
test('preexisting QR image is closed and generated again using the new PIN',async()=>{
 const {context,page}=await pageFor();await page.evaluate(image=>{const d=document.createElement('div');d.role='dialog';d.innerHTML='<h1>QR Code d’ajout de compte sur l’application mobile</h1><img width="50" height="50" src="'+image+'"><button aria-label="Fermer">Fermer</button>';d.querySelector('button').onclick=()=>d.remove();document.body.append(d);},image);
 await page.addScriptTag({content:script});await page.waitForFunction(()=>window.messages.some(m=>m.type==='qr'));
 assert.equal(await page.evaluate(()=>window.clicks.generate),1);await context.close();
});
test('real-account password fields are untouched even when demo flag is requested',async()=>{
 const html='<input aria-label="Identifiant" value="private"><input type="password" aria-label="Mot de passe" value="not-for-app"><button>Se connecter</button>';
 const {context,page}=await pageFor(html,'https://school.index-education.net',true);await page.addScriptTag({content:script});await page.waitForTimeout(650);
 assert.deepEqual(await page.locator('input').evaluateAll(inputs=>inputs.map(e=>e.value)),['private','not-for-app']);assert.equal(await page.evaluate(()=>window.messages.length),0);await context.close();
});
test('external ENT origin cannot start automation',async()=>{
 const {context,page}=await pageFor(fixture,'https://external-ent.invalid');await page.addScriptTag({content:script});await page.waitForTimeout(650);
 assert.equal(await page.evaluate(()=>window.messages.length),0);assert.deepEqual(await page.evaluate(()=>window.clicks),{open:0,generate:0});await context.close();
});
test('stop/cancel prevents QR generation and erases ephemeral configuration',async()=>{
 const {context,page}=await pageFor();await page.addScriptTag({content:script});await page.evaluate(()=>window.__JM_PRONOTE_POC.stop());await page.waitForTimeout(650);
 assert.equal(await page.evaluate(()=>window.clicks.generate),0);assert.ok(await page.evaluate(()=>!window.__JM_CONFIG));await context.close();
});

const loginForm=(outcome)=>`<!doctype html><html><body><div id="form"><label for="u">Identifiant</label><input id="u"><label for="p">Mot de passe</label><input id="p" type="password"><button id="go" disabled>Se connecter</button></div><script>
 window.submissions=[];const u=document.getElementById('u'),p=document.getElementById('p'),go=document.getElementById('go');
 const sync=()=>go.disabled=!(u.value&&p.value);u.oninput=sync;p.oninput=sync;
 go.onclick=()=>{window.submissions.push({u:u.value,p:p.value});${outcome==='ok'
  ?"document.body.innerHTML="+JSON.stringify(fixture.split('<script>')[0].replace('<!doctype html><html><body>',''))+";window.clicks={open:0,generate:0};document.getElementById('qr').onclick=window.openDialog;"
  :"const a=document.createElement('div');a.role='alert';a.textContent='Identifiant ou mot de passe incorrect.';document.body.append(a);"}};
 window.clicks={open:0,generate:0};
 window.openDialog=function(){window.clicks.open++;const d=document.createElement('div');d.role='dialog';d.innerHTML='<h1>QR Code d’ajout de compte sur l’application mobile</h1><span id="code-label">Code de vérification à 4 chiffres :</span><input aria-labelledby="code-label" id="code"><button id="generate">Générer le QR Code</button>';document.body.append(d);d.querySelector('#generate').onclick=()=>{window.clicks.generate++;const img=document.createElement('img');img.width=50;img.height=50;img.src=${JSON.stringify(image)};d.replaceChildren(Object.assign(document.createElement('h1'),{textContent:'QR Code d’ajout de compte sur l’application mobile'}),img);};};
 </script></body></html>`;
test('native credentials fill the hidden form once, then the QR flow continues',async()=>{
 const {context,page}=await pageFor(loginForm('ok'),'https://school.index-education.net',false,{username:'eleve.test',password:'secret-pass'});
 await page.addScriptTag({content:script});await page.waitForFunction(()=>window.messages.some(m=>m.type==='qr'),null,{timeout:8000});
 const r=await page.evaluate(()=>({subs:window.submissions,msgs:window.messages,cfg:window.__JM_CONFIG}));
 assert.deepEqual(r.subs,[{u:'eleve.test',p:'secret-pass'}]);
 assert.ok(!JSON.stringify(r.msgs).includes('secret-pass'));assert.ok(!JSON.stringify(r.msgs).includes('eleve.test'));
 assert.ok(r.msgs.some(m=>m.type==='progress'&&m.stage==='submitting-login'));assert.equal(r.msgs.filter(m=>m.type==='authenticated').length,1);
 assert.equal(r.cfg,undefined);await context.close();
});
test('rejected credentials are reported once without retrying',async()=>{
 const {context,page}=await pageFor(loginForm('ko'),'https://school.index-education.net',false,{username:'eleve.test',password:'wrong'});
 await page.addScriptTag({content:script});await page.waitForFunction(()=>window.messages.some(m=>m.type==='error'),null,{timeout:8000});await page.waitForTimeout(1200);
 const r=await page.evaluate(()=>({subs:window.submissions.length,errors:window.messages.filter(m=>m.type==='error').map(m=>m.code)}));
 assert.equal(r.subs,1);assert.deepEqual(r.errors,['LOGIN_REJECTED']);await context.close();
});
test('credentials are never typed outside the trusted Pronote origin',async()=>{
 const {context,page}=await pageFor(loginForm('ok'),'https://external-ent.invalid',false,{username:'eleve.test',password:'secret-pass'});
 await page.addScriptTag({content:script});await page.waitForTimeout(1200);
 assert.deepEqual(await page.locator('input').evaluateAll(i=>i.map(e=>e.value)),['','']);assert.equal(await page.evaluate(()=>window.submissions.length),0);await context.close();
});

test('security check reveals the page once, then the QR flow resumes by itself',async()=>{
 const {context,page}=await pageFor();
 await page.evaluate(()=>{const d=document.createElement('div');d.id='mfa';d.role='dialog';d.innerHTML='<h1>Sécurisation de votre compte</h1><input aria-label="Code PIN">';document.body.append(d);});
 await page.addScriptTag({content:script});await page.waitForTimeout(1200);
 assert.deepEqual(await page.evaluate(()=>window.messages.filter(m=>m.type==='error').map(m=>m.code)),['MFA_REQUIRED']);
 assert.equal(await page.evaluate(()=>window.clicks.open),0);
 await page.evaluate(()=>document.getElementById('mfa').remove());
 await page.waitForFunction(()=>window.messages.some(m=>m.type==='qr'),null,{timeout:8000});
 const r=await page.evaluate(()=>({errors:window.messages.filter(m=>m.type==='error').length,done:window.messages.some(m=>m.stage==='mfa-done'),clicks:window.clicks}));
 assert.equal(r.errors,1);assert.ok(r.done);assert.deepEqual(r.clicks,{open:1,generate:1});await context.close();
});

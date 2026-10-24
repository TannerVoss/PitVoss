const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const fs=require('node:fs');const path=require('node:path');const {pathToFileURL}=require('node:url');
const root=process.env.PITMASTER_PROJECT||path.resolve(__dirname,'../..');
(async()=>{
 const browser=await chromium.launch({headless:true,...(process.env.PLAYWRIGHT_CHANNEL?{channel:process.env.PLAYWRIGHT_CHANNEL}:{})});
 const page=await browser.newPage({viewport:{width:390,height:844}}),errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(()=>{
  if(!sessionStorage.getItem('seeded')){
   localStorage.clear();sessionStorage.setItem('seeded','yes');
   localStorage.setItem('savedPits',JSON.stringify([{address:'AA:BB:CC:DD:EE:01',nickname:'Backyard Lockhart',model:'PB2180LK',ip:'192.168.1.153'},{address:'AA:BB:CC:DD:EE:02',nickname:'Patio Pit',model:'PBV4PS2',ip:'192.168.1.154'}]));
   localStorage.setItem('selectedPit',JSON.stringify('AA:BB:CC:DD:EE:01'));localStorage.setItem('ip',JSON.stringify('192.168.1.153'));
  }
  window.testCalls={commands:[],scans:0,reports:[],backups:[],policies:[]};
  window.LockhartNative={setTransportMode(){},configureGrillProfile(){},selectBleGrill(){},disconnectSelectedGrill(){},rpc(){},configureMonitoring(){},resetExitGuard(){},
   getRuntimeInfo(){return JSON.stringify({version:'0.3.0-beta2',experimentalPriming:false,safetyAcknowledged:localStorage.getItem('safetyAcknowledged_v1')==='true'})},
   acknowledgeSafety(){},openPolicy(kind){testCalls.policies.push(kind)},setTemperature(...args){testCalls.commands.push(args)},control(...args){testCalls.commands.push(args)},prime(){throw Error('Release priming called')},
   scanWifiNetworks(){testCalls.scans++},exportBleLog(s){testCalls.reports.push(s)},exportLocalBackup(s){testCalls.backups.push(s)},clearLocalData(){localStorage.clear();location.reload()}
  };
 });
 await page.goto(pathToFileURL(path.join(root,'app/src/main/assets/index.html')).href);
 await page.locator('#pmSafetyDialog').waitFor({state:'visible'});
 assert.equal(await page.evaluate(()=>testCalls.commands.length),0);
 await page.locator('#pmReadSafety').click();assert.match(await page.locator('#pmLegalText').textContent(),/manufacturer/);assert.match(await page.locator('#pmLegalText').textContent(),/Tanner Voss/);await page.locator('#pmOpenPublishedPolicy').click();assert.equal(await page.evaluate(()=>testCalls.policies.at(-1)),'terms');await page.locator('#pmCloseLegal').click();await page.locator('#pmAcceptSafety').click();
 assert.equal(await page.locator('#pmDashboardGrill').textContent(),'Backyard Lockhart');
 assert.equal(await page.locator('#pvPrime').isDisabled(),true);assert.equal(await page.evaluate(()=>primeSend('start',30)),false);
 async function emit(value=160,unit=true){await page.evaluate(({value,unit})=>{busy=false;rpc();receiveRpc(pending,JSON.stringify({result:{moduleIsOn:true,isFahrenheit:unit,grillTemp:100,grillSetTemp:value}}),'');},{value,unit});}
 async function navigate(view){await page.locator('#pvMenuButton').click();await page.locator('#pvNav [data-view='+view+']').click();}
 async function slide(){await page.locator('#pvApplyTemp').scrollIntoViewIfNeeded();const h=await page.locator('#pvApplyTemp').boundingBox(),t=await page.locator('.pvSlideTrack').boundingBox();await page.mouse.move(h.x+h.width/2,h.y+h.height/2);await page.mouse.down();await page.mouse.move(t.x+t.width-10,h.y+h.height/2,{steps:10});await page.mouse.up();await page.locator('#pvConfirmOK').click();}
 await emit();
 for(const value of [225,500,225]){
  await page.locator('#pvTargetTemp').fill(String(value));await slide();const command=await page.evaluate(()=>testCalls.commands.at(-1));assert.equal(command[1],value);assert.equal(command[2],true);
  const old=await page.locator('#pvSetpoint').textContent();await page.evaluate(id=>receiveControl(id,'setTemp','{}',''),command[3]);assert.equal(await page.locator('#pvSetpoint').textContent(),old);await emit(value);assert.equal(await page.locator('#pmTemperatureStatus').textContent(),'Temperature set to '+value+'°F.');
 }
 await page.evaluate(()=>{liveWifi.at=Date.now()-16000;});const count=await page.evaluate(()=>testCalls.commands.length);await page.locator('#pvTargetTemp').fill('250');await slide();assert.equal(await page.evaluate(()=>testCalls.commands.length),count);await emit(225);
 await navigate('alerts');await page.evaluate(()=>setDisplayUnit('C'));await page.locator('#lo0').fill('100');await page.locator('#hi0').fill('150');await page.locator('#saveRules').click();assert.equal(await page.evaluate(()=>rules.grillTemp.low),212);assert.equal(await page.evaluate(()=>rules.grillTemp.high),302);
 await page.evaluate(()=>{last.isFahrenheit=false;last.grillTemp=171;setDisplayUnit('F');checkLimits();});assert(await page.evaluate(()=>armed.has('grillTemphigh')));assert.match(await page.locator('#pvToast').textContent(),/340°F/);
 await navigate('addpit');await page.locator('[data-grill]').nth(1).click();assert.equal(await page.evaluate(()=>rules.grillTemp),undefined);await page.locator('[data-grill]').nth(0).click();assert.equal(await page.evaluate(()=>rules.grillTemp.high),302);
 await page.locator('[data-connections]').nth(0).click();assert.equal(await page.locator('#pmGrillModel option').count(),148);assert.equal(await page.evaluate(()=>testCalls.scans),0);await page.locator('#pmWifiNetworks').click();assert.equal(await page.evaluate(()=>testCalls.scans),1);
 await page.evaluate(()=>receiveWifiNetworks(JSON.stringify([{ssid:'Home 2.4',band:'2.4 GHz'},{ssid:'Guest <Safe>',band:'5 GHz'}]),'Choose a network.'));
 await page.getByRole('button',{name:'Home 2.4',exact:false}).click();assert.equal(await page.locator('#pmWifiSsid').inputValue(),'Home 2.4');await page.locator('#pmWifiNetworks').click();await page.locator('#pmWifiChoices').evaluate(el=>{el.scrollTop=0;el.dispatchEvent(new TouchEvent('touchstart',{bubbles:true,touches:[new Touch({identifier:1,target:el,clientY:100})]}));el.dispatchEvent(new TouchEvent('touchmove',{bubbles:true,cancelable:true,touches:[new Touch({identifier:1,target:el,clientY:180})]}));el.dispatchEvent(new TouchEvent('touchend',{bubbles:true,touches:[]}));});assert.equal(await page.evaluate(()=>testCalls.scans),3);
 assert(await page.evaluate(()=>pmHandleBack()));assert(!await page.locator('#pmWifiChoices').isVisible());assert(await page.locator('#pmConnectionsDialog').isVisible());
 await page.locator('#pmBetaReport').click();const report=await page.evaluate(()=>testCalls.reports.at(-1));assert.match(report,/PB2180LK/);assert(!report.includes('192.168.1.153'));assert(!report.includes('AA:BB:CC:DD:EE:01'));
 await page.evaluate(()=>pmHandleBack());await navigate('about');await page.locator('[data-legal=privacy]').click();const privacy=await page.locator('#pmLegalText').textContent();assert.match(privacy,/pitmastercontrollersupporter@gmail.com/);assert.match(privacy,/unencrypted HTTP/);await page.locator('#pmCloseLegal').click();await page.locator('[data-legal=licenses]').click();assert.match(await page.locator('#pmLegalText').textContent(),/Apache License/);await page.locator('#pmCloseLegal').click();
 await page.locator('#pmExportBackup').click();const backup=JSON.parse(await page.evaluate(()=>testCalls.backups.at(-1)));assert.equal(backup.format,'pitmaster-backup');assert.equal(backup.data.savedPits.length,2);assert(!('safetyAcknowledged_v1' in backup.data));
 await page.evaluate(()=>restoreAppBackup('{invalid'));assert.match(await page.locator('#pvToast').textContent(),/could not be restored/);
 const fixtures=JSON.parse(fs.readFileSync(path.join(root,'tests/fixtures/model-frames.json'),'utf8'));for(const f of fixtures){const decoded=await page.evaluate(f=>{const original=savedPits;savedPits=[{address:'AA:BB:CC:DD:EE:01',nickname:'Fixture',model:f.model}];const value={status:modelFrame(f.status,'status'),temperatures:modelFrame(f.temperatures,'temperatures')};savedPits=original;return value;},f);assert.deepEqual(decoded.status,f.expectedStatus,f.board+' status');assert.deepEqual(decoded.temperatures,f.expectedTemperatures,f.board+' temperatures');}
 for(const [width,height] of [[320,568],[360,640],[390,844],[430,932],[844,390],[768,1024]]){
  await page.setViewportSize({width,height});await page.waitForTimeout(150);
  for(const view of ['dashboard','history','alerts','controller','addpit','about']){await page.evaluate(v=>pvOpen(v),view);assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),`overflow ${view} ${width}`);}
  await page.evaluate(()=>{pvOpen('addpit');openGrillConnections(savedPits[0]);});await page.locator('#pmWifiProvisionButton').scrollIntoViewIfNeeded();const box=await page.locator('#pmConnectionsDialog').boundingBox();assert(box.y>=0&&box.y+box.height<=height+1,`dialog ${width}x${height}`);await page.evaluate(()=>pmHandleBack());
 }
 await page.evaluate(()=>{pvOpen('dashboard');pvOpen('history');pvOpen('alerts');});assert(await page.evaluate(()=>pmHandleBack()));assert(await page.locator('#view-history').evaluate(e=>e.classList.contains('active')));assert(await page.evaluate(()=>pmHandleBack()));assert.equal(await page.evaluate(()=>pmHandleBack()),false);
 await page.evaluate(()=>pvOpen('about'));
 for(const kind of ['privacy','terms','licenses']){
  await page.locator('[data-legal="'+kind+'"]').click();
  assert.match(await page.locator('#pmLegalText').textContent(),/Tanner Voss/);
  assert.doesNotMatch(await page.locator('#pmLegalText').textContent(),/Outside Software Development|this draft|Jurisdiction-specific terms, publisher details/);
  await page.locator('#pmOpenPublishedPolicy').click();assert.equal(await page.evaluate(()=>testCalls.policies.at(-1)),kind);
  await page.locator('#pmCloseLegal').click();
 }
 assert.equal(await page.evaluate(()=>releaseConfig.privacyPolicyUrl),'https://tannervoss.github.io/Pit_Master_Controller/privacy-policy.html');
 assert.equal(await page.evaluate(()=>releaseConfig.termsUrl),'https://tannervoss.github.io/Pit_Master_Controller/terms.html');
 assert.equal(await page.evaluate(()=>releaseConfig.thirdPartyNoticesUrl),'https://tannervoss.github.io/Pit_Master_Controller/third-party-notices.html');
 assert.deepEqual(errors,[]);await browser.close();console.log('PASS: release onboarding, safety/priming guards, actual temperature slider, stale readings, per-grill canonical alerts, Wi-Fi gestures, redacted reports, privacy/licenses, backups, 17 board fixtures, six viewport sizes and Back navigation.');
})().catch(e=>{console.error(e);process.exit(1);});

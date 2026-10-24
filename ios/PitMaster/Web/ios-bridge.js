// iOS adapter for the existing Android dashboard contract.
(()=>{let mode='automatic'; const send=(method,args)=>window.webkit.messageHandlers.pit.postMessage({method,args});
window.LockhartNative={getRuntimeInfo:()=>JSON.stringify({version:'0.1.0-ios',debug:false,experimentalPriming:false,safetyAcknowledged:false}),getTransportMode:()=>mode,setTransportMode:m=>{mode=m;send('setTransportMode',[m])},
rpc:(...args)=>send("rpc",args),
control:(...args)=>send("control",args),
setTemperature:(...args)=>send("setTemperature",args),
prime:(...args)=>send("prime",args),
scanBle:(...args)=>send("scanBle",args),
selectBleGrill:(...args)=>send("selectBleGrill",args),
testBleState:(...args)=>send("testBleState",args),
provisionWifi:(...args)=>send("provisionWifi",args),
provisionWifiForGrill:(...args)=>send("provisionWifiForGrill",args),
disconnectSelectedGrill:(...args)=>send("disconnectSelectedGrill",args),
discoverWifiGrill:(...args)=>send("discoverWifiGrill",args),
scanWifiNetworks:(...args)=>send("scanWifiNetworks",args),
configureMonitoring:(...args)=>send("configureMonitoring",args),
copyBleLog:(...args)=>send("copyBleLog",args),
exportBleLog:(...args)=>send("exportBleLog",args),
exportLocalBackup:(...args)=>send("exportLocalBackup",args),
chooseLocalBackup:(...args)=>send("chooseLocalBackup",args),
acknowledgeSafety:(...args)=>send("acknowledgeSafety",args),
clearLocalData:(...args)=>send("clearLocalData",args),
configureGrillProfile:(...args)=>send("configureGrillProfile",args),
resetExitGuard:(...args)=>send("resetExitGuard",args)};
// Export dashboard Blob downloads through the iOS share sheet.
const click=HTMLAnchorElement.prototype.click;HTMLAnchorElement.prototype.click=function(){if(this.download&&this.href.startsWith('blob:')){fetch(this.href).then(r=>r.text()).then(text=>send('shareFile',[text,this.download]));return;}return click.call(this);};
})();
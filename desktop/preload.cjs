const {contextBridge}=require('electron');
// Exposes only host metadata. No process discovery or scanning is implemented in milestone 1.
contextBridge.exposeInMainWorld('electronIntegrity',Object.freeze({platform:process.platform,mode:'host-only'}));

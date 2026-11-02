const {app,BrowserWindow,session}=require('electron');
const path=require('node:path');
const DEV_URL='http://127.0.0.1:4200';
function createWindow(){
  const win=new BrowserWindow({width:1440,height:900,minWidth:1100,minHeight:720,webPreferences:{contextIsolation:true,nodeIntegration:false,sandbox:true,preload:path.join(__dirname,'preload.cjs')}});
  win.loadURL(DEV_URL);
  win.webContents.setWindowOpenHandler(()=>({action:'deny'}));
  win.webContents.on('will-navigate',(event,url)=>{if(!url.startsWith(DEV_URL))event.preventDefault();});
}
app.whenReady().then(()=>{session.defaultSession.setPermissionRequestHandler((webContents,permission,callback)=>{callback(['media','display-capture'].includes(permission)&&webContents.getURL().startsWith(DEV_URL));});createWindow();});
app.on('window-all-closed',()=>{if(process.platform!=='darwin')app.quit();});

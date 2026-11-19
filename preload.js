'use strict';
const { contextBridge, ipcRenderer } = require('electron');
// Minimal bridge for the local setup/offline pages only. The remote site gets no extra powers beyond this.
contextBridge.exposeInMainWorld('shadowApp', {
  getServerUrl: () => ipcRenderer.invoke('shadow:getServerUrl'),
  setServerUrl: (u) => ipcRenderer.invoke('shadow:setServerUrl', u),
  retry: () => ipcRenderer.invoke('shadow:retry'),
  openAdmin: () => ipcRenderer.invoke('shadow:openAdmin'),
});

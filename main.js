'use strict';
/**
 * Shadow Admin - desktop shell.
 * Loads the site's existing admin panel (index.php?r=admin) inside a hardened window.
 * All authentication, permissions, CSRF and data stay on the server; nothing secret is stored here.
 */
const { app, BrowserWindow, Menu, shell, session, ipcMain, net, dialog } = require('electron');
const path = require('path');
const fs = require('fs');

const PARTITION = 'persist:shadow-admin';
const ADMIN_ROUTE = '/index.php?r=admin';
// Only these "r=" routes (and static folders) may load inside the app; everything else opens in the system browser.
const ALLOWED_ROUTES = ['admin', 'login', 'logout', 'forgot', 'reset', 'api', 'ban', 'maintenance'];
const ALLOWED_PATH_PREFIXES = ['/assets/', '/uploads/', '/storage/'];

let win = null;
let serverUrl = '';
let retryTimer = null;

if (!app.requestSingleInstanceLock()) { app.quit(); }
app.on('second-instance', () => { if (win) { if (win.isMinimized()) win.restore(); win.focus(); } });

/* ---------- server URL (config.json shipped with the app, overridden by the user's own saved choice) ---------- */
function userConfigPath() { return path.join(app.getPath('userData'), 'server.json'); }

function normalizeUrl(input) {
  let s = String(input || '').trim();
  if (!s) return '';
  if (!/^https?:\/\//i.test(s)) s = 'https://' + s;
  let u;
  try { u = new URL(s); } catch (e) { return ''; }
  if (!/^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$/i.test(u.hostname)) return ''; // reject malformed hosts
  const local = ['localhost', '127.0.0.1'].includes(u.hostname);
  if (u.protocol !== 'https:' && !local) return ''; // plain HTTP is refused except for local testing
  return u.origin + u.pathname.replace(/\/+$/, '');
}

function loadServerUrl() {
  try {
    const saved = JSON.parse(fs.readFileSync(userConfigPath(), 'utf8'));
    const u = normalizeUrl(saved.serverUrl);
    if (u) return u;
  } catch (e) { /* none saved yet */ }
  const candidates = [
    path.join(process.resourcesPath || '', 'config.json'),
    path.join(__dirname, 'config.json'),
  ];
  for (const f of candidates) {
    try {
      const u = normalizeUrl(JSON.parse(fs.readFileSync(f, 'utf8')).serverUrl);
      if (u) return u;
    } catch (e) { /* try next */ }
  }
  return '';
}

function saveServerUrl(u) {
  fs.mkdirSync(app.getPath('userData'), { recursive: true });
  fs.writeFileSync(userConfigPath(), JSON.stringify({ serverUrl: u }), 'utf8');
}

/* ---------- navigation guard ---------- */
function isAllowedInApp(urlStr) {
  let u, base;
  try { u = new URL(urlStr); base = new URL(serverUrl); } catch (e) { return false; }
  if (u.origin !== base.origin) return false;
  if (ALLOWED_PATH_PREFIXES.some((p) => u.pathname.startsWith(base.pathname.replace(/\/$/, '') + p))) return true;
  const r = (u.searchParams.get('r') || '').replace(/^\/+/, '');
  if (r) return ALLOWED_ROUTES.some((x) => r === x || r.startsWith(x + '/'));
  // plain /admin style links
  const rel = u.pathname.slice(base.pathname.replace(/\/$/, '').length).replace(/^\/+/, '');
  return ALLOWED_ROUTES.some((x) => rel === x || rel.startsWith(x + '/')) || (rel === 'index.php' && !u.search);
}

function openExternalSafe(urlStr) {
  try {
    const u = new URL(urlStr);
    if (u.protocol === 'https:' || u.protocol === 'http:') shell.openExternal(u.toString());
  } catch (e) { /* ignore */ }
}

/* ---------- window ---------- */
function showPage(name) { win.loadFile(path.join(__dirname, 'renderer', name)); }

function goAdmin() {
  if (!serverUrl) { showPage('setup.html'); return; }
  win.loadURL(serverUrl + ADMIN_ROUTE);
}

function scheduleRetry() {
  clearTimeout(retryTimer);
  retryTimer = setTimeout(async () => {
    if (!win || win.isDestroyed()) return;
    if (await serverReachable()) goAdmin(); else scheduleRetry();
  }, 10000);
}

async function serverReachable() {
  if (!serverUrl) return false;
  try {
    const res = await net.fetch(serverUrl + '/index.php?r=api/live', { method: 'GET', cache: 'no-store' });
    return res.ok;
  } catch (e) { return false; }
}

function createWindow() {
  win = new BrowserWindow({
    width: 1360, height: 860, minWidth: 980, minHeight: 640,
    title: 'Shadow Admin', backgroundColor: '#0b0b12', show: false,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true, nodeIntegration: false, sandbox: true,
      webSecurity: true, allowRunningInsecureContent: false,
      devTools: !app.isPackaged,
      partition: PARTITION,
    },
  });
  win.once('ready-to-show', () => win.show());

  win.webContents.setWindowOpenHandler(({ url }) => {
    if (isAllowedInApp(url)) { win.loadURL(url); } else { openExternalSafe(url); }
    return { action: 'deny' };
  });
  win.webContents.on('will-navigate', (e, url) => {
    if (url.startsWith('file://')) return; // our own offline/setup pages
    if (!isAllowedInApp(url)) { e.preventDefault(); openExternalSafe(url); }
  });
  win.webContents.on('did-fail-load', (_e, code, desc, url, isMain) => {
    if (!isMain || code === -3) return; // -3 = aborted by a newer navigation
    showPage('offline.html');
    scheduleRetry();
  });
  // A 5xx/network-level failure page from the server should not leave a blank window
  win.webContents.on('did-navigate', (_e, url, httpCode) => {
    if (httpCode >= 500 && url.startsWith(serverUrl)) { showPage('offline.html'); scheduleRetry(); }
  });

  goAdmin();
}

function buildMenu() {
  const t = [
    { label: 'Shadow Admin', submenu: [
      { label: 'لوحة التحكم', click: () => goAdmin() },
      { label: 'إعادة تحميل', accelerator: 'F5', click: () => win && win.webContents.reload() },
      { label: 'تغيير عنوان الخادم', click: () => showPage('setup.html') },
      { type: 'separator' },
      { label: 'مسح بيانات الجلسة المحلية', click: async () => {
          const { response } = await dialog.showMessageBox(win, { type: 'question', buttons: ['إلغاء', 'مسح'], defaultId: 0, cancelId: 0,
            message: 'سيتم تسجيل الخروج ومسح الكوكيز المحلية لهذا التطبيق.', detail: 'ملاحظة: حساب الإدارة مربوط بجهاز واحد على الخادم؛ بعد المسح قد تحتاج إلى إعادة ربط الجهاز.' });
          if (response === 1) { await session.fromPartition(PARTITION).clearStorageData(); goAdmin(); }
        } },
      { type: 'separator' },
      { role: 'quit', label: 'خروج' },
    ] },
    { label: 'عرض', submenu: [
      { role: 'zoomIn', label: 'تكبير' }, { role: 'zoomOut', label: 'تصغير' }, { role: 'resetZoom', label: 'الحجم الافتراضي' },
      { role: 'togglefullscreen', label: 'ملء الشاشة' },
    ] },
  ];
  Menu.setApplicationMenu(Menu.buildFromTemplate(t));
}

/* ---------- IPC (only what the setup/offline pages need) ---------- */
// IPC is honoured only for our own local pages (file://), never for pages served by the remote site.
function fromLocalPage(e) {
  try { return !!e.senderFrame && e.senderFrame.url.startsWith('file://'); } catch (err) { return false; }
}
ipcMain.handle('shadow:getServerUrl', (e) => (fromLocalPage(e) ? serverUrl : ''));
ipcMain.handle('shadow:setServerUrl', async (e, raw) => {
  if (!fromLocalPage(e)) return { ok: false, error: 'غير مسموح.' };
  const u = normalizeUrl(raw);
  if (!u) return { ok: false, error: 'العنوان غير صالح. استخدم عنوان HTTPS كاملًا (مثال: https://example.com).' };
  serverUrl = u; saveServerUrl(u);
  if (!(await serverReachable())) return { ok: true, warning: 'تم الحفظ لكن الخادم لا يستجيب الآن.' , reachable: false };
  return { ok: true, reachable: true };
});
ipcMain.handle('shadow:retry', async (e) => { if (!fromLocalPage(e)) return false; if (await serverReachable()) { goAdmin(); return true; } return false; });
ipcMain.handle('shadow:openAdmin', (e) => { if (fromLocalPage(e)) goAdmin(); });

app.whenReady().then(() => {
  serverUrl = loadServerUrl();
  const ses = session.fromPartition(PARTITION);
  // The admin panel needs no device permissions: deny all by default.
  ses.setPermissionRequestHandler((_wc, _perm, cb) => cb(false));
  buildMenu();
  createWindow();
  app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) createWindow(); });
});

app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit(); });

(async () => {
  const input = document.getElementById('url'), msg = document.getElementById('msg');
  try { input.value = await window.shadowApp.getServerUrl() || ''; } catch (e) {}
  document.getElementById('save').onclick = async () => {
    msg.textContent = 'جارٍ الحفظ...';
    const r = await window.shadowApp.setServerUrl(input.value);
    if (!r.ok) { msg.textContent = r.error; return; }
    if (r.reachable === false) { msg.textContent = r.warning; return; }
    window.shadowApp.openAdmin();
  };
})();

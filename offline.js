document.getElementById('retry').onclick = async () => {
  document.getElementById('msg').textContent = 'جارٍ المحاولة...';
  const ok = await window.shadowApp.retry();
  if (!ok) document.getElementById('msg').textContent = 'الخادم ما زال غير متاح.';
};
document.getElementById('chg').onclick = () => { location.href = 'setup.html'; };

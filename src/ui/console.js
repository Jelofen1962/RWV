(() => {
  try {
    if (window.top !== window.self) return;
    const font = 'font-family:Poppins,Segoe UI,sans-serif;';
    console.log('%c RWV %c Rustmade-WebView ',
      font + 'background:#171717;color:#fff;font-weight:700;font-size:22px;padding:9px 12px;border-radius:8px 0 0 8px;',
      font + 'background:#f0f0f0;color:#222;font-weight:600;font-size:14px;padding:14px 12px;border-radius:0 8px 8px 0;');
    console.log('%cYour web, in its own window.', font + 'font-size:12px;color:#888;');
    console.group('%cMade by @Jelofen1962', font + 'font-weight:700;font-size:13px;color:#888;');
    console.log('GitHub    https://github.com/Jelofen1962');
    console.log('Telegram  https://t.me/jelofen1962');
    console.log('Phone     +98 910 972 1962');
    console.groupEnd();
  } catch (_) {
  }
})();

package app.animix.data.playback

/** Port of 2.5's browser capture; messages never contain executable native commands. */
internal val captureScript = """
(function () {
  if (window.__animixCaptureInstalled) return;
  window.__animixCaptureInstalled = true;
  var reported = Object.create(null);
  function send(value) {
    try {
      if (typeof value !== 'string' || !value || value.length > 65536) return;
      value = value.replace(/\\\//g, '/').replace(/&amp;/g, '&').trim();
      if (value[0] !== '[') value = new URL(value, document.baseURI).href;
      if (!/\.(m3u8|mp4)(?:[?#]|$)/i.test(value) || reported[value]) return;
      reported[value] = true;
      var message = JSON.stringify({type:'stream', value:value});
      if (typeof window.__animixNativeSend === 'function') window.__animixNativeSend(message);
      else if (window.AnimixCapture) window.AnimixCapture.postMessage(message);
    } catch (_) {}
  }
  window.open = function () { return null; };
  var originalFetch = window.fetch;
  if (originalFetch) window.fetch = function () {
    var input = arguments[0];
    send(typeof input === 'string' ? input : input && input.url);
    return originalFetch.apply(this, arguments).then(function (response) {
      send(response.url);
      return response;
    });
  };
  var originalOpen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function (method, url) {
    send(url);
    this.addEventListener('load', function () { send(this.responseURL); });
    return originalOpen.apply(this, arguments);
  };
  function scan() {
    try {
      performance.getEntriesByType('resource').forEach(function (r) { send(r.name); });
      [window.PlayerjsConfig, window.playerjsConfig, window.playerConfig].forEach(function (c) {
        if (c) send(c.file || c.src);
      });
      document.querySelectorAll('video,source').forEach(function (v) {
        send(v.currentSrc || v.src);
        if (v.tagName === 'VIDEO') {
          v.muted = true;
          var result = v.play();
          if (result && result.catch) result.catch(function () {});
        }
      });
      var button = document.querySelector('.play_button,.play-button,.fp-play,.play-btn,.pjs-play,#play,.vjs-big-play-button,.plyr__control--overlaid,[data-player-play]');
      if (button && button.click) button.click();
    } catch (_) {}
  }
  if (window.PerformanceObserver) {
    try { new PerformanceObserver(function (list) {
      list.getEntries().forEach(function (r) { send(r.name); });
    }).observe({entryTypes:['resource']}); } catch (_) {}
  }
  scan();
  var timer = setInterval(scan, 500);
  window.addEventListener('pagehide', function () { clearInterval(timer); }, {once:true});
})();
""".trimIndent()

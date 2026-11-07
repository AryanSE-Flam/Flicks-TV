// Injected by MainActivity after each main-frame commit. Reports what the page
// actually receives through the FlicksProbe JavascriptInterface.
(function () {
  if (window.__flicksProbe) return;
  window.__flicksProbe = true;

  function send(o) {
    try { FlicksProbe.post(JSON.stringify(o)); } catch (_) {}
  }

  function describe(el) {
    if (!el || !el.nodeName) return String(el);
    var s = el.nodeName.toLowerCase();
    if (el.id) s += '#' + el.id;
    if (typeof el.className === 'string' && el.className) s += '.' + el.className.trim().split(/\s+/).join('.');
    var label = el.getAttribute && (el.getAttribute('aria-label') || el.getAttribute('data-vertex-diff'));
    if (label) s += '[' + label + ']';
    return s;
  }

  function origin(e) {
    return e.composedPath ? e.composedPath()[0] : e.target;
  }

  // Window capture runs before any page listener. defaultPrevented is read
  // after dispatch finishes, so it reflects what the page's handlers did.
  function onKey(e) {
    var rec = {
      t: e.type, key: e.key, code: e.code, keyCode: e.keyCode, which: e.which,
      repeat: e.repeat, trusted: e.isTrusted, target: describe(origin(e))
    };
    setTimeout(function () { rec.prevented = e.defaultPrevented; send(rec); }, 0);
  }
  window.addEventListener('keydown', onKey, true);
  window.addEventListener('keyup', onKey, true);

  window.addEventListener('focusin', function (e) {
    send({ t: 'focus', target: describe(origin(e)) });
  }, true);

  // Event names taken from the player bundle (ad-webgl.*.js). They do not
  // bubble and some are dispatched inside the shadow root, so listen in the
  // capture phase on both window and the shadow root.
  var PLAYER_EVENTS = ['vertex-ready', 'vertex-error', 'vertex-buffering', 'vertex-complete',
    'vertex-cta', 'vertex-interaction', 'vertex-diff-change', 'vertex-scene-change',
    'vertex-polygon-click', 'vertex-speaker', 'vertex-swipe'];

  function detail(d) {
    try { return JSON.stringify(d).slice(0, 200); } catch (_) { return String(d); }
  }

  function watch(root, where) {
    PLAYER_EVENTS.forEach(function (name) {
      root.addEventListener(name, function (e) {
        send({ t: 'player', name: name, where: where, detail: detail(e.detail) });
      }, true);
    });
  }
  watch(window, 'window');

  var tries = 0;
  var timer = setInterval(function () {
    var vp = document.querySelector('vertex-player');
    if (vp && vp.shadowRoot) {
      watch(vp.shadowRoot, 'shadow');
      send({ t: 'probe', msg: 'watching vertex-player shadow root' });
      clearInterval(timer);
    } else if (++tries > 300) {
      send({ t: 'probe', msg: 'no vertex-player shadow root after 30s' });
      clearInterval(timer);
    }
  }, 100);

  send({ t: 'probe', msg: 'injected into ' + location.href });
})();

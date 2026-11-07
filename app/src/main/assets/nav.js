// Injected by MainActivity next to probe.js. Moves focus between the Flick's skin
// controls (colour swatches, CTA) with the D-pad and activates them with Enter/OK.
// Targets the same selector the player uses to wire its skin click handlers, so a
// click() here runs the player's own handlers.
(function () {
  if (window.__flicksTvNav) return;
  window.__flicksTvNav = true;

  var TRIGGERS = '[data-vertex-trigger],[data-vertex-diff],[data-vertex-variant]';
  var DIRS = { ArrowUp: [0, -1], ArrowDown: [0, 1], ArrowLeft: [-1, 0], ArrowRight: [1, 0] };
  var FOCUS_ATTR = 'data-tv-focus';
  var current = null;

  function report(msg) {
    try { FlicksProbe.post(JSON.stringify({ t: 'nav', msg: msg })); } catch (_) {}
  }

  function label(el) {
    return el.getAttribute('aria-label') || (el.textContent || '').trim().slice(0, 30) ||
      el.getAttribute('data-vertex-trigger') || el.nodeName.toLowerCase();
  }

  function roots() {
    var list = [document];
    var players = document.querySelectorAll('vertex-player');
    for (var i = 0; i < players.length; i++) {
      if (players[i].shadowRoot) list.push(players[i].shadowRoot);
    }
    return list;
  }

  function styleRoot(root) {
    if (root === document || root.__tvNavStyled) return;
    root.__tvNavStyled = true;
    var s = document.createElement('style');
    s.textContent = '[' + FOCUS_ATTR + ']{outline:4px solid #fff!important;outline-offset:3px!important;' +
      'box-shadow:0 0 0 8px rgba(0,0,0,.45)!important;transform:scale(1.08);transition:transform .12s}';
    root.appendChild(s);
  }

  function visible(el) {
    var r = el.getBoundingClientRect();
    if (r.width < 1 || r.height < 1) return false;
    if (r.right < 0 || r.bottom < 0 || r.left > innerWidth || r.top > innerHeight) return false;
    // pointer-events is inherited, so the element's own value is the clickable one;
    // the player's skin host is pointer-events:none while its buttons are not.
    var own = getComputedStyle(el);
    if (own.pointerEvents === 'none' || own.visibility === 'hidden') return false;
    for (var n = el; n && n.nodeType === 1; n = n.parentElement || (n.getRootNode && n.getRootNode().host)) {
      var cs = getComputedStyle(n);
      if (cs.display === 'none' || cs.opacity === '0') return false;
    }
    return true;
  }

  var lastScan = '';

  function candidates() {
    var out = [], total = 0;
    roots().forEach(function (root) {
      var found = root.querySelectorAll(TRIGGERS);
      total += found.length;
      for (var i = 0; i < found.length; i++) {
        if (visible(found[i])) { out.push(found[i]); styleRoot(found[i].getRootNode()); }
      }
    });
    lastScan = total + ' trigger elements in ' + roots().length + ' roots, ' + out.length + ' visible';
    return out;
  }

  function centre(r) { return { x: r.left + r.width / 2, y: r.top + r.height / 2 }; }

  // Nearest candidate whose centre lies in the pressed direction; distance across
  // the axis of travel counts double so moves stay in their row/column.
  function next(from, dir, list) {
    var o = from ? centre(from.getBoundingClientRect()) : { x: innerWidth / 2, y: innerHeight / 2 };
    var best = null, bestScore = Infinity;
    list.forEach(function (el) {
      if (el === from) return;
      var c = centre(el.getBoundingClientRect());
      var along = (c.x - o.x) * dir[0] + (c.y - o.y) * dir[1];
      if (along <= 1) return;
      var across = Math.abs((c.x - o.x) * dir[1]) + Math.abs((c.y - o.y) * dir[0]);
      var score = along + 2 * across;
      if (score < bestScore) { bestScore = score; best = el; }
    });
    return best;
  }

  function focusOn(el) {
    if (current) current.removeAttribute(FOCUS_ATTR);
    current = el;
    el.setAttribute(FOCUS_ATTR, '');
    el.focus({ preventScroll: true });
  }

  window.addEventListener('keydown', function (e) {
    var dir = DIRS[e.key];
    if (dir) {
      var list = candidates();
      if (!list.length) { report(e.key + ': no visible skin controls (' + lastScan + ')'); return; }
      var from = current && list.indexOf(current) >= 0 ? current : null;
      var to = next(from, dir, list) || (from ? null : list[0]);
      e.preventDefault();
      if (!to) { report(e.key + ': stay on ' + label(from) + ' (edge)'); return; }
      focusOn(to);
      report(e.key + ': ' + (from ? label(from) : 'none') + ' -> ' + label(to) + ' (' + list.length + ' controls)');
    } else if (e.key === 'Enter' && current && current.isConnected) {
      if (!visible(current)) { report('Enter: focused control ' + label(current) + ' is hidden, ignored'); return; }
      // preventDefault stops the native keypress activation, so the click fires exactly once.
      // The click carries the control's centre so the player's vertex-interaction x/y are sane.
      e.preventDefault();
      report('Enter: activate ' + label(current));
      var c = centre(current.getBoundingClientRect());
      current.dispatchEvent(new MouseEvent('click', {
        bubbles: true, cancelable: true, composed: true, view: window, clientX: c.x, clientY: c.y
      }));
    }
  }, true);
})();

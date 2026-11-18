/* J-Method connection POC: official-page UI automation after explicit user login. */
(function () {
  'use strict';
  if (window.top !== window) return;
  var cfg = window.__JM_CONFIG;
  if (!cfg || cfg.version && cfg.version !== 1 || typeof cfg.nonce !== 'string' || cfg.nonce.length < 16 || !/^\d{4}$/.test(cfg.pin || '')) return;
  var ownNonce = cfg.nonce;
  // Credentials are handed over once by the native form, kept only in this closure and wiped after submission.
  var login = null;
  if (cfg.credentials && typeof cfg.credentials.username === 'string' && typeof cfg.credentials.password === 'string' && cfg.credentials.username && cfg.credentials.password) {
    login = { username: cfg.credentials.username, password: cfg.credentials.password };
  }
  delete cfg.credentials;
  var trusted = function () {
    return location.protocol === 'https:' && location.origin === cfg.allowedOrigin && /\.index-education\.net$/i.test(location.hostname) && /\/(?:eleve|parent)\.html$/i.test(location.pathname);
  };
  if (!trusted()) return;
  if (window.__JM_PRONOTE_POC && window.__JM_PRONOTE_POC.nonce === ownNonce) return;
  if (window.__JM_PRONOTE_POC && window.__JM_PRONOTE_POC.stop) window.__JM_PRONOTE_POC.stop();
  var stopped = false;
  var interval;
  var started = Date.now();
  var authenticatedAt = 0;
  var opened = false;
  var filled = false;
  var generated = false;
  var loginFilled = false;
  var loginSubmittedAt = 0;
  var loginWanted = false;
  var mfaShown = false;
  var lastStage = '';
  var closedOld = false;

  function stop() {
    if (stopped) return;
    stopped = true;
    clearInterval(interval);
    cfg.pin = '';
    login = null;
    if (window.__JM_CONFIG && window.__JM_CONFIG.nonce === ownNonce) delete window.__JM_CONFIG;
  }
  window.__JM_PRONOTE_POC = { nonce: ownNonce, stop: stop };

  function send(type, extra) {
    if (stopped || !trusted()) return;
    var message = Object.assign({ version: 1, nonce: ownNonce, type: type, origin: location.origin, url: location.href }, extra || {});
    try {
      if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.jmethodPronote) window.webkit.messageHandlers.jmethodPronote.postMessage(message);
      else if (window.JMethodNative && typeof window.JMethodNative.postMessage === 'function') window.JMethodNative.postMessage(JSON.stringify(message));
    } catch (_) { stop(); }
  }
  function fail(code) { send('error', { code: code }); stop(); }
  function stage(value) { if (lastStage !== value) { lastStage = value; send('progress', { stage: value }); } }
  function normalize(value) { return String(value || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/\s+/g, ' ').trim().toLowerCase(); }
  function label(element) {
    var result = element.getAttribute('aria-label') || element.getAttribute('title') || '';
    var ids = (element.getAttribute('aria-labelledby') || '').split(/\s+/);
    ids.forEach(function (id) { var item = document.getElementById(id); if (item) result += ' ' + item.textContent; });
    if (element.id) document.querySelectorAll('label').forEach(function (item) { if (item.htmlFor === element.id) result += ' ' + item.textContent; });
    result += ' ' + (element.getAttribute('placeholder') || '') + ' ' + (element.textContent || '');
    return normalize(result);
  }
  function visible(element) {
    if (!element || !element.isConnected || !element.getClientRects().length) return false;
    var style = getComputedStyle(element);
    return style.display !== 'none' && style.visibility !== 'hidden' && element.getAttribute('aria-hidden') !== 'true';
  }
  function targets(scope) { return Array.from((scope || document).querySelectorAll('button,[role="button"],a')); }
  function enabled(element) { return !element.disabled && element.getAttribute('aria-disabled') !== 'true' && !/\bdisabled\b/i.test(element.className || ''); }
  function activate(element) {
    element.scrollIntoView({ block: 'nearest', inline: 'nearest' });
    var rect = element.getBoundingClientRect();
    ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].forEach(function (type) {
      var EventType = type.indexOf('pointer') === 0 && window.PointerEvent ? PointerEvent : MouseEvent;
      element.dispatchEvent(new EventType(type, { bubbles: true, cancelable: true, view: window, clientX: rect.left + rect.width / 2, clientY: rect.top + rect.height / 2, button: 0, pointerId: 1, pointerType: 'mouse', isPrimary: true }));
    });
  }
  function fill(element, value) {
    element.focus();
    if (element.tagName === 'INPUT' || element.tagName === 'TEXTAREA') {
      var prototype = element.tagName === 'INPUT' ? HTMLInputElement.prototype : HTMLTextAreaElement.prototype;
      var setter = Object.getOwnPropertyDescriptor(prototype, 'value').set;
      setter.call(element, value);
    } else if (element.isContentEditable) {
      var range = document.createRange(); range.selectNodeContents(element);
      var selection = window.getSelection(); selection.removeAllRanges(); selection.addRange(range);
      if (!document.execCommand('insertText', false, value)) element.textContent = value;
    } else {
      element.textContent = value;
    }
    element.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: value }));
    element.dispatchEvent(new Event('change', { bubbles: true }));
    element.dispatchEvent(new KeyboardEvent('keyup', { bubbles: true, key: value.slice(-1), code: 'Digit' + value.slice(-1) }));
    element.blur();
  }
  function dialogName(dialog) {
    var heading = dialog.querySelector('h1,h2,[role="heading"]');
    return normalize(dialog.getAttribute('aria-label') || (heading && heading.textContent) || label(dialog));
  }
  function loginFields() {
    var fields = Array.from(document.querySelectorAll('input,[role="textbox"],[contenteditable="true"]')).filter(visible);
    var password = fields.find(function (item) { return item.getAttribute('type') === 'password' || /mot de passe|password/.test(label(item)); });
    var username = fields.find(function (item) { return item !== password && /identifiant|utilisateur|username|login/.test(label(item)) && item.getAttribute('type') !== 'password'; });
    return username && password ? { username: username, password: password } : null;
  }
  function rejectionShown() {
    var nodes = Array.from(document.querySelectorAll('[role="dialog"],[role="alert"],[role="alertdialog"],[aria-live],.message,.erreur,.error')).filter(visible);
    return nodes.some(function (node) {
      var text = normalize(node.textContent);
      return /(identifiant|mot de passe).{0,60}(incorrect|invalide|errone|pas valide)|echec.{0,30}(connexion|authentification|identification)|(incorrect|invalide).{0,40}(identifiant|mot de passe)/.test(text);
    });
  }
  // Fills and submits the official form exactly once, with the credentials typed in the native form
  // (or the published public demo account on demo.index-education.net only).
  function credentialLogin() {
    if (!login && cfg.demo && location.hostname === 'demo.index-education.net' && !loginFilled) login = { username: 'demonstration', password: 'pronotevs' };
    if (!loginWanted && login) loginWanted = true;
    if (!loginWanted) return;
    var fields = loginFields();
    if (loginSubmittedAt) {
      if (rejectionShown()) { fail('LOGIN_REJECTED'); return; }
      // A slow answer is not a rejection: only a visible rejection message may erase a stored password.
      if (Date.now() - loginSubmittedAt > 25000) fail('LOGIN_STALLED');
      return;
    }
    if (!fields) {
      if (Date.now() - started > 30000) fail('LOGIN_FORM_NOT_FOUND');
      return;
    }
    if (!loginFilled) { stage('filling-login'); fill(fields.username, login.username); fill(fields.password, login.password); loginFilled = true; return; }
    var submit = targets().find(function (item) { return visible(item) && /(?:^|\s)(se connecter|connexion|valider|log in|sign in)(?:$|\s)/.test(label(item)) && enabled(item); });
    if (submit) { stage('submitting-login'); loginSubmittedAt = Date.now(); login = null; activate(submit); }
  }

  function tick() {
    if (stopped) return;
    if (!trusted()) { stop(); return; }
    if (Date.now() - started > 600000) { fail('LOGIN_TIMEOUT'); return; }
    if (authenticatedAt && Date.now() - authenticatedAt > 35000) { fail('QR_TIMEOUT'); return; }
    try {
      var dialogs = Array.from(document.querySelectorAll('[role="dialog"]')).filter(visible);
      var security = dialogs.find(function (dialog) { var name = dialogName(dialog); return !/qr\s*code/.test(name) && /securisation|double authentification|verification.*compte|verifier.*identite/.test(name); });
      // Security check: ask native to reveal the page once, keep watching, and resume the QR flow by itself
      // once the user has completed it (the page is not reloaded after MFA).
      if (security) {
        if (!mfaShown) { mfaShown = true; send('error', { code: 'MFA_REQUIRED' }); }
        started = Date.now();
        if (authenticatedAt) authenticatedAt = Date.now();
        if (loginSubmittedAt) loginSubmittedAt = Date.now();
        return;
      }
      if (mfaShown) { mfaShown = false; send('progress', { stage: 'mfa-done' }); }
      var qrButton = targets().find(function (item) { var name = label(item); return visible(item) && /qr\s*code|code a flasher/.test(name) && /application|mobile|flasher/.test(name) && !/generer/.test(name); });
      if (!qrButton) { credentialLogin(); return; }
      if (!authenticatedAt) { authenticatedAt = Date.now(); login = null; send('authenticated'); }
      var dialog = dialogs.find(function (item) { return /qr\s*code/.test(dialogName(item)); });
      if (!dialog) {
        if (!opened && enabled(qrButton)) { stage('opening-qr'); opened = true; activate(qrButton); }
        return;
      }
      var image = Array.from(dialog.querySelectorAll('img')).find(function (item) { return visible(item) && /^data:image\/png;base64,/.test(item.getAttribute('src') || ''); });
      if (image && !generated) {
        // Never reuse an image that was generated using an unknown earlier PIN.
        var close = targets(dialog).find(function (item) { return /(?:^|\s)(fermer|close)(?:$|\s)/.test(label(item)); });
        if (close && !closedOld) { closedOld = true; opened = false; activate(close); }
        return;
      }
      if (image && generated) {
        var data = image.getAttribute('src');
        if (data.length > 2 * 1024 * 1024) { fail('QR_TOO_LARGE'); return; }
        send('qr', { image: data, pin: cfg.pin });
        stop();
        return;
      }
      var code = Array.from(dialog.querySelectorAll('input,[role="textbox"],[contenteditable="true"]')).filter(visible).find(function (item) { return /verification|4 chiffres|code/.test(label(item)); });
      if (!code) return;
      if (!filled) { stage('filling-code'); fill(code, cfg.pin); filled = true; return; }
      var generate = targets(dialog).find(function (item) { return /generer.*qr|generate.*qr/.test(label(item)) && enabled(item) && visible(item); });
      if (generate && !generated) { stage('generating-qr'); generated = true; activate(generate); }
    } catch (_) { fail('AUTOMATION_FAILED'); }
  }
  interval = setInterval(tick, 500);
  tick();
})();

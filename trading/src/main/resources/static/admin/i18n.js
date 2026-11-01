/*
 * Tiny EN/RU runtime for the admin panel.
 *
 *   I18N.t(key, params)   translate; {name} = param, [ ... {name} ... ] = optional group that is
 *                         dropped when any param inside it is missing (same as Java Msg.render)
 *   I18N.tm(msg)          translate an API message {code, params, message|reason}
 *   I18N.getLang()        'en' | 'ru'
 *   I18N.setLang(lang)    persist, re-apply the DOM, fire window event 'langchange' {detail: lang}
 *   I18N.ready            promise resolved once the dictionaries are loaded and the DOM is translated
 *   I18N.applyDom(root)   re-translate markup (also exposed for dynamically inserted nodes)
 *
 * Markup hooks: data-i18n (textContent), data-i18n-placeholder, data-i18n-title, data-i18n-tip
 * (data-tip), data-i18n-aria (aria-label). data-args='{"n":1}' supplies params for data-i18n.
 * data-msg="CODE" [data-msg-args='{}'] [data-msg-text="fallback"] [data-msg-fb="key"] renders an
 * API message code (falls back to the text, then to the key in data-msg-fb).
 */
(function () {
  'use strict';

  var STORAGE_KEY = 'sonar.lang';
  var LANGS = ['en', 'ru'];
  var dicts = {};

  function detect() {
    var saved = null;
    try { saved = localStorage.getItem(STORAGE_KEY); } catch (e) { /* storage blocked */ }
    if (LANGS.indexOf(saved) >= 0) return saved;
    var nav = String(navigator.language || '').toLowerCase();
    return nav.indexOf('ru') === 0 ? 'ru' : 'en';
  }

  var lang = detect();

  function load(l) {
    if (dicts[l]) return Promise.resolve(dicts[l]);
    return fetch('/admin/i18n/' + l + '.json', { cache: 'no-cache' })
      .then(function (r) {
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.json();
      })
      .then(function (d) { dicts[l] = d; return d; })
      .catch(function () { return {}; }); // not cached, so the next call retries
  }

  function has(params, name) {
    return params != null && Object.prototype.hasOwnProperty.call(params, name) && params[name] != null;
  }

  function render(template, params) {
    var withGroups = template.replace(/\[([^\[\]]*)\]/g, function (_, inner) {
      var complete = true;
      inner.replace(/\{(\w+)\}/g, function (m, name) { if (!has(params, name)) complete = false; return m; });
      return complete ? inner : '';
    });
    return withGroups.replace(/\{(\w+)\}/g, function (_, name) { return has(params, name) ? String(params[name]) : ''; });
  }

  function lookup(key) {
    var cur = dicts[lang];
    if (cur && typeof cur[key] === 'string') return cur[key];
    var en = dicts.en;
    if (en && typeof en[key] === 'string') return en[key];
    return null;
  }

  function t(key, params) {
    var tpl = lookup(key);
    return tpl == null ? key : render(tpl, params);
  }

  // Like t(), but null when the key is in no dictionary, so applyDom keeps the default text from
  // the markup instead of overwriting it with a raw key (dictionary fetch failed, typo in a key).
  function tOrNull(key, params) {
    var tpl = lookup(key);
    return tpl == null ? null : render(tpl, params);
  }

  function tm(msg) {
    if (!msg) return '';
    if (msg.code && lookup('msg.' + msg.code) != null) return t('msg.' + msg.code, msg.params);
    var plain = msg.message != null ? msg.message : msg.reason;
    return plain != null ? plain : '';
  }

  function parseArgs(el, attr) {
    var raw = el.getAttribute(attr);
    if (!raw) return undefined;
    try { return JSON.parse(raw); } catch (e) { return undefined; }
  }

  function applyDom(root) {
    root = root || document;
    var q = function (sel) { return root.querySelectorAll(sel); };
    q('[data-i18n]').forEach(function (el) {
      var text = tOrNull(el.getAttribute('data-i18n'), parseArgs(el, 'data-args'));
      if (text != null) el.textContent = text;
    });
    [['data-i18n-placeholder', 'placeholder'], ['data-i18n-title', 'title'], ['data-i18n-tip', 'data-tip'], ['data-i18n-aria', 'aria-label']]
      .forEach(function (pair) {
        q('[' + pair[0] + ']').forEach(function (el) {
          var text = tOrNull(el.getAttribute(pair[0]));
          if (text != null) el.setAttribute(pair[1], text);
        });
      });
    q('[data-msg]').forEach(function (el) {
      var text = tm({
        code: el.getAttribute('data-msg'),
        params: parseArgs(el, 'data-msg-args'),
        message: el.getAttribute('data-msg-text'),
      });
      if (!text && el.getAttribute('data-msg-fb')) text = t(el.getAttribute('data-msg-fb'));
      el.textContent = text;
    });
  }

  function whenDom(fn) {
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', fn, { once: true });
    else fn();
  }

  function activate(next, persist) {
    if (LANGS.indexOf(next) < 0) return Promise.resolve();
    return Promise.all([load(next), next === 'en' ? null : load('en')]).then(function () {
      var changed = next !== lang;
      lang = next;
      if (persist) {
        try { localStorage.setItem(STORAGE_KEY, next); } catch (e) { /* storage blocked */ }
      }
      document.documentElement.lang = next;
      applyDom(document);
      if (changed) window.dispatchEvent(new CustomEvent('langchange', { detail: next }));
    });
  }

  var ready = Promise.all([load(lang), lang === 'en' ? null : load('en')]).then(function () {
    return new Promise(function (resolve) {
      whenDom(function () {
        document.documentElement.lang = lang;
        applyDom(document);
        resolve();
      });
    });
  });

  window.addEventListener('storage', function (e) {
    if (e.key === STORAGE_KEY && LANGS.indexOf(e.newValue) >= 0 && e.newValue !== lang) activate(e.newValue, false);
  });

  window.I18N = {
    t: t,
    tm: tm,
    getLang: function () { return lang; },
    setLang: function (l) { return activate(l, true); },
    ready: ready,
    applyDom: applyDom,
    render: render,
  };
})();

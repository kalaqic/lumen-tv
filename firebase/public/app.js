// Lumen TV · sajt za liste
// Prijava Google nalogom, dodavanje i brisanje lista, povezivanje TV-a kodom, titlovi.
// Sve se cuva u Firebase Realtime Database pod owners/{uid}; TV to samo cita.

import { fromLink, fromLogin, sameList, describe, cleanCode } from './links.js';

const FB = '10.12.2';
const CODE_TTL = 15 * 60 * 1000;
const $ = (id) => document.getElementById(id);
const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const params = new URLSearchParams(location.search);

let api = null;          // firebase ili demo
let user = null;         // {uid, email}
let data = {};           // owners/{uid}
let unwatch = null;
let tab = 'link';
let pendingCode = cleanCode(params.get('kod'));

// ------------------------------------------------------------------ Firebase

async function firebaseBackend() {
  const res = await fetch('/__/firebase/init.json');
  if (!res.ok) throw new Error('config');
  const cfg = await res.json();
  if (!cfg.databaseURL) cfg.databaseURL = `https://${cfg.projectId}-default-rtdb.firebaseio.com`;
  const [{ initializeApp }, A, D] = await Promise.all([
    import(`https://www.gstatic.com/firebasejs/${FB}/firebase-app.js`),
    import(`https://www.gstatic.com/firebasejs/${FB}/firebase-auth.js`),
    import(`https://www.gstatic.com/firebasejs/${FB}/firebase-database.js`),
  ]);
  const app = initializeApp(cfg);
  const auth = A.getAuth(app);
  auth.languageCode = 'bs';
  const db = D.getDatabase(app);
  const ref = (p) => D.ref(db, p);
  return {
    onAuth(cb) {
      A.getRedirectResult(auth).catch(() => {});
      A.onAuthStateChanged(auth, (u) => cb(u && !u.isAnonymous ? { uid: u.uid, email: u.email || u.displayName || '' } : null));
    },
    async signIn() {
      const p = new A.GoogleAuthProvider();
      p.setCustomParameters({ prompt: 'select_account' });
      try {
        await A.signInWithPopup(auth, p);
      } catch (e) {
        if (e && (e.code === 'auth/popup-blocked' || e.code === 'auth/operation-not-supported-in-this-environment')) {
          await A.signInWithRedirect(auth, p);
        } else if (e && e.code !== 'auth/popup-closed-by-user' && e.code !== 'auth/cancelled-popup-request') {
          throw e;
        }
      }
    },
    signOut: () => A.signOut(auth),
    watch(uid, cb, onErr) {
      return D.onValue(ref(`owners/${uid}`), (s) => cb(s.val() || {}), onErr);
    },
    addList: (uid, item) => D.set(D.push(ref(`owners/${uid}/lists`)), { ...item, t: D.serverTimestamp() }),
    updateList: (uid, id, item) => D.update(ref(`owners/${uid}/lists/${id}`), item),
    removeList: (uid, id) => D.remove(ref(`owners/${uid}/lists/${id}`)),
    async readCode(code) {
      const s = await D.get(ref(`pairing/${code}`));
      return s.exists() ? s.val() : null;
    },
    claim: (uid, email, code, tv, tvName) => D.update(ref('/'), {
      [`owners/${uid}/devices/${tv}`]: { name: tvName, t: D.serverTimestamp() },
      [`pairing/${code}/claim`]: { owner: uid, name: email.slice(0, 120) },
    }),
    removeDevice: (uid, tv) => D.remove(ref(`owners/${uid}/devices/${tv}`)),
    saveSubs: (uid, subs) => D.set(ref(`owners/${uid}/subs`), subs),
    removeSubs: (uid) => D.remove(ref(`owners/${uid}/subs`)),
  };
}

// ------------------------------------------------------------------ demo (pregled izgleda bez Firebase-a: ?demo)

function demoBackend() {
  let authCb = null, dataCb = null, n = 0;
  const store = {
    lists: {
      a1: { type: 'login', name: 'Asterix', url: 'http://asterix.example:25461/get.php?username=demo&password=x&type=m3u_plus&output=ts', t: 1 },
      a2: { type: 'link', name: 'Moja lista', url: 'https://example.com/moja.m3u', t: 2 },
    },
    devices: { tv1: { name: 'SHIELD Android TV', t: Date.now() - 86400000 } },
  };
  const codes = { ABC234: { tv: 'tv2', t: Date.now(), name: 'Sony Bravia' } };
  const emit = () => dataCb && dataCb(JSON.parse(JSON.stringify(store)));
  return {
    onAuth(cb) { authCb = cb; cb(params.has('out') ? null : { uid: 'demo', email: 'ti@gmail.com' }); },
    async signIn() { authCb({ uid: 'demo', email: 'ti@gmail.com' }); },
    async signOut() { authCb(null); },
    watch(uid, cb) { dataCb = cb; emit(); return () => { dataCb = null; }; },
    async addList(uid, item) { store.lists['n' + (++n)] = { ...item, t: Date.now() }; emit(); },
    async updateList(uid, id, item) { Object.assign(store.lists[id], item); emit(); },
    async removeList(uid, id) { delete store.lists[id]; emit(); },
    async readCode(code) { return codes[code] || null; },
    async claim(uid, email, code, tv, tvName) { codes[code].claim = { owner: uid }; store.devices[tv] = { name: tvName, t: Date.now() }; emit(); },
    async removeDevice(uid, tv) { delete store.devices[tv]; emit(); },
    async saveSubs(uid, subs) { store.subs = subs; emit(); },
    async removeSubs() { delete store.subs; emit(); },
  };
}

// ------------------------------------------------------------------ pomocno

let toastTimer = 0;
function toast(msg, err) {
  const t = $('toast');
  t.textContent = msg;
  t.className = 'toast' + (err ? ' err' : '');
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.hidden = true; }, 3500);
}

function errText(e) {
  const c = e && (e.code || e.message || '');
  if (/PERMISSION_DENIED|permission/i.test(c)) return 'Nema dozvole. Odjavi se i prijavi ponovo.';
  if (/network|offline|unavailable/i.test(c)) return 'Nema interneta. Pokušaj ponovo.';
  if (/unauthorized-domain/.test(c)) return 'Ova adresa nije dozvoljena za prijavu (Firebase → Authentication → Settings → Authorized domains).';
  if (/operation-not-allowed/.test(c)) return 'Google prijava nije uključena u Firebase konzoli (Authentication → Sign-in method).';
  return (e && e.message) || 'Greška.';
}

/** Dugme za brisanje: prvi klik pita "Sigurno?", drugi brise. */
function armDelete(btn, action) {
  btn.addEventListener('click', async () => {
    if (!btn.classList.contains('armed')) {
      btn.classList.add('armed');
      btn.dataset.label = btn.textContent;
      btn.textContent = 'Sigurno?';
      setTimeout(() => {
        if (btn.isConnected && btn.classList.contains('armed')) {
          btn.classList.remove('armed');
          btn.textContent = btn.dataset.label;
        }
      }, 3000);
      return;
    }
    btn.disabled = true;
    try { await action(); } catch (e) { btn.disabled = false; toast(errText(e), true); }
  });
}

function busy(btn, on, label) {
  if (on) { btn.dataset.label = btn.textContent; btn.textContent = label || '…'; btn.disabled = true; }
  else { btn.textContent = btn.dataset.label || btn.textContent; btn.disabled = false; }
}

function listsArray() {
  return Object.entries(data.lists || {})
    .map(([id, v]) => ({ id, ...v }))
    .sort((a, b) => (a.t || 0) - (b.t || 0) || a.id.localeCompare(b.id));
}

function date(t) {
  if (!t) return '';
  const d = new Date(t);
  return `${d.getDate()}.${d.getMonth() + 1}.${d.getFullYear()}.`;
}

// ------------------------------------------------------------------ ekrani

function showSignedOut() {
  $('who').hidden = true;
  $('app').innerHTML = `
    <section class="card hero">
      <h1>Liste za tvoj TV</h1>
      <p>Ovdje dodaješ IPTV prijave i plejliste. Lumen TV ih sam preuzme, na televizoru ne kucaš ništa.</p>
      <button class="google" id="btn-login" type="button">Prijavi se Google nalogom</button>
      ${pendingCode ? `<p class="hint" style="margin-top:18px">Nakon prijave TV sa kodom <b>${esc(pendingCode)}</b> će se sam povezati.</p>` : ''}
    </section>`;
  $('btn-login').addEventListener('click', async (ev) => {
    busy(ev.target, true, 'Prijava…');
    try { await api.signIn(); } catch (e) { toast(errText(e), true); }
    busy(ev.target, false);
  });
}

function showSignedIn() {
  $('who').hidden = false;
  $('who-name').textContent = user.email;
  $('app').innerHTML = `
    <div id="banner"></div>

    <section class="card">
      <h2>Dodaj listu</h2>
      <p class="hint">Link sa korisnikom i lozinkom ide u „Moje prijave“, ostali linkovi u „Besplatni kanali“.</p>
      <div class="tabs" role="tablist">
        <button type="button" data-tab="link">Link</button>
        <button type="button" data-tab="login">Server i korisnik</button>
      </div>
      <form id="f-link" autocomplete="off">
        <label for="in-url">Link plejliste</label>
        <input id="in-url" inputmode="url" placeholder="http://server.com:8080/get.php?username=…  ili  https://…/lista.m3u">
        <label for="in-name1">Naziv <span class="muted">(nije obavezno)</span></label>
        <input id="in-name1" maxlength="100" placeholder="npr. Asterix">
        <div class="actions"><button class="primary" type="submit">Dodaj</button></div>
      </form>
      <form id="f-login" autocomplete="off" hidden>
        <label for="in-host">Server</label>
        <input id="in-host" inputmode="url" placeholder="http://server.com:8080">
        <label for="in-user">Korisničko ime</label>
        <input id="in-user" autocapitalize="off" spellcheck="false">
        <label for="in-pass">Lozinka</label>
        <input id="in-pass" type="password" autocomplete="new-password">
        <label for="in-name2">Naziv <span class="muted">(nije obavezno)</span></label>
        <input id="in-name2" maxlength="100">
        <div class="actions"><button class="primary" type="submit">Dodaj prijavu</button></div>
      </form>
    </section>

    <section class="card">
      <h2>Moje prijave <span class="count" id="c-logins"></span></h2>
      <p class="hint">Na TV-u: profil „Moje prijave“. Kanali iz svih prijava su zajedno.</p>
      <ul class="items" id="l-logins"></ul>
    </section>

    <section class="card">
      <h2>Linkovi bez prijave <span class="count" id="c-links"></span></h2>
      <p class="hint">Na TV-u: profil „Besplatni kanali“, zajedno sa balkanskim listama.</p>
      <ul class="items" id="l-links"></ul>
    </section>

    <section class="card">
      <h2>Televizori <span class="count" id="c-tvs"></span></h2>
      <p class="hint">Na TV-u otvori „Liste i sajt“ → „Poveži TV sa sajtom“ i skeniraj QR kod, ili upiši kod ovdje.</p>
      <ul class="items" id="l-tvs"></ul>
      <form class="row-form" id="f-pair" autocomplete="off">
        <input class="code" id="in-code" maxlength="6" placeholder="KOD" autocapitalize="characters" spellcheck="false" value="${esc(pendingCode)}">
        <button class="primary" type="submit">Poveži TV</button>
      </form>
    </section>

    <section class="card">
      <h2>Titlovi <span class="count">OpenSubtitles</span></h2>
      <p class="hint" id="subs-state"></p>
      <form id="f-subs" autocomplete="off">
        <label for="in-key">API ključ</label>
        <input id="in-key" spellcheck="false" placeholder="sa opensubtitles.com → API consumers">
        <label for="in-ouser">Korisničko ime <span class="muted">(nije obavezno, više titlova dnevno)</span></label>
        <input id="in-ouser" autocapitalize="off" spellcheck="false">
        <label for="in-opass">Lozinka</label>
        <input id="in-opass" type="password" autocomplete="new-password">
        <div class="actions">
          <button class="primary" type="submit">Sačuvaj</button>
          <button class="danger" type="button" id="btn-subs-del" hidden>Ukloni</button>
        </div>
      </form>
      <p class="hint" style="margin-top:14px">Besplatan ključ: napravi nalog na <a href="https://www.opensubtitles.com" target="_blank" rel="noopener">opensubtitles.com</a>, pa u „API consumers“ napravi novi ključ.</p>
    </section>`;

  document.querySelectorAll('.tabs button').forEach((b) => b.addEventListener('click', () => setTab(b.dataset.tab)));
  setTab(tab);
  $('f-link').addEventListener('submit', (e) => { e.preventDefault(); add('link', e.submitter || e.target.querySelector('button')); });
  $('f-login').addEventListener('submit', (e) => { e.preventDefault(); add('login', e.submitter || e.target.querySelector('button')); });
  $('f-pair').addEventListener('submit', (e) => { e.preventDefault(); pair($('in-code').value, e.target.querySelector('button')); });
  $('in-code').addEventListener('input', (e) => { e.target.value = cleanCode(e.target.value); });
  $('f-subs').addEventListener('submit', (e) => { e.preventDefault(); saveSubs(e.target.querySelector('button.primary')); });
  armDelete($('btn-subs-del'), async () => { await api.removeSubs(user.uid); toast('Titlovi uklonjeni.'); });
  renderData();
}

function setTab(t) {
  tab = t;
  document.querySelectorAll('.tabs button').forEach((b) => b.classList.toggle('on', b.dataset.tab === t));
  $('f-link').hidden = t !== 'link';
  $('f-login').hidden = t !== 'login';
}

function renderData() {
  if (!$('l-logins')) return;
  const all = listsArray();
  fillList('l-logins', 'c-logins', all.filter((x) => x.type === 'login'), 'Još nema prijava.');
  fillList('l-links', 'c-links', all.filter((x) => x.type !== 'login'), 'Nema linkova. Balkanske liste su na TV-u uvijek uključene.');

  const tvs = Object.entries(data.devices || {}).map(([id, v]) => ({ id, ...v })).sort((a, b) => (a.t || 0) - (b.t || 0));
  $('c-tvs').textContent = tvs.length ? tvs.length : '';
  const ul = $('l-tvs');
  ul.innerHTML = tvs.length ? '' : '<p class="empty">Nijedan TV nije povezan.</p>';
  for (const tv of tvs) {
    const li = document.createElement('li');
    li.innerHTML = `<div class="txt"><div class="name">${esc(tv.name || 'TV')}</div><div class="sub">povezan ${esc(date(tv.t))}</div></div><button class="danger" type="button">Odspoji</button>`;
    armDelete(li.querySelector('button'), async () => { await api.removeDevice(user.uid, tv.id); toast('TV odspojen. Liste će nestati sa njega.'); });
    ul.appendChild(li);
  }

  const s = data.subs;
  $('subs-state').textContent = s && s.key
    ? `Sačuvano: ključ ••••${s.key.slice(-4)}${s.user ? ', nalog ' + s.user : ', bez naloga (5 titlova dnevno)'}. Za promjenu upiši nove podatke.`
    : 'Nije podešeno. Bez ključa TV ne može tražiti titlove na internetu.';
  $('btn-subs-del').hidden = !(s && s.key);
}

function fillList(listId, countId, items, emptyText) {
  $(countId).textContent = items.length ? items.length : '';
  const ul = $(listId);
  ul.innerHTML = items.length ? '' : `<p class="empty">${esc(emptyText)}</p>`;
  for (const it of items) {
    const li = document.createElement('li');
    li.innerHTML = `<div class="txt"><div class="name">${esc(it.name)}</div><div class="sub">${esc(describe(it))}</div></div><button class="danger" type="button">Ukloni</button>`;
    armDelete(li.querySelector('button'), async () => { await api.removeList(user.uid, it.id); toast('Uklonjeno: ' + it.name); });
    ul.appendChild(li);
  }
}

function banner(text, ok) {
  const b = $('banner');
  if (!b) return;
  b.innerHTML = text ? `<div class="banner ${ok ? 'ok' : 'err'}">${esc(text)}</div>` : '';
}

// ------------------------------------------------------------------ radnje

async function add(kind, btn) {
  let item;
  try {
    item = kind === 'link'
      ? fromLink($('in-url').value, $('in-name1').value)
      : fromLogin($('in-host').value, $('in-user').value, $('in-pass').value, $('in-name2').value);
  } catch (e) {
    toast(e.message, true);
    return;
  }
  const existing = listsArray().find((x) => sameList(x, item));
  busy(btn, true, 'Dodajem…');
  try {
    if (existing && item.type === 'login') {
      const nameGiven = kind === 'link' ? $('in-name1').value.trim() : $('in-name2').value.trim();
      await api.updateList(user.uid, existing.id, { url: item.url, name: nameGiven ? item.name : existing.name });
      toast('Ova prijava je već bila dodana, podaci su ažurirani.');
    } else if (existing) {
      toast('Ovaj link je već dodan.');
    } else {
      await api.addList(user.uid, item);
      toast(item.type === 'login' ? 'Dodano u „Moje prijave“.' : 'Dodano u „Besplatni kanali“.');
    }
    for (const id of ['in-url', 'in-name1', 'in-user', 'in-pass', 'in-name2']) $(id).value = '';
  } catch (e) {
    toast(errText(e), true);
  }
  busy(btn, false);
}

async function pair(raw, btn) {
  const code = cleanCode(raw);
  if (code.length !== 6) { toast('Kod ima 6 znakova (vidi se na TV-u).', true); return; }
  if (btn) busy(btn, true, 'Povezujem…');
  try {
    const v = await api.readCode(code);
    if (!v || !v.tv) throw new Error('Kod ne postoji. Provjeri kod na TV-u.');
    if (v.claim) throw new Error('Ovaj kod je već iskorišten. Na TV-u otvori povezivanje ponovo.');
    if (v.t && Date.now() - v.t > CODE_TTL) throw new Error('Kod je istekao. Na TV-u otvori povezivanje ponovo.');
    const name = String(v.name || 'TV').slice(0, 80);
    await api.claim(user.uid, user.email || '', code, v.tv, name);
    banner(`TV „${name}“ je povezan. Za par sekundi na njemu se pojave tvoje liste.`, true);
    if ($('in-code')) $('in-code').value = '';
  } catch (e) {
    banner(e.message && !e.code ? e.message : errText(e), false);
  }
  if (btn) busy(btn, false);
}

async function saveSubs(btn) {
  const key = $('in-key').value.trim(), u = $('in-ouser').value.trim(), p = $('in-opass').value;
  if (key.length < 10) { toast('Upiši API ključ sa opensubtitles.com.', true); return; }
  if (!u !== !p) { toast('Upiši i korisničko ime i lozinku, ili ostavi oboje prazno.', true); return; }
  busy(btn, true, 'Čuvam…');
  try {
    const subs = { key };
    if (u) { subs.user = u; subs.pass = p; }
    await api.saveSubs(user.uid, subs);
    for (const id of ['in-key', 'in-ouser', 'in-opass']) $(id).value = '';
    toast('Sačuvano. TV ga preuzme sam.');
  } catch (e) {
    toast(errText(e), true);
  }
  busy(btn, false);
}

// ------------------------------------------------------------------ start

async function start() {
  try {
    api = params.has('demo') ? demoBackend() : await firebaseBackend();
  } catch (e) {
    $('app').innerHTML = '<p class="muted center">Sajt nije povezan sa Firebase-om. Objavi ga komandom <b>firebase deploy</b>.</p>';
    return;
  }
  $('btn-logout').addEventListener('click', () => api.signOut());
  api.onAuth((u) => {
    if (unwatch) { unwatch(); unwatch = null; }
    user = u;
    data = {};
    if (!u) { showSignedOut(); return; }
    showSignedIn();
    unwatch = api.watch(u.uid, (d) => { data = d; renderData(); }, (e) => toast(errText(e), true));
    if (pendingCode) {
      const c = pendingCode;
      pendingCode = '';
      history.replaceState(null, '', location.pathname + (params.has('demo') ? '?demo' : ''));
      pair(c, null);
    }
  });
}

start();

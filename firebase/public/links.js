// Prepoznavanje linkova (isto kao u aplikaciji na TV-u).
//  - get.php / player_api.php sa korisnikom i lozinkom  -> Xtream prijava  ("Moje prijave")
//  - svaki drugi http(s) link                          -> obicni M3U link ("Besplatni kanali")

/** Ocisti zalijepljen link: razmaci, "http://" ako fali, Facebook/Messenger preusmjerenje. */
export function cleanPasted(raw) {
  let s = String(raw == null ? '' : raw).trim().replace(/\s+/g, '');
  if (!s) return s;
  // bez sheme ("server.com/...") -> http://; druga shema (ftp://) ostaje i odbije se kasnije
  if (!/^[a-z][a-z0-9+.-]*:\/\//i.test(s)) s = 'http://' + s;
  try {
    const u = new URL(s);
    const h = u.hostname.toLowerCase();
    if ((h.endsWith('facebook.com') || h.endsWith('messenger.com')) && u.searchParams.get('u')) {
      let inner = u.searchParams.get('u').trim();
      inner = inner.replace(/[?&]fbclid=[^&]*/g, '');
      return cleanPasted(inner);
    }
  } catch (e) { /* nije ispravan link, provjerava se kasnije */ }
  return s;
}

/** {host, user, pass} iz Xtream linka, ili null. host = "http://server:port" */
export function parseXtream(url) {
  if (!url || !/(get\.php|player_api\.php)/.test(url)) return null;
  let u;
  try { u = new URL(url); } catch (e) { return null; }
  const user = u.searchParams.get('username');
  const pass = u.searchParams.get('password');
  if (!user || !pass) return null;
  return { host: u.protocol + '//' + u.host, user, pass };
}

/** Adresa servera iz upisanog teksta: "server.com:8080/nesto" -> "http://server.com:8080" */
export function normalizeHost(raw) {
  let s = String(raw == null ? '' : raw).trim().replace(/\s+/g, '');
  if (!s) throw new Error('Upiši adresu servera.');
  if (!/^https?:\/\//i.test(s)) s = 'http://' + s;
  let u;
  try { u = new URL(s); } catch (e) { throw new Error('Adresa servera nije ispravna.'); }
  if (!u.hostname) throw new Error('Adresa servera nije ispravna.');
  return u.protocol + '//' + u.host;
}

export function loginUrl(host, user, pass) {
  return host + '/get.php?username=' + encodeURIComponent(user) + '&password=' + encodeURIComponent(pass)
    + '&type=m3u_plus&output=ts';
}

function hostName(url) {
  try { return new URL(url).hostname || 'Lista'; } catch (e) { return 'Lista'; }
}

function cut(s, n) {
  s = String(s || '').trim();
  return s.length > n ? s.slice(0, n) : s;
}

/** Iz zalijepljenog linka napravi stavku za bazu: {type, name, url}. Baca Error sa porukom. */
export function fromLink(raw, name) {
  const url = cleanPasted(raw);
  if (!url) throw new Error('Zalijepi link plejliste.');
  let u;
  try { u = new URL(url); } catch (e) { throw new Error('Link nije ispravan.'); }
  if (!/^https?:$/.test(u.protocol) || !u.hostname) throw new Error('Link nije ispravan.');
  const x = parseXtream(url);
  if (x) return fromLogin(x.host, x.user, x.pass, name);
  if (url.length > 2000) throw new Error('Link je predug.');
  return { type: 'link', name: cut(name, 100) || hostName(url), url };
}

/** Iz servera, korisnika i lozinke. */
export function fromLogin(server, user, pass, name) {
  const host = normalizeHost(server);
  user = String(user || '').trim();
  pass = String(pass || '').trim();
  if (!user || !pass) throw new Error('Upiši korisničko ime i lozinku.');
  const url = loginUrl(host, user, pass);
  if (url.length > 2000) throw new Error('Podaci su predugi.');
  return { type: 'login', name: cut(name, 100) || cut(hostName(host) + ' · ' + user, 100), url };
}

/** Ista lista: prijava sa istim serverom i korisnikom, ili isti link. */
export function sameList(a, b) {
  if (a.type !== b.type) return false;
  if (a.type === 'login') {
    const x = parseXtream(a.url), y = parseXtream(b.url);
    return !!(x && y && x.host.toLowerCase() === y.host.toLowerCase() && x.user === y.user);
  }
  return a.url === b.url;
}

/** Opis bez lozinke: "server.com:8080 · korisnik" ili "server.com/putanja". */
export function describe(item) {
  const x = item.type === 'login' ? parseXtream(item.url) : null;
  if (x) return x.host.replace(/^https?:\/\//, '') + ' · ' + x.user;
  try {
    const u = new URL(item.url);
    return u.host + (u.pathname === '/' ? '' : u.pathname);
  } catch (e) { return ''; }
}

/** Kod sa TV-a: 6 znakova, velika slova (TV ne koristi O, 0, I i 1 da se ne mijesaju). */
export function cleanCode(raw) {
  return String(raw || '').toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 6);
}

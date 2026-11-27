// Klika kroz sajt u demo nacinu (headless Chrome preko DevTools protokola).
// node firebase/test/ui.demo.mjs  (sajt mora biti pokrenut: python3 -m http.server 8099 u firebase/public)
import { spawn } from 'node:child_process';
const chrome = spawn('google-chrome', ['--headless=new', '--no-sandbox', '--disable-gpu', '--remote-debugging-port=9333', 'about:blank'], { stdio: 'ignore' });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let fails = 0;
const ok = (c, w) => { console.log((c ? '  OK   ' : '  FAIL ') + w); if (!c) fails++; };
try {
  let target;
  for (let i = 0; i < 40 && !target; i++) {
    await sleep(250);
    try { target = (await (await fetch('http://127.0.0.1:9333/json')).json()).find((t) => t.type === 'page'); } catch (e) { }
  }
  const ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((r) => ws.addEventListener('open', r));
  let id = 0; const waiting = new Map();
  ws.addEventListener('message', (m) => { const d = JSON.parse(m.data); if (waiting.has(d.id)) { waiting.get(d.id)(d); waiting.delete(d.id); } });
  const send = (method, params = {}) => new Promise((r) => { const i = ++id; waiting.set(i, r); ws.send(JSON.stringify({ id: i, method, params })); });
  const js = async (expr) => (await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true })).result.result.value;
  const go = async (url) => { await send('Page.navigate', { url }); await sleep(1200); };
  const text = (sel) => js(`document.querySelector(${JSON.stringify(sel)})?.textContent ?? null`);
  const fill = (sel, v) => js(`(e => { e.value = ${JSON.stringify(v)}; e.dispatchEvent(new Event('input')); })(document.querySelector(${JSON.stringify(sel)}))`);
  const click = async (sel) => { await js(`document.querySelector(${JSON.stringify(sel)}).click()`); await sleep(250); };

  await go('http://127.0.0.1:8099/?demo&out');
  ok(await js(`document.getElementById('who').offsetParent === null`), 'odjavljen: nema "Odjava" gore');
  await click('#btn-login');
  ok((await text('#who-name')) === 'ti@gmail.com', 'prijava -> email gore');

  await fill('#in-url', 'http://51.159.99.103:25461/get.php?username=NxN&password=CQL&type=m3u_plus&output=mpegts');
  await click('#f-link button');
  ok((await text('#l-logins')).includes('51.159.99.103 · NxN') && (await text('#c-logins')) === '2', 'get.php link dodan u "Moje prijave"');
  ok((await text('#toast')).includes('Moje prijave'), 'poruka: ' + await text('#toast'));
  ok((await js(`document.getElementById('in-url').value`)) === '', 'polje ocisceno');

  await fill('#in-url', 'https://example.com/druga.m3u'); await fill('#in-name1', 'Druga');
  await click('#f-link button');
  ok((await text('#l-links')).includes('Druga') && (await text('#c-links')) === '2', 'M3U link dodan u "Linkovi bez prijave"');

  await fill('#in-url', 'https://example.com/druga.m3u');
  await click('#f-link button');
  ok((await text('#toast')).includes('već dodan') && (await text('#c-links')) === '2', 'isti link se ne dodaje dvaput');

  await click('[data-tab=login]');
  ok(await js(`document.getElementById('f-link').hidden && !document.getElementById('f-login').hidden`), 'kartica "Server i korisnik"');
  await fill('#in-host', 'asterix.example:25461'); await fill('#in-user', 'demo'); await fill('#in-pass', 'nova');
  await click('#f-login button');
  ok((await text('#toast')).includes('ažurirani') && (await text('#c-logins')) === '2', 'ista prijava, nova lozinka -> azurirano, ne duplirano');
  await fill('#in-host', 'server.com'); await fill('#in-user', ''); await fill('#in-pass', 'x');
  await click('#f-login button');
  ok((await text('#toast')).includes('korisničko'), 'bez korisnika -> poruka');

  await click('#l-logins li button');
  ok((await text('#l-logins li button')) === 'Sigurno?' && (await text('#c-logins')) === '2', 'prvi klik na Ukloni samo pita');
  await click('#l-logins li button'); await sleep(200);
  ok((await text('#c-logins')) === '1', 'drugi klik brise');

  await fill('#in-code', 'xyz-999');
  ok((await js(`document.getElementById('in-code').value`)) === 'XYZ999', 'kod se sam ispravi u velika slova');
  await click('#f-pair button'); await sleep(200);
  ok((await text('#banner')).includes('ne postoji'), 'nepostojeci kod -> poruka');
  await fill('#in-code', 'abc234');
  await click('#f-pair button'); await sleep(200);
  ok((await text('#banner')).includes('Sony Bravia') && (await text('#c-tvs')) === '2', 'kod ABC234 -> TV povezan');
  await fill('#in-code', 'ABC234');
  await click('#f-pair button'); await sleep(200);
  ok((await text('#banner')).includes('iskorišten'), 'isti kod drugi put -> vec iskoristen');

  await fill('#in-key', 'kratko'); await click('#f-subs button.primary');
  ok((await text('#toast')).includes('API ključ'), 'prekratak kljuc -> poruka');
  await fill('#in-key', 'ABCDEFGHIJ123456'); await fill('#in-ouser', 'nalog'); await fill('#in-opass', 'x'); await click('#f-subs button.primary');
  ok((await text('#subs-state')).includes('••••3456') && (await text('#subs-state')).includes('nalog') && !(await text('#subs-state')).includes('ABCDEFGH'), 'titlovi: kljuc sacuvan, prikazan samo kraj');

  await go('http://127.0.0.1:8099/?demo&out&kod=ABC234');
  await click('#btn-login'); await sleep(300);
  ok((await text('#banner')).includes('Sony Bravia'), 'QR link (?kod=) -> nakon prijave TV se poveze sam');
  ok(!(await js('location.search')).includes('kod'), 'kod uklonjen iz adrese');
  ws.close();
} finally {
  chrome.kill();
}
console.log(fails ? `\nPALO: ${fails}` : '\nSVE PROSLO');
process.exit(fails ? 1 : 0);

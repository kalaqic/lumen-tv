// node firebase/test/links.test.mjs
import { cleanPasted, parseXtream, fromLink, fromLogin, sameList, describe, cleanCode } from '../public/links.js';
let fails = 0;
const ok = (c, w) => { console.log((c ? '  OK   ' : '  FAIL ') + w); if (!c) fails++; };
const throws = (f, re) => { try { f(); return false; } catch (e) { return re.test(e.message); } };

const fb = 'https://l.facebook.com/l.php?u=http%3A%2F%2Fserver.example%3A2052%2Fget.php%3Fusername%3Dsanela08%26password%3D453275%26type%3Dm3u_plus%26output%3Dts%26fbclid%3DIwAR0abc&h=AT0x';
ok(cleanPasted(fb) === 'http://server.example:2052/get.php?username=sanela08&password=453275&type=m3u_plus&output=ts', 'Facebook link razmotan: ' + cleanPasted(fb));
ok(cleanPasted('  server.example:8080/get.php?username=a&password=b ') === 'http://server.example:8080/get.php?username=a&password=b', 'dodan http://, uklonjeni razmaci');

let it = fromLink('http://51.159.99.103:25461/get.php?username=NxN&password=CQL&type=m3u_plus&output=mpegts', '');
ok(it.type === 'login' && it.url === 'http://51.159.99.103:25461/get.php?username=NxN&password=CQL&type=m3u_plus&output=ts', 'get.php -> prijava, output=ts: ' + it.url);
ok(it.name === '51.159.99.103 · NxN', 'naziv: ' + it.name);
it = fromLink(fb, 'Sanela');
ok(it.type === 'login' && it.name === 'Sanela' && parseXtream(it.url).pass === '453275', 'Facebook get.php -> prijava sa nazivom');
it = fromLink('http://h:1/player_api.php?username=a%20b&password=p%26q', '');
ok(it.type === 'login' && parseXtream(it.url).user === 'a b' && parseXtream(it.url).pass === 'p&q' && it.url.includes('username=a%20b&password=p%26q'), 'player_api.php, posebni znakovi: ' + it.url);
it = fromLink('https://raw.githubusercontent.com/x/y/main/lista.m3u', '');
ok(it.type === 'link' && it.name === 'raw.githubusercontent.com', 'obicni M3U -> link');
it = fromLink('http://server.com/get.php?username=samo', '');
ok(it.type === 'link', 'get.php bez lozinke -> link');
ok(throws(() => fromLink('', ''), /Zalijepi/), 'prazno -> poruka');
ok(throws(() => fromLink('ftp://x.com/a.m3u', ''), /nije ispravan/), 'ftp -> nije ispravan');
ok(throws(() => fromLink('http://', ''), /nije ispravan/), 'samo http:// -> nije ispravan');

it = fromLogin('asterix-iptv.club:25461/neki/put', ' 111SelimS ', 'dmE9', '');
ok(it.type === 'login' && it.url === 'http://asterix-iptv.club:25461/get.php?username=111SelimS&password=dmE9&type=m3u_plus&output=ts', 'server/korisnik/lozinka: ' + it.url);
ok(throws(() => fromLogin('server.com', '', 'x', ''), /korisničko/), 'bez korisnika -> poruka');
ok(throws(() => fromLogin('', 'a', 'b', ''), /servera/), 'bez servera -> poruka');

const a = fromLogin('http://Server.com:80', 'u', 'p1', ''), b = fromLogin('http://server.com:80', 'u', 'p2', 'Novi');
ok(sameList(a, b), 'ista prijava (server + korisnik), druga lozinka');
ok(!sameList(a, fromLogin('server.com', 'u2', 'p', '')), 'drugi korisnik = druga prijava');
ok(sameList(fromLink('https://x.com/a.m3u', ''), fromLink('https://x.com/a.m3u', 'B')) && !sameList(fromLink('https://x.com/a.m3u', ''), fromLink('https://x.com/b.m3u', '')), 'isti link');
ok(describe(a) === 'server.com · u' && !describe(a).includes('p1'), 'opis bez lozinke: ' + describe(a));
ok(describe(fromLink('https://x.com:8080/a/b.m3u', '')) === 'x.com:8080/a/b.m3u', 'opis linka');
ok(cleanCode(' ab-c 23 4x') === 'ABC234', 'kod: velika slova, bez razmaka i crtica');
ok(fromLink('x'.repeat(5) + '.com/' + 'a'.repeat(150), 'n'.repeat(150)).name.length === 100, 'naziv najvise 100 znakova');

console.log(fails ? `\nPALO: ${fails}` : '\nSVE PROSLO');
process.exit(fails ? 1 : 0);

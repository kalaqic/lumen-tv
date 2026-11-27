# Lumen TV · sajt za liste

Na ovom sajtu dodaješ IPTV prijave i M3U linkove. TV ih sam preuzme, na televizoru se ništa ne kuca.

- Link sa korisnikom i lozinkom (`get.php`) → na TV-u u „Moje prijave“
- Ostali linkovi → na TV-u u „Besplatni kanali“ (zajedno sa balkanskim listama)
- Liste vide samo tvoj Google nalog i televizori koje si povezao.

## Podešavanje (jednom)

1. https://console.firebase.google.com → **Add project** (Analytics nije potreban). Zapamti **Project ID** (npr. `lumen-tv-1a2b3`).
2. **Build → Authentication → Get started → Sign-in method**: uključi **Google** i **Anonymous**.
3. **Build → Realtime Database → Create database**, lokacija **United States**, „Start in locked mode“.
4. Upiši Project ID na dva mjesta (umjesto `LUMEN_FIREBASE_PROJECT`):
   - `firebase/.firebaserc`
   - `app/src/main/res/values/firebase.xml`
5. Objavi sajt i pravila baze (u Terminalu):

   ```
   curl -sL https://firebase.tools | bash
   cd firebase
   firebase login
   firebase deploy
   ```

Sajt je onda na `https://<Project ID>.web.app`.

## Povezivanje TV-a

Na TV-u: **Liste i sajt → Poveži TV sa sajtom**. Skeniraj QR kod telefonom (ili otvori sajt i upiši kod od 6 znakova).

## Testovi

```
node test/links.test.mjs
cd public && python3 -m http.server 8099   # pa u drugom terminalu:
node test/ui.demo.mjs                       # klika kroz sajt (demo, bez Firebase-a)
```

Izgled bez Firebase-a: `http://localhost:8099/?demo`

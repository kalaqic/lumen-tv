# Lumen TV

IPTV plejer za Android TV / Google TV / Fire TV sa modernim, tamnim interfejsom.

- Učitava M3U / M3U8 plejliste (podrazumijevano `https://iptv-org.github.io/iptv/index.m3u`)
- Bočni meni: Svi kanali, Favoriti, Nedavno gledano, Kategorije, Države (sa zastavama)
- Pretraga kanala, logotipi kanala, oznake kvaliteta (720p, 1080p…)
- Plejer (ExoPlayer / Media3): HLS, DASH, MPEG-TS, podrška za `User-Agent` i `Referer` iz plejliste
- Više plejlista + brzo dodavanje (BiH, Hrvatska, Srbija, Crna Gora, Sport, Vijesti…)

## Kako dobiti APK (bez instaliranja ičega na računar)

1. Napravi besplatan nalog na **github.com** (ako ga nemaš).
2. Klikni **+ → New repository**, nazovi ga npr. `lumen-tv`, ostavi ga **Public** i klikni *Create repository*.
3. Na stranici novog repozitorija klikni **uploading an existing file**, pa prevuci **sav sadržaj** ovog foldera
   (folder `app`, `.github`, i fajlove `build.gradle`, `settings.gradle`, `gradle.properties`, `README.md`).
   Klikni **Commit changes**.
   - Ako se folder `.github` ne prikaže (skriveni folder), napravi ga ručno: *Add file → Create new file*,
     u ime upiši `.github/workflows/build.yml` i zalijepi sadržaj tog fajla.
4. Otvori karticu **Actions** – build kreće sam (traje ~3–5 min). Kad dobije zelenu kvačicu, APK je spreman.
5. Na TV-u otvori **Downloader** i ukucaj:

   ```
   https://github.com/TVOJE-IME/lumen-tv/releases/latest/download/LumenTV.apk
   ```

   (zamijeni `TVOJE-IME` svojim GitHub korisničkim imenom). Downloader će preuzeti i ponuditi instalaciju.

Svaka nova izmjena koda automatski pravi novu verziju – isti link uvijek vodi na najnoviju.

## Alternativa: Android Studio

Otvori ovaj folder u Android Studiju → *Build → Build APK(s)*.

## Upravljanje daljinskim

**Početni ekran**
- Strelice: kretanje kroz meni i kanale (kategorija se mijenja čim staneš na nju)
- OK: pokreni kanal · drži OK: dodaj/ukloni favorit
- Nazad: iz liste kanala vraća u meni / briše pretragu

**Plejer**
- ▲ / ▼ (ili CH+ / CH−): prethodni / sljedeći kanal
- OK: lista kanala sa strane · drži OK (ili Menu): favorit
- ◀ / ▶: prikaži info o kanalu
- Nazad: zatvori listu / izađi iz plejera

## Napomena

Aplikacija ne sadrži nikakve kanale – samo reprodukuje plejliste koje joj daš. Lista iptv-org sadrži
javno dostupne streamove; dio njih je povremeno offline ili geo-blokiran, što je normalno.

Font: Inter (SIL Open Font License).

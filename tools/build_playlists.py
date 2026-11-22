#!/usr/bin/env python3
"""
Pravi Lumen TV plejliste po drzavama (Balkan) od javnih, besplatnih streamova.

Izvori:
  - iptv-org/iptv      (streams/*.m3u)            https://github.com/iptv-org/iptv
  - iptv-org/database  (nazivi, kategorije, logo)  https://github.com/iptv-org/database
  - Free-TV/IPTV       (samo zvanicni besplatni)   https://github.com/Free-TV/IPTV

Upotreba:
  python3 tools/build_playlists.py [--src FOLDER_SA_KLONIRANIM_IZVORIMA]
Ako izvori nisu tu, skripta ih klonira (git clone --depth 1).
Rezultat: playlists/<kod>.m3u (ba, hr, rs, me, si, mk, xk)
"""
import argparse
import csv
import os
import re
import subprocess
import sys
from collections import OrderedDict

COUNTRIES = OrderedDict([
    ("BA", ("ba", "Bosna i Hercegovina", "bosnia_and_herzegovina")),
    ("HR", ("hr", "Hrvatska", "croatia")),
    ("RS", ("rs", "Srbija", "serbia")),
    ("ME", ("me", "Crna Gora", "montenegro")),
    ("SI", ("si", "Slovenija", "slovenia")),
    ("MK", ("mk", "Sjeverna Makedonija", "north_macedonia")),
    ("XK", ("xk", "Kosovo", "kosovo")),
])

REPOS = {
    "iptv": "https://github.com/iptv-org/iptv",
    "database": "https://github.com/iptv-org/database",
    "freetv": "https://github.com/Free-TV/IPTV",
}

ATTR = re.compile(r'([A-Za-z0-9_-]+)="([^"]*)"')
QUALITY = re.compile(r"\s*\((\d{3,4})[pi]\)")
TAGS = re.compile(r"\s*\[([^\]]*)\]")
MARKS = re.compile(r"\s*[ⓈⒼⓎⓂⓅ]")
UNPLAYABLE = ("rtsp://", "rtmp://", "rtp://", "udp://", "mms://")

# Free-TV koristi drugacije ID-eve za neke kanale nego iptv-org baza
ALIASES = {
    "FederalnaTV.ba": "Federalnatelevizija.ba",
    "N1BosniaHerzegovina.ba": "N1.ba",
    "N1Croatia.hr": "N1.hr",
    "N1Serbia.rs": "N1.rs",
}

# Placeni TV brendovi: nisu besplatni zvanicni streamovi, ne ulaze u liste
PAY_TV = ("nationalgeographic", "natgeo", "nickelodeon", "nickjr", "nicktoons", "scifi", "hbo", "cinemax",
          "discovery", "animalplanet", "eurosport", "arenasport", "sportklub", "cartoonnetwork", "disney",
          "foxcrime", "foxlife", "foxmovies", "axn", "cinestar", "pinkpremium", "pinkaction", "pinkfilm",
          "pinkmovies", "pinkcrime", "pinkthriller", "pinkhorror", "pinkromance", "pinkscifi", "pinkkids",
          "pinkfamily", "pinkcomedy", "pinkserije", "pinksoap", "pinkextra", "filmbox", "viasat", "tv1000",
          "amc", "historychannel", "cbsreality", "babytv", "minimax", "jimjam", "travelchannel", "foodnetwork",
          "tlc", "investigationdiscovery", "history", "h2hd", "h2", "bbcearth", "starchannel", "starcrime", "starlife", "starmovies")

# Najvazniji kanali svake drzave idu na vrh (po pocetku normalizovanog imena)
PRIORITY = {
    "BA": ["bht1", "bhrt", "federalna", "rtrs", "n1", "hayat", "facetv", "obn", "tvsa", "novabh", "pinkbh",
           "bntv", "rtvhb", "alternativna", "rtvtk", "rtvzenica", "rtvusk", "rtvbpk"],
    "HR": ["hrt1", "hrt2", "hrt3", "hrt4", "hrtinternational", "rtl", "novatv", "n1", "rtl2", "sportskatv",
           "doma", "otv", "kanalri", "stv"],
    "RS": ["rts1", "rts2", "rts3", "rtssvet", "rtv1", "rtv2", "n1", "nova", "prva", "b92", "o2", "happy",
           "kurir", "informer", "redtv"],
    "ME": ["tvcg1", "tvcg2", "tvcg3", "tvcgmne", "vijesti", "prva", "nova", "televizijae"],
    "SI": ["tvslo1", "tvslo2", "tvslo3", "pop", "kanala", "planet"],
    "MK": ["mrt1", "mrt2", "mrt3", "mrt4", "mrt5", "sitel", "kanal5", "telma", "alsat", "alfa"],
    "XK": ["rtk1", "rtk2", "rtk3", "rtk4", "klankosova", "ktv", "rtv21"],
}


def ensure_sources(src):
    paths = {}
    for key, url in REPOS.items():
        d = os.path.join(src, key)
        if not os.path.isdir(d):
            print("Kloniram", url, file=sys.stderr)
            subprocess.check_call(["git", "clone", "-q", "--depth", "1", url, d])
        paths[key] = d
    return paths


def parse_m3u(path):
    """Vraca listu dict-ova: name, url, attrs, ua, ref."""
    out, cur = [], None
    with open(path, encoding="utf-8", errors="replace") as f:
        for raw in f:
            line = raw.strip().lstrip("﻿")
            if not line:
                continue
            if line.startswith("#EXTINF"):
                head = line[line.index(":") + 1:]
                q, comma = False, -1
                for i, ch in enumerate(head):
                    if ch == '"':
                        q = not q
                    elif ch == "," and not q:
                        comma = i
                        break
                attrs = dict((k.lower(), v) for k, v in ATTR.findall(head[:comma] if comma >= 0 else head))
                name = head[comma + 1:].strip() if comma >= 0 else ""
                cur = {"name": name, "attrs": attrs, "ua": attrs.get("http-user-agent"),
                       "ref": attrs.get("http-referrer")}
            elif line.startswith("#EXTVLCOPT:"):
                if cur is not None and "=" in line:
                    k, v = line[11:].split("=", 1)
                    k = k.strip().lower()
                    if k == "http-user-agent":
                        cur["ua"] = v.strip()
                    elif k in ("http-referrer", "http-referer"):
                        cur["ref"] = v.strip()
            elif line.startswith("#"):
                continue
            else:
                if cur is not None:
                    cur["url"] = line
                    out.append(cur)
                cur = None
    return out


def clean_name(name):
    """Vraca (cisto_ime, kvalitet, geo, not247)."""
    geo = "Ⓖ" in name
    tags = [t.lower() for t in TAGS.findall(name)]
    geo = geo or any("geo" in t for t in tags)
    not247 = any("24/7" in t for t in tags)
    m = QUALITY.search(name)
    quality = int(m.group(1)) if m else None
    n = QUALITY.sub("", name)
    n = TAGS.sub("", n)
    n = MARKS.sub("", n)
    return n.strip(), quality, geo, not247


def norm(s):
    s = re.sub(r"\(.*?\)", "", s.lower())
    return re.sub(r"[^a-z0-9]", "", s)


def is_pay_tv(name):
    n = norm(name)
    return any(n.startswith(p) for p in PAY_TV)


def priority(cc, name):
    n = norm(name)
    for i, p in enumerate(PRIORITY.get(cc, [])):
        if n.startswith(p):
            return i
    return 1000


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default=os.path.join(os.path.dirname(__file__), ".sources"))
    ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "..", "playlists"))
    args = ap.parse_args()
    os.makedirs(args.src, exist_ok=True)
    os.makedirs(args.out, exist_ok=True)
    src = ensure_sources(args.src)

    # ---------------------------------------------------------- baza kanala
    data = os.path.join(src["database"], "data")
    channels = {}
    with open(os.path.join(data, "channels.csv"), encoding="utf-8") as f:
        for r in csv.DictReader(f):
            channels[r["id"]] = r
    blocked = set()
    with open(os.path.join(data, "blocklist.csv"), encoding="utf-8") as f:
        for r in csv.DictReader(f):
            blocked.add(r["channel"])
    logos = {}
    with open(os.path.join(data, "logos.csv"), encoding="utf-8") as f:
        for r in csv.DictReader(f):
            ch = r["channel"]
            score = (r.get("feed", "") == "", r.get("in_use", "").upper() == "TRUE",
                     r.get("format", "").upper() in ("PNG", "SVG", "WEBP"))
            if ch not in logos or score > logos[ch][0]:
                logos[ch] = (score, r["url"])

    def ok_channel(cid):
        r = channels.get(cid)
        if not r:
            return True  # nepoznat id: zadrzi
        if cid in blocked or r.get("is_nsfw", "").upper() == "TRUE" or r.get("closed"):
            return False
        return True

    # ---------------------------------------------------------- streamovi
    # kljuc kanala -> {info, streams[]}
    per_country = {cc: OrderedDict() for cc in COUNTRIES}

    seen_urls = {cc: set() for cc in COUNTRIES}

    def add(cc, key, info, stream):
        if stream["url"] in seen_urls[cc]:
            return
        seen_urls[cc].add(stream["url"])
        bucket = per_country[cc]
        if key not in bucket:
            bucket[key] = {"info": info, "streams": [], "urls": set()}
        e = bucket[key]
        if stream["url"] in e["urls"]:
            return
        e["urls"].add(stream["url"])
        e["streams"].append(stream)
        for k, v in info.items():  # dopuni prazna polja
            if v and not e["info"].get(k):
                e["info"][k] = v

    def playable(url):
        u = url.lower()
        return u.startswith("http") and not u.startswith(UNPLAYABLE) and "youtube.com" not in u and "youtu.be" not in u

    # 1) Free-TV (zvanicni besplatni) – prvi, njihov redoslijed
    for cc, (code, label, ftv) in COUNTRIES.items():
        path = os.path.join(src["freetv"], "playlists", "playlist_%s.m3u8" % ftv)
        if not os.path.exists(path):
            continue
        for order, e in enumerate(parse_m3u(path)):
            if not playable(e["url"]) or "Ⓨ" in e["name"]:
                continue
            name, quality, geo, not247 = clean_name(e["attrs"].get("tvg-name") or e["name"])
            if "Ⓢ" not in e["name"] and quality is None:
                quality = 720  # Free-TV: bez oznake Ⓢ = HD
            geo = geo or "Ⓖ" in e["name"]
            cid = e["attrs"].get("tvg-id", "").split("@")[0]
            cid = ALIASES.get(cid, cid)
            if (cid and not ok_channel(cid)) or is_pay_tv(name):
                continue
            key = cid or norm(name)
            add(cc, key, {"id": cid, "name": name, "logo": e["attrs"].get("tvg-logo"), "order": order,
                          "official": True},
                {"url": e["url"], "quality": quality, "geo": geo, "not247": not247,
                 "ua": e["ua"], "ref": e["ref"], "official": True})

    # 2) iptv-org – svi fajlovi, kanal se svrstava po drzavi iz baze
    sdir = os.path.join(src["iptv"], "streams")
    for fn in sorted(os.listdir(sdir)):
        if not fn.endswith(".m3u"):
            continue
        file_cc = fn.split("_")[0].split(".")[0].upper()
        for e in parse_m3u(os.path.join(sdir, fn)):
            if not playable(e["url"]):
                continue
            tid = e["attrs"].get("tvg-id", "")
            cid = tid.split("@")[0]
            ch = channels.get(cid)
            cc = ch["country"] if ch else file_cc
            if cc not in COUNTRIES or (cid and not ok_channel(cid)):
                continue
            name, quality, geo, not247 = clean_name(e["name"])
            if is_pay_tv(name) or (ch and is_pay_tv(ch["name"])):
                continue
            key = cid or norm(name)
            # ako Free-TV vec ima isti kanal pod istim imenom, spoji
            if not cid:
                for k, v in per_country[cc].items():
                    if norm(v["info"]["name"]) == key:
                        key = k
                        break
            add(cc, key, {"id": cid, "name": (ch["name"] if ch else name), "logo": None,
                          "order": 10_000, "official": False},
                {"url": e["url"], "quality": quality, "geo": geo, "not247": not247,
                 "ua": e["ua"], "ref": e["ref"], "official": False})

    # ---------------------------------------------------------- pisanje
    summary = []
    for cc, (code, label, _) in COUNTRIES.items():
        bucket = per_country[cc]
        items = list(bucket.values())
        for it in items:
            cid = it["info"]["id"]
            if cid in channels:
                it["info"]["name"] = channels[cid]["name"] or it["info"]["name"]
                cats = channels[cid].get("categories") or ""
                it["info"]["cat"] = cats.split(";")[0] if cats else ""
            if cid in logos:
                it["info"]["logo"] = logos[cid][1]
            # najbolji stream prvi: zvanicni, bez geo-blokade, 24/7, veci kvalitet
            it["streams"].sort(key=lambda s: (not s["official"], s["geo"], s["not247"], -(s["quality"] or 0)))
        items.sort(key=lambda it: (priority(cc, it["info"]["name"]), it["info"]["order"], norm(it["info"]["name"])))

        path = os.path.join(args.out, code + ".m3u")
        n_streams = 0
        with open(path, "w", encoding="utf-8") as f:
            f.write("#EXTM3U\n")
            f.write("# Lumen TV · %s · javni besplatni streamovi\n" % label)
            f.write("# Izvori: iptv-org/iptv, iptv-org/database, Free-TV/IPTV\n")
            for it in items:
                info = it["info"]
                cid = info.get("id") or ""
                cat = (info.get("cat") or "general").capitalize()
                for s in it["streams"]:
                    name = info["name"]
                    if s["quality"]:
                        name += " (%dp)" % s["quality"]
                    if s["geo"]:
                        name += " [Geo-blocked]"
                    if s["not247"]:
                        name += " [Not 24/7]"
                    attrs = []
                    if cid:
                        attrs.append('tvg-id="%s"' % cid)
                    attrs.append('tvg-country="%s"' % cc)
                    if info.get("logo"):
                        attrs.append('tvg-logo="%s"' % info["logo"])
                    attrs.append('group-title="%s"' % cat)
                    f.write("#EXTINF:-1 %s,%s\n" % (" ".join(attrs), name))
                    if s["ref"]:
                        f.write("#EXTVLCOPT:http-referrer=%s\n" % s["ref"])
                    if s["ua"]:
                        f.write("#EXTVLCOPT:http-user-agent=%s\n" % s["ua"])
                    f.write(s["url"] + "\n")
                    n_streams += 1
        summary.append((code, label, len(items), n_streams))

    for code, label, nc, ns in summary:
        print("%s  %-22s %3d kanala, %3d linkova" % (code, label, nc, ns))


if __name__ == "__main__":
    main()

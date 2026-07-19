#!/usr/bin/env python3
"""
Regenerates app/src/main/assets/base_stats.csv from the public pvpoke GameMaster.

Build-time only. The APK never fetches anything: this script runs on a dev machine and
the resulting CSV ships inside the app, which is what keeps PokeBuddy fully offline.

    python scripts/make_base_stats.py [gamemaster.json]

Downloads the GameMaster if no local path is given.

Writes two assets:

base_stats.csv — key,dex,name,atk,def,hp,types,family,fast,charged
  key      normalized speciesName (lowercase alphanumerics) — the lookup key
  name     display name, e.g. "Raichu (Alolan)"
  types    pipe-separated, "none" dropped: "electric|psychic" or "electric"
  family   evolution family, candy is shared across it (FAMILY_ prefix stripped)
  fast     pipe-separated move ids the species can learn
  charged  pipe-separated move ids the species can learn

moves.csv — id,name,type,power,energy,energyGain,cooldown,turns
  Move stats are pvpoke's PVP values. Pokemon GO uses DIFFERENT numbers for PvE
  (raids/gyms), so anything ranked off these is an approximation outside PvP and
  should be presented as such.
"""
import json
import re
import sys
import urllib.request

URL = "https://raw.githubusercontent.com/pvpoke/pvpoke/master/src/data/gamemaster.json"
OUT = "app/src/main/assets/base_stats.csv"
OUT_MOVES = "app/src/main/assets/moves.csv"


def normalize(name):
    return re.sub(r"[^a-z0-9]", "", name.lower())


def main():
    if len(sys.argv) > 1:
        raw = open(sys.argv[1], encoding="utf-8").read()
    else:
        raw = urllib.request.urlopen(URL).read().decode("utf-8")
    data = json.loads(raw)

    rows = {}
    for p in data["pokemon"]:
        species_id = p.get("speciesId", "")
        # Shadows share their base form's stats and would collide on the same key.
        if species_id.endswith("_shadow"):
            continue
        stats = p.get("baseStats") or {}
        if not all(k in stats for k in ("atk", "def", "hp")):
            continue
        name = p.get("speciesName", "")
        key = normalize(name)
        if not key:
            continue
        types = "|".join(t for t in p.get("types", []) if t and t != "none")
        family = (p.get("family") or {}).get("id", "").replace("FAMILY_", "")
        # A comma would silently shift every later column.
        assert "," not in name, f"comma in speciesName: {name!r}"
        rows[key] = (
            key, p.get("dex", 0), name,
            stats["atk"], stats["def"], stats["hp"], types, family,
            "|".join(p.get("fastMoves", [])),
            "|".join(p.get("chargedMoves", [])),
        )

    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write("key,dex,name,atk,def,hp,types,family,fast,charged\n")
        for key in sorted(rows):
            f.write(",".join(str(c) for c in rows[key]) + "\n")

    moves = {}
    for m in data.get("moves", []):
        mid = m.get("moveId")
        if not mid:
            continue
        assert "," not in m.get("name", ""), f"comma in move name: {m['name']!r}"
        moves[mid] = (
            mid, m.get("name", mid), m.get("type", ""), m.get("power", 0),
            m.get("energy", 0), m.get("energyGain", 0),
            m.get("cooldown", 0), m.get("turns", 0),
        )

    with open(OUT_MOVES, "w", encoding="utf-8", newline="\n") as f:
        f.write("id,name,type,power,energy,energyGain,cooldown,turns\n")
        for mid in sorted(moves):
            f.write(",".join(str(c) for c in moves[mid]) + "\n")

    typed = sum(1 for r in rows.values() if r[6])
    withmoves = sum(1 for r in rows.values() if r[8])
    print(f"wrote {len(rows)} species to {OUT} ({typed} typed, {withmoves} with moves)")
    print(f"wrote {len(moves)} moves to {OUT_MOVES}")


if __name__ == "__main__":
    main()

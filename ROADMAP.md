# PokeBuddy — roadmap and working notes

Living document. Update it in the same commit as the work it describes.

Scope and hard constraints live in [README.md](README.md); this file tracks what's **done**,
what's **next**, what's **deferred and why**, and the **decisions** that would otherwise have
to be rediscovered.

---

## Built and verified on device

| Area | State |
|---|---|
| MediaProjection capture + ML Kit OCR | PoGO does not set FLAG_SECURE; screen reads cleanly |
| IV decoder | Exact CP/HP formulas, validated against Calcy IV |
| Appraisal reader | Pixel-calibrated, fixture-tested |
| Auto-appraisal | Accessibility-driven, menu → exact IV in ~5s |
| Local index (Room) | Dedup on rescan, IV% rank within species |
| Form disambiguation | By type row — confirmed necessary on real hardware |
| Candy / mega energy | Family-keyed candy; one row per mega variant |
| Movesets | Fast + charged (incl. 2nd charged), attached across scroll positions |
| Type chart + counters | `bestInGame` / `fromInventory`, verified against PokeAPI |
| Overlay | Draggable, closable, rescan button, app-scoped, auto-following |

---

## Next up

1. **Box scan — semi-manual**: user scrolls, app OCRs each page, dedups, upserts.
2. **Box scan — auto-navigate**: accessibility drives the scrolling.
3. **Filter rescan** via PoGO's own search field (approved: app may type into it).
   Search supports `shiny`, `mega`, names, types — far more robust than scrolling the whole
   box and filtering ourselves.
4. **Counters in the overlay**: data layer is done; needs UI and a decision on presentation.
5. **`megaLevelUnlocked`**: not on the detail screen. Sources to sample — Raichu
   (multi-mega), Steelix (single), Beedrill (on cooldown), Absol (insufficient energy).
6. **Shiny**: no DB column yet; need a capture to see how shiny is marked visually.
   Search `shiny` lists them.

## Deferred, with reasons

- **Egg-hatch hooks** — needs a hatch to happen on demand; not practical to sit and wait.
  Revisit when an egg is close.
- **Trade-result hooks** — needs a live trade with a second account. User will prompt when
  ready to run one.
- **Raid/PvE accuracy** — move stats come from pvpoke and are **PvP** values. PoGO uses
  different numbers for raids/gyms, so counter scores are a relative ordering, not truth.
  Fixing this needs a PvE move source.
- **Battle simulation** — explicit non-goal. Counters rank offensive output only: no
  defender attacks, dodging, breakpoints or shields.

---

## Decisions worth not relitigating

- **IV % over the a/d/s split.** % depends only on the stat sum, so it resolves far more
  often. Panels are percent-first. Ranking is **by IV% within a species only** — CP ranking
  was explicitly rejected as irrelevant.
- **Never present a guess as certain.** Unresolved decodes show a candidate set or a
  percentage range. Indistinguishable forms (Deoxys' four are all pure psychic) resolve to
  null rather than a coin flip.
- **Megas are excluded** from form disambiguation and counter recommendations — a transient
  battle state, not something you own or can send into a fight.
- **Candy is family-keyed**, mega energy is **species+variant keyed** (one row per variant,
  so a species can have any number without a schema change).
- **Screen following is pixel-based.** PoGO is Unity: internal navigation fires no
  accessibility events, so `ScreenWatcher` fingerprints frames and OCRs only on change.
- **Assets are generated, not hand-written.** `scripts/make_base_stats.py` regenerates
  `base_stats.csv` and `moves.csv` from the public GameMaster. The app never hits the
  network; only the generator does, on a dev machine.

---

## Traps that have already cost time

- **`adb shell am force-stop com.pokebuddy` wipes the accessibility toggle**, which cannot be
  re-enabled over adb on this OnePlus (`WRITE_SECURE_SETTINGS` blocked). Re-arm by launching
  MainActivity instead. `adb install -r` is safe — but repeated reinstalls have wiped it too,
  so check `dumpsys accessibility` when automation mysteriously does nothing.
- **Watch mode must not depend on accessibility.** It needs only MediaProjection and overlay
  permission; gating it on the gesture service made it silently do nothing.
- **MediaProjection consent cannot persist** — re-arm every session (Android design).
- **Identity and moveset are never on screen together.** Scrolling to the move rows scrolls
  name/CP off the top; moves attach to the last identified Pokémon.
- **The map parses as a grid tile.** Trainer name + level looked like a Pokémon; grid tiles
  must name a real species.
- **Fixtures contain personal data.** Real captures embed location, catch dates and trainer
  name. Scrub before committing; full-screen PNGs are gitignored under test resources.

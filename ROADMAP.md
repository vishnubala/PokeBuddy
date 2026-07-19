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

### Known hole: identity is not stable

Rows are matched on **species + CP + maxHP** (`OwnedPokemonDao.findMatch`). That holds for a
rescan or a move purchase, but it is not an identity — it's a fingerprint of mutable values:

| Action | Result today | Correct? |
|---|---|---|
| Rescan the same Pokémon | updates the row | yes |
| Buy a 2nd charged move | updates the row (CP/HP unchanged) | yes |
| **Power up** | CP and HP both change → **new duplicate row** | no |
| **Evolve** | species, CP and HP change → **new duplicate row** | no |
| Two genuinely identical Pokémon | collapse into one row | no (undercount) |

PoGO exposes no id on screen, so a true key isn't available. The best proxy is
**weight + height**: both are per-individual constants fixed at catch, unchanged by powering
up, and already parsed into `DetailInfo`. Species + weight + height would survive power-ups
and separate near-identical Pokémon. Catch date/location is a further tiebreaker, at the cost
of storing personal data in the index.

Worth fixing BEFORE the first full box scan, since a scan followed by normal play would
otherwise accumulate duplicates.

### Variants the type row cannot separate

Form disambiguation uses the on-screen type row, which works for regional forms (Alolan
Grimer is poison/dark vs plain poison). It **fails** where a variant shares its base form's
typing:

| Variant | Types | Attack | Problem |
|---|---|---|---|
| Mewtwo vs **Mewtwo (Armored)** | both `psychic` | 300 vs **182** | huge stat gap, identical types |
| Dialga vs **Dialga (Origin)** | both `steel/dragon` | 275 vs 270 | identical types |
| Palkia vs **Palkia (Origin)** | both `water/dragon` | 280 vs 286 | identical types |
| Pikachu costumes (Libre, Flying, …) | all `electric` | all 112 | **stats identical — IV maths unaffected** |

Costumes are therefore harmless for correctness; they only matter for labelling. Armored and
Origin forms are not: picking the wrong one produces a confidently wrong IV. Today `resolve`
returns null for these and the panel says "Which form?", which is honest but unhelpful.

**Proposed fix — disambiguate by feasibility.** `IvDecoder` already rejects a species whose
CP+HP admit no valid IV solution (the guard behind `CpResolver`). Run the candidate forms
through it and keep the ones that are actually solvable: Mewtwo at 300 attack and Armored at
182 will rarely both explain the same CP/HP. Where more than one survives, keep reporting the
ambiguity rather than guessing.

### Sprite image matching — considered, not recommended first

Could we identify variants from the sprite instead of the type row? Technically yes; the
frame is already in hand and the sprite sits in a known region. But:

- PoGO renders an **animated 3D model**, not a fixed 2D sprite. Pose, lighting and framing
  shift between frames, which is exactly what perceptual hashing is bad at.
- It needs a reference set. Hashes themselves are tiny (a few bytes each, so all species and
  forms would fit comfortably), but generating them means sourcing artwork per form, and
  matching a 3D pose against 2D reference art is the weak link, not storage.

**UI markers are the better first move.** Shiny, lucky and Dynamax-capable all show dedicated
on-screen chrome (sparkle icon, background treatment, a Dynamax button). Detecting a fixed
UI element in a known region is far more robust than matching a rendered model, and it's the
same technique already proven on the appraisal bars. Reserve sprite matching for cases with
no UI marker at all — Armored Mewtwo being the likely one.

### Dynamax capability

**Not in pvpoke's GameMaster** — zero entries carry any dynamax/gigantamax tag, so it cannot
be derived from the current data source. Two options:

1. Read it off the screen (PoGO shows Dynamax UI on capable Pokémon) — consistent with how
   everything else here works, and needs no new data source.
2. Add a second data source (PokeMiners' GAME_MASTER carries the flags) — more data to keep
   in sync, and a species-level capability rather than a per-Pokémon fact.

Option 1 preferred, and it folds into the same UI-marker work as shiny/lucky.

Unused metadata already in the GameMaster that may be worth carrying later:
`legendary`, `mythical`, `ultrabeast`, `regional`, `starter`, `untradeable`, `shadoweligible`.

### Tracked flags (requested, not built)

`shiny`, `lucky`, `special background` (and Armored/costume labelling) need DB columns and a
detection method. Shiny and lucky are visual markers rather than text, so they likely need
pixel checks like the appraisal bars, not OCR. Captures needed before designing this.

## Settings, backup and export

Requested; not built yet.

- **Settings page** to configure the overlay — poll interval, auto-appraise on/off, panel
  position/size, which fields to show, scan mode (semi-manual vs auto-navigate).
- **Export settings** as a backup file, re-importable after a reinstall.
- **Export the DB** once the schema is settled, so the index survives reinstalling PokeBuddy
  or PoGO. Deliberately AFTER the schema stabilises — exporting a format still in flux
  produces backups that can't be restored.
  - Export must be explicit and user-initiated. The DB contains catch locations and dates
    (personal data), so it is never uploaded anywhere: local file only, and never committed.
  - Import needs a schema version in the file and a migration path, or old backups become
    unrestorable — the same discipline as the Room migrations.

**Order matters here**: box scan comes last, after the features are built, scanning is
ironed out, and the DB is robust. A scan writing into an unstable schema just creates data
that has to be thrown away.

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

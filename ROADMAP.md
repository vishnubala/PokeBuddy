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
| Tracked flags | `lucky` / `dynamax` / size badge, live-verified into the DB |
| Mega level | Panel parsed; stored per species+variant, declined when ambiguous |
| Form disambiguation | Type row **plus feasibility** — Armored Mewtwo, Origin formes, Kyurem |
| Settings | Spec-driven screen + backup codec; overlay and scan options live |

---

## Next up

1. **Variant feasibility fix** (see "Variants the type row cannot separate").
2. **Settings page** (see "Settings, backup and export").
3. **Power-up duplicate test** on the lucky Exeggcute — authorised to spend dust/candy.
   Confirms identity survives a real power-up rather than only in theory.
4. **Counters in the overlay**: data layer is done; needs UI and a decision on presentation.
5. **Shiny detection**, which belongs to the grid path — see below.
6. **Box scan** — semi-manual, then auto-navigate. LAST, after the schema settles.
   **Filter rescan** via PoGO's own search field is approved (the app may type into it) and
   is far more robust than scrolling the whole box: `shiny`, `lucky`, `mega`, `dynamax` all
   work as queries.

### How PoGO marks each flag — measured, 2026-07-19

Captured on device across shiny / lucky / mega / dynamax screens. The headline is that
**almost none of this needed pixel work**: only shiny does, and not where expected.

| Flag | Detail screen | Box grid |
|---|---|---|
| **lucky** | text `LUCKY POKÉMON` under the name | golden bubble tile background |
| **dynamax** | text `Dynamax` in its own row | pink crossed-X glyph on the sprite |
| **mega (active)** | name reads `Mega Raichu X`; `07:57 left` beside the DNA icon | name carries the prefix |
| **mega (cooling down)** | `7 DAYS` beside the DNA icon | — |
| **size badge** | `LIGHTEST` / `HEAVIEST` / `TALLEST` **replacing** the WEIGHT/HEIGHT label | — |
| **shiny** | **nothing at all** | teal sparkles top-left of the sprite |

**Shiny IS on the detail screen** — sparkles scattered across the background (user-confirmed;
an earlier note here claimed otherwise and was wrong). The mistake is worth keeping: a
block-mean pixel diff between a shiny and non-shiny Pikachu showed almost nothing, because
the sparkles are large, soft and low-contrast, and averaging over blocks erased them. **Don't
conclude "no marker" from a mean-difference test.**

It is nonetheless **not yet detectable reliably**, and `OwnedPokemon.shiny` stays nullable
with nothing writing it. A measured sparkle profile: peak luminance ~116 against a ~20
background, falling to background by ~20px — a soft local blob, not a resolvable 4-point
star at this resolution. Three detector shapes were tried and all failed to separate shiny
from plain:

| Approach | Why it failed |
|---|---|
| Global bright-pixel count | Backgrounds differ hugely in brightness (Exeggcute's is bright, Pikachu's near-black) |
| Axis-vs-diagonal star shape | At the real scale the arms aren't separable from the core |
| Local blob vs dark ring | Ordinary backgrounds are full of bokeh and star specks — shiny Pikachu scored 68, plain Pikachu 64 |

The blocker is that plain backgrounds already contain bright specks, so a single static frame
can't separate them. **The promising angle is temporal**: shiny sparkles animate, while
background bokeh is largely static, and `ScreenWatcher` already holds multiple frames. Per-
pixel variance across frames in the background band is the next thing to try. Failing that,
the **grid marker is unambiguous** (teal sparkles on the tile sprite, verified visually) and
needs no calibration — which is another reason shiny detection belongs to the box scan.

**The favourite star is not a rarity marker** (it is just the favourite toggle), and neither
is the gold star-with-Pokéball at the left edge — that one is PokeBuddy's own overlay
bleeding into its own capture. Both are tempting false positives in exactly the region a
badge would occupy.

### `megaLevelUnlocked` — done, with one honest gap

Not on the detail screen (confirmed by scrolling a mega-evolved Raichu top to bottom). It
lives on a separate panel reached by tapping the DNA icon, titled `<Species>'s Mega Level`,
with a `Base Level` / `High Level` / `Max Level` banner — all text, so `MegaLevelParser`
reads it without pixels.

Two things fell out of the capture that shape the schema:

- The panel is **species-scoped**, not per-individual ("Raichu's Mega Level"), and where a
  species has several megas it shows one tab per variant. So the level is keyed by
  **species + variant** — exactly the key `mega_energy` already had, which is why
  `megaLevel` is a column there rather than on `owned_pokemon`.
- **Which tab is selected is a fill colour**, and both tabs OCR as plain text. For a
  multi-mega species the parser therefore reports both tabs and a **null variant**, and the
  service declines to store rather than attach Raichu's level to the wrong mega. Resolving
  it needs a colour check behind the tab label — the same anchor-then-sample technique as
  `AppraisalReader`. Not built; one capture is too thin to calibrate against.

Also unverified: the single-variant store path. Steelix is single-mega but has never been
mega evolved, and **the level panel is only reachable once a species has been mega evolved
at least once** — an un-evolved Steelix shows three grey pips and no panel. (Pip count also
varies by species: Steelix 3, Raichu 4. The level is read from the banner text, not counted.)

### Identity: fixed, with a bug the flag work uncovered

Rows match on species + weight + height (+ catch date/location as tiebreakers) — see
`OwnedPokemonDao.findByIdentity`. Weight and height are fixed at catch and survive powering
up and evolving, unlike CP and maxHP.

The size-badge finding above broke this silently. A badge **replaces** the `WEIGHT`/`HEIGHT`
label, so a Pokémon with one parsed as having no weight or height at all — and identity
matching fell back to the mutable CP/HP path for exactly those Pokémon. Two fixes landed:

- `DetailParser` now accepts the badge labels as weight/height anchors.
- Identity backfill is gated **per field**. It previously ran only when `weight` was null, so
  a row written with a weight but a missing height could never repair itself. Confirmed live:
  the lucky Exeggcute had `weight=3.03kg, height=NULL`, and now reads `0.46m`.

Still worth doing: an actual power-up on the lucky Exeggcute to prove the row survives one.

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

**Fixed — `FormResolver` disambiguates by feasibility.** Types narrow first, then any form
whose base stats admit no valid IV for the observed CP+HP is dropped. Mewtwo (300 attack)
and Armored Mewtwo (182) essentially never both explain the same numbers, so one survives.

The useful subtlety is what happens when several forms survive **with identical base stats**.
That isn't ambiguity worth refusing: the IV maths is the same whichever is right, so the IV
is reported as exact and only the NAME is flagged as a tie ("Kyurem (Black) or (White) —
same stats, IV unaffected"). Forms that survive with *different* stats still resolve to null,
because picking one would produce a confidently wrong IV.

There is an ordering wrinkle worth remembering: feasibility needs the CP, and reading the CP
is sharper with the base stats. `aggregateDetail` therefore resolves by type first purely to
help `CpResolver`, then lets feasibility settle the form, then re-reads the CP if the form
was only just pinned down.

### Fusion Pokémon

Necrozma and Kyurem fuse, and they behave completely differently:

| Fusion | Types | Separable? |
|---|---|---|
| Necrozma / Dusk Mane / Dawn Wings / Ultra | psychic / +steel / +ghost / +dragon | **Yes — type row alone.** All four typings differ |
| Kyurem vs Black/White | all `dragon/ice` | **Yes — feasibility.** Base is 246 attack, fused are 310 |
| Kyurem (Black) vs (White) | both `dragon/ice` | **No** — identical types AND identical stats (310/183/245) |

So only the Black/White pair is genuinely undecidable, and it's the harmless kind: identical
stats mean the IV is unaffected, exactly like the Pikachu costumes. Covered by tests in
`FormResolverTest`.

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

### Dynamax capability — resolved by reading the screen

It is **not in pvpoke's GameMaster** (zero entries carry a dynamax/gigantamax tag), so a
second data source looked necessary. It wasn't: the detail screen simply says `Dynamax`, so
it is read like everything else and needs no new dependency.

Unused metadata already in the GameMaster that may be worth carrying later:
`legendary`, `mythical`, `ultrabeast`, `regional`, `starter`, `untradeable`, `shadoweligible`.

### Special background — deferred, and probably not a pixel check

Low priority (user's call). The detail-screen backdrop varies a lot between Pokémon —
teal-with-leaves, navy-with-stars, purple bokeh — and with no labelled control pair there is
no way to tell a genuine special background from ordinary type or event theming. Sampling
the backdrop would produce confident nonsense.

There is likely a better identifier than the backdrop itself, so revisit the approach rather
than trying to calibrate this one. Not urgent, and nothing else depends on it.

**The lead worth trying first**: PoGO's own search field, which is already the plan for
filter rescan (see "Next up"). Search terms exist for the flags we care about — `shiny`,
`lucky`, `mega` are confirmed working — so if a background term exists too, the game does
the classification for us and we only read the result set. That turns an uncalibratable
pixel problem into the same list-reading problem we already solve, and it is the same reason
shiny detection is better done from the grid than from the detail backdrop. Check what
search terms the game accepts before writing any pixel code for this.

## Settings, backup and export

**Settings page — built.** Generated at runtime from `SettingsSpec` rather than from a
layout: a setting is one entry in that list and the screen, the persistence and the backup
format all follow from it. A hand-written screen would have kept three copies of the same
list in XML, in the read/write code and in the exporter, and they drift.

Live settings: panel text size, panel auto-hide (0 = stay until dismissed), remember panel
position, show IV rank, frames per scan, auto-appraise on scan. All verified persisting on
device.

**Export settings — built, NOT yet device-verified.** `SettingsCodec` round-trips to JSON
with a schema version, refuses a version it doesn't recognise, clamps out-of-range values
and defaults keys missing from older backups. Every key is tested round-tripping at a
non-default value, which is the test that actually exercises the hand-rolled parser —
round-tripping defaults proves nothing, since a key that silently falls back to its default
passes by coincidence.

Export/import go through the **Storage Access Framework**: the user picks the destination in
the system picker, so the app needs no storage permission and the export stays explicitly
user-initiated. Two wrinkles worth keeping:

- The import picker filters on `*/*`, not `application/json`. Providers often report a
  `.json` on external storage as `octet-stream`, and a backup that doesn't appear in the
  picker is a backup you don't have.
- Backup filenames are dated, so successive exports don't overwrite each other.

⚠️ **The picker flow has not been exercised on the phone** — the device was unplugged and
came back unauthorized before this could be tested. The codec is unit-tested; the SAF
round trip, the toasts and the post-import `recreate()` are not. Test before trusting a
backup taken with it.

Known limitation: `OverlayController` reads settings when it builds the panel, so an
imported panel text size applies to the next panel, not one already on screen.

**Export the DB — codec + UI built, NOT device-verified.** `BackupCodec` serialises all
three tables (owned Pokémon, family candy, mega energy) to JSON and back, through the SAF
picker, on a worker thread (Room refuses main-thread queries). Restore is a full REPLACE in
one transaction — "make my index match this file", not a merge, which would resurrect
released Pokémon.

Restore-safety, the whole point of doing this carefully:

- The backup is stamped with the schema version, and `@Database(version = …)` **reads that
  same constant** (`BackupCodec.SCHEMA_VERSION`) — they cannot drift, because there is only
  one number. A bump to the Room schema is a bump to the backup format by construction.
- A NEWER backup than the build understands is refused; an EQUAL or OLDER one restores with
  new columns defaulting cleanly. That older-is-OK rule holds only while migrations stay
  **additive** (every one to date is `ADD COLUMN`). A future destructive migration breaks
  the assumption — at which point the version guard fails loudly instead of corrupting, and
  a real backup-migration is needed. This is called out in `BackupCodec`'s header.
- The JSON is parsed with a real hand-written tokenizer (`Json.kt`), not regex like the
  settings codec, because catch locations are free text with commas, quotes and non-ASCII.
  Tested against exactly those: `"Waterloo, Ontario, Canada"`, embedded quotes/backslashes,
  and unicode all round-trip; tri-state `shiny` (null/true/false) survives each value.

The index holds catch locations and dates, so this file is personal: local only, never
uploaded, never committed as a fixture.

⚠️ **Not exercised on the phone** — same reason as the settings export; the device came back
unauthorized. Codec, JSON layer and transaction logic are unit-tested (30-odd cases); the
SAF round trip and the actual DB read/write on-device are not. First real export should be
verified by re-importing it into a fresh install before it's trusted as a backup.

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
  A mega-raid catch also lists **the party you caught it with** ("WITH YOUR PARTY: …"),
  which is other people's data — that capture is deliberately not a fixture.
- **PokeBuddy's own overlay appears in PokeBuddy's own captures.** The gold star at the left
  edge is ours, and it sits right where a rarity badge would. Don't read it as game UI, and
  don't site a pixel check underneath it.
- **`CAPTURE_NOW` grabs whatever is on screen at that instant.** Twice it caught a
  transition rather than the intended screen and reported "No Pokémon screen detected" while
  the right screen was plainly visible. Re-screenshot and re-issue rather than debugging the
  parser.
- **Scripted PoGO navigation stays unreliable.** A cold start does not resume the previous
  screen, so a blind tap sequence lands somewhere else — one such run hit the AR button and
  raised a camera-permission prompt. Screenshot between steps, or have the user navigate.
- **Pull the DB with `adb exec-out`, never a shell redirect.** `adb shell "... > file"` and
  Bash redirection both corrupt the binary; the copy opens as "file is not a database".
  Take the `-wal` file too or recent writes are missing.

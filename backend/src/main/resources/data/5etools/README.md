# 5etools data

The 5etools files the backend reads. Only what code under `src/main` reads is kept here; R7 workstream G deleted
the rest (the adventures, books, fluff, Foundry and index files, most of `generated/` and the unused top-level
files). Git history keeps every deleted file: `git log --diff-filter=D --name-only -- backend/src/main/resources/data/5etools`
lists them, and `git checkout <commit>^ -- <path>` brings one back.

| Kept | Read by |
|------|---------|
| `backgrounds.json` | `content.internal.BackgroundLoader` |
| `conditionsdiseases.json` | `content.internal.ConditionLoader` |
| `feats.json` | `content.internal.FeatLoader` (with `data/content/feat-effects.json`) |
| `items-base.json`, `items.json` | `content.internal.ItemLoader` |
| `optionalfeatures.json` | `content.internal.OptionalFeatureLoader` |
| `races.json` | `content.internal.RaceLoader` (with `data/content/race-effects.json`) |
| `spells/spells-*.json` | `content.internal.SpellLoader` |
| `spells/sources.json` | `content.internal.SpellLoader`, for each spell's classes |
| `class/class-*.json` | `content.internal.CharacterClassLoader`, and `SpellLoader` for subclass spell lists. `class-mystic.json` and `class-sidekick.json` match the pattern and are skipped by the loader's `SKIP_FILES`, so they stay to keep its report the same |
| `bestiary/bestiary-*.json` | `content.internal.MonsterLoader`, and `TextOnlySavesReportTest` |
| `generated/bookref-quick.json` | `reference.ReferenceController`, for `GET /api/reference/quickref` |

Each loader names its files in `files()`, and the content hash that decides whether a start reloads them is taken
over those files alone, so deleting files no pattern matches left every hash, and the loaded content, unchanged.
A new loader that reads another 5etools file adds it back from git history and to this table.

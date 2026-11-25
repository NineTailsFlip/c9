# C9 — Calendars and Contacts on 9 keys

The phone's calendars and contacts, as two launcher entries, for Android phones with a keypad and no touchscreen.

[![Build](https://github.com/NineTailsFlip/c9/actions/workflows/build.yml/badge.svg)](https://github.com/NineTailsFlip/c9/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/NineTailsFlip/c9)](https://github.com/NineTailsFlip/c9/releases/latest)
[![License: GPL-3.0-or-later](https://img.shields.io/badge/license-GPL--3.0--or--later-blue)](LICENSE)

C9 is [NineTailsFlip](https://github.com/NineTailsFlip)'s fork of C9 from Timo Furrer's
[Nine suite](https://nine.furrer.life). It keeps upstream's look — one column of plain text on black, a numbered `*` menu, a hint bar — and adds updates from GitHub and the look chosen in the phone's first-time setup.

## Features

**Calendar**
- Day, 3-day, week and agenda views (`#` cycles them); Left/Right page through time.
- Events: create, edit, delete, search; jump to today or to any date.
- Choose which calendars show; sync.

**Contacts**
- Every contact, A–Z, with letter-group jumps; type digits to search by name or number.
- Favorites, groups and accounts; create, edit, delete; call a contact with the Call key.

**Ours**
- Updates from GitHub Releases.
- The font and hint bar chosen in the phone's first-time setup.

## Installation

Download the APK from [Releases](https://github.com/NineTailsFlip/c9/releases/latest) and
install it. After that C9 checks for a new release once a day and offers it; Android asks
you to confirm each update. Settings → Updates turns the check off or runs it now.

Our builds are `io.github.ninetailsflip.c9`, so they install next to the official C9
(`life.furrer.c9`) instead of replacing it.

## Usage

**Calendar**

| Key | Does |
|---|---|
| Up / Down | Move the selection |
| Left / Right | Page backward / forward (a day, 3 days or a week) |
| OK | Open the event, or the day in Agenda/Week |
| 1–9 | Jump to that event |
| 0 / hold 0 | Today / go to a date |
| `#` | Cycle the view: Day → 3-Day → Week → Agenda |
| `*` | Menu: new event, edit, delete, search, calendars, sync, settings |

**Contacts**

| Key | Does |
|---|---|
| Up / Down | Move the selection |
| Left / Right | Previous / next letter group |
| OK / hold OK | Open the contact / menu |
| Digits | Search by name or number |
| `#` | Favorites only |
| Call | Dial the selected contact |
| `*` | Menu: new contact, edit, delete, groups, accounts, sync, settings |

Hold `*` opens Settings everywhere; the full key map is in Settings → About → Key mapping.

## Building

Requirements: JDK 17+, Android SDK (platform 34).

```bash
git clone --recursive https://github.com/NineTailsFlip/c9.git
cd c9
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
./gradlew :app:assembleDebug
```

Tests and lint: `./gradlew :app:testDebugUnitTest :app:lintDebug`.
`scripts/run.sh` installs the app on a connected phone and follows its log (it expects
`ANDROID_HOME=/opt/android-sdk`; edit it if your SDK lives elsewhere).

Releases are built by GitHub Actions from a `v<versionName>` tag — see
[RELEASING.md](https://github.com/NineTailsFlip/.github/blob/main/RELEASING.md).

## Project structure

| Path | What's there |
|---|---|
| `app/` | C9 itself (Kotlin package `life.furrer.c9`, kept from upstream so its changes merge cleanly) |
| `nine-core/` | Git submodule: [nine-core](https://github.com/NineTailsFlip/nine-core) — the Nine suite's shared `core/` and `contacts/`, plus our `update/` |
| `scripts/` | `sync-upstream.sh`, `run.sh` |

## Staying in sync with upstream

This repository holds upstream's full history of `c9/` (from `~timofurrer/nine` at
`ab73f97`) with our changes on top.

```bash
scripts/sync-upstream.sh
```

merges what changed upstream since the last sync. When upstream changes `core/` or
`contacts/`, update [nine-core](https://github.com/NineTailsFlip/nine-core) first, then bump the
submodule here.

## Contributing

Issues and pull requests are welcome; see
[CONTRIBUTING](https://github.com/NineTailsFlip/.github/blob/main/CONTRIBUTING.md). If a bug
also happens in the official C9, please report it upstream too:
<https://todo.sr.ht/~timofurrer/nine>.

## License

GPL-3.0-or-later — see [LICENSE](LICENSE). C9 is © Timo Furrer and the Nine contributors; our
changes are under the same license.

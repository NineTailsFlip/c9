# C9 — Calendars and Contacts on 9 keys

**NineTailsFlip's fork** of C9 from the [Nine suite](https://nine.furrer.life) by Timo Furrer.
It is the phone's calendars and contacts, as two launcher entries, for Android phones with a physical keypad/D-pad and no touchscreen.

- Upstream: <https://git.sr.ht/~timofurrer/nine>, in the `c9/` folder, at commit `ab73f97`.
  Please report problems that also happen in the official C9 upstream too:
  <https://todo.sr.ht/~timofurrer/nine>.
- This repository holds upstream's full history of `c9/` (141 commits), plus our
  changes on top. The Nine suite's shared `core/` and `contacts/` come from the
  [nine-core](https://github.com/NineTailsFlip/nine-core) submodule, unchanged.
- Our apps and the rules they follow: <https://github.com/NineTailsFlip>.

## Building

You need JDK 17+ and the Android SDK (platform 34).

```bash
git clone --recursive https://github.com/NineTailsFlip/c9.git
cd c9
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
./gradlew :app:assembleDebug
```

Tests and lint, as upstream's CI runs them:
`./gradlew :app:testDebugUnitTest :app:lintDebug`. `scripts/run.sh` installs the app on a
connected phone, starts it and follows its log. It expects `ANDROID_HOME=/opt/android-sdk`, so
edit it if your SDK lives elsewhere.

## Package name

Our builds are `io.github.ninetailsflip.c9`, so they install **next to** the official C9
(`life.furrer.c9`) instead of replacing it. The Kotlin package stays upstream's
(`life.furrer.c9`), so upstream's changes still merge cleanly.

## Installing and updates

Signed builds are published on [Releases](https://github.com/NineTailsFlip/c9/releases). Install the first one by hand. After that, C9 checks for a newer release once a day when its main screen opens and offers to update. Android asks you to confirm each update, and the first time it also asks you to allow C9 to install apps. You can turn the check off, or run it now, in Settings → Updates.

Releases are built by GitHub Actions from a `v<versionName>` tag. See
[RELEASING.md](https://github.com/NineTailsFlip/.github/blob/main/RELEASING.md).

## Staying in sync with upstream

```bash
scripts/sync-upstream.sh
```

This merges what changed in upstream's `c9/` since the last sync. When upstream changes
`core/` and `contacts/`, update [nine-core](https://github.com/NineTailsFlip/nine-core) as its
README describes, then bump the submodule here.


## License

GPL-3.0-or-later (see `LICENSE`). C9 is © Timo Furrer and the Nine contributors; our changes
are under the same license.

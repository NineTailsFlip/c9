#!/usr/bin/env bash
# Build, install, launch, and tail logs for the connected phone. Takes which of C9's two
# launcher entries to start, since one APK ships both (see IMPLEMENTATION.md §2).
set -euo pipefail

cd "$(dirname "$0")/.."

export ANDROID_HOME=/opt/android-sdk

target="${1:-}"
case "$target" in
    calendar) component="io.github.ninetailsflip.c9/life.furrer.c9.CalendarLauncher" ;;
    contacts) component="io.github.ninetailsflip.c9/life.furrer.c9.ContactsLauncher" ;;
    *)
        echo "usage: $0 calendar|contacts" >&2
        exit 1
        ;;
esac

./gradlew :app:installDebug

adb shell am force-stop io.github.ninetailsflip.c9
adb shell am start -n "$component"

PID=""
for _ in $(seq 1 20); do
    PID="$(adb shell pidof io.github.ninetailsflip.c9 || true)"
    [ -n "$PID" ] && break
    sleep 0.2
done

if [ -z "$PID" ]; then
    echo "warning: could not find running process, showing unfiltered logcat" >&2
    exec adb logcat
fi

exec adb logcat --pid="$PID"

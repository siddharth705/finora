#!/usr/bin/env bash
set -euo pipefail

# Runs as reactivecircus/android-emulator-runner@v2's `script:` input, with a booted emulator
# already up. That action does NOT run a multi-line `script:` block as one shell session -- it
# executes each line as its own separate `sh -c` invocation (confirmed by a real run: a `for
# attempt in 1 2 3 4 5; do` line failed on its own with "Syntax error: end of file unexpected
# (expecting done)", because its `done` was never in the same invocation). So the retry loop below
# has to live in a script file invoked as a single command, not inline in the workflow YAML.

export PATH="$PATH:$HOME/.maestro/bin"

# This job's first real run installed straight after boot and got "cmd: Failure calling service
# package: Broken pipe (32)" -- a known reactivecircus/android-emulator-runner race, not anything
# about this APK: `getprop sys.boot_completed` (which the action already waits on) can report 1
# before the emulator's own package-manager service has finished coming up behind it. A handful of
# retries a few seconds apart is the standard workaround; not looping forever, so a genuinely
# broken APK still fails the job instead of hanging it.
install_ok=false
for attempt in 1 2 3 4 5; do
  if adb install -r mobile/android/app/build/outputs/apk/release/app-release.apk; then
    install_ok=true
    break
  fi
  echo "adb install attempt $attempt failed -- retrying in 5s"
  sleep 5
done
if [ "$install_ok" != true ]; then
  echo "adb install did not succeed after 5 attempts"
  exit 1
fi

adb push mobile/.maestro/fixtures/maestro-statement.csv /sdcard/Download/maestro-statement.csv
maestro test mobile/.maestro/flows/login.yaml
maestro test mobile/.maestro/flows/dashboard.yaml
maestro test mobile/.maestro/flows/import.yaml

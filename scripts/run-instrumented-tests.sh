#!/usr/bin/env bash
# run-instrumented-tests.sh — instrumented-test driver for the 8 Ball Pool
# Guideline Tool, run INSIDE the android-emulator-runner `script:` step.
#
# Why a file instead of inline YAML? The runner executes its `script:` input
# LINE BY LINE via `/usr/bin/sh -c <line>` (observed in the 8 Ball Pool
# emulator repo, run #1: a multi-line `if...then...fi` block broke with
# "Syntax error: end of file unexpected"). So the workflow calls this file
# with a single line and ALL control flow lives here, in bash.
#
# What it does:
#   1. Resets the ADB server (the documented band-aid for stale adb state)
#      and waits for the device + full boot (sys.boot_completed == 1).
#   2. Installs the debug APK with retries (install is the flakiest step:
#      ShellCommandUnresponsiveException / broken pipe / device offline).
#   3. Grants the overlay permission the overlay tests need.
#   4. Runs connectedDebugAndroidTest, retrying ONCE only when the device
#      went offline (infra failure). A test failure on a healthy device is
#      a REAL failure and is reported immediately, never retried.
#   5. Dumps logcat to logcat.txt when anything fails (uploaded by the
#      workflow as an artifact).
#
# Exit code: 0 when the instrumented tests pass, 1 otherwise.
# Env: ADB_INSTALL_TIMEOUT (minutes, set by the workflow, default 10).

set -u

PACKAGE="com.poolguide.tool"
MAX_BOOT_WAIT_S=600
INSTALL_ATTEMPTS=3
TEST_ATTEMPTS=2

log() { echo "[instrumented-tests] $*"; }

device_online() {
  # True only when adb reports the emulator as a healthy `device`.
  local state
  state="$(adb get-state 2>/dev/null || true)"
  [ "$state" = "device" ]
}

reset_adb() {
  log "resetting adb server (kill-server / start-server / wait-for-device)"
  adb kill-server >/dev/null 2>&1 || true
  adb start-server >/dev/null 2>&1 || true
  adb wait-for-device || true
}

wait_for_boot() {
  log "waiting for boot to complete (up to ${MAX_BOOT_WAIT_S}s)"
  local waited=0
  while [ "$waited" -lt "$MAX_BOOT_WAIT_S" ]; do
    if device_online; then
      local completed
      completed="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
      if [ "$completed" = "1" ]; then
        log "boot completed after ${waited}s"
        return 0
      fi
    fi
    sleep 10
    waited=$((waited + 10))
  done
  log "ERROR: boot did not complete within ${MAX_BOOT_WAIT_S}s"
  return 1
}

dump_diagnostics() {
  log "--- diagnostics ---"
  free -m || true
  adb devices || true
  adb shell getprop sys.boot_completed 2>/dev/null || true
  adb shell getprop ro.build.version.sdk 2>/dev/null || true
  log "--- end diagnostics ---"
}

dump_logcat() {
  log "dumping logcat to logcat.txt"
  adb logcat -d > logcat.txt 2>/dev/null || true
}

# ---------------------------------------------------------------------------
# 1. ADB reset + boot wait (second layer behind the runner's own boot wait:
#    the device has flapped offline AFTER the runner declared it booted).
# ---------------------------------------------------------------------------
log "host memory before install:"
free -m || true
reset_adb
if ! wait_for_boot; then
  dump_diagnostics
  dump_logcat
  exit 1
fi
dump_diagnostics

# ---------------------------------------------------------------------------
# 2. Install the debug APK (flakiest step) with retries.
# ---------------------------------------------------------------------------
installed=0
attempt=1
while [ "$attempt" -le "$INSTALL_ATTEMPTS" ]; do
  log "install attempt ${attempt}/${INSTALL_ATTEMPTS}: ./gradlew installDebug"
  if ./gradlew installDebug --no-daemon --stacktrace; then
    installed=1
    break
  fi
  log "install attempt ${attempt} failed"
  if [ "$attempt" -lt "$INSTALL_ATTEMPTS" ]; then
    reset_adb
    wait_for_boot || true
    log "sleeping 30s before next install attempt"
    sleep 30
  fi
  attempt=$((attempt + 1))
done
if [ "$installed" -ne 1 ]; then
  log "ERROR: installDebug failed after ${INSTALL_ATTEMPTS} attempts"
  dump_diagnostics
  dump_logcat
  exit 1
fi
log "install succeeded"

# ---------------------------------------------------------------------------
# 3. Overlay permission (the overlay tests add real TYPE_APPLICATION_OVERLAY
#    windows). Must run AFTER install — granting before install fails with
#    "No UID for com.poolguide.tool" (run 43475219203).
# ---------------------------------------------------------------------------
granted=0
attempt=1
while [ "$attempt" -le 2 ]; do
  if adb shell appops set --uid "$PACKAGE" SYSTEM_ALERT_WINDOW allow; then
    granted=1
    break
  fi
  log "appops grant attempt ${attempt} failed, retrying after adb reset"
  reset_adb
  attempt=$((attempt + 1))
done
if [ "$granted" -ne 1 ]; then
  log "ERROR: could not grant overlay permission"
  dump_diagnostics
  dump_logcat
  exit 1
fi
log "overlay permission granted"

# ---------------------------------------------------------------------------
# 4. Run the instrumented tests. Retry ONCE only on infra failure (device
#    offline after the run). A failure on a healthy device is a real test
#    failure: report it, do not retry.
# ---------------------------------------------------------------------------
attempt=1
while [ "$attempt" -le "$TEST_ATTEMPTS" ]; do
  log "test attempt ${attempt}/${TEST_ATTEMPTS}: ./gradlew connectedDebugAndroidTest"
  if ./gradlew connectedDebugAndroidTest --no-daemon --stacktrace; then
    log "instrumented tests PASSED (attempt ${attempt})"
    exit 0
  fi
  log "test attempt ${attempt} failed"
  if device_online; then
    log "device is healthy — this is a REAL test failure, not infra. Not retrying."
    dump_logcat
    exit 1
  fi
  log "device is offline — infra failure. Resetting adb and retrying once."
  reset_adb
  wait_for_boot || true
  attempt=$((attempt + 1))
done

log "ERROR: instrumented tests failed after ${TEST_ATTEMPTS} attempts (infra)"
dump_diagnostics
dump_logcat
exit 1

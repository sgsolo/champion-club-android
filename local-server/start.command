#!/bin/zsh
set -eu
sandbox_root=${0:A:h}
cd "$sandbox_root"
# Prefer adb on PATH, otherwise use the configured/default Android SDK.
sandbox_sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}
sandbox_adb=${commands[adb]:-$sandbox_sdk/platform-tools/adb}
if [[ -x "$sandbox_adb" ]]; then
    if [[ $# -gt 0 ]]; then
        sandbox_devices=(-s "$1")
    else
        sandbox_devices=()
    fi
    if ! "$sandbox_adb" "${sandbox_devices[@]}" reverse tcp:18765 tcp:18765; then
        print 'USB forwarding was not configured. Connect one phone, or pass its serial as an argument.'
    fi
else
    print 'adb not found. Install Android SDK platform-tools or configure ANDROID_HOME.'
fi
if /usr/bin/python3 - <<'CHECK'
import json,sys
from urllib.request import build_opener,ProxyHandler
try:
    with build_opener(ProxyHandler({})).open('http://127.0.0.1:18765/health',timeout=2) as response:
        sys.exit(0 if json.load(response).get('service')=='champion-local' else 1)
except Exception:sys.exit(1)
CHECK
then
    print 'Champion local server is already running: http://127.0.0.1:18765/'
    exit 0
fi
exec /usr/bin/python3 server.py

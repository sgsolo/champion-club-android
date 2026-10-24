#!/bin/zsh
set -eu
/usr/bin/python3 - <<'PY'
import json,os,signal,sys
from urllib.request import build_opener,ProxyHandler
try:
    with build_opener(ProxyHandler({})).open('http://127.0.0.1:18765/health',timeout=2) as response:
        state=json.load(response)
    if state.get('service')!='champion-local':sys.exit('Port 18765 belongs to another service; nothing was stopped.')
    os.kill(int(state['pid']),signal.SIGTERM)
    print('Champion local server stopped.')
except OSError as e:print('Server is not running or could not be stopped:',e)
PY

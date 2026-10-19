#!/usr/bin/env python3
"""Exercise actual optimized APKs on a disposable, rooted Android emulator."""
import argparse
import json
import re
from pathlib import Path
import subprocess
import time

PACKAGE='com.plainnotes.android'
ROOT='/data/user/0/'+PACKAGE
REPORT=Path('smoke-results')
REPORT.mkdir(exist_ok=True)
results=[]

def adb(*args, check=True, binary=False, timeout=45):
    result=subprocess.run(['adb',*args],capture_output=True,text=not binary,timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(f'adb {args[:3]} failed: {result.stderr}')
    return result.stdout

def stop():
    adb('shell','am','force-stop',PACKAGE)

def install(apk):
    adb('uninstall',PACKAGE,check=False)
    output=adb('install','--no-streaming',str(apk),timeout=90)
    assert 'Success' in output,output

def seed_fault(starting=False,broken=True):
    stop()
    package=adb('shell','cmd','package','list','packages','-U',PACKAGE)
    uid=re.search(r'uid:(\d+)',package).group(1)
    xml='<?xml version="1.0" encoding="utf-8" standalone="yes" ?><map><boolean name="enabled" value="true" />'
    if starting:xml+='<boolean name="scheduler_starting" value="true" />'
    xml+='</map>'
    (REPORT/'synthetic-prefs.xml').write_text(xml)
    adb('push',str(REPORT/'synthetic-prefs.xml'),'/data/local/tmp/jnotes-smoke-prefs.xml')
    adb('shell',f'mkdir -p {ROOT}/shared_prefs; cp /data/local/tmp/jnotes-smoke-prefs.xml {ROOT}/shared_prefs/web-sync.xml; chown -R {uid}:{uid} {ROOT}/shared_prefs; restorecon -R {ROOT}/shared_prefs')
    if broken:
        # WorkManager migrates the old databases/ path to no_backup/. Block both.
        for parent in ['databases','no_backup']:
            path=f'{ROOT}/{parent}/androidx.work.workdb'
            adb('shell',f'mkdir -p {path}; chown -R {uid}:{uid} {ROOT}/{parent}; restorecon -R {ROOT}/{parent}')

def launch(label, expected_alive=True):
    stop();adb('logcat','-c')
    started=adb('shell','am','start','-W','-n',PACKAGE+'/.MainActivity',check=False)
    time.sleep(7)
    pid=adb('shell','pidof',PACKAGE,check=False).strip()
    logs=adb('logcat','-d','-v','threadtime')
    (REPORT/(label+'.log')).write_text(logs)
    (REPORT/(label+'-launch.txt')).write_text(started)
    alive=bool(pid) and 'FATAL EXCEPTION' not in '\n'.join(line for line in logs.splitlines() if 'AndroidRuntime' in line)
    ui=''
    if alive:
        adb('shell','uiautomator','dump','/sdcard/jnotes-ui.xml',check=False,timeout=25)
        ui=adb('shell','cat','/sdcard/jnotes-ui.xml',check=False)
        (REPORT/(label+'.xml')).write_text(ui)
        (REPORT/(label+'.png')).write_bytes(adb('exec-out','screencap','-p',binary=True))
        assert PACKAGE in ui,'Application process is alive but no JNotes window is visible'
        assert 'Pick notes folder' in ui or 'Got it' in ui,'JNotes did not finish rendering its setup screen'
    results.append({'case':label,'alive':alive,'expected_alive':expected_alive})
    (REPORT/'results.json').write_text(json.dumps(results,indent=2))
    if alive != expected_alive:
        tail='\n'.join(line for line in logs.splitlines() if any(s in line for s in ['AndroidRuntime','WM-','JNotes']))[-12000:]
        raise AssertionError(f'{label}: expected alive={expected_alive}, actual={alive}\n{tail}')
    print(f'PASS {label}: '+('JNotes window visible' if alive else 'baseline crash reproduced'),flush=True)
    return ui

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--baseline',required=True,type=Path)
parser.add_argument('--candidate',required=True,type=Path)
args=parser.parse_args()
adb('root');adb('wait-for-device')
install(args.baseline)
launch('baseline-fresh')
seed_fault()
launch('baseline-failed-sync',expected_alive=False)
install(args.candidate)
launch('candidate-fresh')
seed_fault(broken=False)
launch('candidate-missing-session')
# Clear only this disposable test installation before the independent database fault.
adb('shell','pm','clear',PACKAGE)
seed_fault()
launch('candidate-failed-sync')
assert 'name="scheduler_paused" value="true"' in adb('shell','cat',ROOT+'/shared_prefs/web-sync.xml')
launch('candidate-reopen-after-failure')
adb('shell','pm','clear',PACKAGE)
seed_fault(starting=True)
launch('candidate-interrupted-startup')
install(args.candidate)
launch('candidate-reinstall')
print('PASS: release APK fresh install, failed-sync recovery, reopening, and reinstall',flush=True)

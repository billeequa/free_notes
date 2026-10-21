#!/usr/bin/env python3
"""Portable handoff checks. Native build/tests run separately on a Mac."""
import json, pathlib, plistlib, subprocess, sys, hashlib
root = pathlib.Path(__file__).resolve().parents[1]
repo = root.parent
for file in [root/'JNotes/Info.plist', root/'JNotes/PrivacyInfo.xcprivacy']:
    with file.open('rb') as stream: plistlib.load(stream)
assert json.loads((root/'JNotes/Resources/jnotes-service.json').read_text()) == json.loads((repo/'config/jnotes-service.json').read_text()), 'Bundled service configuration drifted'
assert (root/'JNotesTests/vectors.json').read_bytes() == (repo/'protocol/vectors.json').read_bytes(), 'Compatibility vectors drifted'
project = root/'JNotes.xcodeproj/project.pbxproj'
before = project.read_bytes() if project.exists() else b''
subprocess.run([sys.executable, str(root/'scripts/generate_project.py')], check=True)
assert before == project.read_bytes(), 'Regenerate and commit the Xcode project'
text=project.read_text()
for file in (root/'JNotes').rglob('*.swift'):
    assert str(file.relative_to(root)) in text, f'Missing source: {file}'
assert 'DEVELOPMENT_TEAM = ""' in text, 'Do not publish a personal signing configuration'
for file in (root/'JNotes').rglob('*.swift'):
    source=file.read_text()
    assert 'sb_secret_' not in source and 'service_role' not in source, f'Admin credential reference: {file}'
    assert 'fatalError(' not in source, f'Unhandled app path: {file}'
assert (root/'Vendor/argon2/LICENSE').exists()
print('Project, resources, plists, protocol vectors, source membership, and public configuration validated.')

#!/usr/bin/env python3
"""Check actual APK DEX definitions for constructors used reflectively by sync.

No Android SDK or third-party parser is needed. A method reference is not enough:
the constructor must be defined on the retained class and contain executable code.
"""
import argparse
import struct
import zipfile


def constructors(data):
    if not data.startswith(b'dex\n'):
        raise ValueError('Expected an Android DEX file')
    def u32(offset):
        return struct.unpack_from('<I', data, offset)[0]
    def uleb(offset):
        value = shift = 0
        for _ in range(5):
            byte = data[offset]
            offset += 1
            value |= (byte & 127) << shift
            if byte < 128:
                return value, offset
            shift += 7
        raise ValueError('Invalid ULEB128')
    strings = []
    for i in range(u32(56)):
        _, start = uleb(u32(u32(60) + 4*i))
        strings.append(data[start:data.index(0, start)].decode('utf-8', errors='replace'))
    types = [strings[u32(u32(68) + 4*i)] for i in range(u32(64))]
    protos = []
    for i in range(u32(72)):
        at = u32(76) + 12*i
        params = u32(at + 8)
        args = ''.join(types[struct.unpack_from('<H', data, params+4+2*j)[0]] for j in range(u32(params))) if params else ''
        protos.append('(' + args + ')' + types[u32(at+4)])
    methods = []
    for i in range(u32(88)):
        owner, proto, name = struct.unpack_from('<HHI', data, u32(92)+8*i)
        methods.append((types[owner], strings[name], protos[proto]))
    found = {}
    for i in range(u32(96)):
        at = u32(100) + 32*i
        owner = types[u32(at)]
        found.setdefault(owner, set())
        cursor = u32(at+24)
        if not cursor:
            continue
        sizes = []
        for _ in range(4):
            size, cursor = uleb(cursor)
            sizes.append(size)
        for _ in range(sizes[0]+sizes[1]):
            _, cursor = uleb(cursor)
            _, cursor = uleb(cursor)
        for size in sizes[2:]:
            index = 0
            for _ in range(size):
                delta, cursor = uleb(cursor)
                access, cursor = uleb(cursor)
                code, cursor = uleb(cursor)
                index += delta
                method_owner, name, proto = methods[index]
                if name == '<init>' and code and access & 1:
                    assert method_owner == owner
                    found[owner].add(proto)
    return found


REQUIRED = {
    'Landroidx/work/impl/WorkDatabase_Impl;': '()V',
    'Landroidx/work/OverwritingInputMerger;': '()V',
    'Landroidx/work/ArrayCreatingInputMerger;': '()V',
    'Lcom/plainnotes/android/sync/SyncWorker;': '(Landroid/content/Context;Landroidx/work/WorkerParameters;)V',
}


def check_apk(path):
    classes = {}
    with zipfile.ZipFile(path) as apk:
        for name in apk.namelist():
            if name.startswith('classes') and name.endswith('.dex'):
                classes.update(constructors(apk.read(name)))
    missing = [f'{name} <init>{proto}' for name, proto in REQUIRED.items() if proto not in classes.get(name, set())]
    if missing:
        raise SystemExit('Missing reflective sync constructors in optimized APK:\n' + '\n'.join(missing))
    print('PASS: optimized APK retains the WorkManager database, input-merger, and sync-worker constructors')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk')
    check_apk(parser.parse_args().apk)

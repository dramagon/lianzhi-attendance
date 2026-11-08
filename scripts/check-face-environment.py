#!/usr/bin/env python3
"""Offline ArcFace Windows x64 preflight. Never activates SDK or emits secrets."""
import argparse
import base64
import json
import math
import os
from pathlib import Path
import platform
import re
import shutil
import struct
import subprocess
import zipfile

DLLS = ('libarcsoft_face.dll', 'libarcsoft_face_engine.dll', 'libarcsoft_face_engine_jni.dll')

def is_x64_dll(path):
    try:
        with Path(path).open('rb') as stream:
            header = stream.read(64)
            if len(header) != 64 or header[:2] != b'MZ': return False
            stream.seek(struct.unpack_from('<I', header, 60)[0])
            signature = stream.read(6)
            return len(signature) == 6 and signature[:4] == b'PE\0\0' and struct.unpack_from('<H', signature, 4)[0] == 0x8664
    except (OSError, ValueError, struct.error): return False

def encrypted_key_ready(value):
    try: return len(base64.b64decode(value, validate=True)) == 32
    except (ValueError, TypeError): return False

def score_ready(value):
    try: return math.isfinite(float(value)) and 0 < float(value) < 1
    except (ValueError, TypeError): return False

def inspect_environment(env):
    checks = []
    def check(name, ready, note): checks.append({'check': name, 'result': 'PASS' if ready else 'FAIL', 'note': note})
    check('windows_x64', platform.system() == 'Windows' and platform.machine().lower() in ('amd64', 'x86_64'), '实际SDK仅支持Windows x64；Linux云端不代表SDK已运行')
    candidates = []
    if env.get('JAVA_HOME'): candidates.append(str(Path(env['JAVA_HOME'])/'bin'/('java.exe' if os.name == 'nt' else 'java')))
    if platform.system() == 'Windows':
        for root_name in ('ProgramFiles', 'ProgramW6432'):
            root = env.get(root_name)
            if root:
                for vendor in ('Java', 'Eclipse Adoptium'):
                    candidates.extend(str(path) for path in (Path(root)/vendor).glob('jdk*/bin/java.exe'))
    configured_java = shutil.which('java')
    if configured_java: candidates.append(configured_java)
    java_ready = False
    for candidate in dict.fromkeys(candidates):
        try:
            result = subprocess.run([candidate, '-XshowSettings:properties', '-version'], capture_output=True, text=True, timeout=10)
            text = result.stdout + result.stderr
            match = re.search(r'version\s+"(\d+)', text)
            java_ready = result.returncode == 0 and match is not None and int(match[1]) >= 17 and re.search(r'sun\.arch\.data\.model\s*=\s*64', text) is not None
            if java_ready: break
        except (OSError, subprocess.SubprocessError): pass
    check('java_17_x64', java_ready, '检查Java17+和64位，未加载JNI或SDK')
    jar_ready = False
    if env.get('LIANZHI_ARCSOFT_JAR'):
        try:
            with zipfile.ZipFile(env['LIANZHI_ARCSOFT_JAR']) as archive:
                jar_ready = 'com/arcsoft/face/FaceEngine.class' in archive.namelist()
        except (OSError, zipfile.BadZipFile): pass
    check('arcsoft_java_library', jar_ready, '仅确认可读取的Java库含FaceEngine类，不验证授权')
    directory = env.get('LIANZHI_ARCSOFT_NATIVE_DIRECTORY')
    for name in DLLS: check(name, bool(directory) and is_x64_dll(Path(directory)/name), '检查PE格式及x64架构，不执行二进制')
    check('face_enabled', env.get('LIANZHI_FACE_ENABLED', '').lower() == 'true' and env.get('LIANZHI_ARCSOFT_ENABLED', '').lower() == 'true', '检查显式启用配置，不修改环境')
    check('credentials_configured', bool(env.get('LIANZHI_ARCSOFT_APP_ID', '').strip()) and bool(env.get('LIANZHI_ARCSOFT_SDK_KEY', '').strip()), '仅报告是否配置，不显示授权值；激活结果须由真实服务确认')
    check('encryption_key', encrypted_key_ready(env.get('LIANZHI_FACE_ENCRYPTION_KEY', '')), '只验证32字节Base64格式，密钥须稳定保存于本机')
    check('identity_threshold', score_ready(env.get('LIANZHI_FACE_MINIMUM_SCORE')), '只检查(0,1)格式，不代表已校准准确率')
    check('rgb_threshold', score_ready(env.get('LIANZHI_ARCSOFT_RGB_LIVENESS_THRESHOLD', '0.8')), 'RGB活体配置，不代表IR或专业防攻击验收')
    return {'schemaVersion': 1, 'scope': 'OFFLINE_STATIC_PREFLIGHT', 'ready': all(item['result'] == 'PASS' for item in checks), 'checks': checks,
            'actualSdkActivation': 'NOT_RUN', 'realPersonComparison': 'NOT_RUN', 'livenessAndSpoofingAcceptance': 'NOT_RUN', 'facePunchReceipt': 'NOT_RUN'}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, help='Optional JSON report; contains readiness flags, never configuration values')
    args = parser.parse_args()
    report = inspect_environment(os.environ)
    text = json.dumps(report, ensure_ascii=False, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text+'\n', encoding='utf-8')
    print(text)
    return 0 if report['ready'] else 2

if __name__ == '__main__':
    raise SystemExit(main())

#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
classes_dir="$(mktemp -d)"
trap 'rm -rf -- "$classes_dir"' EXIT
java --module jdk.compiler/com.sun.tools.javac.Main --release 8 -d "$classes_dir" \
  "$project_dir/app/src/main/java/ua/iben/recorder/StoragePolicy.java" \
  "$project_dir/tools/StoragePolicyTest.java"
java -cp "$classes_dir" ua.iben.recorder.StoragePolicyTest
java --module jdk.compiler/com.sun.tools.javac.Main -d "$classes_dir" "$project_dir/tools/JavaSyntaxCheck.java"
mapfile -t java_sources < <(find "$project_dir/app/src/main/java" -name '*.java' -type f)
java -cp "$classes_dir" JavaSyntaxCheck "${java_sources[@]}"
python3 - "$project_dir" <<'PY'
import pathlib, sys, xml.etree.ElementTree as ET
root = pathlib.Path(sys.argv[1])
for path in root.glob('app/src/main/**/*.xml'):
    ET.parse(path)
manifest = ET.parse(root / 'app/src/main/AndroidManifest.xml').getroot()
ns = '{http://schemas.android.com/apk/res/android}'
permissions = {p.get(ns + 'name') for p in manifest.findall('uses-permission')}
assert 'android.permission.INTERNET' not in permissions
assert 'android.permission.RECORD_AUDIO' in permissions
print('PASS: XML parsed; recording permission present; no Internet permission')
PY

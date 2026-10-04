#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
python3 "$project_dir/tools/generate_strings.py" --check
python3 "$project_dir/tools/check_translations.py"
classes_dir="$(mktemp -d)"
trap 'rm -rf -- "$classes_dir"' EXIT
java --module jdk.compiler/com.sun.tools.javac.Main --release 8 -d "$classes_dir" \
  "$project_dir/app/src/main/java/ua/iben/recorder/StoragePolicy.java" \
  "$project_dir/app/src/main/java/ua/iben/recorder/StorageFullException.java" \
  "$project_dir/app/src/main/java/ua/iben/recorder/RecordingFailure.java" \
  "$project_dir/app/src/main/java/ua/iben/recorder/CaptureHealth.java" \
  "$project_dir/app/src/main/java/ua/iben/recorder/AudioInputPolicy.java" \
  "$project_dir/tools/CaptureRecoveryTest.java" \
  "$project_dir/app/src/main/java/ua/iben/recorder/AudioGain.java" \
  "$project_dir/app/src/main/java/ua/iben/recorder/RecordingNames.java" \
  "$project_dir/app/src/main/java/ua/iben/recorder/SegmentTimeline.java" \
  "$project_dir/app/src/main/java/ua/iben/recorder/DavTarget.java" \
  "$project_dir/app/src/main/java/ua/iben/recorder/DavClient.java" \
  "$project_dir/app/src/main/java/ua/iben/recorder/TlsCertificate.java" \
  "$project_dir/app/src/main/java/ua/iben/recorder/RecordingPosition.java" \
  "$project_dir/tools/LibraryFeaturesTest.java" \
  "$project_dir/tools/TlsCertificateTest.java" \
  "$project_dir/app/src/main/java/ua/iben/recorder/TransferPolicy.java" \
  "$project_dir/tools/StoragePolicyTest.java" \
  "$project_dir/tools/AudioCoreTest.java" \
  "$project_dir/tools/WebDavTest.java" \
  "$project_dir/app/src/main/java/ua/iben/recorder/WeeklySchedule.java" \
  "$project_dir/app/src/main/java/ua/iben/recorder/AudioEnvelope.java" \
  "$project_dir/app/src/main/java/ua/iben/recorder/I18n.java" \
  "$project_dir/tools/FeaturesTest.java" \
  "$project_dir/app/src/main/java/ua/iben/recorder/PlatformPolicy.java" \
  "$project_dir/app/src/main/java/ua/iben/recorder/BoundedCopy.java" \
  "$project_dir/tools/CompatibilityTest.java" \
  "$project_dir/app/src/main/java/ua/iben/recorder/PlaybackProgress.java" \
  "$project_dir/app/src/main/java/ua/iben/recorder/CaptureEnvelope.java" \
  "$project_dir/app/src/main/java/ua/iben/recorder/IncidentPolicy.java" \
  "$project_dir/tools/WorkflowTest.java" \
  "$project_dir/app/src/main/java/ua/iben/recorder/LocalDeletion.java" \
  "$project_dir/tools/LocalDeletionTest.java"
java -cp "$classes_dir" ua.iben.recorder.StoragePolicyTest
java -cp "$classes_dir" ua.iben.recorder.AudioCoreTest
java -cp "$classes_dir" ua.iben.recorder.CaptureRecoveryTest
java --add-modules jdk.httpserver -cp "$classes_dir" ua.iben.recorder.WebDavTest
java -cp "$classes_dir" ua.iben.recorder.FeaturesTest
java -cp "$classes_dir" ua.iben.recorder.CompatibilityTest
java -cp "$classes_dir" ua.iben.recorder.WorkflowTest
java -cp "$classes_dir" ua.iben.recorder.LocalDeletionTest
java -cp "$classes_dir" ua.iben.recorder.LibraryFeaturesTest
java --add-modules jdk.httpserver -cp "$classes_dir" ua.iben.recorder.TlsCertificateTest
java --module jdk.compiler/com.sun.tools.javac.Main -d "$classes_dir" "$project_dir/tools/JavaSyntaxCheck.java"
mapfile -t java_sources < <(find "$project_dir/app/src/main/java" -name '*.java' -type f)
java -cp "$classes_dir" JavaSyntaxCheck "${java_sources[@]}"
python3 - "$project_dir" <<'PY'
import pathlib, sys, re, sqlite3, json, xml.etree.ElementTree as ET
root = pathlib.Path(sys.argv[1])
for path in root.glob('app/src/main/**/*.xml'):
    ET.parse(path)
manifest = ET.parse(root / 'app/src/main/AndroidManifest.xml').getroot()
ns = '{http://schemas.android.com/apk/res/android}'
permissions = {p.get(ns + 'name') for p in manifest.findall('uses-permission')}
assert 'android.permission.INTERNET' in permissions
assert 'android.permission.RECORD_AUDIO' in permissions
assert {'android.permission.BLUETOOTH_CONNECT','android.permission.MODIFY_AUDIO_SETTINGS'} <= permissions
assert not {'android.permission.BLUETOOTH_SCAN','android.permission.ACCESS_FINE_LOCATION'} & permissions
assert next(p for p in manifest.findall('uses-permission') if p.get(ns+'name')=='android.permission.BLUETOOTH').get(ns+'maxSdkVersion')=='30'
for permission in ['FOREGROUND_SERVICE','FOREGROUND_SERVICE_MICROPHONE','POST_NOTIFICATIONS','SCHEDULE_EXACT_ALARM']:
    assert 'android.permission.'+permission in permissions
assert not {'android.permission.MANAGE_EXTERNAL_STORAGE','android.permission.READ_MEDIA_AUDIO'} & permissions
for p in manifest.findall('uses-permission'):
    if p.get(ns+'name').endswith(('READ_EXTERNAL_STORAGE','WRITE_EXTERNAL_STORAGE')):
        assert p.get(ns+'maxSdkVersion') == '28'
recorder = next(s for s in manifest.find('application').findall('service') if s.get(ns+'name').endswith('RecorderService'))
assert recorder.get(ns+'foregroundServiceType') == 'microphone'
wave = next(s for s in manifest.find('application').findall('service') if s.get(ns+'name').endswith('WaveformService'))
assert wave.get(ns+'foregroundServiceType') == 'dataSync|mediaProcessing'
assert wave.get(ns+'exported') == 'false' and wave.get(ns+'stopWithTask') == 'false'
for permission in ['FOREGROUND_SERVICE_DATA_SYNC','FOREGROUND_SERVICE_MEDIA_PROCESSING']:
    assert 'android.permission.'+permission in permissions
gradle=(root/'app/build.gradle').read_text()
assert re.search(r'minSdk\s+27', gradle) and re.search(r'targetSdk\s+35', gradle)
assert "applicationId 'ua.iben.recorder.oreo'" in gradle
for path in root.glob('app/src/main/java/**/*.java'):
    assert not re.search(r'SDK_INT\s*[!=]=\s*27|VERSION_CODES\.O_MR1', path.read_text()),path
print('PASS: modern permissions, microphone service, stable app ID, no API-27-only runtime guards')
assert manifest.find('application').get(ns + 'usesCleartextTraffic') == 'true'
assert manifest.find('application').get(ns + 'allowBackup') == 'false'
service = next(s for s in manifest.find('application').findall('service') if s.get(ns+'name').endswith('SyncJobService'))
assert service.get(ns+'permission') == 'android.permission.BIND_JOB_SERVICE'
print('PASS: XML parsed; cleartext capability guarded by tested runtime LAN policy; backup disabled; job service protected')
PY
python3 "$project_dir/tools/check_database.py"

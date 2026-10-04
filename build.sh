#!/usr/bin/env bash
# First build downloads the official pinned Gradle distribution and Android build dependencies.
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
sdk_path="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
if [[ ! -f "$sdk_path/platforms/android-35/android.jar" ]]; then
  echo 'Потрібен Android SDK Platform 35. Встановіть його в SDK Manager Android Studio.' >&2
  echo 'Вкажіть шлях до SDK через ANDROID_HOME або ANDROID_SDK_ROOT.' >&2
  exit 1
fi
export ANDROID_HOME="$sdk_path"
java -version
python3 - "$project_dir" <<'PY'
import hashlib, pathlib, re, shutil, sys, urllib.request, zipfile
root = pathlib.Path(sys.argv[1]) / '.tools'
root.mkdir(exist_ok=True)
destination = root / 'gradle-8.9'
if not (destination / 'bin/gradle').is_file():
    base = 'https://services.gradle.org/distributions/gradle-8.9-bin.zip'
    with urllib.request.urlopen(base + '.sha256', timeout=60) as response:
        expected = response.read(256).decode('ascii').strip().split()[0]
    if not re.fullmatch('[0-9a-f]{64}', expected):
        raise SystemExit('Unexpected Gradle SHA-256 response')
    archive = root / 'gradle-8.9-bin.zip'
    print('Завантаження офіційного Gradle 8.9…', flush=True)
    with urllib.request.urlopen(base, timeout=120) as response, archive.open('wb') as output:
        shutil.copyfileobj(response, output)
    with archive.open('rb') as source:
        actual = hashlib.file_digest(source, 'sha256').hexdigest()
    if actual != expected:
        archive.unlink()
        raise SystemExit('Gradle checksum mismatch; build stopped')
    with zipfile.ZipFile(archive) as package:
        for member in package.infolist():
            path = (root / member.filename).resolve()
            if not path.is_relative_to(root.resolve()):
                raise SystemExit('Unsafe archive entry')
        package.extractall(root)
    archive.unlink()
    (destination / 'bin/gradle').chmod(0o755)
PY
exec "$project_dir/.tools/gradle-8.9/bin/gradle" -p "$project_dir" ${GRADLE_TASK:-:app:assembleDebug} "$@"

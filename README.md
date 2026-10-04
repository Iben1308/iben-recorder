# Iben Recorder

Standalone audio recorder for Android 8.1+ (originally built for a Samsung Galaxy J7 2016
running LineageOS). Long-running background recording split into files, a built-in
player, and optional upload to your own WebDAV server (for example Nextcloud).

🇺🇦 [Українська версія](README.uk.md)

> **Status:** hobby project, version 0.7.x. The app is developed and used by one person;
> expect rough edges. Please test multi-day recording on your own device before relying on it.

## Features

- **Recording:** continuous capture from the microphone, AAC in M4A, mono, configurable
  bitrate and sample rate, files of configurable length, foreground service with a
  persistent notification (always visible while recording).
- **Local storage management:** archive size limit, free-space reserve, optional
  auto-delete of the oldest own files.
- **Listen tab:** waveform player (1×/4×/16× zoom), bookmarks with notes, "listened" and
  "important" marks, multi-select with group actions, export.
- **WebDAV upload:** HTTPS to your own server; password stored with the Android Keystore;
  uploads are verified by comparing SHA-256; redirects are refused; plain HTTP is accepted only
  for private local-network addresses after an explicit confirmation; a self-signed
  certificate can be imported and trusted for that single connection.
- **Safe cloud deletion:** a file is removed from the server only after a `HEAD` size
  check and a conditional `DELETE` with `If-Match`.
- **UI:** three tabs (Listen / Record / Settings), light and dark themes, Ukrainian,
  English and Polish.
- **Privacy:** no Google Play Services, no Firebase, no ads, no analytics, no third-party
  libraries. The only network traffic is to the WebDAV server you configure.

## Requirements

- Android 8.1 (API 27) or newer (`targetSdk 35`)
- A WebDAV server if you want cloud upload (optional)

## Building

Requirements: JDK 17, Python 3.11+, Android SDK (platform and build-tools versions are in
`app/build.gradle`), network access on the first build.

```bash
bash tools/check.sh   # unit-level checks
bash build.sh         # downloads Gradle 8.9 and builds the debug APK
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. For releases you
must sign with your own key and keep it: the same key is needed for every future update.

## Responsible use

Recording other people may require their consent and is regulated by law in many
countries. You are solely responsible for using this app lawfully. The app shows a
persistent notification while it records and is not designed for covert recording.

## Project history

Earlier versions (0.1 – 0.7.4) are preserved as tagged commits. Their original
documentation is in [docs/history.md](docs/history.md).

## License

Copyright (C) 2026 Iben1308

This program is free software: you can redistribute it and/or modify it under the terms of
the **GNU General Public License v3.0** as published by the Free Software Foundation.
It is distributed in the hope that it will be useful, but **without any warranty**.
See [LICENSE](LICENSE) for details.

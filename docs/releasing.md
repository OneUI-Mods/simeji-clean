# Signed release packaging

Use the existing signing identity backed up for Simeji Clean. Set `SIMEJI_CLEAN_SIGNING_PROPERTIES` to a private UTF-8 file containing `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. The keystore path must be absolute. Never commit credentials or create a replacement key for an update.

After committing the version change, run:

```sh
python3 tools/package_release.py
```

The script builds a release variant with JDK 27 and SDK 37.2, runs JVM tests and lint, checks the API 102 metadata and Simeji-only scope, verifies the existing signing certificate and 16 KiB alignment, and packages the exact Git source with SHA-256 sums. Target SDK 36 and minimum SDK 31 are retained.

Publish only the signed APK, matching source archive, checksum file, metadata and signature report to a new release tag. Preserve existing tags. Local recovery files, signing keys, personal logs and third-party APKs must not appear in source archives.

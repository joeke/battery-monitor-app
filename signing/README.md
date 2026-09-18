# Test release signing

`test-release.keystore` signs the directly distributed test APKs. Its credentials are deliberately stored in `app/build.gradle.kts` so any development machine can produce upgrade-compatible builds:

- Alias: `androiddebugkey`
- Store password: `android`
- Key password: `android`

This is a public, test-only key. Do not use it to sign Play Store or other production releases; anyone with this repository can use it to sign an APK with the same application ID.

Versions before 0.4.1 used a different macOS-hosted debug key and cannot be upgraded directly to the shared-key lineage.

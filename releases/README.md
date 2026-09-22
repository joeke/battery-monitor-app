# Test releases

These APKs are standalone release builds intended for direct installation and device testing.

Starting with 0.4.1, they are signed with the shared test key in `signing/test-release.keystore`, so builds made on different machines remain upgrade-compatible. The key and its credentials are intentionally public and must never be used for Play Store or production distribution.

The GitHub Actions release workflow uploads the highest versioned APK in this directory directly to `joeke.dev` over SSH whenever a new APK is pushed to `main`. Build and test the signed APK locally, name it `Battery-Monitor-<version>.apk`, and commit it together with the matching `versionCode` and `versionName` changes in `app/build.gradle.kts`. GitHub Releases and tags are not used. Update checks and downloads do not depend on repository visibility. See the project README for the release commands and required Actions secrets.

| APK | Version | SHA-256 |
| --- | --- | --- |
| `Battery-Monitor-0.4.3.apk` | 0.4.3 (8) | `b85536ac48f4be8fe3c46339296884ae081731f174e8b2a2d00ee5fa2a666814` |
| `Battery-Monitor-0.4.2.apk` | 0.4.2 (7) | `2301423fcca64d5d3f8f21d17aa6d849091c533ac9a11ce55b8e93a75e63d2e7` |
| `Battery-Monitor-0.4.1.apk` | 0.4.1 (6) | `63ba02d046ae2514183b830fef2f0172c99dd3cf2a1a4a490407bbe4d9008280` |
| `Battery-Monitor-0.3.0.apk` | 0.3.0 (5) | `485c1dc9b1c89a835c0a5c4d1ee97a185bac3edc8fe72daaaf57f00073bdd4d3` |
| `Battery-Monitor-0.2.0.apk` | 0.2.0 (2) | `79c7dccb80fc6229071dab6fde5ee93829fd01bce7ec2589f83f449eaefc4149` |

Version 0.4.1 starts the shared test-key signing lineage. Earlier APKs were signed on macOS, so they must be uninstalled before installing 0.4.1.

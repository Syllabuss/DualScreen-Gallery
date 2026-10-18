# Contributing

This is a private collaboration repository. Use an authorized account and discuss changes with the maintainer. Build/test setup is in [Development](docs/DEVELOPMENT.md).

Keep changes focused. Preserve stable media IDs, existing folder grants and Room data. Do not introduce destructive migrations, duplicate players, device-wide scanning or dependencies without a concrete requirement. Widget actions must not reclaim a display from another app. Preview touches must not take controller navigation away from the browsing panel. B must not close the app.

For file operations, retain journaling and copy verification before deletion. Editors save new copies. Test interrupted access, provider failure and unavailable SD cards where relevant. Do not reset a user's device or clear app data as routine troubleshooting.

Update every supported language when adding, changing or removing user-facing text; follow the [localization workflow](localization/README.md). Include accessibility and large-text behavior when changing controls.

Pull requests should explain the problem and resulting behavior, list meaningful validation and disclose unresolved limits. Distinguish implementation, runner verification and physical-device verification. Include safe screenshots for visual changes. Use invented/demo media rather than personal images or paths.

Before submitting, run the appropriate checks, update docs/changelog where needed and review the diff for local credentials, generated APKs, logs and workspace-only material.

The [first-party terms](LICENSE.md) explicitly permit recipient modifications and reverse engineering for LGPL reconstruction. This does not make the project generally open source. Never distribute an APK without its matching reconstruction kit; see [the relinking guide](docs/LGPL_RELINKING.md). Report sensitive security concerns privately following [SECURITY.md](SECURITY.md).

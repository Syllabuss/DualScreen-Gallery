# Translation workflow

Italian (`res/values`) is the product copy. English and all eight additional languages must cover every translatable string. Do not fill missing translations with English or leave source-language placeholders.

Use Android string resources for all user-facing text. Keep identifiers stable; translate only values, never media names, roots, saved settings IDs or locale tags. Preserve numbered placeholders (`%1$s`), escaped newlines and literal percentages. Use concise everyday language suitable for the Thor's smaller display.

For **every text addition, change or removal**:

1. Update the Italian text and any Kotlin fallback together.
2. Translate new or changed text into English and all eight additional languages in the same change. Update existing translations, and remove obsolete keys from every language.
3. Run `python scripts/localization.py`. It checks missing translations in every language, obsolete keys, format arguments and translations whose Italian source has changed.
4. After reviewing the translations, run `python scripts/localization.py --record` to record the reviewed revisions. Never record revisions merely to silence a failure.
5. Run `./gradlew :app:testDebugUnitTest --tests it.ayngallery.LocalizationResourcesTest --tests it.ayngallery.LanguageTest :app:assembleDebug :app:lintDebug`. Resource consistency is also checked by the ordinary unit-test task; Python is optional for building the app.

Export a translation worksheet with `python scripts/localization.py --export qa/localization.csv`. UTF-8 CSV columns contain key, Italian, English and all eight languages; blank cells are untranslated. Files stay in native Android XML; no online account, translation SDK or runtime network request is required. The CSV is a worksheet, not a second source of truth; apply reviewed changes to XML.

Locales: `ru`, `ko`, `zh-Hans`, `ja`, `de`, `fr`, `pt-BR`, `es`. The settings show native language names. Chinese is simplified, and Portuguese is Brazilian.

Coverage: English and all eight additional languages 1,728/1,728. Automated resource checks verify completeness and formatting; wording and physical layout still benefit from native-speaker review.

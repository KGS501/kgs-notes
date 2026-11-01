# KGS Notes

> **ALPHA:** KGS Notes is an early prototype intended for testing.
> Features, storage formats, and synchronization behavior may still change.

KGS Notes is an Android-first, local-first Markdown notes app built for open and
self-hosted Sources. Its Local Source and Nextcloud Notes Source are available
for early testing.

## Features

- exact Markdown files in app-private Local Source storage;
- multiple Nextcloud Notes Sources with offline Working Copies and conflict-safe synchronization;
- automatic titles, search, favorites, Trash, restore, and permanent deletion;
- list and card views with content previews and shared transitions into the editor;
- privacy-aware Inline Image import into a durable Local Source Attachment Vault;
- bundled Tiptap Rich Mode with native Source Mode fallback;
- phone and two-pane tablet/foldable layouts;
- light and dark KGS visual themes with reduced, purposeful motion; and
- no analytics, advertisements, runtime downloads, or proprietary services.

## Build and test

Build with JDK 17, the Android SDK, and the checked-in Gradle wrapper:

```bash
./gradlew :app:assembleDebug
```

Build the pinned rich-editor assets after editing `editor/web`:

```bash
cd editor/web
corepack pnpm install --frozen-lockfile
corepack pnpm build
```

`tools/dev-env.sh` resolves the SDK, AVD, and JDK locations without any private
configuration. It honours `KGS_NOTES_ANDROID_SDK_ROOT`, `ANDROID_SDK_ROOT`,
`ANDROID_HOME`, `sdk.dir` in `local.properties`, and the conventional
`~/Android/Sdk`; `KGS_NOTES_JAVA_HOME` selects the JDK. Check a machine with:

```bash
tools/dev-env.sh                  # prints the resolved environment and readiness
source tools/dev-env.sh           # exports it into the current shell
```

A cold Gradle configuration compiles the Kotlin build scripts and can take
several minutes on a busy machine; run it online once so the plugin markers are
cached before using `--offline`.

Run the JVM suites, lint, and both APKs as CI does:

```bash
./gradlew :notes-engine:test :source:nextcloud:test :editor:testDebugUnitTest :app:testDebugUnitTest \
    :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
bash tools/test-android-emulator.sh   # emulator helper fixture tests; no SDK needed
```

Run instrumentation tests on a connected emulator or device. Named suites keep
CI and local runs aligned (`full`, `core`, `minimum`, `adaptive`, `security`):

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest $(tools/instrumentation-suites.sh core)
tools/android-emulator.sh start && tools/android-emulator.sh connected-test   # managed API 36 AVD
```

CI compiles the instrumentation APK on every pull request and runs an offline
emulator matrix: API 26 (minimum supported) with the `minimum` suite, API 36
phone with the full suite, and an API 36 tablet with the `adaptive` suite.
German chrome and tablet/foldable sizes are also forced inside the suites.
Reports and logcat are kept as workflow artifacts. The live Nextcloud tests in
`source/nextcloud` run only when the `KGS_TEST_NEXTCLOUD_*` variables point at a
disposable local HTTPS server; CI never provides them.

## Principles

- Notes remain usable offline and portable outside the app.
- Markdown is the canonical saved and synchronized representation.
- Synchronization never chooses silent last-writer-wins over preserving content.
- The app has no ads, tracking, analytics, mandatory account, or developer-operated backend.
- Production functionality does not require proprietary services or Google Play Services.
- Accessibility and adaptive phone, tablet, and foldable layouts are product requirements.

## Delivery roadmap

1. **Local experience** — polished Local Source, editor, categories, search, attachments, Trash, import/export, themes, and accessibility.
2. **Nextcloud integration** — multiple Sources, authentication, offline synchronization, conflicts, transfers, attachment lifecycle, and reconnect.

## Privacy

The privacy policy is available in [index.html](index.html). It describes local device storage, Nextcloud sync and credential protection, photo metadata handling, the editor, backup exclusions, deletion behavior, and contact information.

## License

KGS Notes is licensed under `GPL-3.0-or-later`. See [LICENSE](LICENSE).

The app bundles the license texts of its open-source dependencies under `app/src/main/assets/licenses/`. After changing editor dependencies, run `pnpm install` in `editor/web` and then `python3 tools/generate_open_source_notices.py`.

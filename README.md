# KGS Notes

> **ALPHA:** KGS Notes is an early prototype intended for testing and dogfooding.
> Features, storage formats, and synchronization behavior may still change.

KGS Notes is an Android-first, local-first Markdown notes app built for open and
self-hosted Sources. Its Local Source and first Nextcloud Notes Source are usable
for early dogfooding.

## Current development slice

- exact Markdown files in app-private Local Source storage;
- multiple Nextcloud Notes Sources with offline Working Copies and conflict-safe synchronization;
- automatic titles, search, favorites, Trash, restore, and permanent deletion;
- privacy-aware Inline Image import into a durable Local Source Attachment Vault;
- bundled Tiptap Rich Mode with native Source Mode fallback;
- phone and two-pane tablet/foldable layouts;
- light and dark KGS visual themes with reduced, purposeful motion; and
- no analytics, advertisements, runtime downloads, or proprietary services.

## Build and run

Use JDK 17 and the shared Android SDK configured in `local.properties`.

```bash
./gradlew :app:assembleDebug
tools/android-emulator.sh create
tools/android-emulator.sh start
tools/android-emulator.sh install app/build/outputs/apk/debug/app-debug.apk
tools/android-emulator.sh launch
```

Build the pinned rich-editor assets after editing `editor/web`:

```bash
pnpm --dir editor/web install --frozen-lockfile
pnpm --dir editor/web build
```

Run the focused behavioral suites with:

```bash
./gradlew :notes-engine:test :source:nextcloud:test :editor:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
```

## Principles

- Notes remain usable offline and portable outside the app.
- Markdown is the canonical saved and synchronized representation.
- Synchronization never chooses silent last-writer-wins over preserving content.
- The app has no ads, tracking, analytics, mandatory account, or developer-operated backend.
- Production functionality does not require proprietary services or Google Play Services.
- Accessibility and adaptive phone, tablet, and foldable layouts are product requirements.

## Delivery roadmap

1. **Local Dogfood** — polished Local Source, editor, categories, search, attachments, Trash, import/export, themes, and accessibility.
2. **Nextcloud Dogfood** — multiple Sources, authentication, fast offline synchronization, conflicts, transfers, attachment lifecycle, and reconnect.

## License

KGS Notes is licensed under `GPL-3.0-or-later`. See [LICENSE](LICENSE).

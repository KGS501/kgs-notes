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

1. **Local experience** — polished Local Source, editor, categories, search, attachments, Trash, import/export, themes, and accessibility.
2. **Nextcloud integration** — multiple Sources, authentication, offline synchronization, conflicts, transfers, attachment lifecycle, and reconnect.

## Privacy

The privacy policy is available in [index.html](index.html). It describes local device storage, Nextcloud sync and credential protection, photo metadata handling, the editor, backup exclusions, deletion behavior, and contact information.

## License

KGS Notes is licensed under `GPL-3.0-or-later`. See [LICENSE](LICENSE).

The app bundles the license texts of its open-source dependencies under `app/src/main/assets/licenses/`. After changing editor dependencies, run `pnpm install` in `editor/web` and then `python3 tools/generate_open_source_notices.py`.

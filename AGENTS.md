# KGS Notes agent guide

## Product language

- Read `CONTEXT.md` before changing product behavior or naming.
- Use **Note**, **Source**, **Local Source**, **Category**, **Working Copy**, **Rich Mode**, and **Source Mode** exactly as defined there.
- Read the relevant decision record under `docs/adr` before changing architecture, storage, synchronization, editor behavior, security, or recovery.

## Architecture

- Keep the five deep modules: `:app`, `:notes-engine`, `:source:nextcloud`, `:editor`, and `:design-system`.
- `NotesEngine`, `Source`, and `MarkdownEditor` are the confirmed public test seams. Compose semantics are the UI seam.
- Keep Room, files, HTTP choreography, operation journals, and Hilt out of those public interfaces.
- Markdown is canonical. Never make editor JSON, HTML, or the search projection authoritative.

## Development

- Use JDK 17 and the checked-in Gradle wrapper.
- Use the current stable Android toolchain with min SDK 26.
- Run Gradle tasks sequentially; parallel Gradle invocations can contend for daemons and caches.
- `local.properties` is machine-local and must not be committed.
- On this machine, the shared SDK is `/home/agent/Projects/kgs-calendar/.android-sdk`.
- Use `tools/android-emulator.sh create` once, then `start`, `install <apk>`,
  `launch`, `screenshot <png>`, `ui [xml]`, and `logs` for device work. The
  wrapper waits for both Android boot completion and a responsive package
  manager; do not race installation against boot.
- Do not print, edit, or commit signing material, credentials, private certificates, note content, or server URLs.

## Testing

- Work in red-green vertical slices: one failing behavior test, then the minimum implementation.
- Assert behavior only through the confirmed seams; do not mock internal KGS classes.
- Use controlled adapters at external seams and real temporary storage where practical.
- Run focused tests during each slice, then module tests, lint, and the debug build before handoff.
- Use the shared Android emulator for Compose semantics, IME, accessibility, lifecycle, screenshots, and animation checks.

## Repository hygiene

- Keep generated builds, SDKs, AVDs, screenshots, local paths, and device state ignored.
- Preserve user-authored or unrelated changes in a dirty worktree.
- Never use destructive migration fallbacks for canonical content.

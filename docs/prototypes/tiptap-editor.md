# Tiptap editor feasibility prototype

The disposable prototype on branch `prototype/tiptap-editor` at commit `6623b47`
passed conditionally on an Android API 36 emulator. It proved GFM tasks, tables,
links, attachment cards, byte-exact no-op handling, conservative Source Mode
fallback, app-process recovery, WebView-renderer recovery, Gboard input, mixed
Unicode rendering, and keyboard traversal.

Production carries forward these non-negotiable constraints:

- Markdown is the only persisted and synchronized truth.
- A conservative classifier opens unsupported syntax intact in Source Mode.
- Rich Mode reloads a persisted Markdown revision after renderer death.
- The WebView uses bundled assets, a strict content policy, blocked navigation,
  and a narrow origin-checked message bridge.
- Real-device TalkBack and keyboard coverage remains a release gate.

The prototype code is not production code. `:editor` reimplements the validated
behavior behind the `MarkdownEditor` seam.

# Use Tiptap for Rich Mode while keeping Markdown canonical

Rich Mode will use the open-source Tiptap 3 editor loaded entirely from pinned, reproducibly built assets bundled in the APK, with no CDN or runtime code download, while a native Compose Source Mode remains available. Markdown—not Tiptap JSON or HTML—is the sole saved and synchronized representation, and a round-trip lossiness guard must prevent unsupported source from being damaged. Unsupported markup appears as an exact read-only Source Capsule where it can be isolated; otherwise the note opens in Source Mode with an explanation.

The bounded Android prototype recorded in `docs/prototypes/tiptap-editor.md` passed conditionally. Production may therefore use Tiptap only behind the `MarkdownEditor` seam and the conservative Source Mode fallback proven by that prototype. Real-device TalkBack behavior and the strict-CSP behavior of table and media styling remain release gates.

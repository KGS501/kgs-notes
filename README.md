# KGS Notes

KGS Notes is an Android-first, local-first note-taking app built around portable Markdown and open, self-hosted Sources—starting with Nextcloud Notes.

The project is at the beginning of implementation. Its product vocabulary is defined in [CONTEXT.md](CONTEXT.md), and consequential architecture decisions are recorded under [docs/adr](docs/adr).

## Principles

- Notes remain usable offline and portable outside the app.
- Markdown is the canonical saved and synchronized representation.
- Synchronization never chooses silent last-writer-wins over preserving content.
- The app has no ads, tracking, analytics, mandatory account, or developer-operated backend.
- Production functionality does not require proprietary services or Google Play Services.
- Accessibility and adaptive phone, tablet, and foldable layouts are product requirements.

## Delivery

1. **Local Dogfood** — polished Local Source, editor, categories, search, attachments, Trash, import/export, themes, and accessibility.
2. **Nextcloud Dogfood** — multiple Sources, authentication, fast offline synchronization, conflicts, transfers, attachment lifecycle, and reconnect.

## License

KGS Notes is licensed under `GPL-3.0-or-later`. See [LICENSE](LICENSE).

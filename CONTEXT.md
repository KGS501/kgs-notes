# KGS Notes

KGS Notes is a polished personal note-taking product centered on open and self-hosted note services. This glossary defines the product language shared across its integrations.

## Language

**Note**:
A personal content item that a person can read and edit in KGS Notes. Its available structure and portable representation are constrained by the source it belongs to.
_Avoid_: Document, page

**Source**:
A local or external place to which notes belong. A source determines the portable formats and organizational features available to its notes.
_Avoid_: Note Source, backend, provider, account

**Source Capability**:
A content or organizational feature supported by a source and therefore available to its notes.
_Avoid_: Integration feature

**Local Source**:
The source whose authoritative notes exist as plain Markdown in app-private storage on the current device. Portable access happens through explicit sharing or export rather than a live externally editable folder.
_Avoid_: Offline cache, device cache

**Working Copy**:
The editable on-device replica of a note whose authoritative source is external. A working copy remains available without a network connection and retains the note's remote identity.
_Avoid_: Local note, backup

**Available Offline**:
The state in which a note body and its Managed Attachments are stored on the device. New external sources make note bodies available first, then download Managed Attachments in the background; low storage pauses attachment downloads without deleting completed copies.
_Avoid_: Backed up, local note

**Default Source**:
The source chosen automatically when a person creates a note without explicitly selecting another source.
_Avoid_: Default account, default location

**Source Name**:
A unique, person-chosen label used to distinguish a source in KGS Notes. Changing it never changes server identity, credentials, or remote note identities.
_Avoid_: Account name, server name

**Category**:
A hierarchical path that groups notes within a source that supports organization. A Local Source may retain an empty category; a Nextcloud Notes Source cannot represent one without a note.
_Avoid_: Folder, tag

**Favorite**:
A note marked for prominence in its source and in KGS Notes library views.
_Avoid_: Pinned note, starred note

**Move**:
A transfer that changes a note's authoritative source only after the destination has confirmed its new copy. The existing note remains authoritative if the transfer cannot be completed.
_Avoid_: Upload, relocate

**Copy**:
A transfer that creates an independently authoritative note in another source without changing the original note.
_Avoid_: Duplicate, upload a copy

**Disconnected Source**:
An external source whose credentials have been removed while its identity and working copies remain on the device. It can be reconnected without treating its notes as new copies.
_Avoid_: Removed source, local source

**Remove Source**:
A device-local operation that removes an external source from KGS Notes without deleting server content. Pending edits must first be moved to the Local Source, exported, or explicitly discarded.
_Avoid_: Disconnect, delete server, delete account

**Rich Mode**:
The default editing mode that presents recognized note markup as formatted, directly editable content. It may normalize equivalent markup without changing its meaning and must preserve unsupported source content. A note outside its validated safety envelope opens intact in Source Mode rather than being truncated or rejected.
_Avoid_: Preview, rendered mode, WYSIWYG mode

**Source Mode**:
The editing mode that exposes a note's underlying text representation directly.
_Avoid_: Raw mode, code mode

**Source Capsule**:
A read-only region within Rich Mode that presents source markup the rich editor cannot safely interpret or rewrite.
_Avoid_: Code block, unsupported block, raw block

**Editing Session**:
The single live writer for a note inside KGS Notes. Adaptive panes and app windows share this state rather than creating competing drafts.
_Avoid_: Editor instance, open copy

**Trash**:
The KGS Notes recovery area that retains deleted note content for at least 30 days independently of recovery offered by a source. Content becomes eligible for safe cleanup after that time and remains until the app or its worker next completes cleanup.
_Avoid_: Archive, recently deleted

**Nextcloud Notes Source**:
An external source backed by the official Nextcloud Notes server app and a conflict-safe Notes API version.
_Avoid_: Nextcloud source, WebDAV source

## Synchronization

**Saved Locally**:
The state in which the current note content is durable on the device but has not yet been confirmed by its external source.
_Avoid_: Saved, offline

**Syncing**:
The state in which KGS Notes is actively reconciling a note with its external source.
_Avoid_: Saving, uploading

**Synced**:
The state in which the current working copy matches the revision confirmed by its external source.
_Avoid_: Saved, up to date

**Needs Attention**:
The state in which synchronization cannot safely continue without a person's decision or intervention.
_Avoid_: Error, failed

**Conflict**:
A Needs Attention state caused by concurrent local and source changes that cannot be merged safely. Resolution compares the shared base, local content, and source content, and preserves any discarded side as a Revision.
_Avoid_: Sync error, duplicate

**Recovered Note**:
A new note in the Local Source that preserves pending local edits after an external source deletes the note they belonged to. The deleted note remains in Needs Attention until the person republishes or discards it.
_Avoid_: Conflict copy, backup note

## Content

**Attachment**:
A file associated with and referenced by a note. Its available operations and portable representation depend on the source's capabilities.
_Avoid_: Asset, media, embedded file

**Managed Attachment**:
An attachment stored in the Attachment Vault whose lifecycle KGS Notes owns. KGS Notes may copy, detach, restore, or delete it as part of note operations.
_Avoid_: Uploaded file, internal attachment

**Pending Attachment**:
A Managed Attachment that is durable on the device but has not yet been verified by an external source. Its remote Markdown reference is not published until its binary is confirmed, and the note remains Saved Locally meanwhile.
_Avoid_: Failed upload, local attachment

**External File Reference**:
A note link to a source file that KGS Notes does not own. KGS Notes may render it and fetch it on demand or when explicitly made Available Offline, but never renames, moves, deletes, or silently includes the referenced file in an export.
_Avoid_: Managed Attachment, linked attachment

**Inline Image**:
An image attachment presented within the content of its note.
_Avoid_: Embedded image, media

**Attachment Vault**:
A source-owned, KGS-managed directory containing immutable attachment files organized by note identity. Notes refer to vault files through relative links while people see editable attachment labels instead of storage names.
_Avoid_: Attachment folder, sidecar directory

**Detached Attachment**:
An attachment removed from a live note but retained in its source's vault for at least 30 days so the removal can be reversed. It is removed only by a later successful cleanup.
_Avoid_: Orphan, deleted attachment

**Read-only Note**:
A note whose source currently permits reading but not mutation. It remains searchable and allows viewing, downloading attachments, and copying into a writable source, while editing, moving, deleting, and attachment mutation are unavailable.
_Avoid_: Locked note, disabled note

**Automatic Title**:
A provisional title derived from the first nonblank content line after removing markup and limited to 80 visible characters. It becomes fixed when the draft is first closed, first synchronized, or explicitly renamed.
_Avoid_: Generated title, filename

**Title**:
A human-readable note label limited to 80 visible characters. A Local Source uses its sanitized title as the note filename, while an external source may further normalize the title.
_Avoid_: Filename, heading

**Empty Draft**:
A newly opened note with neither content nor an explicitly entered title. Closing it discards it without creating a Trash entry.
_Avoid_: Empty note, untitled note

**Revision**:
A recoverable local checkpoint of a note created before a meaningful or risky change. KGS Notes retains revisions for at least 30 days, capped at the latest 50 per note, and removes eligible revisions only during a later successful cleanup.
_Avoid_: Backup, version, autosave

**Restore**:
An operation that first creates a Revision of the current note and then makes an older Revision the new current content. The restored content synchronizes as an ordinary edit.
_Avoid_: Revert, rollback, overwrite

**Note Link**:
A standard relative Markdown link from one note to another note in the same source.
_Avoid_: Wiki link, internal link, KGS link

**Backlink**:
A locally derived reference showing which notes contain a Note Link to the current note. Backlinks are an index, not content stored in the note source.
_Avoid_: Incoming link, reverse link

**Import**:
An operation that copies portable notes into the Local Source without retaining a live connection to their prior location. Plain Markdown uses its filename as Title and folder path as Category; referenced files inside the chosen import root become Managed Attachments, while inaccessible or outside-root links remain reported External File References.
_Avoid_: Open folder, connect folder

**Portable Export**:
A snapshot of selected Markdown notes, category folders, and referenced attachments that omits KGS-only state and does not change their source or identity.
_Avoid_: KGS Archive, move, backup, sync

**Share**:
An outbound Android action that sends selected note content as plain text, a Markdown file, or a Portable Export. It does not create a public server link or collaborative note.
_Avoid_: Publish, collaborate, export source

**KGS Archive**:
A plain, unencrypted ZIP snapshot containing portable note content plus KGS metadata so IDs, favorites, empty categories, Trash state, and attachment ownership can be restored faithfully. Its contents may include revisions and other sensitive note data, so export must communicate that clearly.
_Avoid_: Portable Export, Android backup

**Archive Import**:
An additive restore from a KGS Archive. It previews its changes, never overwrites an existing note, preserves an archived identity only when unused, and assigns new identities while rewriting Note Links when collisions occur.
_Avoid_: Restore backup, replace library

**Local Metadata**:
Versioned, human-readable KGS state stored under the Local Source's hidden `.kgs/` directory. It preserves behavior that plain Markdown cannot represent without becoming note content.
_Avoid_: Frontmatter, database, cache

# Never use destructive content migrations

KGS Notes will version its canonical file, metadata, operation-journal, and projection schemas from the first release. Every upgrade path from a shipped schema must be tested and recoverable, and migration failure must preserve the old data for retry or explicit export rather than deleting and recreating canonical content. Rebuildable indexes may be discarded, but Notes, Managed Attachments, Local Metadata, pending operations, Trash, and Revisions may not.

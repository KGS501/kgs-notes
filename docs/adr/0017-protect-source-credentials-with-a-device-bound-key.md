# Protect source credentials with a device-bound key

Source app passwords and tokens will be stored in backup-excluded application storage and encrypted with a non-exportable Android Keystore key. They are removed only by an explicit disconnect or source deletion flow and are never included in Android backup, KGS Archive, logs, or diagnostics. This makes a restored installation require authentication again and prevents portable archives from silently carrying access to a server.

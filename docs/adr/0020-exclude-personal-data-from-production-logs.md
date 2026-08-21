# Exclude personal data from production logs

Production logs will contain only redacted event codes and technical failure classes. Note text, Titles, attachment names, credentials, server URLs, certificate material, and Source Names must never enter them, and routine application use will not create a diagnostic export. This reduces post-failure detail available to developers but keeps a private note-taking app from creating a second, less-protected record of personal content and infrastructure.

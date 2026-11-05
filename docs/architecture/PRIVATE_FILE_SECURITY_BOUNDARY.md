# Private file security boundary

The platform now has a server-mediated private-file boundary for assignment resources and learner submission evidence. Browsers never receive an object-storage bucket name, object key, public URL, or reusable signed URL. The same API and authorization model is intended for later learner, admission, workforce, finance, and generated-document contexts.

## Upload path

1. The API first verifies the active organisation, school membership, capability, teaching allocation, or accepted guardian relationship for the requested record.
2. The multipart body is bounded by both the HTTP multipart limit and `APP_FILES_MAX_SIZE_BYTES`.
3. The API writes the body to an operating-system quarantine file. The supplied content type and filename are not trusted.
4. Magic bytes identify PDF, JPEG, PNG, or WebP. Active formats such as HTML and SVG, archives, executables, and unknown formats are rejected.
5. A SHA-256 digest is calculated from the quarantined bytes.
6. The file is streamed to ClamAV with `INSTREAM`. Scanner failure, timeout, an invalid response, or a detection fails closed; rejected bytes are never uploaded to object storage.
7. Clean bytes are uploaded to a random tenant/school/date object key with an exact content length and SHA-256 checksum. The original filename is not part of the key.
8. A transaction re-checks the current context, inserts immutable metadata and lifecycle evidence, advances the assignment/submission optimistic version, and records a hash-chained security audit event.
9. If database persistence fails, the just-uploaded object is deleted as compensating cleanup. Any cleanup failure is retained as a suppressed infrastructure error for operations investigation.

## Download path

Every download re-evaluates the file's current context. A staff member needs both file-read and learning-read capability plus the assignment's effective teaching allocation, unless they have school learning moderation. A guardian needs an active accepted relationship to a frozen assignment recipient or to the learner whose submission owns the evidence. Draft assignment resources never become family-visible.

Authorized bytes are streamed through the API with `Content-Disposition: attachment`, `Cache-Control: private, no-store`, `X-Content-Type-Options: nosniff`, and a content length. Object-storage credentials and coordinates remain server-side.

## History and replacement

- File metadata, checksum, scanner identity, context, creator, and storage coordinates are immutable.
- Removing an editable attachment moves it from `AVAILABLE` to `EXPIRED`; rows are not deleted.
- Replacement inserts a new file linked through `replaces_file_id`, then expires the old row in the same database transaction.
- Published assignment resources are frozen. Submission evidence is editable only while the assignment window and response state permit it.
- `private_file_history`, learner submission history, and the security audit stream retain the actor, reason, state transition, and safe identifiers. Audit metadata never stores file bytes or a full original filename.

## Configurable assignment policy

Each assignment stores whether response attachments are allowed, the maximum count from one to ten, and whether an attachment is required. Response mode can be text, HTTPS link, text-or-link, private file, flexible, or offline. These are school-owned record settings rather than source-code constants.

## Launch gates and limitations

The initial allowlist is deliberately narrow. Office archives, video, audio, and SVG remain disabled until each format has a safe parser, decompression limits, preview policy, and school-approved need. Expired object-byte retention and eventual physical purge require a signed school retention schedule and a tested purge/restore job before production launch; metadata and audit evidence remain append-only. The PostgreSQL migration must be rehearsed against the production engine because Docker is not available in the current workstation environment.

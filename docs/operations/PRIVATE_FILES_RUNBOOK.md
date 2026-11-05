# Private files

The API stores metadata and authorization in PostgreSQL and file bytes in private S3-compatible storage. A private scanner must be available for the implemented upload boundary. Missing services are an operational configuration issue, not a reason to disable scanning or ownership checks.

## Storage identity

Provision the bucket separately and keep `S3_AUTO_CREATE_BUCKET=false`. Give the API a dedicated bucket-scoped identity with only the operations required by the adapter. Do not use a storage root, backup or administrator key. Deny anonymous access and unrelated buckets.

Select a maintained provider and verify the actual adapter operations, checksums, byte counts, history/versioning policy and denied operations. A compatible product name alone does not prove acceptance.

## Upload and download

Preserve type/size limits, quarantine state, scan outcomes and file-context permissions. The scanner should be reachable only on the private service network. Do not send school files to a public malware-analysis service.

Downloads must recheck current account, membership, school, relationship and document/source status. Verify stored length and hash before returning bytes. Treat receipts, report cards, certificates, payslips and staff documents according to their own access rules.

## Recovery

An encrypted recovery unit needs the database, referenced object versions and required configuration/keys. Restore it into an isolated environment and verify issued document availability, byte integrity and continued denial to other users. A database-only dump does not establish private-document recovery.

Keep uploads, object exports, keys, scan payloads and private document screenshots out of source control and build contexts.

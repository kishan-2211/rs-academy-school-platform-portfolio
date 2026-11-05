# Security

This application handles school, staff and children's information. The public repository contains source code and fictional fixtures, not a live school dataset.

## Reporting

Contact the repository owner privately using an established verified channel. Do not post passwords, personal records, private files or exploit details in a public issue. Include the affected version, a minimal reproduction and the authorization boundary involved.

## Supported code

The current `main` branch is the portfolio snapshot. No production release or security certification is implied by its publication.

## Core controls

- HttpOnly browser sessions, CSRF checks and privileged MFA policy.
- Active organisation/school membership and object-level capability checks.
- Parent-child and teacher-assignment boundaries backed by PostgreSQL row security.
- Separate migration-owner and non-owner runtime database credentials.
- Private, scanned files with authorized downloads and integrity verification.
- Retained audit, academic publication and financial correction history.

Production acceptance must verify these controls in the actual environment, including HTTPS, secrets, private storage/scanning, provider callbacks, backup/restore and session/recovery behavior. Never test a live school installation or another person's account without explicit authorization.

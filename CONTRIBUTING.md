# Contributing

Keep changes focused on one workflow and describe their effect on data, permissions and existing records. Use fictional records in tests and local demonstrations.

Before opening a pull request, run the web checks, API checks and OpenAPI validation listed in the README. Data changes need invalid-input, denied-access, cross-school, concurrency/retry and migration coverage as appropriate. Inspect changed UI at mobile and desktop widths with the keyboard.

Keep domain repositories private to their module. Check the record's school scope and current authority in the API, even when the browser already hides an action. Published results, financial entries and audit events must retain their history.

Do not include environment files, passwords, tokens, private uploads, database exports or dependency caches. Retain required third-party notices. Production policy and deployment decisions require review by the responsible school/operator.

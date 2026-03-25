# Publication Guardrails

This repository is intended for publication-safe architecture and engineering artifacts.

## Required Rules

- No local machine absolute paths in committed files.
- No personal identifiers in docs, examples, or logs.
- No secrets, credentials, or private keys in source control.
- Use placeholders for environment-specific paths and values.

## Validation Checklist

- Run leak scan for local paths and identities.
- Run secret-pattern scan before push.
- Verify README links resolve to repo-local files or valid URLs.

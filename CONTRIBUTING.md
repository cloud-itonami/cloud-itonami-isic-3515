# Contributing

`cloud-itonami-3515` accepts contributions to the OSS actor, policy tests,
documentation, examples and open business blueprint.

## Development

```bash
clojure -M:dev:test
clojure -M:lint
```

Keep changes small and include tests for policy, audit, store or disclosure
behavior.

## Rules

- Do not commit real employee data, credentials or customer documents.
- Keep production writes and disclosures behind PolicyGovernor.
- Treat HR workflows as high-risk: add tests for permission, purpose,
  fairness, minimal disclosure and audit logging.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests

PRs should describe:

- what behavior changed
- which policy invariant is affected
- how it was tested
- whether operator or certification docs need updates

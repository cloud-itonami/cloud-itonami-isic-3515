# Governance

`cloud-itonami-3515` is an OSS open-business blueprint. Governance covers both
code and the operator model.

## Maintainers

Maintainers may merge changes that preserve these invariants:

- HR-LLM cannot directly commit or disclose records.
- PolicyGovernor remains independent of the advisor.
- hard policy violations cannot be overridden by human approval.
- every commit, hold and approval path is auditable.
- real employee data stays outside Git.

## Decision Records

Architecture decisions live in `docs/adr/`. Changes to the trust model,
storage contract, public business model, operator certification or license
should add or update an ADR.

## Operator Governance

Anyone may fork and operate independently. itonami.cloud certification is a
separate trust mark and should require security, audit, support and data-flow
review.

Certified operators can lose certification for:

- bypassing policy checks
- mishandling HR data
- misrepresenting certification status
- failing to respond to security incidents
- hiding material changes to customer-facing operation

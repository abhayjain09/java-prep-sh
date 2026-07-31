# security/

**Status:** Not started — coming in Module 6, after the REST API layer exists in `spring/`.

Planned coverage:
- Spring Security fundamentals: filter chain, authentication vs. authorization, method security.
- JWT issuing/validation for the Order/Inventory API, refresh tokens.
- OAuth2 / OIDC flows, integration concepts for OKTA, SCIM provisioning, MFA.
- RBAC applied to order/inventory operations (e.g. only managers can adjust stock).
- CORS and CSRF: what they protect against, when each applies (stateless API vs. session-based).
- Token validation filters and common misconfigurations interviewers probe for.

See the root [README.md](../README.md) for the full module roadmap and current progress.

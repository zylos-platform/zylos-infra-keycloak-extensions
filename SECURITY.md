# Security Policy

Report security issues privately via GitHub's "Security advisories" tab.

## Scope

This is a Keycloak provider. In-scope vulnerabilities:

- Incorrect actor identification (e.g., recording the wrong client as actor)
- Bypass of mapper activation (e.g., conditions where mapper should run but doesn't)
- Information leakage in the `act` claim (e.g., exposing sensitive client metadata)

Out of scope: vulnerabilities in Keycloak itself (report to upstream).

# zylos-infra-keycloak-extensions

> Custom Keycloak SPI extensions for the Zylos platform.

## Extensions

### ActClaimMapper

Populates the RFC 8693 `act` claim on tokens issued via Standard Token Exchange V2 (Keycloak 26.6+). The claim records
the immediate **requesting client** (the actor in delegation semantics):

```json
{
  "sub": "alice",
  "aud": "zylos-internal-pricing",
  "act": {
    "client_id": "zylos-gateway"
  }
}
```

**Scope:** single-hop only. Multi-hop chains (nested `act` recording the full delegation history) are not supported in
this version; see `docs/adr/0001-act-claim-mapper-design.md` for the rationale and Phase 2 plan.

## Why

Keycloak's Standard Token Exchange V2 implementation defaults to **impersonation semantics** — the exchanged token
replaces the subject context without recording the requesting party as an actor. This is consistent with the OAuth 2.0
spec (which doesn't mandate `act` population) but it eliminates the delegation context that downstream services may want
for audit logging or chain-sensitive authorization decisions.

This extension restores delegation semantics by always populating `act` with the requesting client's identifier when a
token is issued via the exchange grant.

Tracked upstream: [keycloak/keycloak#38279](https://github.com/keycloak/keycloak/issues/38279).

## Deployment

Build the JAR:

```bash
./mvnw clean package
# Produces: target/zylos-keycloak-extensions-0.1.0.jar
```

Deploy to Keycloak (one of):

1. **Mount as volume:** mount the JAR into `/opt/keycloak/providers/` in the Keycloak pod, then run `kc.sh build` once
   at startup.
2. **Custom image:** layer the JAR onto `quay.io/keycloak/keycloak:26.6.1` via Dockerfile. Ships the official
   Zylos image build.

Activate the mapper for a client via realm-config-cli (see `docs/usage.md` for the YAML snippet) or via the admin UI
under: Clients → *client_id* → Client scopes → Add mapper from configured → **Zylos Act Claim Mapper**.

## Compatibility

- Keycloak 26.6.x
- Java 21 (Keycloak's runtime; not Java 25 — this repo deliberately doesn't inherit from `zylos-service-parent`)

## License

MIT. See [LICENSE](LICENSE).

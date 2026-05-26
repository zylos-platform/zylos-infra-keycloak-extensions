# ADR 0001: ActClaimMapper Design

- **Status:** Accepted
- **Date:** 2026-05-26
- **Relates to:** zylos-infra-gitops ADR 0011 (Token Exchange V2); zylos-infra-security-starter ADR 0003 (Actor Chain);

## Context

The Zylos Phase 1 architecture authorizes most endpoints
on direct-actor + user identity. A handful of sensitive endpoints
(payment, refund, admin, PII export) also match the delegation chain
against `permittedChains` declared in `actor-chains.yaml`.

For chain matching to work, tokens must carry an `act` claim identifying
the immediate caller. RFC 8693 defines this claim:

```json
{
  "sub": "alice",
  "act": {
    "sub": "https://gateway.example.com",
    "act": {
      "sub": "https://service-A.example.com"
    }
  }
}
```

**Keycloak 26's Standard Token Exchange V2 does not populate `act` by
default.** Upstream tracking: [keycloak/keycloak#38279](https://github.com/keycloak/keycloak/issues/38279). The V2
implementation
optimizes for impersonation semantics — the new token largely replaces
the subject context. For Zylos, this means realm configuration,
actor-chain validator, and integration tests all
ship assuming `act` will appear; without intervention, the chain
validator receives empty chains and rejects every request under
`allowEmptyChain: false`.

## Decision

Ship a custom Keycloak protocol mapper (`ActClaimMapper`) that, when
attached to a client, populates `act.client_id` with the immediate
requesting client's identifier on tokens issued via the exchange grant.

### Scope: single-hop only

The mapper writes ONLY the immediate requesting client. Multi-hop chains
(nested `act` preserving the full delegation history across multiple
exchanges) are deferred to Phase 2.

### Detection heuristic

A token is treated as exchange-issued when the authenticated client in
the current request context (`KeycloakSession.getContext().getClient()`)
differs from the client whose session is being built
(`ClientSessionContext.getClientSession().getClient()` — the new token's
target audience).

In a normal user login or client-credentials flow, these are the same
client — the mapper does nothing.

In an exchange flow, they differ by definition — the requesting party is
the client presenting credentials to the token endpoint; the target is
the audience the new token is bound to. The mapper records the
requesting party as the actor.

## Rationale

### Why a protocol mapper, not a `TokenExchangeProvider`

Three alternatives evaluated:

| Approach                       | Pros                                                                      | Cons                                                                                                       | Decision          |
|--------------------------------|---------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------|-------------------|
| **Protocol mapper (chosen)**   | Standard SPI extension; small surface; trivial to attach via realm config | Single-hop only; can't easily access subject_token's existing `act`                                        | ✓ Phase 1         |
| Custom `TokenExchangeProvider` | Full access to subject_token; can preserve nested chains                  | Replaces Keycloak's default exchange handler; high maintenance burden; risk of drift with upstream changes | Phase 2 candidate |
| Event listener + session notes | Can capture subject_token claims during exchange                          | Two SPI extensions to maintain; race conditions between listener and mapper                                | Rejected          |

The protocol mapper is the minimum viable solution. It addresses the
direct-actor question (which is what authorization actually needs)
without taking on the maintenance burden of replacing core Keycloak
exchange code.

### Why single-hop is sufficient for Phase 1

Under this model, the authorization-relevant question for sensitive
endpoints is "did this request come through an unexpected immediate
hop?" Examples:

- `/payments/initiate` should only be reachable via `zylos-gateway` directly,
  not via `zylos-internal-cart` calling on behalf of the user.
- `/admin/seller-payouts` should only be reachable via `zylos-internal-admin`,
  not via any other service relaying through.

These questions are answered by the immediate actor (one hop). Full
chain history (gateway → cart → checkout → payment) is mostly audit
material, not authorization material. For audit, distributed traces
(traceId, spanId — already in MDC) provide a separate, more
complete record of the call path.

### Why this repo is standalone

The mapper runs inside Keycloak's Quarkus JVM (Java 21), not Spring Boot
(Java 25). Inheriting from `zylos-service-parent` would force inappropriate
constraints (Java 25 bytecode, Spring Boot dependency management). This
repo deliberately stands alone with its own minimal build configuration.

## Trade-offs Accepted

- **Lost chain history beyond one hop.** A token exchanged twice — first
  by gateway, then by an intermediate service — loses the gateway from
  its `act` claim. Only the most recent actor is recorded. For Phase 1
  this is acceptable; for higher-assurance models it isn't.

- **Heuristic exchange detection.** Comparing requesting and target
  clients is a strong indicator of exchange but not airtight. Edge case:
  a user-login flow where the user's session was previously associated
  with a different client could theoretically produce false positives.
  In practice this doesn't happen in well-formed Keycloak deployments.

- **String-equality client comparison.** No support for client aliasing
  or grouped client identities. Each `client_id` is a distinct actor.

## Phase 2: Multi-Hop Support

When the platform needs nested `act` chains preserved across multiple
exchanges, the upgrade path is:

1. Replace this protocol mapper with a custom `TokenExchangeProvider`
   that handles the V2 grant flow and explicitly chains the new
   `act.client_id` under the subject_token's existing `act`.
2. The Spring side (`ActChainExtractor` in zylos-infra-security-starter)
   already handles nested chains correctly — no client-side changes.
3. Realm configuration changes minimally — the new provider replaces
   the default V2 implementation.

This is deferred to Phase 2 unless a concrete need emerges.

## Verification

- Unit tests in `ActClaimMapperTest` cover: exchange flow detection,
  normal-login no-op, client-credentials no-op, edge cases (null
  clients, blank client IDs), and the `isExchangeFlow` helper directly.
- Integration tests against a real Keycloak with the mapper deployed
  arrive in future PR.
- End-to-end tests via the updated security-starter integration tests
  arrive in future PR.

## References

- RFC 8693 §4.1: <https://datatracker.ietf.org/doc/html/rfc8693#section-4.1>
- Keycloak #38279 (delegation support tracking issue): <https://github.com/keycloak/keycloak/issues/38279>
- Keycloak protocol mapper SPI
  docs: <https://www.keycloak.org/docs-api/latest/javadocs/org/keycloak/protocol/oidc/mappers/package-summary.html>

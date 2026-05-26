# Usage Guide

## Attaching the mapper to a client

### Via realm-config-cli (recommended)

In the realm YAML, add to the client's `protocolMappers` array:

```yaml
clients:
  - clientId: zylos-gateway
    protocol: openid-connect
    serviceAccountsEnabled: true
    attributes:
      standard.token.exchange.enabled: "true"
    protocolMappers:
      - name: zylos-act-claim
        protocol: openid-connect
        protocolMapper: zylos-act-claim-mapper
        consentRequired: false
        config:
          # No config keys — behavior is fixed
          access.token.claim: "true"
          id.token.claim: "false"
          userinfo.token.claim: "false"
```

Attach the mapper to **every client that initiates token exchange** —
typically the gateway and any internal service that may exchange tokens
downstream.

### Via admin UI

1. Realm Console → Clients → *client_id*
2. Client scopes tab → *client_id*-dedicated → Add mapper → "Zylos Act Claim Mapper"
3. The mapper has no configuration — just attach it.

## Verifying it works

After deployment, perform a token exchange and inspect the result:

```bash
# 1. Get a token for the gateway via client_credentials
GATEWAY_TOKEN=$(curl -s -X POST "https://keycloak.zylos.local/realms/zylos/protocol/openid-connect/token" \
  -d "grant_type=client_credentials&client_id=zylos-gateway&client_secret=$GATEWAY_SECRET" \
  | jq -r .access_token)

# 2. Exchange for the target audience
EXCHANGED_TOKEN=$(curl -s -X POST "https://keycloak.zylos.local/realms/zylos/protocol/openid-connect/token" \
  -d "grant_type=urn:ietf:params:oauth:grant-type:token-exchange" \
  -d "client_id=zylos-gateway" \
  -d "client_secret=$GATEWAY_SECRET" \
  -d "subject_token=$GATEWAY_TOKEN" \
  -d "audience=zylos-internal-pricing" \
  | jq -r .access_token)

# 3. Decode the new token — verify act claim
echo $EXCHANGED_TOKEN | cut -d. -f2 | base64 -d 2>/dev/null | jq .act
# Expected output: { "client_id": "zylos-gateway" }
```

## Troubleshooting

**Mapper not appearing in admin UI:**

- Verify JAR is in `/opt/keycloak/providers/` (`ls -la` from inside the pod)
- Verify `kc.sh build` ran on startup (check pod logs for "Updating the configuration")
- Verify the `META-INF/services/org.keycloak.protocol.ProtocolMapper` file lists the FQN
- Restart the pod

**Act claim not populated despite mapper being attached:**

- Verify the token was issued via exchange grant (decode JWT and inspect `iss`, `azp`, and grant context)
- Check Keycloak logs at DEBUG level — the mapper logs whether it ran and why it skipped
- Verify the requesting client and target audience differ (mapper is no-op when they're the same)

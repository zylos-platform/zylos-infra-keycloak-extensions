package app.zylos.keycloak.mappers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;
import org.keycloak.models.*;
import org.keycloak.protocol.oidc.mappers.AbstractOIDCProtocolMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAccessTokenMapper;
import org.keycloak.protocol.oidc.mappers.OIDCIDTokenMapper;
import org.keycloak.protocol.oidc.mappers.UserInfoTokenMapper;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.IDToken;

/**
 * Populates the RFC 8693 {@code act} claim on tokens issued via Standard
 * Token Exchange V2.
 *
 * <p>When attached to a client, this mapper detects whether the current
 * token is being issued in response to a token-exchange request (versus a
 * direct user login or client-credentials flow). If so, it records the
 * <strong>requesting client</strong> as the actor:
 *
 * <pre>
 * {
 *   "sub": "alice",
 *   "aud": "zylos-internal-pricing",
 *   "act": {
 *     "client_id": "zylos-gateway"
 *   }
 * }
 * </pre>
 *
 * <p>Detection heuristic: if the authenticated client in the current
 * request context ({@code KeycloakSession.getContext().getClient()})
 * differs from the client whose session is being built (the new token's
 * target audience), this is treated as an exchange flow.
 *
 * <h2>Single-hop limitation</h2>
 *
 * <p>The mapper writes ONLY the immediate requesting client. Multi-hop
 * chains (nested {@code act} recording the full delegation history) are
 * NOT supported in this version. The {@code subject_token}'s existing
 * {@code act} claim, if any, is not preserved.
 *
 * <p>Rationale: from within a {@link AbstractOIDCProtocolMapper}, the
 * original {@code subject_token}'s claims are not directly accessible at
 * the time {@code setClaim} runs. Multi-hop preservation would require
 * either a custom {@code TokenExchangeProvider} SPI (replacing Keycloak's
 * default) or an event-listener-based note-storage mechanism. Both are
 * out of scope for Phase 1.
 *
 * <p>For Zylos authorization (most endpoints use direct-actor +
 * user; only sensitive endpoints use chain matching), knowing the
 * immediate caller is sufficient — that's what governs the
 * authorization decision under this model.
 *
 * <p>See ADR 0001 for the full design discussion and Phase 2 plan.
 */
public class ActClaimMapper extends AbstractOIDCProtocolMapper
        implements OIDCAccessTokenMapper, OIDCIDTokenMapper, UserInfoTokenMapper {

    /**
     * Stable provider id; referenced by realm-config-cli and the admin UI.
     * Changing this is a breaking change for existing realm configurations.
     */
    public static final String PROVIDER_ID = "zylos-act-claim-mapper";

    /**
     * The claim name written to the token. RFC 8693 specifies "act".
     */
    static final String ACT_CLAIM_NAME = "act";

    /**
     * Field within the act object identifying the actor.
     */
    static final String CLIENT_ID_FIELD = "client_id";

    private static final Logger LOG = Logger.getLogger(ActClaimMapper.class);

    private static final List<ProviderConfigProperty> CONFIG_PROPERTIES = new ArrayList<>();

    /**
     * A flow is treated as a token exchange when the authenticated client
     * in the current request ({@code requestingClient}) differs from the
     * client whose session is being built ({@code targetClient}). In a
     * normal user-login or client-credentials flow these are the same.
     */
    static boolean isExchangeFlow(ClientModel requestingClient, ClientModel targetClient) {
        if (requestingClient == null || targetClient == null) {
            return false;
        }

        String requesting = requestingClient.getClientId();
        String target = targetClient.getClientId();

        if (requesting == null || target == null) {
            return false;
        }
        return !requesting.equals(target);
    }

    private static String safeClientId(ClientModel client) {
        if (client == null) {
            return "<null>";
        }

        String clientId = client.getClientId();
        return clientId == null ? "<no-id>" : clientId;
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return CONFIG_PROPERTIES;
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayCategory() {
        return TOKEN_MAPPER_CATEGORY;
    }

    @Override
    public String getDisplayType() {
        return "Zylos Act Claim Mapper";
    }

    @Override
    public String getHelpText() {
        return "Adds an RFC 8693 'act' claim recording the requesting client (the actor) "
                + "during a token-exchange grant. Single-hop only — does not preserve nested "
                + "act chains from the subject_token. See ADR 0001 for design rationale.";
    }

    /**
     * Core mapping hook called by Keycloak when assembling an access token
     * (or ID token, or UserInfo response) for any client that has this
     * mapper attached.
     */
    @Override
    protected void setClaim(
            IDToken token,
            ProtocolMapperModel mappingModel,
            UserSessionModel userSession,
            KeycloakSession keycloakSession,
            ClientSessionContext clientSessionCtx) {

        ClientModel targetClient = clientSessionCtx.getClientSession().getClient();
        ClientModel requestingClient = keycloakSession.getContext().getClient();

        String grantType = null;
        var httpRequest = keycloakSession.getContext().getHttpRequest();

        if (httpRequest != null && httpRequest.getDecodedFormParameters() != null) {
            grantType = httpRequest.getDecodedFormParameters().getFirst("grant_type");
        }

        boolean isV2Exchange = "urn:ietf:params:oauth:grant-type:token-exchange".equals(grantType);

        if (!isExchangeFlow(requestingClient, targetClient) && !isV2Exchange) {
            LOG.debugv(
                    "ActClaimMapper: skipping — not an exchange flow (requesting={0}, target={1})",
                    safeClientId(requestingClient), safeClientId(targetClient));
            return;
        }

        String actorClientId = requestingClient.getClientId();
        if (actorClientId == null || actorClientId.isBlank()) {
            LOG.warnv(
                    "ActClaimMapper: requesting client has no client_id; skipping act claim for target={0}",
                    safeClientId(targetClient));
            return;
        }

        Map<String, Object> actClaim = Map.of(CLIENT_ID_FIELD, actorClientId);
        token.getOtherClaims().put(ACT_CLAIM_NAME, actClaim);

        LOG.debugv(
                "ActClaimMapper: wrote act.client_id={0} for target={1} (sub={2})",
                actorClientId, safeClientId(targetClient), token.getSubject());
    }
}

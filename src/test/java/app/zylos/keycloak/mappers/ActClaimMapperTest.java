package app.zylos.keycloak.mappers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.models.*;
import org.keycloak.protocol.oidc.mappers.AbstractOIDCProtocolMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAttributeMapperHelper;
import org.keycloak.representations.AccessToken;

class ActClaimMapperTest {

    private ActClaimMapper mapper;
    private AccessToken token;
    private KeycloakSession session;
    private KeycloakContext context;
    private ClientSessionContext clientSessionCtx;
    private AuthenticatedClientSessionModel clientSession;
    private ClientModel targetClient;

    // --- helpers -----------------------------------------------------------

    private static ClientModel mockClient(String clientId) {
        ClientModel client = mock(ClientModel.class);
        when(client.getClientId()).thenReturn(clientId);
        return client;
    }

    @BeforeEach
    void setUp() {
        mapper = new ActClaimMapper();
        token = new AccessToken();
        session = mock(KeycloakSession.class);
        context = mock(KeycloakContext.class);
        clientSessionCtx = mock(ClientSessionContext.class);
        clientSession = mock(AuthenticatedClientSessionModel.class);
        targetClient = mockClient("zylos-internal-pricing");

        when(session.getContext()).thenReturn(context);
        when(clientSessionCtx.getClientSession()).thenReturn(clientSession);
        when(clientSession.getClient()).thenReturn(targetClient);
    }

    @Test
    void writesActClaimOnExchangeFlow() {
        // Requesting client (the actor) differs from target client (the audience).
        ClientModel requestingClient = mockClient("zylos-gateway");
        when(context.getClient()).thenReturn(requestingClient);

        invokeSetClaim();

        assertThat(token.getOtherClaims()).containsKey(ActClaimMapper.ACT_CLAIM_NAME);
        @SuppressWarnings("unchecked")
        Map<String, Object> actClaim =
                (Map<String, Object>) token.getOtherClaims().get(ActClaimMapper.ACT_CLAIM_NAME);
        assertThat(actClaim).containsEntry(ActClaimMapper.CLIENT_ID_FIELD, "zylos-gateway");
    }

    @Test
    void doesNotWriteActOnDirectUserLogin() {
        // In a normal user login, requesting and target are the same client.
        when(context.getClient()).thenReturn(targetClient);

        invokeSetClaim();

        assertThat(token.getOtherClaims()).doesNotContainKey(ActClaimMapper.ACT_CLAIM_NAME);
    }

    @Test
    void doesNotWriteActOnClientCredentialsFlow() {
        // Service account flow: requesting client == target client.
        ClientModel selfClient = mockClient("zylos-internal-cron");
        when(context.getClient()).thenReturn(selfClient);
        when(clientSession.getClient()).thenReturn(selfClient);

        invokeSetClaim();

        assertThat(token.getOtherClaims()).doesNotContainKey(ActClaimMapper.ACT_CLAIM_NAME);
    }

    @Test
    void skipsWhenRequestingClientHasNoClientId() {
        ClientModel anonymousClient = mock(ClientModel.class);
        when(anonymousClient.getClientId()).thenReturn("");
        when(context.getClient()).thenReturn(anonymousClient);

        invokeSetClaim();

        assertThat(token.getOtherClaims()).doesNotContainKey(ActClaimMapper.ACT_CLAIM_NAME);
    }

    @Test
    void isExchangeFlowReturnsFalseForSameClient() {
        ClientModel same = mockClient("alice");
        assertThat(ActClaimMapper.isExchangeFlow(same, same)).isFalse();
    }

    @Test
    void isExchangeFlowReturnsTrueForDifferentClients() {
        ClientModel a = mockClient("zylos-gateway");
        ClientModel b = mockClient("zylos-internal-pricing");
        assertThat(ActClaimMapper.isExchangeFlow(a, b)).isTrue();
    }

    @Test
    void isExchangeFlowReturnsFalseForNulls() {
        ClientModel a = mockClient("zylos-gateway");
        assertThat(ActClaimMapper.isExchangeFlow(null, a)).isFalse();
        assertThat(ActClaimMapper.isExchangeFlow(a, null)).isFalse();
        assertThat(ActClaimMapper.isExchangeFlow(null, null)).isFalse();
    }

    @Test
    void mapperIdentityFieldsAreStable() {
        assertThat(mapper.getId()).isEqualTo(ActClaimMapper.PROVIDER_ID);
        assertThat(mapper.getDisplayCategory()).isEqualTo(AbstractOIDCProtocolMapper.TOKEN_MAPPER_CATEGORY);
        assertThat(mapper.getDisplayType()).isEqualTo("Zylos Act Claim Mapper");
        assertThat(mapper.getHelpText()).contains("RFC 8693", "actor", "Single-hop");
        assertThat(mapper.getConfigProperties()).isEmpty();
    }

    private void invokeSetClaim() {
        // setClaim is protected; we reach it via the public transform entry
        // points the test fixture is set up to satisfy. Direct package-level
        // invocation via reflection isn't needed since AbstractOIDCProtocolMapper's
        // transform methods funnel into setClaim.
        // For simplicity in unit tests we call setClaim through transformAccessToken,
        // which is the standard entry point the framework uses.
        UserSessionModel userSession = mock(UserSessionModel.class);
        ProtocolMapperModel mappingModel = new ProtocolMapperModel();

        Map<String, String> config = new HashMap<>();
        config.put(OIDCAttributeMapperHelper.INCLUDE_IN_ACCESS_TOKEN, "true");
        config.put(OIDCAttributeMapperHelper.INCLUDE_IN_ID_TOKEN, "true");
        mappingModel.setConfig(config);

        mapper.transformAccessToken(token, mappingModel, session, userSession, clientSessionCtx);
    }
}

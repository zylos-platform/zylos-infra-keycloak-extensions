package app.zylos.keycloak.mappers;

import static java.util.stream.StreamSupport.stream;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/**
 * End-to-end integration test for {@link ActClaimMapper}.
 *
 * <p>Spins up a vanilla Keycloak 26.6.1 container, mounts the freshly-built
 * provider JAR into {@code /opt/keycloak/providers/}, imports a test realm
 * with the mapper attached to {@code zylos-gateway}, performs a real RFC
 * 8693 token exchange, and asserts the {@code act} claim appears on the
 * exchanged token with the correct {@code client_id}.
 *
 * <p>This is the highest-confidence test of the mapper: every layer
 * (Keycloak token exchange handler, the mapper's {@code setClaim}, the
 * token serializer, the JWT structure) runs in a real container.
 *
 * <p>Container startup uses {@code start-dev} (dasniko's default) because
 * the provider JAR is added after the base image is built — running
 * {@code start --optimized} would fail since {@code kc.sh build} hasn't
 * indexed our provider in the vanilla image. {@code start-dev} runs the
 * build implicitly at startup. Slower (~15s vs ~5s) but correct for tests;
 * production deployments use the pre-built optimized image from Dockerfile.
 */
@Testcontainers
class ActClaimMapperIT {

    @Container
    static final KeycloakContainer KEYCLOAK = new KeycloakContainer("quay.io/keycloak/keycloak:26.6.1")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(findProviderJar()),
                    "/opt/keycloak/providers/zylos-keycloak-extensions.jar")
            .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("testcontainers.keycloak")))
            .withRealmImportFile("/integration/zylos-act-mapper-test-realm.json");

    private static final String REALM = "zylos-act-mapper-test";
    private static final String CLIENT_GATEWAY = "zylos-gateway";
    private static final String SECRET_GATEWAY = "dev-secret-gateway";
    private static final String CLIENT_PRICING = "zylos-internal-pricing";
    private static final String SECRET_PRICING = "dev-secret-pricing";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /**
     * Locate the freshly-built provider JAR in target/.
     *
     * <p>The {@code maven-failsafe-plugin} runs in the {@code verify} phase,
     * after {@code package}, so {@code target/*.jar} is guaranteed to exist
     * when this test runs.
     */
    private static Path findProviderJar() {
        Path targetDir = Paths.get("target");

        try (Stream<Path> entries = Files.list(targetDir)) {
            return entries.filter(p -> p.getFileName().toString().startsWith("zylos-keycloak-extensions-")
                            && p.getFileName().toString().endsWith(".jar")
                            && !p.getFileName().toString().contains("sources")
                            && !p.getFileName().toString().contains("javadoc"))
                    .findFirst()
                    .orElseThrow(() ->
                            new IllegalStateException("Provider JAR not found in target/; run `mvn package` first"));

        } catch (Exception e) {
            throw new IllegalStateException("Failed to locate provider JAR", e);
        }
    }

    @BeforeAll
    static void ensureContainerStarted() {
        // @Container manages lifecycle; this hook documents the intent.
        assertThat(KEYCLOAK.isRunning()).isTrue();
    }

    // --- helpers -----------------------------------------------------------

    private static URI tokenEndpoint() {
        return URI.create(KEYCLOAK.getAuthServerUrl() + "/realms/" + REALM + "/protocol/openid-connect/token");
    }

    private static String obtainToken(String clientId, String clientSecret) throws Exception {
        String form = "grant_type=client_credentials" + "&client_id=" + clientId + "&client_secret=" + clientSecret;

        HttpRequest request = HttpRequest.newBuilder(tokenEndpoint())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "client_credentials failed (" + response.statusCode() + "): " + response.body());
        }
        return MAPPER.readTree(response.body()).get("access_token").asText();
    }

    private static String exchangeToken(
            String requestingClient, String requestingSecret, String subjectToken, String targetAudience)
            throws Exception {

        String form = "grant_type=urn:ietf:params:oauth:grant-type:token-exchange"
                + "&client_id=" + requestingClient
                + "&client_secret=" + requestingSecret
                + "&subject_token=" + subjectToken
                + "&subject_token_type=urn:ietf:params:oauth:token-type:access_token"
                + "&audience=" + targetAudience;

        HttpRequest request = HttpRequest.newBuilder(tokenEndpoint())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "token-exchange failed (" + response.statusCode() + "): " + response.body());
        }
        return MAPPER.readTree(response.body()).get("access_token").asText();
    }

    private static JsonNode decodeClaims(String jwt) throws Exception {
        String[] parts = jwt.split("\\.");

        if (parts.length != 3) {
            throw new IllegalStateException("Not a valid JWT structure: " + parts.length + " parts");
        }
        byte[] claimsJson = Base64.getUrlDecoder().decode(parts[1]);
        return MAPPER.readTree(claimsJson);
    }

    private static List<String> audienceList(JsonNode aud) {
        if (aud.isArray()) {
            return stream(aud.spliterator(), false).map(JsonNode::asText).toList();
        }
        return List.of(aud.asText());
    }

    @Test
    void exchangePopulatesActClaim() throws Exception {
        // Get a service-account token for the gateway via client_credentials.
        String gatewayToken = obtainToken(CLIENT_GATEWAY, SECRET_GATEWAY);
        assertThat(gatewayToken).isNotBlank();

        // Exchange that token for one bound to zylos-internal-pricing.
        String exchangedToken = exchangeToken(CLIENT_GATEWAY, SECRET_GATEWAY, gatewayToken, CLIENT_PRICING);
        assertThat(exchangedToken).isNotBlank();

        // Decode and inspect the exchanged token's claims.
        JsonNode claims = decodeClaims(exchangedToken);

        // Assert audience is the target (sanity check on the exchange itself).
        JsonNode aud = claims.get("aud");
        assertThat(aud).isNotNull();
        List<String> audiences = audienceList(aud);
        assertThat(audiences).contains(CLIENT_PRICING);

        // THE KEY ASSERTION: act claim is present with the requesting client.
        JsonNode actClaim = claims.get("act");
        assertThat(actClaim)
                .as("act claim must be present after token exchange when ActClaimMapper is attached")
                .isNotNull();
        assertThat(actClaim.get("client_id").asText())
                .as("act.client_id must record the requesting party (zylos-gateway)")
                .isEqualTo(CLIENT_GATEWAY);
    }

    @Test
    void directGrantDoesNotPopulateAct() throws Exception {
        // The mapper is attached to zylos-gateway. A direct client_credentials
        // call to zylos-gateway is NOT an exchange — requesting and target
        // clients are the same. Mapper should be a no-op.
        String directToken = obtainToken(CLIENT_GATEWAY, SECRET_GATEWAY);

        JsonNode claims = decodeClaims(directToken);

        assertThat(claims.has("act"))
                .as("No act claim should appear on a direct client_credentials token")
                .isFalse();
    }

    @Test
    void exchangeWithoutMapperAttachedDoesNotPopulateAct() throws Exception {
        // The realm attaches the mapper ONLY to zylos-gateway's client scope.
        // An exchange initiated by zylos-internal-pricing (which has no
        // mapper) targeting zylos-gateway should NOT produce an act claim.
        // This verifies the mapper is per-client, not global.
        String pricingToken = obtainToken(CLIENT_PRICING, SECRET_PRICING);
        String exchanged = exchangeToken(CLIENT_PRICING, SECRET_PRICING, pricingToken, CLIENT_GATEWAY);

        JsonNode claims = decodeClaims(exchanged);

        assertThat(claims.has("act"))
                .as("No act claim — pricing client doesn't have the mapper attached")
                .isFalse();
    }
}

# syntax=docker/dockerfile:1.7

# =============================================================================
# zylos-keycloak: custom Keycloak 26.6.1 image with the Zylos ActClaimMapper
# pre-installed and the build step baked in.
#
# Multi-stage build follows the Keycloak project's recommended pattern (see
# docs/server/containers.adoc upstream): the builder stage places the provider
# JAR into /opt/keycloak/providers/ BEFORE running `kc.sh build` so the build
# command indexes our provider; the final stage copies the entire built tree
# from the builder, preserving provider-JAR mtimes so `start --optimized`
# doesn't fail with "A provider JAR was updated since the last build".
#
# Build args:
#   KEYCLOAK_VERSION: pinned to match the cluster runtime
#   PROVIDER_JAR:     path to the provider JAR relative to the build context
#
# Build context expectations:
#   target/zylos-keycloak-extensions-${project.version}.jar already exists.
#   The `mvn package` phase produces this.
# =============================================================================

ARG KEYCLOAK_VERSION=26.6.1

# -----------------------------------------------------------------------------
# Builder stage: place provider JAR, configure build-time options, run kc.sh build.
# -----------------------------------------------------------------------------
FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION} AS builder

# Build-time options baked into the optimized image. These reflect Zylos's
# fixed deployment choices (Postgres via CloudNativePG, health and metrics
# enabled). Runtime env vars handle instance-specific config (hostname, DB URL,
# credentials).
ENV KC_DB=postgres
ENV KC_HEALTH_ENABLED=true
ENV KC_METRICS_ENABLED=true

# Copy the provider JAR. --chmod=644 ensures the JAR is readable by the
# keycloak user in the final image regardless of build-host umask.
COPY --chmod=644 target/zylos-keycloak-extensions-*.jar /opt/keycloak/providers/

# Run the build step: indexes providers, caches Quarkus augmentation, produces
# /opt/keycloak/lib/quarkus/ with the augmented application.
RUN /opt/keycloak/bin/kc.sh build

# -----------------------------------------------------------------------------
# Final stage: clean Keycloak image with builder's /opt/keycloak/ copied over.
# Copying the WHOLE /opt/keycloak/ (not just /opt/keycloak/lib/quarkus/) is
# critical — it preserves the provider JAR with the exact mtime kc.sh build
# saw, avoiding the well-documented "provider JAR was updated" startup error.
# -----------------------------------------------------------------------------
FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION}

COPY --from=builder /opt/keycloak/ /opt/keycloak/

# Same runtime env vars surfaced by the Zylos GitOps deployment — declared
# here so they're discoverable via `docker inspect` without needing to read
# the Helm values.
ENV KC_HOSTNAME_STRICT=false
ENV KC_HTTP_ENABLED=true

# OCI labels for traceability.
ARG ZYLOS_EXTENSIONS_VERSION
LABEL org.opencontainers.image.title="zylos-keycloak"
LABEL org.opencontainers.image.description="Keycloak 26.6.1 with Zylos ActClaimMapper"
LABEL org.opencontainers.image.source="https://github.com/zylos-platform/zylos-infra-keycloak-extensions"
LABEL org.opencontainers.image.version="${ZYLOS_EXTENSIONS_VERSION}"
LABEL org.opencontainers.image.licenses="MIT"
LABEL app.zylos.keycloak.base-version="26.6.1"
LABEL app.zylos.keycloak.extensions-version="${ZYLOS_EXTENSIONS_VERSION}"

EXPOSE 8080 8443 9000

# Use `start --optimized` since the build step already ran in the builder
# stage. ~5s startup vs ~30s for `start-dev`.
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start", "--optimized"]

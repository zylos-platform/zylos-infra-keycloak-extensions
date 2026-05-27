# Changelog

All notable changes to `zylos-infra-keycloak-extensions` documented here.

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versioning: [SemVer](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Multi-stage `Dockerfile` building a custom Keycloak 26.6.1 image with
  the ActClaimMapper pre-installed and `kc.sh build` baked in.
- GitHub Actions workflow publishing the image to GHCR on release
  (`ghcr.io/zylos-platform/keycloak`), multi-arch (amd64, arm64).
- `ActClaimMapperIT` — Testcontainers integration test against real
  Keycloak with the mapper JAR mounted; verifies act claim population
  via real RFC 8693 token exchange.
- Test realm (`src/test/resources/integration/test-realm.json`) with
  the mapper attached to one client and absent from another, exercising
  per-client mapper activation.
- Failsafe plugin configuration in `pom.xml` for `*IT.java` execution.

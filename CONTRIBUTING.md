# Contributing

## Branching

Trunk-based development. Short-lived feature branches prefixed `feat/`, `fix/`, `chore/`, `docs/`. Squash-merge to
`main` only.

## Commits

[Conventional Commits 1.0](https://www.conventionalcommits.org/en/v1.0.0/) enforced via commitlint:

```
feat(mapper): add nested act chain support via TokenExchangeProvider SPI
fix(mapper): handle null requesting client gracefully
docs(adr): clarify single-hop limitation
```

All commits must be SSH/GPG-signed.

## Pull Requests

- Use the PR template
- Tests for new code paths
- Coverage ≥ 80% (gate enforced by JaCoCo)
- `./mvnw spotless:check` passes (Palantir Java Format)
- One approving review required

## Local Build

```bash
./mvnw verify  # full build, tests, coverage
./mvnw spotless:apply  # auto-fix formatting
```

## Code Style

- Java 21 (Keycloak's runtime; this repo does NOT use Java 25)
- No Lombok — Keycloak's classloading is finicky; avoid annotation processors that aren't strictly necessary
- Palantir Java Format via Spotless

## Why standalone (no parent POM)

This repo runs inside Keycloak's Quarkus JVM, not Spring Boot. It would be incorrect to inherit from
`zylos-service-parent` (which is Spring-centric and Java 25). Keep this repo self-contained.

## Release Process

Tagged via GitHub Releases. CI publishes the JAR to GitHub Packages and, also publishes a custom Keycloak
Docker image to GHCR.

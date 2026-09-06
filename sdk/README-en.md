# SaaS Forge Java SDK

[简体中文](README.md)

This directory contains the first-release BOM, Java SDKs, and Spring Boot Starter for business services integrating with SaaS Forge.

## First-release artifacts

| Artifact | Status | Responsibility |
|---|---|---|
| `saas-forge-bom` | Published | Manages one compatible version for the four consumer artifacts below |
| `saas-forge-sdk-core` | Published | Low-level REST client generated from an explicitly safe OpenAPI subset |
| `saas-forge-sdk-auth` | Published | Public contracts for immutable Identity and Service Contexts and token verification |
| `saas-forge-sdk-tenant` | Published | Immutable Tenant Context snapshots and mandatory access |
| `saas-forge-spring-boot-starter` | Published | Route Catalog-driven HTTP receiver authentication and context wiring |

The Permission, Feature, Quota, and Audit SDKs are Reactor placeholders for later stages. They are absent from the BOM and Starter dependency graph and are skipped during Maven Central publication. The Starter's `saas-forge-http-route-catalog` dependency is a publishable internal support artifact and is not declared directly by business applications.

## Maven dependencies

Import the BOM and declare only the Starter. The four supported consumer artifacts do not need individual versions:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.github.crane0927</groupId>
            <artifactId>saas-forge-bom</artifactId>
            <version>${saas-forge.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>io.github.crane0927</groupId>
        <artifactId>saas-forge-spring-boot-starter</artifactId>
    </dependency>
</dependencies>
```

The application must provide User and Service Token signature-verification and revocation-checking adapters. Missing adapters fail application startup. Production JWKS discovery and caching and Redis-backed revocation adapters are outside this first release.

Business code constructor-injects the read-only accessors and never depends on the Starter's internal Spring Security principal:

```java
final class CurrentTenantService {
    private final IdentityContextAccessor identities;
    private final TenantContextAccessor tenants;

    CurrentTenantService(IdentityContextAccessor identities, TenantContextAccessor tenants) {
        this.identities = identities;
        this.tenants = tenants;
    }

    TenantContextSnapshot requireCurrent() {
        IdentityContext identity = identities.current().orElseThrow();
        TenantContextSnapshot tenant = tenants.requireCurrent();
        if (!identity.identityId().equals(tenant.identityId())) {
            throw new IllegalStateException("Identity and Tenant Context do not match");
        }
        return tenant;
    }
}
```

The application must explicitly provide `UserAccessTokenSignatureVerifier`, `UserAccessTokenContextRevocationChecker`, `ServiceAccessTokenSignatureVerifier`, and `ServiceAccessTokenRevocationChecker` beans. A missing bean prevents startup, and an indeterminate revocation status remains fail-closed. The fixture's in-memory keys and revocation checkers are test-only, not production implementation examples.

## REST client security boundary

`saas-forge-sdk-core` generates code only for formal OpenAPI v1 operations marked `x-saasforge-java-sdk: true`. The filtered view and generated sources exist only under `target` and are not independent contracts. Browser login, refresh, Password Setup, Context Selection, logout, and Tenant Context Switch are excluded, as are HttpOnly Cookie, `Origin`, and Fetch Metadata parameters.

Consumers explicitly configure the Gateway address and provide the Basic or Bearer credential required by each operation. The default `https://api.example.invalid` address cannot be deployed. Automatic retries, circuit breakers, domain façades, and a complete Problem Details exception layer are outside the first release.

## Release gates

[`public-api-allowlist.json`](public-api-allowlist.json) records the exact public packages and types allowed in the four consumer artifacts. Maven verification checks the BOM, Starter, publication whitelist, public signatures, JAR contents, implementation references, and transitive dependencies. It rejects internal Protobuf, gRPC, database, MyBatis, Repository, migration, and browser-security leakage. Every new public type requires an explicit allowlist change.

No invented Java binary-compatibility baseline is used before the first formal release. Later versions will compare against the actual published artifact.

## External consumer acceptance

[`sdk-external-consumer`](../test-support/sdk-external-consumer) uses its own Spring Boot parent and does not inherit this repository's parent POM or `dependencyManagement`. It integrates with saas-forge only through the BOM and Starter, then uses real HTTP under a dedicated test Route Catalog overlay to verify Tenant Context, fail-closed behavior, request cleanup, and startup failure:

```bash
./mvnw --batch-mode --no-transfer-progress \
  -Psdk-external-consumer-acceptance \
  -pl :saas-forge-external-consumer-fixture,:saas-forge-quality-gates \
  -am verify
```

This acceptance proves only the SDK/Starter consumer boundary actually exercised in the local Reactor. It is not Maven Central publication verification and does not replace the full-infrastructure Gateway-to-Starter acceptance in `scripts/verify-platform-mechanism-e2e.sh`.

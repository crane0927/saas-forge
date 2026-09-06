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

## REST client security boundary

`saas-forge-sdk-core` generates code only for formal OpenAPI v1 operations marked `x-saasforge-java-sdk: true`. The filtered view and generated sources exist only under `target` and are not independent contracts. Browser login, refresh, Password Setup, Context Selection, logout, and Tenant Context Switch are excluded, as are HttpOnly Cookie, `Origin`, and Fetch Metadata parameters.

Consumers explicitly configure the Gateway address and provide the Basic or Bearer credential required by each operation. The default `https://api.example.invalid` address cannot be deployed. Automatic retries, circuit breakers, domain façades, and a complete Problem Details exception layer are outside the first release.

## Release gates

[`public-api-allowlist.json`](public-api-allowlist.json) records the exact public packages and types allowed in the four consumer artifacts. Maven verification checks the BOM, Starter, publication whitelist, public signatures, JAR contents, implementation references, and transitive dependencies. It rejects internal Protobuf, gRPC, database, MyBatis, Repository, migration, and browser-security leakage. Every new public type requires an explicit allowlist change.

No invented Java binary-compatibility baseline is used before the first formal release. Later versions will compare against the actual published artifact.

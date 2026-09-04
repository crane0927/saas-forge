import assert from "node:assert/strict";
import test from "node:test";

import {
  assertGrpcHostPortPlan,
  assertSupportedService,
  callersAreReady,
  classifyServiceState,
  localIamCallerEnvironment,
  localIamEnvironment,
  nacosHosts,
  parseComposePs,
  stateOf,
} from "../local-service-replacement.mjs";

test("requires the fixed, unique loopback gRPC host-port assignments", () => {
  assert.doesNotThrow(() =>
    assertGrpcHostPortPlan({ iam: 9091, tenantAccess: 9092, entitlement: 9093 }),
  );
  assert.throws(
    () => assertGrpcHostPortPlan({ iam: 9091, tenantAccess: 9091, entitlement: 9093 }),
    /tenant-access-service.*9092/u,
  );
});

test("routes container callers to the documented Docker Desktop host entry", () => {
  assert.deepEqual(localIamCallerEnvironment(), {
    "entitlement-service": {
      IAM_GRPC_ADDRESS: "static://host.docker.internal:9091",
      IAM_HTTP_BASE_URL: "http://host.docker.internal:8081",
    },
    "tenant-access-service": {
      IAM_GRPC_ADDRESS: "static://host.docker.internal:9091",
      IAM_HTTP_BASE_URL: "http://host.docker.internal:8081",
    },
  });
});

test("requires every recreated caller to register exactly one healthy Nacos instance", () => {
  assert.equal(
    callersAreReady({
      "entitlement-service": [{ ip: "172.19.0.8", port: 8080 }],
      "tenant-access-service": [{ ip: "172.19.0.7", port: 8080 }],
    }),
    true,
  );
  assert.equal(
    callersAreReady({
      "entitlement-service": [],
      "tenant-access-service": [{ ip: "172.19.0.7", port: 8080 }],
    }),
    false,
  );
});

test("requires the explicit IAM service target", () => {
  assert.doesNotThrow(() => assertSupportedService("iam-service"));
  assert.throws(() => assertSupportedService("gateway"), /只支持 iam-service/u);
  assert.throws(() => assertSupportedService(undefined), /只支持 iam-service/u);
});

test("classifies container, local, unavailable, and duplicate IAM states", () => {
  assert.equal(
    classifyServiceState({
      containerRunning: true,
      healthyInstances: 1,
      localProcessRunning: false,
      localReady: false,
      localRegistered: false,
    }),
    "CONTAINER",
  );
  assert.equal(
    classifyServiceState({
      containerRunning: false,
      healthyInstances: 1,
      localProcessRunning: true,
      localReady: true,
      localRegistered: true,
    }),
    "LOCAL",
  );
  assert.equal(
    classifyServiceState({
      containerRunning: false,
      healthyInstances: 0,
      localProcessRunning: false,
      localReady: false,
      localRegistered: false,
    }),
    "UNAVAILABLE",
  );
  assert.equal(
    classifyServiceState({
      containerRunning: true,
      healthyInstances: 2,
      localProcessRunning: true,
      localReady: true,
      localRegistered: true,
    }),
    "DUPLICATE",
  );
});

test("parses Compose JSON-lines status without reading application logs", () => {
  assert.deepEqual(
    parseComposePs(
      [
        '{"Service":"iam-service","State":"running","ExitCode":0}',
        '{"Service":"iam-migrate","State":"exited","ExitCode":0}',
      ].join("\n"),
    ),
    [
      { Service: "iam-service", State: "running", ExitCode: 0 },
      { Service: "iam-migrate", State: "exited", ExitCode: 0 },
    ],
  );
});

test("prefers a running Compose entry when an old recreated container remains", () => {
  assert.equal(
    stateOf(
      [
        { Service: "iam-service", State: "created" },
        { Service: "iam-service", State: "running" },
      ],
      "iam-service",
    )?.State,
    "running",
  );
});

test("accepts only enabled and healthy IPv4 Nacos hosts", () => {
  assert.deepEqual(
    nacosHosts({
      data: {
        hosts: [
          { ip: "192.168.65.254", port: 8081, healthy: true, enabled: true },
          { ip: "192.168.65.2", port: 8080, healthy: false, enabled: true },
          { ip: "not-an-ip", port: 8080, healthy: true, enabled: true },
        ],
      },
    }),
    [{ ip: "192.168.65.254", port: 8081 }],
  );
  assert.deepEqual(
    nacosHosts({
      data: [
        { ip: "192.168.65.254", port: 8081, healthy: true, enabled: true },
      ],
    }),
    [{ ip: "192.168.65.254", port: 8081 }],
  );
  assert.deepEqual(nacosHosts({ data: [] }), []);
});

test("maps only IAM runtime settings to host-reachable infrastructure", () => {
  const environment = localIamEnvironment(
    {
      environment: {
        BROWSER_ROOT_DOMAIN: "saasforge.test",
        IAM_JWT_ISSUER: "https://api.saasforge.test",
        IAM_JWT_PEM_KEY_VERSION_REF: "local/dev/pem/1",
        NACOS_IAM_PASSWORD: "iam-password",
        NACOS_IAM_USERNAME: "iam-dev",
        PASSWORD_SETUP_PAGE_URI:
          "https://console.saasforge.test/password-setup",
        SMTP_FROM: "no-reply@saasforge.test",
        SPRING_DATA_REDIS_PASSWORD: "redis-password",
        SPRING_DATASOURCE_PASSWORD: "iam-app-password",
        SPRING_DATASOURCE_USERNAME: "iam_app",
      },
      grpcPorts: { iam: 9091, tenantAccess: 9092, entitlement: 9093 },
      nacosGrpcPort: 9848,
      nacosPort: 8848,
      serviceClientIdFile: "/secure/iam-client-id",
      serviceClientSecretFile: "/secure/iam-client-secret",
      signingKeyFile: "/secure/iam-key.pem",
    },
    "192.168.65.254",
  );

  assert.equal(environment.SERVER_PORT, "8081");
  assert.equal(environment.SPRING_GRPC_SERVER_PORT, "9091");
  assert.equal(environment.SPRING_CLOUD_NACOS_DISCOVERY_IP, "192.168.65.254");
  assert.equal(environment.NACOS_SERVER_ADDR, "127.0.0.1:8848");
  assert.equal(
    environment.SPRING_DATASOURCE_URL,
    "jdbc:postgresql://127.0.0.1:5432/iam_db",
  );
  assert.equal(environment.SPRING_DATA_REDIS_HOST, "127.0.0.1");
  assert.equal(environment.KAFKA_BOOTSTRAP_SERVERS, "127.0.0.1:29092");
  assert.equal(environment.SMTP_HOST, "127.0.0.1");
  assert.equal(environment.TENANT_ACCESS_GRPC_ADDRESS, "static://127.0.0.1:9092");
  assert.equal(
    environment.IAM_JWT_PEM_PRIVATE_KEY_LOCATION,
    "file:/secure/iam-key.pem",
  );
});

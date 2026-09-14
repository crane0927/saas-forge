import assert from 'node:assert/strict';
import test from 'node:test';
import {
  waitForHostHttps,
  recoverIsolatedTlsForwarding,
} from '../scripts/host-https-readiness.mjs';

const four = (value) =>
  Object.fromEntries(['platform', 'console', 'api', 'remote'].map((host) => [host, value]));
function fixture(probe, recoverForwarding) {
  let time = 0;
  return {
    probe,
    recoverForwarding,
    now: () => time,
    pause: async (ms) => {
      time += ms;
    },
    timeoutMs: 20_000,
  };
}

test('requires four trusted responses after one forwarding recovery', async () => {
  let restarts = 0;
  let callsAfterRestart = 0;
  const result = await waitForHostHttps(
    fixture(
      async () => {
        if (restarts) callsAfterRestart++;
        return four(restarts ? 200 : 'ERR_CONNECTION_CLOSED');
      },
      async () => {
        restarts++;
      },
    ),
  );
  assert.equal(restarts, 1);
  assert.equal(callsAfterRestart, 1);
  assert.equal(result.recovered, true);
});

test('persistent forwarding failure still fails after a single recovery', async () => {
  let restarts = 0;
  await assert.rejects(
    waitForHostHttps(
      fixture(
        async () => four('ERR_CONNECTION_RESET'),
        async () => {
          restarts++;
        },
      ),
    ),
    /did not become ready/,
  );
  assert.equal(restarts, 1);
});

for (const failure of [503, 'ERR_CERT_AUTHORITY_INVALID', 'ERR_CONNECTION_REFUSED']) {
  test(`does not restart for ${failure}`, async () => {
    let restarts = 0;
    await assert.rejects(
      waitForHostHttps(
        fixture(
          async () => four(failure),
          async () => {
            restarts++;
          },
        ),
      ),
      /did not become ready/,
    );
    assert.equal(restarts, 0);
  });
}

test('internal TLS failure prevents recovery from being treated as success', async () => {
  await assert.rejects(
    waitForHostHttps(
      fixture(
        async () => four('ERR_CONNECTION_CLOSED'),
        async () => {
          throw new Error('internal TLS unavailable');
        },
      ),
    ),
    /internal TLS unavailable/,
  );
});

const project = 'saas-forge-console-123-456-abcdef';
const container = 'a'.repeat(64);
for (const labels of [
  { 'com.docker.compose.project': 'developer', 'com.docker.compose.service': 'console-tls' },
  { 'com.docker.compose.project': project, 'com.docker.compose.service': 'iam-service' },
]) {
  test(`never restarts a container outside the isolated Edge: ${Object.values(labels).join('/')}`, () => {
    const calls = [];
    assert.throws(() =>
      recoverIsolatedTlsForwarding({
        project,
        container,
        urls: [],
        execute: (_command, args) => {
          calls.push(args[0]);
          return JSON.stringify(labels);
        },
      }),
    );
    assert.deepEqual(calls, ['inspect']);
  });
}

test('does not restart the owned Edge if its internal TLS check fails', () => {
  const calls = [];
  assert.throws(
    () =>
      recoverIsolatedTlsForwarding({
        project,
        container,
        urls: [],
        execute: (_command, args) => {
          calls.push(args[0]);
          if (args[0] === 'inspect')
            return JSON.stringify({
              'com.docker.compose.project': project,
              'com.docker.compose.service': 'console-tls',
            });
          throw new Error('internal TLS unavailable');
        },
      }),
    /internal TLS unavailable/,
  );
  assert.deepEqual(calls, ['inspect', 'exec']);
});

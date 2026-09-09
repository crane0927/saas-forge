import assert from 'node:assert/strict';
import test from 'node:test';
import { staticRemoteEvidence } from '../integration-test/static-remote-evidence.mjs';

test('retains observed Remote metadata and rendering without header values or response bodies', () => {
  const evidence = staticRemoteEvidence({
    passed: true,
    tenantOrigin: 'https://console.saasforge.test',
    records: [
      {
        path: '/static-acceptance/v1/remote.js',
        method: 'GET',
        status: 200,
        credentials: [],
        allowOrigin: 'https://console.saasforge.test',
        allowCredentials: null,
        contentType: 'text/javascript; charset=utf-8',
        headers: { cookie: 'PRIVATE_SECRET' },
        body: 'PRIVATE_SECRET',
      },
    ],
    errors: [],
    rendering: [
      { version: 'v1', moduleExecuted: true, borderTopWidth: '7px', imageDimensions: [24, 16] },
    ],
  });
  assert.equal(evidence.status, 'passed');
  assert.deepEqual(evidence.requests, [
    {
      path: '/static-acceptance/v1/remote.js',
      method: 'GET',
      status: 200,
      credentialHeaders: [],
      allowOrigin: 'https://console.saasforge.test',
      allowCredentials: 'absent',
      contentType: 'text/javascript; charset=utf-8',
    },
  ]);
  assert.deepEqual(evidence.rendering, [
    { version: 'v1', moduleExecuted: true, borderTopWidth: '7px', imageDimensions: [24, 16] },
  ]);
  assert.deepEqual(evidence.consoleErrors, []);
  assert.doesNotMatch(JSON.stringify(evidence), /PRIVATE_SECRET/);
});

test('failure evidence distinguishes missing observations and strips unexpected values', () => {
  const evidence = staticRemoteEvidence({
    passed: false,
    tenantOrigin: 'PRIVATE_SECRET',
    records: [
      {
        path: '/static-acceptance/v1/remote.js?token=PRIVATE_SECRET',
        method: 'PRIVATE_SECRET',
        status: 'PRIVATE_SECRET',
        credentials: ['cookie', 'authorization', 'x-sf-csrf', 'PRIVATE_SECRET'],
        allowOrigin: 'PRIVATE_SECRET',
        allowCredentials: 'PRIVATE_SECRET',
        contentType: 'PRIVATE_SECRET',
      },
      {},
    ],
    errors: ['console-error', 'pageerror', 'PRIVATE_SECRET'],
    rendering: [
      {
        version: 'PRIVATE_SECRET',
        moduleExecuted: false,
        borderTopWidth: 'PRIVATE_SECRET',
        imageDimensions: ['PRIVATE_SECRET', -1],
      },
    ],
  });
  assert.equal(evidence.status, 'failed');
  assert.deepEqual(evidence.requests[0].credentialHeaders, [
    'cookie',
    'authorization',
    'x-sf-csrf',
  ]);
  assert.equal(evidence.requests[1].credentialHeaders, null);
  assert.equal(evidence.requests[1].status, null);
  assert.equal(evidence.requests[1].allowCredentials, 'present-or-unobserved');
  assert.deepEqual(evidence.consoleErrors, ['console-error', 'pageerror', 'unknown-error']);
  assert.doesNotMatch(JSON.stringify(evidence), /PRIVATE_SECRET/);
});

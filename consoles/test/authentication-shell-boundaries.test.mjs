import assert from 'node:assert/strict';
import test from 'node:test';

import { findAuthenticationShellBoundaryViolations } from '../scripts/check-authentication-shell-boundaries.mjs';

test('keeps the shared React Shell and Platform host inside the authentication boundary', async () => {
  assert.deepEqual(await findAuthenticationShellBoundaryViolations(), []);
});

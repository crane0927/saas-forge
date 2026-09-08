import { readFile } from 'node:fs/promises';

// Compose 的 Command、Labels、错误文本等可能含凭据，只输出已知服务及枚举状态。
const services = new Set([
  'postgres',
  'redis',
  'kafka',
  'mailpit',
  'otel-collector',
  'nacos',
  'nacos-init',
  'iam-migrate',
  'tenant-access-migrate',
  'entitlement-migrate',
  'audit-migrate',
  'iam-service',
  'tenant-access-service',
  'entitlement-service',
  'audit-service',
  'gateway',
  'platform-console',
  'tenant-console',
  'console-tls',
]);
const states = new Set([
  'created',
  'running',
  'paused',
  'restarting',
  'removing',
  'exited',
  'dead',
]);
const healthStates = new Set(['healthy', 'unhealthy', 'starting']);
try {
  const source = (await readFile(process.argv[2], 'utf8')).trim();
  const entries = source.startsWith('[')
    ? JSON.parse(source)
    : source
        .split('\n')
        .filter(Boolean)
        .map((line) => JSON.parse(line));
  for (const entry of entries) {
    if (!entry || !services.has(entry.Service)) continue;
    const state = states.has(entry.State) ? entry.State : 'unknown';
    const health = healthStates.has(entry.Health) ? entry.Health : 'none';
    const exitCode =
      Number.isInteger(entry.ExitCode) && entry.ExitCode >= 0 && entry.ExitCode <= 255
        ? entry.ExitCode
        : 'unknown';
    console.info(
      `COMPOSE: service=${entry.Service} state=${state} health=${health} exit=${exitCode}`,
    );
  }
} catch {
  console.info('COMPOSE: status unavailable');
}

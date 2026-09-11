import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import path from 'node:path';
const [stage, status] = process.argv.slice(2);
if (
  !/^[a-z][a-z0-9-]*$/.test(stage ?? '') ||
  !['running', 'passed', 'failed', 'blocked'].includes(status)
)
  throw new Error('invalid acceptance result');
const directory = process.env.SF_BRAND_EVIDENCE_DIRECTORY;
if (!directory) throw new Error('acceptance evidence directory required');
await mkdir(directory, { recursive: true, mode: 0o700 });
const file = path.join(directory, 'acceptance-run.json');
const report =
  stage === 'preflight' && status === 'running'
    ? {
        recordedAt: new Date().toISOString(),
        commit: execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim(),
        dirty: Boolean(
          execFileSync('git', ['status', '--porcelain', '--untracked-files=no'], {
            encoding: 'utf8',
          }).trim(),
        ),
        mode: 'fresh-compose',
        target: process.env.SF_ACCEPTANCE_TARGET ?? 'local',
        scope: process.env.SF_ACCEPTANCE_SCOPE ?? 'full',
        status: 'running',
        stages: [],
        channels: [{ browser: 'chrome', status: 'not-run' }],
      }
    : JSON.parse(await readFile(file, 'utf8'));
if (process.env.SF_ACCEPTANCE_PROJECT) report.project = process.env.SF_ACCEPTANCE_PROJECT;
if (stage === 'complete') report.status = status;
else {
  const channel = report.channels.find((item) => `product-${item.browser}` === stage);
  if (channel) channel.status = status;
  const existing = report.stages.find((item) => item.name === stage);
  if (existing) existing.status = status;
  else report.stages.push({ name: stage, status });
}
await writeFile(file, JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });

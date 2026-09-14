import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { setTimeout } from 'node:timers/promises';

/** 宿主转发恢复仅允许一次；恢复后仍须四域正常证书校验全部返回 200。 */
export async function waitForHostHttps({
  probe,
  recoverForwarding,
  now = Date.now,
  pause = setTimeout,
  timeoutMs = 180_000,
}) {
  const startedAt = now();
  let recovered = false;
  let observations;
  while (now() - startedAt < timeoutMs) {
    observations = await probe();
    const statuses = Object.values(observations);
    if (statuses.length === 4 && statuses.every((value) => value === 200))
      return { observations, recovered };
    // 给正常启动留出时间；HTTP/证书错误不属于端口转发故障，不能触发重启。
    if (
      !recovered &&
      now() - startedAt >= 15_000 &&
      statuses.length === 4 &&
      statuses.every((value) => ['ERR_CONNECTION_CLOSED', 'ERR_CONNECTION_RESET'].includes(value))
    ) {
      recovered = true;
      await recoverForwarding();
    }
    await pause(500);
  }
  throw new Error(`host HTTPS entrypoints did not become ready: ${JSON.stringify(observations)}`);
}

export function recoverIsolatedTlsForwarding({ project, container, urls, execute = execFileSync }) {
  assert.match(project ?? '', /^saas-forge-console-\d+-\d+-[a-f0-9]{6}$/);
  assert.match(container ?? '', /^[a-f0-9]{64}$/);
  const labels = JSON.parse(
    execute('docker', ['inspect', '--format', '{{json .Config.Labels}}', container], {
      encoding: 'utf8',
    }),
  );
  assert.equal(labels['com.docker.compose.project'], project);
  assert.equal(labels['com.docker.compose.service'], 'console-tls');
  // 内部探针只诊断转发：校对实际证书指纹并要求四域 HTTP 200。
  // 外部 Chrome 的证书链、域名与信任校验始终保持启用。
  const internalProbe = `
  const https = require('https');
  const fs = require('fs');
  const { X509Certificate } = require('crypto');
  const expected = new X509Certificate(fs.readFileSync('/run/secrets/tls-cert.pem')).fingerprint256;
  Promise.all(${JSON.stringify(urls)}.map(url => new Promise((resolve, reject) => {
    const target = new URL(url);
    const request = https.get({host:'127.0.0.1',port:8443,servername:target.hostname,
      path:target.pathname,headers:{host:target.hostname},rejectUnauthorized:false}, response => {
      if (response.socket.getPeerCertificate().fingerprint256 !== expected || response.statusCode !== 200)
        reject(new Error('internal TLS probe rejected'));
      response.resume();
      response.on('end', resolve);
      response.on('error', reject);
    });
    request.setTimeout(5000, () => request.destroy(new Error('internal TLS probe timeout')));
    request.on('error', reject);
  }))).then(() => process.exit(0), () => process.exit(1));
`;
  execute('docker', ['exec', container, 'node', '-e', internalProbe], {
    timeout: 10_000,
    stdio: 'pipe',
  });
  console.info(
    'Recovering confirmed host forwarding failure for this isolated console-tls container once',
  );
  execute('docker', ['restart', container], { timeout: 30_000, stdio: 'pipe' });
}

import { X509Certificate, createPublicKey } from 'node:crypto';
import { lookup } from 'node:dns/promises';
import { readFile } from 'node:fs/promises';
import { createServer } from 'node:https';
import { createConnection } from 'node:net';
import { isAbsolute } from 'node:path';
import { chromium, firefox, webkit } from 'playwright';

const rootDomain = process.env.SF_ACCEPTANCE_ROOT_DOMAIN ?? 'saasforge.test';
// 对照实验仅允许已批准的两个根域；不能把验收代理开放给任意 Host。
if (!['saasforge.test', 'saasforge.example.com'].includes(rootDomain)) {
  throw new Error('unsupported acceptance root domain');
}

const problems = [];
const target = process.env.SF_ACCEPTANCE_TARGET ?? 'local';
if (!['local', 'ci'].includes(target)) throw new Error('SF_ACCEPTANCE_TARGET must be local or ci');
function blocked(message) {
  problems.push(message);
  console.error(`BLOCKED: ${message}`);
}
const hosts = ['platform', 'console', 'api'].map((name) => `${name}.${rootDomain}`);
if (process.version !== 'v24.14.1') blocked('需要 Node 24.14.1');

const certFile = process.env.SF_ACCEPTANCE_TLS_CERT;
const keyFile = process.env.SF_ACCEPTANCE_TLS_KEY;
if (!certFile || !keyFile || !isAbsolute(certFile) || !isAbsolute(keyFile)) {
  blocked('请以绝对路径设置 SF_ACCEPTANCE_TLS_CERT 和 SF_ACCEPTANCE_TLS_KEY');
} else {
  try {
    const cert = new X509Certificate(await readFile(certFile));
    const publicKey = createPublicKey(await readFile(keyFile));
    if (!cert.publicKey.equals(publicKey)) blocked('TLS 证书与私钥不匹配');
    if (Date.parse(cert.validFrom) > Date.now() || Date.parse(cert.validTo) <= Date.now()) {
      blocked('TLS 证书不在有效期内');
    }
    for (const host of hosts) {
      if (!cert.checkHost(host)) blocked(`TLS 证书不覆盖 ${host}`);
    }
  } catch {
    blocked('无法读取或解析 TLS 证书/私钥');
  }
}

for (const host of hosts) {
  try {
    const addresses = await lookup(host, { all: true });
    if (!addresses.length || addresses.some(({ address }) => address !== '127.0.0.1')) {
      blocked(`${host} 必须仅解析到 127.0.0.1`);
    }
  } catch {
    blocked(`${host} 尚未配置本地 DNS/hosts`);
  }
}

await new Promise((done) => {
  // Docker 负责发布低端口；非 root Node 的 EACCES 不代表 Docker 不能绑定 443。
  const socket = createConnection({ host: '127.0.0.1', port: 443 });
  socket.once('error', (error) => {
    if (error.code !== 'ECONNREFUSED') blocked('无法检查 127.0.0.1:443 是否已被占用');
    done();
  });
  socket.once('connect', () => {
    blocked('127.0.0.1:443 已有监听服务，不能覆盖现有环境');
    socket.destroy();
    done();
  });
});

const browsers = [
  ['Chromium', chromium],
  ['WebKit', webkit],
  ['Chrome', chromium, 'chrome'],
];
if (target === 'ci') browsers.push(['Firefox', firefox], ['Microsoft Edge', chromium, 'msedge']);
else
  console.info(
    'SCOPE: local；Firefox 与 Microsoft Edge 必须由真实产品 CI 补齐，不能据此声明聚合通过',
  );
// 在长时间构建前验证浏览器实际信任；临时监听仅绑定回环随机端口，不替代正式 443 产品路径。
let tlsServer;
if (problems.length === 0) {
  tlsServer = createServer(
    { cert: await readFile(certFile), key: await readFile(keyFile) },
    (_request, response) => response.end('TLS acceptance preflight'),
  );
  await new Promise((resolve, reject) => {
    tlsServer.once('error', reject);
    tlsServer.listen(0, '127.0.0.1', resolve);
  });
}
for (const [name, engine, channel] of browsers) {
  let browser;
  try {
    browser = await engine.launch({ channel });
    if (tlsServer) {
      const page = await browser.newPage({ ignoreHTTPSErrors: false });
      for (const host of hosts) {
        const response = await page.goto(`https://${host}:${tlsServer.address().port}/`, {
          timeout: 10_000,
        });
        if (response.status() !== 200) throw new Error('TLS preflight response unavailable');
      }
    }
    console.info(`READY: ${name} ${browser.version()}`);
  } catch (error) {
    const code = [
      'SEC_ERROR_UNKNOWN_ISSUER',
      'SEC_ERROR_EXPIRED_CERTIFICATE',
      'SSL_ERROR_BAD_CERT_DOMAIN',
      'MOZILLA_PKIX_ERROR_SELF_SIGNED_CERT',
      'NS_ERROR_UNKNOWN_HOST',
      'NS_ERROR_CONNECTION_REFUSED',
      'NS_ERROR_NET_RESET',
      'ERR_CERT_AUTHORITY_INVALID',
      'ERR_CERT_COMMON_NAME_INVALID',
      'ERR_CERT_DATE_INVALID',
    ].find((value) => error?.message?.includes(value));
    blocked(
      `${name} 无法启动或未通过真实 TLS 导航预检${code ? ` [${code}]` : ''}；不得跳过或忽略证书错误`,
    );
  } finally {
    await browser?.close();
  }
}
if (tlsServer) {
  tlsServer.closeAllConnections();
  await new Promise((resolve) => tlsServer.close(resolve));
}

if (problems.length) {
  process.exitCode = 1;
} else {
  console.info(`PASS: ${target} 的 DNS、证书材料、443 端口及浏览器 TLS 导航预检通过`);
  console.info('正式 443 产品路径仍须经过 Fresh Compose 和真实服务验证。');
}

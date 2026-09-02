import { X509Certificate, createPublicKey } from 'node:crypto';
import { lookup } from 'node:dns/promises';
import { readFile } from 'node:fs/promises';
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
for (const [name, engine, channel] of browsers) {
  try {
    const browser = await engine.launch({ channel });
    console.info(`READY: ${name} ${browser.version()}`);
    await browser.close();
  } catch {
    blocked(`${name} 无法启动；必须安装对应引擎/实机渠道，不得跳过`);
  }
}

if (problems.length) {
  process.exitCode = 1;
} else {
  console.info(`PASS: ${target} 的 DNS、证书材料、443 端口及浏览器渠道预检通过`);
  console.info('证书信任仍须由各浏览器在 ignoreHTTPSErrors=false 下访问真实 TLS 入口验证。');
}

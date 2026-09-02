import { createReadStream } from "node:fs";
import { readFile, stat } from "node:fs/promises";
import { createServer as httpServer, request } from "node:http";
import { createServer as httpsServer } from "node:https";
import { extname, resolve } from "node:path";

const rootDomain = process.env.SF_ACCEPTANCE_ROOT_DOMAIN ?? "saasforge.test";
// 对照实验仅允许已批准的两个根域；不能把验收代理开放给任意 Host。
if (!["saasforge.test", "saasforge.example.com"].includes(rootDomain)) {
  throw new Error("unsupported acceptance root domain");
}

// 仅供 Fresh Compose 验收：生产构建只读挂载，外部入口只接受三个正式受控 Host。
const targets = {
  [`platform.${rootDomain}`]: "platform-console",
  [`console.${rootDomain}`]: "tenant-console",
  [`api.${rootDomain}`]: "gateway",
};
const types = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".ico": "image/x-icon",
  ".woff2": "font/woff2",
};

function unavailable(response) {
  if (response.headersSent) return response.destroy();
  response.writeHead(502, {
    "Content-Type": "application/problem+json",
    "Cache-Control": "no-store",
  });
  response.end(JSON.stringify({ status: 502, code: "UPSTREAM_UNAVAILABLE" }));
}

function proxy(incoming, outgoing) {
  const url = new URL(incoming.url, `https://api.${rootDomain}`);
  const probe = url.searchParams.get("acceptanceProbe");
  // 浏览器工具不保证暴露 opaque 响应的原始安全头；只记录显式探针的枚举元数据。
  // 不记录任意 Origin 文本、其他请求头、Cookie 或请求体，也不补造/更改转发头。
  const observedProbe =
    incoming.headers.host === `api.${rootDomain}` &&
    incoming.method === "POST" &&
    url.pathname === "/api/v1/auth/logout" &&
    /^[0-9a-f-]{36}$/.test(probe ?? "");
  if (observedProbe) {
    console.info(JSON.stringify({
      event: "acceptance-browser-metadata",
      probe,
      origin: incoming.headers.origin === "null" ? "opaque" : "other",
      fetchSite: incoming.headers["sec-fetch-site"] === "cross-site" ? "cross-site" : "other",
    }));
  }
  const target =
    Object.hasOwn(targets, incoming.headers.host) &&
    targets[incoming.headers.host];
  if (!target) {
    outgoing.writeHead(421).end();
    return;
  }
  // Origin、Fetch Metadata 和 Cookie 原样交由真实 Gateway/IAM 验证，代理不替客户端补安全头。
  const upstream = request(
    {
      hostname: target,
      port: 8080,
      method: incoming.method,
      path: incoming.url,
      headers: { ...incoming.headers, connection: "close" },
    },
    (response) => {
      // CORS 拒绝可隐藏浏览器响应；只记录探针对应的拒绝状态和允许头是否存在。
      if (observedProbe) {
        console.info(JSON.stringify({
          event: "acceptance-browser-response",
          probe,
          status: response.statusCode === 403 ? 403 : "other",
          allowOrigin: Object.hasOwn(response.headers, "access-control-allow-origin"),
        }));
      }
      outgoing.writeHead(response.statusCode, response.headers);
      response.on("error", () => outgoing.destroy());
      response.pipe(outgoing);
    },
  );
  upstream.on("error", () => unavailable(outgoing));
  incoming.on("aborted", () => upstream.destroy());
  outgoing.on("close", () => upstream.destroy());
  incoming.pipe(upstream);
}

async function serve(incoming, outgoing) {
  if (!["GET", "HEAD"].includes(incoming.method)) {
    outgoing.writeHead(405, { Allow: "GET, HEAD" }).end();
    return;
  }
  let pathname;
  try {
    pathname = decodeURIComponent(
      new URL(incoming.url, "http://static").pathname,
    );
  } catch {
    outgoing.writeHead(400).end();
    return;
  }
  outgoing.setHeader("X-Content-Type-Options", "nosniff");
  outgoing.setHeader("Referrer-Policy", "no-referrer");
  // 公开 Client 的真实 HTTP 验收入口，与两个 Console 生产包独立挂载。
  if (pathname === "/acceptance-client.html") {
    outgoing.writeHead(200, { "Content-Type": types[".html"], "Cache-Control": "no-store" });
    outgoing.end(incoming.method === "HEAD" ? undefined :
      '<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>公开 Client 验收</title><body><main>公开 Client HTTP 验收</main></body></html>');
    return;
  }
  if (pathname === "/acceptance-runtime.js") {
    const source = await readFile("/app/acceptance/runtime.js");
    outgoing.writeHead(200, { "Content-Type": types[".js"], "Cache-Control": "no-store" });
    outgoing.end(incoming.method === "HEAD" ? undefined : source);
    return;
  }
  // 仅供隔离验收品牌夹具使用；素材仍通过相同的受信 TLS 静态入口读取。
  const brandAssets = {
    "/acceptance-brands/blue.svg": "#155EEF",
    "/acceptance-brands/violet.svg": "#7C3AED",
  };
  if (Object.hasOwn(brandAssets, pathname)) {
    outgoing.writeHead(200, {
      "Content-Type": types[".svg"],
      "Cache-Control": "no-store",
    });
    outgoing.end(incoming.method === "HEAD" ? undefined :
      `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32"><rect width="32" height="32" rx="6" fill="${brandAssets[pathname]}"/></svg>`);
    return;
  }
  if (pathname === "/runtime-config.json") {
    outgoing.writeHead(200, {
      "Content-Type": types[".json"],
      "Cache-Control": "no-store",
    });
    outgoing.end(
      incoming.method === "HEAD"
        ? undefined
        : JSON.stringify({
            schemaVersion: 1,
            apiBaseUrl: `https://api.${rootDomain}`,
          }),
    );
    return;
  }
  const root = "/app/dist";
  let filename = resolve(root, `.${pathname}`);
  if (!filename.startsWith(`${root}/`) && filename !== root) {
    outgoing.writeHead(404).end();
    return;
  }
  let info = await stat(filename).catch(() => undefined);
  if (
    !info?.isFile() &&
    incoming.headers.accept?.includes("text/html") &&
    !extname(pathname)
  ) {
    filename = `${root}/index.html`;
    info = await stat(filename).catch(() => undefined);
  }
  if (!info?.isFile()) {
    outgoing.writeHead(404).end();
    return;
  }
  outgoing.writeHead(200, {
    "Content-Type": types[extname(filename)] ?? "application/octet-stream",
    "Content-Length": info.size,
    "Cache-Control":
      extname(filename) === ".html"
        ? "no-store"
        : "public, max-age=31536000, immutable",
  });
  if (incoming.method === "HEAD") outgoing.end();
  else
    createReadStream(filename)
      .on("error", () => outgoing.destroy())
      .pipe(outgoing);
}

const edge = process.env.SF_CONSOLE_SERVICE === "edge";
const server = edge
  ? httpsServer(
      {
        cert: await readFile("/run/secrets/tls-cert.pem"),
        key: await readFile("/run/secrets/tls-key.pem"),
        minVersion: "TLSv1.2",
      },
      proxy,
    )
  : httpServer((incoming, outgoing) => {
      void serve(incoming, outgoing).catch(() => unavailable(outgoing));
    });
server.listen(edge ? 8443 : 8080, "0.0.0.0");
process.on("SIGTERM", () => server.close());

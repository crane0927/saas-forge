import { request } from "node:http";
import { createServer } from "node:https";
import { readFile } from "node:fs/promises";

const viteHost = process.env.SF_LOCAL_HTTPS_VITE_HOST ?? "host.docker.internal";
const vitePort = Number.parseInt(
  process.env.SF_LOCAL_HTTPS_VITE_PORT ?? "5173",
  10,
);
const apiTargetFile = process.env.SF_LOCAL_HTTPS_API_TARGET_FILE;

export function targetForHost(host) {
  if (host === "platform.saasforge.test")
    return { hostname: viteHost, port: vitePort };
  if (host === "api.saasforge.test") return { hostname: "gateway", port: 8080 };
  return undefined;
}

export function copyOriginalHeaders(headers) {
  return [...headers];
}

export function parseApiTarget(value) {
  let target;
  try {
    target = JSON.parse(value);
  } catch {
    return undefined;
  }
  if (
    (target?.hostname !== "gateway" &&
      target?.hostname !== "host.docker.internal") ||
    target?.port !== 8080
  ) {
    return undefined;
  }
  return target;
}

export function createEdgeServer({
  certificate,
  key,
  targets = defaultTargets(),
  apiTargetFile: configuredApiTargetFile = apiTargetFile,
}) {
  const server = createServer(
    {
      cert: certificate,
      key,
      minVersion: "TLSv1.2",
    },
    (incoming, outgoing) => proxy(incoming, outgoing, targets, configuredApiTargetFile),
  );
  server.on("upgrade", (incoming, socket, head) =>
    proxyUpgrade(incoming, socket, head, targets, configuredApiTargetFile),
  );
  return server;
}

function defaultTargets() {
  return {
    "platform.saasforge.test": { hostname: viteHost, port: vitePort },
    "api.saasforge.test": { hostname: "gateway", port: 8080 },
  };
}

function unavailable(response) {
  if (response.headersSent) {
    response.destroy();
    return;
  }
  response.writeHead(502, {
    "Content-Type": "application/problem+json",
    "Cache-Control": "no-store",
  });
  response.end(JSON.stringify({ status: 502, code: "UPSTREAM_UNAVAILABLE" }));
}

async function resolveTarget(host, targets, configuredApiTargetFile) {
  const target = targets[host];
  if (host !== "api.saasforge.test" || !configuredApiTargetFile) return target;
  let value;
  try {
    value = await readFile(configuredApiTargetFile, "utf8");
  } catch {
    return target;
  }
  return parseApiTarget(value);
}

async function proxy(incoming, outgoing, targets, configuredApiTargetFile) {
  const target = await resolveTarget(
    incoming.headers.host,
    targets,
    configuredApiTargetFile,
  );
  if (target === undefined) {
    outgoing.writeHead(421).end();
    return;
  }
  const upstream = request(
    {
      hostname: target.hostname,
      port: target.port,
      method: incoming.method,
      path: incoming.url,
      headers: copyOriginalHeaders(incoming.rawHeaders),
    },
    (response) => {
      outgoing.writeHead(
        response.statusCode ?? 502,
        copyOriginalHeaders(response.rawHeaders),
      );
      response.on("error", () => outgoing.destroy());
      response.pipe(outgoing);
    },
  );
  upstream.on("error", () => unavailable(outgoing));
  incoming.on("aborted", () => upstream.destroy());
  outgoing.on("close", () => upstream.destroy());
  incoming.pipe(upstream);
}

async function proxyUpgrade(incoming, socket, head, targets, configuredApiTargetFile) {
  const target = await resolveTarget(
    incoming.headers.host,
    targets,
    configuredApiTargetFile,
  );
  if (target === undefined) {
    socket.end("HTTP/1.1 421 Misdirected Request\r\nConnection: close\r\n\r\n");
    return;
  }
  const upstream = request({
    hostname: target.hostname,
    port: target.port,
    method: incoming.method,
    path: incoming.url,
    headers: copyOriginalHeaders(incoming.rawHeaders),
  });
  upstream.on("upgrade", (response, upstreamSocket, upstreamHead) => {
    socket.write(
      `HTTP/${response.httpVersion} ${String(response.statusCode)} ${response.statusMessage}\r\n`,
    );
    for (let index = 0; index < response.rawHeaders.length; index += 2) {
      socket.write(
        `${response.rawHeaders[index]}: ${response.rawHeaders[index + 1]}\r\n`,
      );
    }
    socket.write("\r\n");
    if (head.length > 0) upstreamSocket.write(head);
    if (upstreamHead.length > 0) socket.write(upstreamHead);
    upstreamSocket.on("error", () => socket.destroy());
    socket.on("error", () => upstreamSocket.destroy());
    upstreamSocket.pipe(socket);
    socket.pipe(upstreamSocket);
  });
  upstream.on("response", (response) => {
    socket.write(
      `HTTP/${response.httpVersion} ${String(response.statusCode)} ${response.statusMessage}\r\n`,
    );
    for (let index = 0; index < response.rawHeaders.length; index += 2) {
      socket.write(
        `${response.rawHeaders[index]}: ${response.rawHeaders[index + 1]}\r\n`,
      );
    }
    socket.write("\r\n");
    response.pipe(socket);
  });
  upstream.on("error", () => socket.destroy());
  upstream.end();
}

async function start() {
  const server = createEdgeServer({
    certificate: await readFile("/run/secrets/server.pem"),
    key: await readFile("/run/secrets/server.key"),
  });
  server.listen(8443, "0.0.0.0");
  process.on("SIGTERM", () => server.close());
}

if (process.argv[1] === new URL(import.meta.url).pathname) {
  await start();
}

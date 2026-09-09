import { readFile } from "node:fs/promises";

const artifactRoot = process.env.SF_REMOTE_STATIC_DIRECTORY
  ? new URL(`file://${process.env.SF_REMOTE_STATIC_DIRECTORY}/`)
  : new URL(
      "../../../consoles/dist/static-remote-acceptance/",
      import.meta.url,
    );
const contentTypes = {
  "remote.js": "text/javascript; charset=utf-8",
  "styles.css": "text/css; charset=utf-8",
  "image.svg": "image/svg+xml",
};

/** 仅交付已构建的验收静态制品；CORS 读取许可不是下载鉴权。E2E 可复用同一处理器与目录。 */
export async function serveRemoteStatic(incoming, outgoing) {
  outgoing.setHeader("Vary", "Origin");
  outgoing.setHeader("X-Content-Type-Options", "nosniff");
  if (incoming.headers.origin === "https://console.saasforge.test") {
    outgoing.setHeader(
      "Access-Control-Allow-Origin",
      "https://console.saasforge.test",
    );
  }
  if (!["GET", "HEAD"].includes(incoming.method)) {
    outgoing
      .writeHead(405, { Allow: "GET, HEAD", "Cache-Control": "no-store" })
      .end();
    return;
  }
  const match =
    /^\/static-acceptance\/(v1|v2)\/(remote\.js|styles\.css|image\.svg)(?:\?[^#]*)?$/u.exec(
      incoming.url,
    );
  if (!match) {
    outgoing
      .writeHead(404, {
        "Content-Type": "text/plain",
        "Cache-Control": "no-store",
      })
      .end("Not found");
    return;
  }
  try {
    const body = await readFile(
      new URL(`${match[1]}/${match[2]}`, artifactRoot),
    );
    outgoing
      .writeHead(200, {
        "Content-Type": contentTypes[match[2]],
        "Cache-Control": "public, max-age=31536000, immutable",
      })
      .end(incoming.method === "HEAD" ? undefined : body);
  } catch (error) {
    const missing = error.code === "ENOENT";
    outgoing
      .writeHead(missing ? 404 : 503, {
        "Content-Type": "text/plain",
        "Cache-Control": "no-store",
      })
      .end(missing ? "Not found" : "Unavailable");
  }
}

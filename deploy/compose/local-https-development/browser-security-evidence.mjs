const list = (value) =>
  String(value ?? "")
    .toLowerCase()
    .split(/[,\n]/)
    .map((v) => v.trim())
    .filter(Boolean)
    .sort();

// 仅记录带随机关联 ID 的验收攻击，不记录凭据值、正文或任意来源文本。
export function observeSecurityProbe(incoming, response, rootDomain) {
  const url = new URL(incoming.url, `https://api.${rootDomain}`);
  const probe = url.searchParams.get("sessionProbe");
  if (
    incoming.headers.host !== `api.${rootDomain}` ||
    !["/api/v1/auth/refresh", "/api/v1/auth/logout"].includes(url.pathname) ||
    !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(
      probe ?? "",
    )
  )
    return;
  {
    const h = incoming.headers;
    const allowedOrigins = ["platform", "console", "remote"].map(
      (host) => `https://${host}.${rootDomain}`,
    );
    const origin = (value) =>
      value == null
        ? null
        : value === "null" || allowedOrigins.includes(value)
          ? value
          : "other";
    const header = (name) => response.headers[name];
    console.info(
      JSON.stringify({
        event: "acceptance-session-security",
        probe,
        host: `api.${rootDomain}`,
        path: url.pathname,
        method: ["POST", "OPTIONS", "PROPFIND"].includes(incoming.method)
          ? incoming.method
          : "other",
        origin: origin(h.origin),
        fetchSite: ["same-origin", "same-site", "cross-site", "none"].includes(
          h["sec-fetch-site"],
        )
          ? h["sec-fetch-site"]
          : null,
        requestedHeaders: list(h["access-control-request-headers"]).filter(
          (v) =>
            [
              "authorization",
              "content-type",
              "idempotency-key",
              "traceparent",
              "tracestate",
              "x-sf-csrf",
              "x-unlisted-probe",
            ].includes(v),
        ),
        status: response.statusCode,
        allowOrigin: origin(header("access-control-allow-origin")),
        allowCredentials:
          header("access-control-allow-credentials") == null
            ? null
            : header("access-control-allow-credentials") === "true"
              ? "true"
              : "invalid",
        allowMethods: list(header("access-control-allow-methods")),
        allowHeaders: list(header("access-control-allow-headers")),
        maxAge: header("access-control-max-age") === "600" ? "600" : null,
        vary: list(header("vary")),
        cookies: header("set-cookie") == null ? [] : ["present"],
      }),
    );
  }
}

export function observeRemoteProbe(incoming, outgoing, rootDomain) {
  const url = new URL(incoming.url, `https://remote.${rootDomain}`);
  const probe = url.searchParams.get("remoteProbe");
  if (
    !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(
      probe ?? "",
    ) ||
    url.pathname !== "/static-acceptance/v1/remote.js"
  )
    return;
  outgoing.once("finish", () =>
    console.info(
      JSON.stringify({
        event: "acceptance-remote-security",
        probe,
        status: outgoing.statusCode,
        credentialHeaders: ["cookie", "authorization", "x-sf-csrf"].filter(
          (name) => Object.hasOwn(incoming.headers, name),
        ),
        allowOrigin: outgoing.hasHeader("access-control-allow-origin")
          ? "present"
          : null,
        allowCredentials: outgoing.hasHeader("access-control-allow-credentials")
          ? "present"
          : null,
      }),
    ),
  );
}

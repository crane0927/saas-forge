import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import process from "node:process";
import { chromium } from "playwright";

function createUuidV7(timestamp) {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  const milliseconds = Math.max(0, Math.min(Math.trunc(timestamp), 0xffffffffffff));
  bytes[0] = Math.floor(milliseconds / 0x10000000000) & 0xff;
  bytes[1] = Math.floor(milliseconds / 0x100000000) & 0xff;
  bytes[2] = Math.floor(milliseconds / 0x1000000) & 0xff;
  bytes[3] = Math.floor(milliseconds / 0x10000) & 0xff;
  bytes[4] = Math.floor(milliseconds / 0x100) & 0xff;
  bytes[5] = milliseconds & 0xff;
  bytes[6] = ((bytes.at(6) ?? 0) & 0x0f) | 0x70;
  bytes[8] = ((bytes.at(8) ?? 0) & 0x3f) | 0x80;
  const hex = Array.from(bytes, (value) => value.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

async function readRequiredSecret(name) {
  const file = process.env[name];
  if (!file) {
    throw new Error(`缺少 ${name} 指定的受限凭据文件。`);
  }
  const value = (await readFile(file, "utf8")).trim();
  if (!value) {
    throw new Error(`${name} 指定的受限凭据文件为空。`);
  }
  return value;
}

const email = await readRequiredSecret("SF_LOCAL_REPLACEMENT_PLATFORM_EMAIL_FILE");
const password = await readRequiredSecret("SF_LOCAL_REPLACEMENT_PLATFORM_PASSWORD_FILE");
const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage();
  const consoleErrors = [];
  page.on("console", (message) => {
    if (message.type() === "error") consoleErrors.push(message.text());
  });

  await page.goto("https://platform.saasforge.test/", {
    waitUntil: "domcontentloaded",
  });
  await page.waitForLoadState("networkidle");
  // 平台页首次加载会在无会话时刷新 Cookie；该预期 401 不属于后续跨服务操作。
  consoleErrors.length = 0;
  const idempotencyKey = createUuidV7(Date.now());
  const result = await page.evaluate(async ({ email, idempotencyKey, password }) => {
    const login = await fetch("https://api.saasforge.test/api/v1/auth/login", {
      body: JSON.stringify({ contextType: "PLATFORM", email, password }),
      credentials: "include",
      headers: { "Content-Type": "application/json", "X-SF-CSRF": "1" },
      method: "POST",
    });
    const loginBody = await login.json();
    if (!login.ok || typeof loginBody.accessToken !== "string") {
      return { loginStatus: login.status, quotaStatus: undefined };
    }
    const quota = await fetch(
      "https://api.saasforge.test/api/v1/platform/quota-definitions",
      {
        body: JSON.stringify({ code: "max_users" }),
        credentials: "include",
        headers: {
          Authorization: `Bearer ${loginBody.accessToken}`,
          "Content-Type": "application/json",
          "Idempotency-Key": idempotencyKey,
          "X-SF-CSRF": "1",
        },
        method: "POST",
      },
    );
    const quotaBody = await quota.json().catch(() => undefined);
    return {
      loginStatus: login.status,
      quotaCode: quotaBody?.code,
      quotaCreated: quota.status === 201,
      quotaStatus: quota.status,
    };
  }, { email, idempotencyKey, password });

  assert.equal(result.loginStatus, 200);
  assert.ok(
    result.quotaStatus === 201 ||
      (result.quotaStatus === 409 && result.quotaCode === "QUOTA_DEFINITION_ALREADY_EXISTS"),
    result.quotaCode,
  );
  assert.deepEqual(
    consoleErrors,
    result.quotaStatus === 409
      ? ["Failed to load resource: the server responded with a status of 409 (Conflict)"]
      : [],
  );
  console.log(
    `BROWSER: HTTPS Edge → Gateway → Entitlement → IAM Platform Role gRPC 返回正式结果（${result.quotaCreated ? "创建 DRAFT" : "既有定义已验证"}）。`,
  );
} finally {
  await browser.close();
}

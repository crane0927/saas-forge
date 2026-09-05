import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

test("keeps UUID generation inside the serialized page callback", async () => {
  const browserScript = await readFile(
    new URL("../scripts/verify-local-service-replacement-browser.mjs", import.meta.url),
    "utf8",
  );
  const callbackStart = browserScript.indexOf("async ({ email, password, target }) => {");
  const callbackEnd = browserScript.indexOf("    },\n    { email, password, target },", callbackStart);
  const uuidGenerator = browserScript.indexOf("function createUuidV7", callbackStart);

  assert.ok(callbackStart >= 0);
  assert.ok(callbackEnd > callbackStart);
  assert.ok(uuidGenerator > callbackStart && uuidGenerator < callbackEnd);
});

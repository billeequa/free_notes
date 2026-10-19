import test from "node:test";
import assert from "node:assert/strict";
import { handle, publicConfig, securityHeaders } from "./serve.mjs";
import { mkdtemp, writeFile, readFile, rm } from "node:fs/promises";
import { configurePages } from "./build-pages.mjs";
import { tmpdir } from "node:os";
import { join } from "node:path";
const env = {
  SUPABASE_URL: "https://cloud.example.test",
  SUPABASE_PUBLISHABLE_KEY: "sb_publishable_test",
  WEB_URL: "https://notes.example.test",
};
test("host exposes only public config and rejects service-role/admin credentials", () => {
  assert.equal(publicConfig(env).web_url, env.WEB_URL);
  for (const key of [
    "sb_secret_do-not-ship",
    "x." +
      Buffer.from(JSON.stringify({ role: "service_role" })).toString(
        "base64url",
      ) +
      ".x",
  ])
    assert.throws(() =>
      publicConfig({ ...env, SUPABASE_PUBLISHABLE_KEY: key }),
    );
  assert.throws(() =>
    publicConfig({ ...env, SUPABASE_URL: "http://internet.example" }),
  );
  assert.throws(() =>
    publicConfig({ ...env, SUPABASE_URL: "ftp://localhost" }),
  );
  assert.throws(() =>
    publicConfig({
      ...env,
      SUPABASE_URL: "https://user:password@cloud.example.test",
    }),
  );
  const headers = securityHeaders(publicConfig(env));
  assert.equal(headers["Cache-Control"], "no-store");
  assert.ok(!headers["Content-Security-Policy"].includes("unsafe-inline"));
  assert.match(
    headers["Content-Security-Policy"],
    /connect-src 'self' https:\/\/cloud.example.test/,
  );
});
test("Pages output carries validated public config and the portable host's security headers", async () => {
  const root = await mkdtemp(join(tmpdir(), "jnotes-pages-test-"));
  try {
    await writeFile(join(root, "index.html"), "JNotes");
    await configurePages(root, {
      ...env,
      WEB_URL: "",
      CF_PAGES_URL: "https://jnotes-test.pages.dev",
      PRIVATE_TOKEN: "must-never-ship",
    });
    const config = await readFile(join(root, "config.json"), "utf8");
    assert.deepEqual(JSON.parse(config), {
      ...publicConfig(env),
      web_url: "https://jnotes-test.pages.dev",
    });
    assert.ok(!config.includes("must-never-ship"));
    const headers = await readFile(join(root, "_headers"), "utf8");
    assert.ok(headers.startsWith("/*\n"));
    for (const [name, value] of Object.entries(
      securityHeaders(publicConfig(env)),
    ))
      assert.ok(headers.includes(`  ${name}: ${value}\n`));
    await assert.rejects(
      configurePages(root, {
        ...env,
        SUPABASE_PUBLISHABLE_KEY: "sb_secret_no",
      }),
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
test("static handler blocks writes and path escape and returns no-store config", async () => {
  const root = await mkdtemp(join(tmpdir(), "jnotes-server-test-"));
  await writeFile(join(root, "index.html"), "JNotes");
  async function request(url, method = "GET") {
    let status, headers, body;
    await handle(
      { url, method },
      {
        writeHead: (s, h) => {
          status = s;
          headers = h;
        },
        end: (b) => {
          body = b;
        },
      },
      publicConfig(env),
      root,
    );
    return { status, headers, body };
  }
  try {
    assert.equal((await request("/")).body.toString(), "JNotes");
    const config = await request("/config.json");
    assert.equal(config.status, 200);
    assert.deepEqual(JSON.parse(config.body), publicConfig(env));
    assert.equal((await request("/config.json", "POST")).status, 405);
    assert.equal((await request("/..%2fsecret.txt")).status, 404);
    assert.equal((await request("/%00")).status, 404);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

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
test("the shipped mobile directory contains only valid public connection information", async () => {
  const service = JSON.parse(await readFile(new URL("../config/jnotes-service.json", import.meta.url), "utf8"));
  assert.deepEqual(Object.keys(service).sort(), ["deployment_id","protocol_version","publishable_key","revision","schema_version","supabase_url","web_url"].sort());
  assert.equal(service.schema_version, 1);
  assert.equal(service.protocol_version, 1);
  assert.ok(Number.isSafeInteger(service.revision) && service.revision > 0);
  assert.match(service.deployment_id, /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/);
  const config = publicConfig({SUPABASE_URL:service.supabase_url,SUPABASE_PUBLISHABLE_KEY:service.publishable_key,WEB_URL:service.web_url});
  assert.equal(config.supabase_url, service.supabase_url);
  for (const value of [service.web_url,service.supabase_url]) {
    const url = new URL(value);
    assert.equal(url.protocol, "https:");
    assert.equal(url.origin, value);
  }
});
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
    const html = '<html><head><title>JNotes</title></head><body><script src="/assets/notebook.js"></script></body></html>';
    await writeFile(join(root, "index.html"), html);
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
    const work = await readFile(join(root, "work", "index.html"), "utf8");
    assert.ok(work.includes('<meta name="jnotes-workspace" content="work">'));
    assert.ok(work.includes("<title>JNotes · Work</title>"));
    assert.ok(work.includes('src="/assets/notebook.js"'));
    assert.equal(await readFile(join(root, "index.html"), "utf8"), html);
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
    for (const path of ["/", "/work", "/work/"]) {
      const response = await request(path);
      assert.equal(response.status, 200);
      assert.equal(response.body.toString(), "JNotes");
      assert.equal(response.headers.Location, undefined);
    }
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


test("production verification rejects stale bundles and Work redirects into Personal", async () => {
  const { checkPages } = await import("./verify-pages.mjs");
  const index = '<html><head></head><script src="/assets/current.js"></script></html>';
  const origin = "https://notes.example.test";
  const work = index.replace("<head>", '<head><meta name="jnotes-workspace" content="work">');
  const response = (url, html) => ({ ok: true, status: 200, url, text: async () => html });
  await checkPages(index, origin, async url => response(url, url.includes("/work?") ? work : index));
  await assert.rejects(checkPages(index, origin, async url => response(url.includes("/work?") ? origin + "/" : url, index)), /redirected outside/);
  await assert.rejects(checkPages(index, origin, async url => response(url, index.replace("current.js", "old.js"))), /different app bundle/);
  await assert.rejects(checkPages(index, origin, async url => response(url, index)), /wrong notebook entry/);
});

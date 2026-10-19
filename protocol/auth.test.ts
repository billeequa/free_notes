import test from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { once } from "node:events";
import { Cloud } from "../web/src/client";
import { parseEmailReturn, emailSendError } from "../web/src/authReturn";
import { b64, derive, open, recoveryKey } from "./crypto";
import { vaultAAD, type Bootstrap, type Vault } from "./types";

const owner = "11111111-1111-4111-8111-111111111111";
const bootstrap: Bootstrap = {
  protocol_version: 1,
  deployment_id: "22222222-2222-4222-8222-222222222222",
  kdf_salt: b64(new Uint8Array(16)),
  memory_kib: 65536,
  iterations: 3,
  parallelism: 1,
};
const humanPassword = "test notebook password π";
const user = {
  id: owner,
  email: "owner@example.test",
  email_confirmed_at: "2026-01-01T00:00:00Z",
  aud: "authenticated",
  role: "authenticated",
  app_metadata: {},
  user_metadata: {},
  identities: [],
  created_at: "2026-01-01T00:00:00Z",
};
function jwt() {
  const part = (v: object) =>
    Buffer.from(JSON.stringify(v)).toString("base64url");
  return `${part({ alg: "HS256", typ: "JWT" })}.${part({ sub: owner, exp: Math.floor(Date.now() / 1000) + 3600, role: "authenticated", session_id: "synthetic-session" })}.${Buffer.from("test-signature").toString("base64url")}`;
}
async function gateway() {
  let vault: Vault | null = null,
    authPassword = "old-invitation-credential",
    highWater = 0,
    loseCreateReply = false,
    allowed = true,
    failReadAfterCreate = false,
    created = false;
  const requests: { method: string; path: string; query: string; body: any }[] =
    [];
  const session = () => ({
    access_token: jwt(),
    refresh_token: "synthetic-refresh",
    expires_in: 3600,
    token_type: "bearer",
    user,
  });
  const server = createServer(async (req, res) => {
    let body = "";
    for await (const chunk of req) body += chunk;
    const url = new URL(req.url!, "http://localhost");
    const input = body ? JSON.parse(body) : {};
    requests.push({
      method: req.method!,
      path: url.pathname,
      query: url.search,
      body: input,
    });
    let value: unknown = {},
      status = 200;
    if (url.pathname === "/auth/v1/user") {
      if (req.method === "PUT") authPassword = input.password;
      value = user;
    } else if (url.pathname === "/auth/v1/token") {
      if (input.password !== authPassword) {
        status = 400;
        value = {
          code: "invalid_credentials",
          msg: "Invalid login credentials",
        };
      } else value = session();
    } else if (url.pathname === "/auth/v1/logout") status = 204;
    else if (
      url.pathname === "/auth/v1/otp" ||
      url.pathname === "/auth/v1/recover"
    )
      value = {};
    else if (url.pathname === "/rest/v1/rpc/get_vault") {
      if (created && failReadAfterCreate) {
        failReadAfterCreate = false;
        status = 503;
        value = { code: "synthetic", message: "read unavailable" };
      } else if (!allowed) {
        status = 403;
        value = { code: "42501", message: "NOT_AUTHORIZED" };
      } else value = { vault, epoch: "epoch", high_water: highWater };
    } else if (url.pathname === "/rest/v1/rpc/create_vault") {
      assert.equal(vault, null);
      assert.equal(highWater, 0);
      // Postgres JSONB is free to reorder object properties.
      const envelope = Object.fromEntries(
        Object.entries(input.p_recovery_envelope).reverse(),
      );
      vault = {
        key_id: input.p_key_id,
        password_envelope: input.p_password_envelope,
        recovery_envelope: envelope,
        staged_envelope: null,
        transition_id: null,
        epoch: "epoch",
        high_water: 0,
      } as unknown as Vault;
      created = true;
      if (loseCreateReply) {
        loseCreateReply = false;
        status = 503;
        value = { code: "synthetic", message: "lost acknowledgment" };
      }
    } else if (url.pathname === "/rest/v1/rpc/stage_password") {
      vault = {
        ...vault!,
        staged_envelope: input.p_envelope,
        transition_id: input.p_transition_id,
      };
    } else if (url.pathname === "/rest/v1/rpc/finish_password") {
      assert.equal(input.p_transition_id, vault!.transition_id);
      vault = {
        ...vault!,
        password_envelope: vault!.staged_envelope!,
        staged_envelope: null,
        transition_id: null,
      };
    } else if (url.pathname === "/rest/v1/rpc/get_changes")
      value = { changes: [], epoch: "epoch", high_water: 0, next_cursor: 0 };
    else if (url.pathname !== "/rest/v1/rpc/ack_cursor") {
      status = 404;
      value = { message: "Unexpected request" };
    }
    res.writeHead(status, { "Content-Type": "application/json" });
    res.end(status === 204 ? undefined : JSON.stringify(value));
  });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  const clients: Cloud[] = [];
  const client = () => {
    const c = new (Cloud as any)({
      supabase_url: `http://127.0.0.1:${(server.address() as any).port}`,
      publishable_key: "sb_publishable_test",
    }) as Cloud;
    c.bootstrap = bootstrap;
    clients.push(c);
    return c;
  };
  return {
    client,
    requests,
    session,
    get vault() {
      return vault;
    },
    get authPassword() {
      return authPassword;
    },
    history: () => {
      highWater = 4;
    },
    deny: () => {
      allowed = false;
    },
    dropCreate: () => {
      loseCreateReply = true;
    },
    failRead: () => {
      failReadAfterCreate = true;
    },
    close: async () => {
      for (const c of clients) await c.api.auth.stopAutoRefresh();
      server.closeAllConnections();
      await new Promise<void>((r) => server.close(() => r()));
    },
  };
}

test("email callback accepts supported link types and removes all credentials from its URL", () => {
  for (const type of ["invite", "magiclink", "recovery", "signup", "email"]) {
    const parsed = parseEmailReturn(
      `https://notes.example.test/?keep=ok#access_token=secret&refresh_token=refresh&type=${type}&expires_in=3600`,
    );
    assert.equal(parsed.cleanURL, "https://notes.example.test/?keep=ok");
    assert.deepEqual(parsed.session, {
      access_token: "secret",
      refresh_token: "refresh",
    });
    assert.equal(parsed.flow, type === "recovery" ? "recovery" : "setup");
  }
  assert.equal(
    parseEmailReturn("https://notes.example.test/#setup").flow,
    "setup",
  );
  assert.equal(
    parseEmailReturn("https://notes.example.test/#recover").flow,
    "recovery",
  );
});
test("expired, incomplete, and duplicate email tokens are scrubbed and never accepted", () => {
  for (const suffix of [
    "#error=access_denied&error_description=private",
    "?error=access_denied&code=private",
    "#access_token=one&type=invite",
    "#access_token=one&access_token=two&refresh_token=x&type=invite",
    "?code=private",
  ]) {
    const parsed = parseEmailReturn("https://notes.example.test/" + suffix);
    assert.equal(parsed.cleanURL, "https://notes.example.test/");
    assert.ok(parsed.error);
    assert.equal(parsed.session, undefined);
    assert.ok(!parsed.error.includes("private"));
  }
  assert.match(emailSendError({ status: 429 }), /temporarily limited/);
});
test("setup email is a link for an existing user and recovery uses a separate endpoint", async () => {
  const h = await gateway();
  try {
    const c = h.client();
    await c.sendEmailLink(
      " owner@example.test ",
      false,
      "https://notes.example.test/",
    );
    await c.sendEmailLink(
      "owner@example.test",
      true,
      "https://notes.example.test/",
    );
    assert.equal(h.requests[0].path, "/auth/v1/otp");
    assert.equal(h.requests[0].body.create_user, false);
    assert.equal(h.requests[0].body.email, "owner@example.test");
    assert.match(h.requests[0].query, /redirect_to=/);
    assert.equal(h.requests[1].path, "/auth/v1/recover");
  } finally {
    await h.close();
  }
});
test("email setup creates a recoverable encrypted notebook; ordinary password login sends no email", async () => {
  const h = await gateway();
  try {
    const c = h.client();
    assert.deepEqual(await c.acceptEmailLink(h.session()), {
      email: user.email,
      hasNotebook: false,
    });
    const code = (await c.createNotebook(humanPassword))!;
    assert.match(code, /^JN1-/);
    assert.notEqual(h.authPassword, humanPassword);
    assert.equal(c.key.length, 32);
    const savedKey = Uint8Array.from(c.key),
      savedVault = structuredClone(h.vault);
    const rk = recoveryKey(code);
    assert.deepEqual(
      await open(
        rk,
        h.vault!.recovery_envelope,
        vaultAAD(bootstrap.deployment_id, owner, h.vault!.key_id, "recovery"),
      ),
      savedKey,
    );
    rk.fill(0);
    await c.logout();
    assert.equal(c.key.length, 0);
    const start = h.requests.length;
    const next = h.client();
    await next.login("owner@example.test", humanPassword);
    assert.deepEqual(next.key, savedKey);
    assert.ok(
      !h.requests
        .slice(start)
        .some(
          (r) =>
            ["/auth/v1/otp", "/auth/v1/recover"].includes(r.path) ||
            r.method === "PUT",
        ),
    );
    assert.deepEqual(h.vault, savedVault);
    await next.logout();
    const wrong = h.client();
    await assert.rejects(
      wrong.login("owner@example.test", "wrong password"),
      /Email or password/,
    );
    assert.equal(wrong.key.length, 0);
    assert.deepEqual(h.vault, savedVault);
    const recovery = h.client();
    await recovery.acceptEmailLink(h.session());
    await assert.rejects(
      recovery.recoverFromEmail("bad-code", "new notebook password"),
    );
    assert.deepEqual(h.vault, savedVault);
    await recovery.recoverFromEmail(code, "new notebook password");
    assert.deepEqual(recovery.key, savedKey);
    assert.deepEqual(h.vault!.recovery_envelope, savedVault!.recovery_envelope);
    assert.equal(h.vault!.key_id, savedVault!.key_id);
    const after = h.client();
    await after.login("owner@example.test", "new notebook password");
    assert.deepEqual(after.key, savedKey);
  } finally {
    await h.close();
  }
});
test("lost create acknowledgment returns the original recovery code, even with JSONB property reordering", async () => {
  const h = await gateway();
  try {
    const c = h.client();
    await c.acceptEmailLink(h.session());
    h.dropCreate();
    assert.match((await c.createNotebook(humanPassword))!, /^JN1-/);
    assert.equal(c.key.length, 32);
    assert.equal(
      h.requests.filter((r) => r.path.endsWith("create_vault")).length,
      1,
    );
  } finally {
    await h.close();
  }
});
test("email verification cannot replace a missing key over history or authorize a different owner", async () => {
  const h = await gateway();
  try {
    h.history();
    const c = h.client();
    await assert.rejects(c.acceptEmailLink(h.session()), /key is missing/);
    assert.ok(
      !h.requests.some(
        (r) => r.method === "PUT" || r.path.endsWith("create_vault"),
      ),
    );
    h.deny();
    const other = h.client();
    await assert.rejects(
      other.acceptEmailLink(h.session()),
      /Account recovery/,
    );
    assert.equal(other.owner, "");
    assert.equal(other.key.length, 0);
  } finally {
    await h.close();
  }
});

test("retry after a committed setup and unavailable read still displays the original recovery code", async () => {
  const h = await gateway();
  try {
    const c = h.client();
    await c.acceptEmailLink(h.session());
    h.dropCreate();
    h.failRead();
    await assert.rejects(c.createNotebook(humanPassword));
    const code = await c.createNotebook(humanPassword);
    assert.match(code!, /^JN1-/);
    const rk = recoveryKey(code!);
    assert.deepEqual(
      await open(
        rk,
        h.vault!.recovery_envelope,
        vaultAAD(bootstrap.deployment_id, owner, h.vault!.key_id, "recovery"),
      ),
      c.key,
    );
    rk.fill(0);
    assert.equal(
      h.requests.filter((r) => r.path.endsWith("create_vault")).length,
      1,
    );
    c.completeSetup();
  } finally {
    await h.close();
  }
});

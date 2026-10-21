import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync, writeFileSync } from "node:fs";
import { createCipheriv } from "node:crypto";
import {
  b64,
  derive,
  expand,
  jsonBytes,
  open,
  recoveryCode,
  recoveryKey,
  seal,
  unb64,
} from "./crypto";
import {
  recordAAD,
  vaultAAD,
  newPayload,
  validatePayload,
  type Bootstrap,
} from "./types";
import { assertCloudIdentity, mergeTask } from "./merge";
const bootstrap: Bootstrap = {
  protocol_version: 1,
  deployment_id: "11111111-1111-4111-8111-111111111111",
  kdf_salt: b64(Uint8Array.from({ length: 16 }, (_, i) => i)),
  memory_kib: 65536,
  iterations: 3,
  parallelism: 1,
};
const owner = "22222222-2222-4222-8222-222222222222",
  keyId = "33333333-3333-4333-8333-333333333333",
  id = "44444444-4444-4444-8444-444444444444";
const key = Uint8Array.from({ length: 32 }, (_, i) => i),
  nonce = Uint8Array.from({ length: 12 }, (_, i) => i);
test("Work decrypts Android's fixed slash-escaped AAD vector and writes phone-compatible ciphertext", async () => {
  const v = JSON.parse(readFileSync(new URL("./work-vectors.json", import.meta.url), "utf8"));
  const workKey = expand(unb64(v.master_key), "workspace/work");
  assert.equal(b64(workKey), v.key);
  assert.equal(new TextDecoder().decode(await open(workKey, v.envelope, v.aad)), v.plaintext);
  assert.deepEqual(await seal(workKey, v.aad[4], new TextEncoder().encode(v.plaintext), v.aad, Uint8Array.from(unb64(v.envelope.nonce))), v.envelope);
  await assert.rejects(open(unb64(v.master_key), v.envelope, v.aad));
  for (const index of [2, 3, 5, 6, 7]) {
    const changed = [...v.aad];
    changed[index] = index === 6 ? 8 : index === 7 ? true : "other";
    await assert.rejects(open(workKey, v.envelope, changed as any));
  }
});
test("older browser Work ciphertext still opens without relaxing its authenticated context", async () => {
  const v = JSON.parse(readFileSync(new URL("./work-vectors.json", import.meta.url), "utf8"));
  const cipher = createCipheriv("aes-256-gcm", unb64(v.key), unb64(v.envelope.nonce));
  cipher.setAAD(Buffer.from(JSON.stringify(v.aad)));
  const ciphertext = Buffer.concat([cipher.update(v.plaintext, "utf8"), cipher.final(), cipher.getAuthTag()]);
  const envelope = { ...v.envelope, ciphertext_with_tag: ciphertext.toString("base64") };
  assert.notEqual(envelope.ciphertext_with_tag, v.envelope.ciphertext_with_tag);
  assert.equal(new TextDecoder().decode(await open(unb64(v.key), envelope, v.aad)), v.plaintext);
  await assert.rejects(open(unb64(v.master_key), envelope, v.aad));
  const changed = [...v.aad]; changed[6]++;
  await assert.rejects(open(unb64(v.key), envelope, changed as any));
});
test("portable full-cost Argon2id credentials and fixed AES-GCM vector", async () => {
  const keys = await derive("JNotes π e\u0301 🔒 password", bootstrap);
  const aad = recordAAD(bootstrap.deployment_id, owner, keyId, id, 7, false);
  const e = await seal(
    key,
    keyId,
    jsonBytes({ body: "λ\nline two 🚨", title: "A <script> is just text" }),
    aad,
    nonce,
  );
  const vector = {
    bootstrap,
    password: "JNotes π e\u0301 🔒 password",
    auth: keys.auth,
    wrap: b64(keys.wrap),
    key: b64(key),
    nonce: b64(nonce),
    aad,
    envelope: e,
    plaintext: { body: "λ\nline two 🚨", title: "A <script> is just text" },
    recovery_code: recoveryCode(key),
    recovery_key: b64(recoveryKey(recoveryCode(key))),
  };
  const fixture = new URL("./vectors.json", import.meta.url);
  if (process.env.UPDATE_VECTORS === "1")
    writeFileSync(fixture, JSON.stringify(vector, null, 2) + "\n");
  assert.deepEqual(vector, JSON.parse(readFileSync(fixture, "utf8")));
  assert.notEqual(keys.auth, b64(keys.wrap));
  keys.wrap.fill(0);
});
test("wrong key, AAD swaps, tombstone/revision tamper and modified ciphertext fail closed", async () => {
  const aad = recordAAD(bootstrap.deployment_id, owner, keyId, id, 1, false);
  const e = await seal(key, keyId, jsonBytes({ body: "secret" }), aad);
  assert.deepEqual(
    JSON.parse(new TextDecoder().decode(await open(key, e, aad))),
    { body: "secret" },
  );
  const wrong = Uint8Array.from(key);
  wrong[0] ^= 1;
  await assert.rejects(open(wrong, e, aad));
  for (const index of [2, 3, 5, 6, 7]) {
    const swap = [...aad] as typeof aad;
    (swap as any)[index] = index === 6 ? 2 : index === 7 ? true : "swapped";
    await assert.rejects(open(key, e, swap));
  }
  const bytes = unb64(e.ciphertext_with_tag);
  bytes[0] ^= 1;
  await assert.rejects(
    open(key, { ...e, ciphertext_with_tag: b64(bytes) }, aad),
  );
  await assert.rejects(open(key, { ...e, protocol_version: 2 as any }, aad));
});
test("random nonces remain unique across 1000 encryptions", async () => {
  const values = new Set<string>();
  for (let i = 0; i < 1000; i++) {
    const e = await seal(
      key,
      keyId,
      jsonBytes(i),
      recordAAD(bootstrap.deployment_id, owner, keyId, id, i + 1, false),
    );
    values.add(e.nonce);
  }
  assert.equal(values.size, 1000);
});
test("recovery secret has checked 256-bit encoding and independent wrapping key", async () => {
  const code = recoveryCode(key);
  assert.equal(code, recoveryCode(key));
  assert.notEqual(b64(recoveryKey(code)), b64(expand(key, "wrap")));
  assert.throws(() =>
    recoveryKey(code.slice(0, -1) + (code.endsWith("0") ? "1" : "0")),
  );
  const aad = vaultAAD(bootstrap.deployment_id, owner, keyId, "recovery");
  const e = await seal(recoveryKey(code), keyId, key, aad);
  assert.deepEqual(await open(recoveryKey(code), e, aad), key);
  await assert.rejects(open(expand(key, "wrap"), e, aad));
});
test("disjoint task fields merge; concurrent same-field edits and document edits never merge", () => {
  const b = newPayload("task", "initial");
  const local = { ...b, title: "Phone" };
  const remote = { ...b, completed_at: "2026-10-18T12:00:00Z" };
  const merged = mergeTask(b, local, remote)!;
  assert.equal(merged.title, "Phone");
  assert.equal(merged.completed_at, remote.completed_at);
  assert.equal(mergeTask(b, local, { ...remote, title: "Web" }), null);
  assert.equal(
    mergeTask(
      { ...b, kind: "note" },
      { ...local, kind: "note" },
      { ...remote, kind: "note" },
    ),
    null,
  );
  const reopened = { ...b, completed_at: null },
    completed = { ...b, completed_at: "2026-10-17T00:00:00Z" };
  assert.equal(
    mergeTask(completed, reopened, {
      ...completed,
      completed_at: "2026-10-18T00:00:00Z",
    }),
    null,
  );
});
test("cloud rollback, replacement and corrupt payload are rejected", () => {
  const accepted = { deployment: "a", epoch: "b", cursor: 12 };
  assert.throws(() =>
    assertCloudIdentity(accepted, {
      deployment: "a",
      epoch: "b",
      high_water: 11,
    }),
  );
  assert.throws(() =>
    assertCloudIdentity(accepted, {
      deployment: "c",
      epoch: "b",
      high_water: 12,
    }),
  );
  assert.throws(() =>
    assertCloudIdentity(accepted, {
      deployment: "a",
      epoch: "new",
      high_water: 14,
    }),
  );
  assert.doesNotThrow(() =>
    assertCloudIdentity(accepted, {
      deployment: "a",
      epoch: "b",
      high_water: 12,
    }),
  );
  const p = newPayload("journal");
  assert.throws(() => validatePayload({ ...p, id: "swapped" }, p.id));
  assert.throws(() => validatePayload({ ...p, flags: ["invalid"] }, p.id));
});

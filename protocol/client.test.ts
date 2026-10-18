import test from "node:test";
import assert from "node:assert/strict";
import { Cloud, type Entry } from "../web/src/client";
import { newPayload, type Remote } from "./types";
function harness() {
  const records = new Map<string, Remote>(),
    ops = new Map<string, { fingerprint: string; result: any }>();
  let seq = 0,
    loseReply = false;
  const client = new (Cloud as any)({
    supabase_url: "https://example.test",
    publishable_key: "sb_publishable_test",
  }) as Cloud;
  client.bootstrap = {
    protocol_version: 1,
    deployment_id: "deploy",
    kdf_salt: "",
    memory_kib: 65536,
    iterations: 3,
    parallelism: 1,
  };
  client.owner = "owner";
  client.key = new Uint8Array(32).fill(3);
  client.vault = { key_id: "key", epoch: "epoch", high_water: 0 } as any;
  client.rpc = async (name: string, args: any): Promise<any> => {
    assert.equal(name, "commit_operation");
    const old = ops.get(args.p_op_id);
    const fingerprint = JSON.stringify(args);
    if (old) {
      assert.equal(fingerprint, old.fingerprint);
      return old.result;
    }
    const previous = records.get(args.p_id);
    if ((previous?.revision || 0) !== args.p_expected_revision)
      return { status: "CONFLICT", current: previous };
    const r = {
      id: args.p_id,
      revision: args.p_expected_revision + 1,
      seq: ++seq,
      envelope: args.p_envelope,
      deleted: args.p_deleted,
    };
    records.set(r.id, r);
    const result = { status: "OK", revision: r.revision, seq: r.seq };
    ops.set(args.p_op_id, { fingerprint, result });
    if (loseReply) {
      loseReply = false;
      throw Error("network lost after server commit");
    }
    return result;
  };
  return {
    client,
    records,
    ops,
    drop: () => {
      loseReply = true;
    },
  };
}
test("client retries an ambiguous server commit with identical nonce/op instead of making a duplicate", async () => {
  const h = harness();
  const p = newPayload("note", "Unicode λ 🚨");
  p.body = "text\n".repeat(100000);
  h.drop();
  await assert.rejects(h.client.save(p, undefined));
  assert.equal(h.records.size, 1);
  const first = h.records.get(p.id)!;
  const saved = await h.client.save(p, undefined);
  assert.equal(saved!.remote.revision, 1);
  assert.equal(h.ops.size, 1);
  assert.deepEqual(saved!.remote.envelope, first.envelope);
  assert.equal((await h.client.decrypt(first)).body, p.body);
});
test("new edit after lost acknowledgment commits after the original, without losing either revision", async () => {
  const h = harness(),
    p = newPayload("journal", "Day");
  h.drop();
  await assert.rejects(h.client.save(p, undefined));
  const saved = await h.client.save(
    { ...p, body: "newer while retrying" },
    undefined,
  );
  assert.equal(saved!.remote.revision, 2);
  assert.equal(h.ops.size, 2);
});
test("task CAS merges disjoint fields but holds complete document conflicts", async () => {
  const h = harness(),
    p = newPayload("task", "before");
  const original = (await h.client.save(p, undefined))!;
  await h.client.save({ ...p, completed_at: "2026-10-18T12:00:00Z" }, original);
  const merged = await h.client.save({ ...p, title: "phone edit" }, original);
  assert.equal(merged!.payload.title, "phone edit");
  assert.equal(merged!.payload.completed_at, "2026-10-18T12:00:00Z");
  const doc = newPayload("note", "document");
  const base = (await h.client.save(doc, undefined))!;
  await h.client.save({ ...doc, body: "web edit" }, base);
  assert.equal(await h.client.save({ ...doc, body: "phone edit" }, base), null);
  const c = h.client.conflicts.get(doc.id)!;
  assert.equal(c.local.body, "phone edit");
  assert.equal(c.remotePayload.body, "web edit");
  await h.client.resolve(doc.id, "remote");
  assert.equal(h.client.entries.get(doc.id)!.payload.body, "web edit");
  assert.ok([...h.records.values()].some((r) => r.id !== doc.id && r.deleted)); // rejected edit preserved in encrypted Trash
});
test("pending write meeting a concurrent edit becomes conflict, not a silent replacement", async () => {
  const h = harness(),
    p = newPayload("note", "initial");
  const base = (await h.client.save(p, undefined))!;
  // Fail before reaching the server, keeping a prepared local operation.
  const rpc = h.client.rpc;
  h.client.rpc = async () => {
    throw Error("offline");
  };
  await assert.rejects(h.client.save({ ...p, body: "first local" }, base));
  h.client.rpc = rpc;
  await rpc("commit_operation", {
    p_op_id: crypto.randomUUID(),
    p_id: p.id,
    p_expected_revision: 1,
    p_envelope: base.remote.envelope,
    p_deleted: false,
  });
  // Replace the deliberately synthetic envelope with a valid concurrent revision.
  const { seal, jsonBytes } = await import("./crypto");
  const { recordAAD } = await import("./types");
  const r = h.records.get(p.id)!;
  r.envelope = await seal(
    h.client.key,
    "key",
    jsonBytes({ ...p, body: "remote concurrent" }),
    recordAAD("deploy", "owner", "key", p.id, 2, false),
  );
  assert.equal(await h.client.save({ ...p, body: "newest local" }, base), null);
  assert.equal(h.client.conflicts.get(p.id)!.local.body, "newest local");
  assert.equal(
    h.client.conflicts.get(p.id)!.remotePayload.body,
    "remote concurrent",
  );
});

import { PGlite } from "../../web/node_modules/@electric-sql/pglite/dist/index.js";
import { readFileSync } from "node:fs";
import assert from "node:assert/strict";
import { restoreSQL } from "../../server/restore-encrypted-export.mjs";
const db = new PGlite();
await db.exec(`
 create role anon;create role authenticated;create schema auth;
 create table auth.users(id uuid primary key,email_confirmed_at timestamptz);
 create table auth.sessions(id uuid primary key,user_id uuid references auth.users(id));
 create function auth.jwt() returns jsonb language sql stable as $$select coalesce(nullif(current_setting('request.jwt.claims',true),''),'{}')::jsonb$$;
 create function auth.uid() returns uuid language sql stable as $$select (auth.jwt()->>'sub')::uuid$$;
 grant usage on schema auth to anon,authenticated;
 grant execute on function auth.jwt(),auth.uid() to anon,authenticated;
 grant usage on schema public to anon,authenticated;
 alter default privileges in schema public grant all on tables to anon,authenticated;
`);
await db.exec(
  readFileSync(
    new URL("../migrations/202610180001_web_sync.sql", import.meta.url),
    "utf8",
  ),
);
const owner = "11111111-1111-4111-8111-111111111111",
  other = "22222222-2222-4222-8222-222222222222",
  session = "33333333-3333-4333-8333-333333333333",
  otherSession = "44444444-4444-4444-8444-444444444444",
  key = "55555555-5555-4555-8555-555555555555",
  id = "66666666-6666-4666-8666-666666666666",
  device = "77777777-7777-4777-8777-777777777777";
await db.exec(
  `insert into auth.users values('${owner}',now()),('${other}',now());insert into auth.sessions values('${session}','${owner}'),('${otherSession}','${other}');insert into jnotes_private.owner_account(owner_uid) values('${owner}');`,
);
async function as(role: string, user = "", sid = "") {
  await db.exec("reset role");
  await db.query(`select set_config('request.jwt.claims',$1,false)`, [
    JSON.stringify({ sub: user || null, session_id: sid || null }),
  ]);
  await db.exec(`set role ${role}`);
}
async function scalar(sql: string, args: any[] = []) {
  return (await db.query<{ v: any }>(sql, args)).rows[0]?.v;
}
async function denied(sql: string, args: any[] = []) {
  let threw = false;
  try {
    await db.query(sql, args);
  } catch {
    threw = true;
  }
  assert.ok(threw, `Must deny: ${sql}`);
}
const envelope = {
  protocol_version: 1,
  key_id: key,
  algorithm: "A256GCM",
  nonce: Buffer.alloc(12).toString("base64"),
  ciphertext_with_tag: Buffer.alloc(32, 1).toString("base64"),
};
await as("anon");
assert.equal(
  await scalar("select count(*)::int as v from public_bootstrap"),
  1,
);
for (const table of ["vaults", "records", "record_history", "client_devices"])
  await denied(`select * from ${table}`);
await denied(`select get_vault()`);
await denied(`select * from jnotes_private.owner_account`);
await denied(`delete from public_bootstrap`);
await as("authenticated", other, otherSession);
await denied("select get_vault()");
await as("authenticated", owner, session);
await scalar("select create_vault($1,$2,$2) as v", [key, envelope]);
const state = await scalar("select get_vault() as v");
const epoch = state.epoch;
assert.equal(state.vault.key_id, key);
await denied("select create_vault($1,$2,$2)", [key, envelope]);
for (const table of ["vaults", "records", "record_history", "client_devices"]) {
  await denied(`select * from ${table}`);
  await denied(`delete from ${table}`);
}
const op = "88888888-8888-4888-8888-888888888888";
async function commit(
  expected: number,
  operation = crypto.randomUUID(),
  e = envelope,
  deleted = false,
) {
  return scalar("select commit_operation($1,$2,$3,$4,$5,$6,$7,$8) as v", [
    epoch,
    0,
    operation,
    device,
    id,
    expected,
    e,
    deleted,
  ]);
}
const first = await commit(0, op);
assert.equal(first.status, "OK");
assert.equal(first.seq, 1);
assert.equal(first.revision, 1);
assert.deepEqual(await commit(0, op), first);
assert.equal((await commit(0)).status, "CONFLICT");
await denied("select commit_operation($1,0,$2,$3,$4,0,$5,false)", [
  epoch,
  op,
  device,
  id,
  { ...envelope, ciphertext_with_tag: Buffer.alloc(32, 2).toString("base64") },
]);
await denied("select commit_operation($1,0,$2,$3,$4,1,$5,false)", [
  epoch,
  crypto.randomUUID(),
  device,
  id,
  { ...envelope, algorithm: null },
]);
for (let rev = 1; rev < 25; rev++)
  assert.equal((await commit(rev)).revision, rev + 1);
assert.equal(
  (await commit(25, crypto.randomUUID(), envelope, true)).revision,
  26,
);
assert.equal((await commit(0)).status, "CONFLICT"); // old device cannot recreate tombstoned UUID
const page = await scalar("select get_changes($1,0,10) as v", [epoch]);
assert.equal(page.changes.length, 10);
assert.equal(page.next_cursor, 10);
assert.equal(page.changes[0].revision, 1);
assert.equal(page.changes[0].deleted, false); // immutable history, not latest record join
const next = await scalar("select get_changes($1,10,100) as v", [epoch]);
assert.equal(next.changes.at(-1).deleted, true);
assert.equal(next.next_cursor, 26);
const history = await scalar("select get_history($1) as v", [id]);
assert.equal(history.length, 26);
await denied("select get_changes($1,27,10)", [epoch]);
await denied("select get_changes($1,0,10)", [crypto.randomUUID()]);
const transition = crypto.randomUUID();
await db.query("select stage_password($1,$2)", [transition, envelope]);
let vault = await scalar("select get_vault() as v");
assert.deepEqual(vault.vault.recovery_envelope, envelope);
assert.equal(vault.vault.transition_id, transition);
await denied("select finish_password($1)", [crypto.randomUUID()]);
await db.query("select finish_password($1)", [transition]);
vault = await scalar("select get_vault() as v");
assert.equal(vault.vault.staged_envelope, null);
assert.deepEqual(vault.vault.recovery_envelope, envelope);
// Temporarily grant reads to exercise RLS itself, then restore the stricter grants.
await db.exec(
  "reset role;grant select on vaults,records,record_history,client_devices to authenticated",
);
await as("authenticated", owner, session);
assert.equal(await scalar("select count(*)::int as v from records"), 1);
await as("authenticated", other, otherSession);
for (const table of ["vaults", "records", "record_history", "client_devices"])
  assert.equal(await scalar(`select count(*)::int as v from ${table}`), 0);
await denied("select get_history($1)", [id]);
await denied("select rotate_recovery($1)", [envelope]);
await denied("select ack_cursor($1,$2,0)", [device, epoch]);
await db.exec(
  "reset role;revoke select on vaults,records,record_history,client_devices from authenticated",
);
// Deleting a Supabase session enforces global revocation even before JWT expiration.
await db.query("delete from auth.sessions where id=$1", [session]);
await as("authenticated", owner, session);
await denied("select get_vault()");
await db.exec("reset role");
// No exposed RPC retains PUBLIC EXECUTE, no security definer has an unpinned search path.
const functions = (
  await db.query<{ proname: string; proconfig: string[] }>(
    `select proname,proconfig from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname='public' and p.prosecdef`,
  )
).rows;
for (const f of functions)
  assert.ok(
    f.proconfig?.some((c) => c.startsWith("search_path=")),
    f.proname,
  );
// Synthetic encrypted-export restore drill, using the same schema and strict restore helper.
await db.query(`insert into auth.sessions values($1,$2)`, [session, owner]);
await as("authenticated", owner, session);
const snapshot = await scalar("select get_vault() as v");
const full = await scalar("select get_changes($1,0,100) as v", [epoch]);
await db.exec("reset role");
const bootstrap = await scalar(
  "select to_jsonb(t) as v from public_bootstrap t",
);
const archive = {
  format: "jnotes.encrypted-export.v1",
  bootstrap,
  owner_uid: owner,
  vault: snapshot.vault,
  epoch,
  high_water: snapshot.high_water,
  changes: full.changes,
};
const retained = JSON.stringify(full.changes.map((r: any) => r.envelope));
await db.exec(
  "delete from public.records;delete from public.record_history;delete from public.vaults;delete from public.client_devices;",
);
await db.exec(restoreSQL(archive));
assert.equal(await scalar("select count(*)::int as v from record_history"), 26);
assert.notEqual(
  await scalar("select epoch::text as v from jnotes_private.owner_account"),
  epoch,
);
assert.equal(
  await scalar(
    "select count(*)::int as v from auth.sessions where user_id=$1",
    [owner],
  ),
  0,
);
const restored = (
  await db.query<{ envelope: any }>(
    "select envelope from record_history order by seq",
  )
).rows.map((r) => r.envelope);
assert.equal(JSON.stringify(restored), retained);
assert.equal(
  await scalar("select deployment_id::text as v from public_bootstrap"),
  bootstrap.deployment_id,
);
console.log(
  "PASS: encrypted-export restore drill: exact ciphertext/history, preserved bootstrap, new epoch and invalidated sessions.",
);
console.log(
  "PASS: actual PostgreSQL engine — anonymous/other-owner grants and RLS, live-session revoke, vault CAS, staged recovery, 26 revisions, tombstones, replay idempotency, immutable cursor pages, rollback guards.",
);
await db.close();

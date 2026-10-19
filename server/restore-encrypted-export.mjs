import { readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
const uuid = (s) =>
  typeof s === "string" &&
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(s);
export function restoreSQL(archive) {
  if (
    archive.format !== "jnotes.encrypted-export.v1" ||
    !uuid(archive.owner_uid) ||
    !uuid(archive.vault?.key_id) ||
    !uuid(archive.bootstrap?.deployment_id) ||
    archive.bootstrap.protocol_version !== 1 ||
    !Array.isArray(archive.changes)
  )
    throw Error("Invalid encrypted export");
  if (
    archive.bootstrap.memory_kib !== 65536 ||
    archive.bootstrap.iterations !== 3 ||
    archive.bootstrap.parallelism !== 1 ||
    Buffer.from(archive.bootstrap.kdf_salt, "base64").length !== 16
  )
    throw Error("Invalid bootstrap");
  if (archive.changes.length !== archive.high_water)
    throw Error("Archive does not contain the complete history");
  let seq = 0;
  for (const r of archive.changes) {
    if (
      !uuid(r.id) ||
      !uuid(r.op_id) ||
      r.seq !== ++seq ||
      !Number.isSafeInteger(r.revision) ||
      r.revision < 1 ||
      typeof r.deleted !== "boolean" ||
      r.envelope?.key_id !== archive.vault.key_id
    )
      throw Error("Invalid history");
  }
  // Data goes only through a properly escaped SQL text literal on psql stdin.
  const json = JSON.stringify(archive).replaceAll("'", "''");
  return `begin;
 do $restore$
 declare a jsonb := '${json}'::jsonb; u uuid := (a->>'owner_uid')::uuid; v jsonb := a->'vault'; b jsonb := a->'bootstrap'; e jsonb;
 begin
  if exists(select 1 from public.records) or exists(select 1 from public.record_history) or exists(select 1 from public.vaults) then raise exception 'TARGET_NOT_EMPTY'; end if;
  if not exists(select 1 from auth.users where id=u and email_confirmed_at is not null) then raise exception 'RESTORE_MATCHING_AUTH_USER_FIRST'; end if;
  if exists(select 1 from jnotes_private.owner_account where owner_uid<>u) then raise exception 'OWNER_MISMATCH'; end if;
  insert into jnotes_private.owner_account(owner_uid) values(u) on conflict(owner_uid) do nothing;
  update public.public_bootstrap set deployment_id=(b->>'deployment_id')::uuid,kdf_salt=b->>'kdf_salt',protocol_version=1,memory_kib=65536,iterations=3,parallelism=1;
  perform jnotes_private.check_envelope(v->'password_envelope',(v->>'key_id')::uuid);
  perform jnotes_private.check_envelope(v->'recovery_envelope',(v->>'key_id')::uuid);
  insert into public.vaults(owner_uid,key_id,password_envelope,recovery_envelope,staged_envelope,transition_id) values(u,(v->>'key_id')::uuid,v->'password_envelope',v->'recovery_envelope',nullif(v->'staged_envelope','null'),nullif(v->>'transition_id','null')::uuid);
  for e in select value from jsonb_array_elements(a->'changes') loop
   perform jnotes_private.check_envelope(e->'envelope',(v->>'key_id')::uuid);
   if (e->>'revision')::bigint<>coalesce((select revision from public.records where owner_uid=u and id=(e->>'id')::uuid),0)+1 then raise exception 'INVALID_HISTORY_CHAIN'; end if;
   insert into public.record_history(owner_uid,id,revision,seq,envelope,deleted,op_id,device_id) values(u,(e->>'id')::uuid,(e->>'revision')::bigint,(e->>'seq')::bigint,e->'envelope',(e->>'deleted')::boolean,(e->>'op_id')::uuid,'00000000-0000-4000-8000-000000000000');
   insert into public.records values(u,(e->>'id')::uuid,(e->>'revision')::bigint,(e->>'seq')::bigint,e->'envelope',(e->>'deleted')::boolean)
    on conflict(owner_uid,id) do update set revision=excluded.revision,seq=excluded.seq,envelope=excluded.envelope,deleted=excluded.deleted;
  end loop;
  update jnotes_private.owner_account set epoch=gen_random_uuid(),high_water=(a->>'high_water')::bigint,history_bytes=(select coalesce(sum(octet_length(envelope::text)),0) from public.record_history where owner_uid=u) where owner_uid=u;
  delete from auth.sessions where user_id=u;
 end $restore$;
 commit;`;
}
if (process.argv[1] === fileURLToPath(import.meta.url)) {
  if (process.argv[3] !== "--empty-development-target")
    throw Error(
      "Usage: node server/restore-encrypted-export.mjs backup.json --empty-development-target",
    );
  const sql = restoreSQL(JSON.parse(readFileSync(process.argv[2], "utf8")));
  const result = spawnSync("psql", ["-X", "-v", "ON_ERROR_STOP=1"], {
    input: sql,
    env: process.env,
    encoding: "utf8",
  });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    process.stderr.write(result.stderr);
    process.exit(result.status || 1);
  }
  console.log(
    "Encrypted export restored. Sessions invalidated and cloud epoch changed; clients must perform a reviewed repair.",
  );
}

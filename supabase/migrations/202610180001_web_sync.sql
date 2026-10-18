-- JNotes v1. No plaintext record columns. All mutations serialize on the owner row.
create schema if not exists jnotes_private;
revoke all on schema jnotes_private from public, anon, authenticated;

create table public.public_bootstrap (
 singleton boolean primary key default true check(singleton), protocol_version integer not null default 1 check(protocol_version=1),
 deployment_id uuid not null default gen_random_uuid(),
 kdf_salt text not null default encode(decode(replace(gen_random_uuid()::text,'-',''),'hex'),'base64'),
 memory_kib integer not null default 65536 check(memory_kib=65536),
 iterations integer not null default 3 check(iterations=3), parallelism integer not null default 1 check(parallelism=1)
);
insert into public.public_bootstrap(singleton) values(true);
create table jnotes_private.owner_account (
 singleton boolean primary key default true check(singleton), owner_uid uuid not null unique references auth.users(id),
 epoch uuid not null default gen_random_uuid(), high_water bigint not null default 0 check(high_water>=0),
 history_bytes bigint not null default 0, max_history_bytes bigint not null default 1073741824 check(max_history_bytes>0),
 max_records integer not null default 100000 check(max_records>0)
);
create table public.vaults (
 owner_uid uuid primary key references auth.users(id), key_id uuid not null,
 password_envelope jsonb not null, recovery_envelope jsonb not null,
 staged_envelope jsonb, transition_id uuid, updated_at timestamptz not null default now()
);
create table public.records (
 owner_uid uuid not null references auth.users(id), id uuid not null, revision bigint not null check(revision>0),
 seq bigint not null, envelope jsonb not null, deleted boolean not null,
 primary key(owner_uid,id)
);
create table public.record_history (
 owner_uid uuid not null, id uuid not null, revision bigint not null, seq bigint not null,
 envelope jsonb not null, deleted boolean not null, op_id uuid not null, device_id uuid not null, changed_at timestamptz not null default now(),
 primary key(owner_uid,id,revision), unique(owner_uid,op_id), unique(owner_uid,seq)
);
-- Change feed is immutable history ordered by a commit-serialized counter, not a PostgreSQL sequence.
create table public.client_devices (
 owner_uid uuid not null, device_id uuid not null, last_ack_seq bigint not null default 0,
 last_seen timestamptz not null default now(), primary key(owner_uid,device_id)
);

create function jnotes_private.is_owner() returns boolean language sql stable security definer set search_path='' as $$
 select exists(select 1 from jnotes_private.owner_account a join auth.users u on u.id=a.owner_uid
  join auth.sessions s on s.user_id=u.id and s.id::text=(auth.jwt()->>'session_id')
  where a.owner_uid=auth.uid() and u.email_confirmed_at is not null);
$$;
revoke all on function jnotes_private.is_owner() from public;
-- The helper is not exposed through PostgREST (private schema).
grant usage on schema jnotes_private to authenticated;
grant execute on function jnotes_private.is_owner() to authenticated;

alter table public.public_bootstrap enable row level security;
create policy bootstrap_read on public.public_bootstrap for select to anon,authenticated using(true);
grant select on public.public_bootstrap to anon,authenticated;
revoke insert,update,delete,truncate,references,trigger on public.public_bootstrap from anon,authenticated;

-- Defense in depth: no client direct data-table grants; RPCs additionally enforce live-session owner checks.
alter table public.vaults enable row level security;
alter table public.records enable row level security;
alter table public.record_history enable row level security;
alter table public.client_devices enable row level security;
create policy vault_owner on public.vaults for select to authenticated using(owner_uid=auth.uid() and jnotes_private.is_owner());
create policy record_owner on public.records for select to authenticated using(owner_uid=auth.uid() and jnotes_private.is_owner());
create policy history_owner on public.record_history for select to authenticated using(owner_uid=auth.uid() and jnotes_private.is_owner());
create policy device_owner on public.client_devices for select to authenticated using(owner_uid=auth.uid() and jnotes_private.is_owner());
revoke all on public.vaults,public.records,public.record_history,public.client_devices from public,anon,authenticated;

create function jnotes_private.require_owner() returns uuid language plpgsql stable security definer set search_path='' as $$
begin
 if not jnotes_private.is_owner() then raise exception 'NOT_AUTHORIZED' using errcode='42501'; end if;
 return auth.uid();
end $$;
create function jnotes_private.check_envelope(e jsonb,k uuid) returns void language plpgsql immutable set search_path='' as $$
begin
 if e is null or jsonb_typeof(e)<>'object' or e->>'protocol_version' is distinct from '1' or e->>'algorithm' is distinct from 'A256GCM'
  or e->>'key_id' is distinct from k::text or length(e::text)>2800000
  or (e->>'nonce') is null or (e->>'ciphertext_with_tag') is null
  then raise exception 'INVALID_ENVELOPE'; end if;
 if length(decode(e->>'nonce','base64'))<>12 or length(decode(e->>'ciphertext_with_tag','base64'))<16 then raise exception 'INVALID_ENVELOPE'; end if;
end $$;
revoke all on function jnotes_private.require_owner(),jnotes_private.check_envelope(jsonb,uuid) from public,anon,authenticated;

create function public.get_vault() returns jsonb language plpgsql security definer set search_path='' as $$
declare u uuid:=jnotes_private.require_owner(); v jsonb; a jnotes_private.owner_account;
begin
 select * into a from jnotes_private.owner_account where owner_uid=u;
 select to_jsonb(t)-'owner_uid' into v from public.vaults t where owner_uid=u;
 return jsonb_build_object('vault',v,'epoch',a.epoch,'high_water',a.high_water,'history_bytes',a.history_bytes,'max_history_bytes',a.max_history_bytes);
end $$;

create function public.create_vault(p_key_id uuid,p_password_envelope jsonb,p_recovery_envelope jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
declare u uuid:=jnotes_private.require_owner();
begin
 perform 1 from jnotes_private.owner_account where owner_uid=u for update;
 perform jnotes_private.check_envelope(p_password_envelope,p_key_id);
 perform jnotes_private.check_envelope(p_recovery_envelope,p_key_id);
 insert into public.vaults(owner_uid,key_id,password_envelope,recovery_envelope) values(u,p_key_id,p_password_envelope,p_recovery_envelope);
 return public.get_vault();
end $$;

create function public.stage_password(p_transition_id uuid,p_envelope jsonb) returns void language plpgsql security definer set search_path='' as $$
declare u uuid:=jnotes_private.require_owner(); v public.vaults;
begin
 select * into strict v from public.vaults where owner_uid=u for update;
 perform jnotes_private.check_envelope(p_envelope,v.key_id);
 if v.transition_id is not null and v.transition_id<>p_transition_id then raise exception 'TRANSITION_PENDING'; end if;
 update public.vaults set staged_envelope=p_envelope,transition_id=p_transition_id,updated_at=now() where owner_uid=u;
end $$;
create function public.finish_password(p_transition_id uuid) returns void language plpgsql security definer set search_path='' as $$
declare u uuid:=jnotes_private.require_owner();
begin
 update public.vaults set password_envelope=staged_envelope,staged_envelope=null,transition_id=null,updated_at=now()
 where owner_uid=u and transition_id=p_transition_id and staged_envelope is not null;
 if not found then raise exception 'TRANSITION_MISMATCH'; end if;
end $$;

create function jnotes_private.guard_cloud(p_epoch uuid,p_floor bigint) returns void language plpgsql security definer set search_path='' as $$
declare a jnotes_private.owner_account;
begin
 select * into strict a from jnotes_private.owner_account where owner_uid=auth.uid();
 if a.epoch<>p_epoch or p_floor<0 or a.high_water<p_floor then raise exception 'CLOUD_STATE_CHANGED'; end if;
end $$;
revoke all on function jnotes_private.guard_cloud(uuid,bigint) from public,anon,authenticated;

create function public.get_changes(p_epoch uuid,p_after bigint,p_limit integer default 50) returns jsonb language plpgsql security definer set search_path='' as $$
declare u uuid:=jnotes_private.require_owner(); items jsonb; h bigint; e uuid;
begin
 -- READ COMMITTED statement snapshot returns a stable prefix; owner lock orders all commits.
 perform jnotes_private.guard_cloud(p_epoch,p_after);
 select high_water,epoch into h,e from jnotes_private.owner_account where owner_uid=u;
 select coalesce(jsonb_agg(to_jsonb(t) order by t.seq),'[]'::jsonb) into items from
  (select id,revision,seq,envelope,deleted,op_id from
   (select id,revision,seq,envelope,deleted,op_id,
      sum(length(envelope::text)) over(order by seq) as size_bytes,
      row_number() over(order by seq) as row_number
    from public.record_history where owner_uid=u and seq>p_after and seq<=h) bounded
   where size_bytes<=3000000 or row_number=1
   order by seq limit greatest(1,least(p_limit,100))) t;
 return jsonb_build_object('changes',items,'high_water',h,'epoch',e,'next_cursor',coalesce((items->-1->>'seq')::bigint,p_after));
end $$;

create function public.commit_operation(p_epoch uuid,p_floor bigint,p_op_id uuid,p_device_id uuid,p_id uuid,p_expected_revision bigint,p_envelope jsonb,p_deleted boolean) returns jsonb language plpgsql security definer set search_path='' as $$
declare u uuid:=jnotes_private.require_owner(); a jnotes_private.owner_account; r public.records; prior public.record_history; n bigint; k uuid;
begin
 select * into strict a from jnotes_private.owner_account where owner_uid=u for update;
 perform jnotes_private.guard_cloud(p_epoch,p_floor);
 select key_id into strict k from public.vaults where owner_uid=u;
 perform jnotes_private.check_envelope(p_envelope,k);
 select * into prior from public.record_history where owner_uid=u and op_id=p_op_id;
 if found then
  if prior.id<>p_id or prior.revision<>p_expected_revision+1 or prior.envelope<>p_envelope or prior.deleted<>p_deleted then raise exception 'OP_ID_REUSED'; end if;
  return jsonb_build_object('status','OK','revision',prior.revision,'seq',prior.seq);
 end if;
 select * into r from public.records where owner_uid=u and id=p_id;
 if coalesce(r.revision,0)<>p_expected_revision or p_expected_revision<0 then
  return jsonb_build_object('status','CONFLICT','current',case when r.id is null then null else to_jsonb(r)-'owner_uid' end);
 end if;
 if a.history_bytes+octet_length(p_envelope::text)>a.max_history_bytes then raise exception 'VAULT_QUOTA_REACHED'; end if;
 if r.id is null and (select count(*) from public.records where owner_uid=u)>=a.max_records then raise exception 'VAULT_QUOTA_REACHED'; end if;
 n:=a.high_water+1;
 insert into public.records values(u,p_id,p_expected_revision+1,n,p_envelope,p_deleted)
 on conflict(owner_uid,id) do update set revision=excluded.revision,seq=excluded.seq,envelope=excluded.envelope,deleted=excluded.deleted;
 insert into public.record_history(owner_uid,id,revision,seq,envelope,deleted,op_id,device_id) values(u,p_id,p_expected_revision+1,n,p_envelope,p_deleted,p_op_id,p_device_id);
 update jnotes_private.owner_account set high_water=n,history_bytes=history_bytes+octet_length(p_envelope::text) where owner_uid=u;
 return jsonb_build_object('status','OK','revision',p_expected_revision+1,'seq',n);
end $$;

create function public.get_history(p_id uuid,p_before bigint default 9223372036854775807) returns jsonb language plpgsql security definer set search_path='' as $$
declare u uuid:=jnotes_private.require_owner(); items jsonb;
begin
 select coalesce(jsonb_agg(to_jsonb(t) order by revision desc),'[]'::jsonb) into items from
 (select id,revision,seq,envelope,deleted,changed_at from
   (select id,revision,seq,envelope,deleted,changed_at,sum(length(envelope::text)) over(order by revision desc) as size_bytes,row_number() over(order by revision desc) as row_number
    from public.record_history where owner_uid=u and id=p_id and revision<p_before) bounded
   where size_bytes<=3000000 or row_number=1 order by revision desc limit 50)t;
 return items;
end $$;
create function public.ack_cursor(p_device_id uuid,p_epoch uuid,p_cursor bigint) returns void language plpgsql security definer set search_path='' as $$
declare u uuid:=jnotes_private.require_owner();
begin
 perform jnotes_private.guard_cloud(p_epoch,p_cursor);
 insert into public.client_devices(owner_uid,device_id,last_ack_seq) values(u,p_device_id,p_cursor)
 on conflict(owner_uid,device_id) do update set last_ack_seq=greatest(public.client_devices.last_ack_seq,excluded.last_ack_seq),last_seen=now();
end $$;

-- Exact grants; new SQL functions do not retain PostgreSQL's default PUBLIC EXECUTE.
revoke all on function public.get_vault(),public.create_vault(uuid,jsonb,jsonb),public.stage_password(uuid,jsonb),public.finish_password(uuid),
 public.get_changes(uuid,bigint,integer),public.commit_operation(uuid,bigint,uuid,uuid,uuid,bigint,jsonb,boolean),public.get_history(uuid,bigint),public.ack_cursor(uuid,uuid,bigint) from public,anon;
grant execute on function public.get_vault(),public.create_vault(uuid,jsonb,jsonb),public.stage_password(uuid,jsonb),public.finish_password(uuid),
 public.get_changes(uuid,bigint,integer),public.commit_operation(uuid,bigint,uuid,uuid,uuid,bigint,jsonb,boolean),public.get_history(uuid,bigint),public.ack_cursor(uuid,uuid,bigint) to authenticated;

-- Replace recovery only after the client confirmed the replacement code is saved.
create function public.rotate_recovery(p_envelope jsonb) returns void language plpgsql security definer set search_path='' as $$
declare u uuid:=jnotes_private.require_owner(); k uuid;
begin
 select key_id into strict k from public.vaults where owner_uid=u for update;
 perform jnotes_private.check_envelope(p_envelope,k);
 update public.vaults set recovery_envelope=p_envelope,updated_at=now() where owner_uid=u;
end $$;
revoke all on function public.rotate_recovery(jsonb) from public,anon;
grant execute on function public.rotate_recovery(jsonb) to authenticated;

-- Work has its own immutable feed, cursor, quota and records. Personal RPCs and bytes stay unchanged.
-- The verified owner and password/recovery envelopes are shared; work record keys are HKDF-separated on clients.
create table jnotes_private.work_owner_account (like jnotes_private.owner_account including all);
alter table jnotes_private.work_owner_account add foreign key(owner_uid) references auth.users(id);
alter table jnotes_private.work_owner_account enable row level security;
revoke all on jnotes_private.work_owner_account from public, anon, authenticated;
create table public.work_records (like public.records including all);
alter table public.work_records enable row level security;
create policy work_records_owner on public.work_records for select to authenticated
 using(owner_uid=(select auth.uid()) and (select jnotes_private.is_owner()));
revoke all on public.work_records from public, anon, authenticated;
create table public.work_record_history (like public.record_history including all);
alter table public.work_record_history enable row level security;
create policy work_record_history_owner on public.work_record_history for select to authenticated
 using(owner_uid=(select auth.uid()) and (select jnotes_private.is_owner()));
revoke all on public.work_record_history from public, anon, authenticated;
create table public.work_client_devices (like public.client_devices including all);
alter table public.work_client_devices enable row level security;
create policy work_client_devices_owner on public.work_client_devices for select to authenticated
 using(owner_uid=(select auth.uid()) and (select jnotes_private.is_owner()));
revoke all on public.work_client_devices from public, anon, authenticated;

create function jnotes_private.work_guard_cloud(p_epoch uuid,p_floor bigint) returns void language plpgsql security definer set search_path='' as $$
declare a jnotes_private.work_owner_account;
begin
 select * into strict a from jnotes_private.work_owner_account where owner_uid=auth.uid();
 if a.epoch<>p_epoch or p_floor<0 or a.high_water<p_floor then raise exception 'CLOUD_STATE_CHANGED'; end if;
end $$;

create function public.work_get_vault() returns jsonb language plpgsql security definer set search_path='' as $$
declare u uuid:=jnotes_private.require_owner(); v jsonb; a jnotes_private.work_owner_account;
begin
 -- First use creates only Work state, under the existing live-session owner check.
 insert into jnotes_private.work_owner_account(owner_uid)
 select owner_uid from jnotes_private.owner_account where owner_uid=u
 on conflict(owner_uid) do nothing;
 select * into a from jnotes_private.work_owner_account where owner_uid=u for share;
 if (select count(*) from public.work_record_history where owner_uid=u)<>a.high_water
    or (select count(distinct id) from public.work_record_history where owner_uid=u)<>(select count(*) from public.work_records where owner_uid=u)
 then raise exception 'CLOUD_STATE_CHANGED'; end if;
 select to_jsonb(t)-'owner_uid' into v from public.vaults t where owner_uid=u;
 return jsonb_build_object('vault',v,'epoch',a.epoch,'high_water',a.high_water,'history_bytes',a.history_bytes,'max_history_bytes',a.max_history_bytes);
end $$;

create function public.work_get_changes(p_epoch uuid,p_after bigint,p_limit integer default 50) returns jsonb language plpgsql security definer set search_path='' as $$
declare u uuid:=jnotes_private.require_owner(); items jsonb; h bigint; e uuid;
begin
 -- READ COMMITTED statement snapshot returns a stable prefix; owner lock orders all commits.
 perform jnotes_private.work_guard_cloud(p_epoch,p_after);
 select high_water,epoch into h,e from jnotes_private.work_owner_account where owner_uid=u;
 select coalesce(jsonb_agg(to_jsonb(t) order by t.seq),'[]'::jsonb) into items from
  (select id,revision,seq,envelope,deleted,op_id from
   (select id,revision,seq,envelope,deleted,op_id,
      sum(length(envelope::text)) over(order by seq) as size_bytes,
      row_number() over(order by seq) as row_number
    from public.work_record_history where owner_uid=u and seq>p_after and seq<=h) bounded
   where size_bytes<=3000000 or row_number=1
   order by seq limit greatest(1,least(p_limit,100))) t;
 return jsonb_build_object('changes',items,'high_water',h,'epoch',e,'next_cursor',coalesce((items->-1->>'seq')::bigint,p_after));
end $$;

create function public.work_commit_operation(p_epoch uuid,p_floor bigint,p_op_id uuid,p_device_id uuid,p_id uuid,p_expected_revision bigint,p_envelope jsonb,p_deleted boolean) returns jsonb language plpgsql security definer set search_path='' as $$
declare u uuid:=jnotes_private.require_owner(); a jnotes_private.work_owner_account; r public.work_records; prior public.work_record_history; n bigint; k uuid;
begin
 select * into strict a from jnotes_private.work_owner_account where owner_uid=u for update;
 perform jnotes_private.work_guard_cloud(p_epoch,p_floor);
 select key_id into strict k from public.vaults where owner_uid=u;
 perform jnotes_private.check_envelope(p_envelope,k);
 select * into prior from public.work_record_history where owner_uid=u and op_id=p_op_id;
 if found then
  if prior.id<>p_id or prior.revision<>p_expected_revision+1 or prior.envelope<>p_envelope or prior.deleted<>p_deleted then raise exception 'OP_ID_REUSED'; end if;
  return jsonb_build_object('status','OK','revision',prior.revision,'seq',prior.seq);
 end if;
 select * into r from public.work_records where owner_uid=u and id=p_id;
 if coalesce(r.revision,0)<>p_expected_revision or p_expected_revision<0 then
  return jsonb_build_object('status','CONFLICT','current',case when r.id is null then null else to_jsonb(r)-'owner_uid' end);
 end if;
 if a.history_bytes+octet_length(p_envelope::text)>a.max_history_bytes then raise exception 'VAULT_QUOTA_REACHED'; end if;
 if r.id is null and (select count(*) from public.work_records where owner_uid=u)>=a.max_records then raise exception 'VAULT_QUOTA_REACHED'; end if;
 n:=a.high_water+1;
 insert into public.work_records values(u,p_id,p_expected_revision+1,n,p_envelope,p_deleted)
 on conflict(owner_uid,id) do update set revision=excluded.revision,seq=excluded.seq,envelope=excluded.envelope,deleted=excluded.deleted;
 insert into public.work_record_history(owner_uid,id,revision,seq,envelope,deleted,op_id,device_id) values(u,p_id,p_expected_revision+1,n,p_envelope,p_deleted,p_op_id,p_device_id);
 update jnotes_private.work_owner_account set high_water=n,history_bytes=history_bytes+octet_length(p_envelope::text) where owner_uid=u;
 return jsonb_build_object('status','OK','revision',p_expected_revision+1,'seq',n);
end $$;

create function public.work_get_history(p_id uuid,p_before bigint default 9223372036854775807) returns jsonb language plpgsql security definer set search_path='' as $$
declare u uuid:=jnotes_private.require_owner(); items jsonb;
begin
 select coalesce(jsonb_agg(to_jsonb(t) order by revision desc),'[]'::jsonb) into items from
 (select id,revision,seq,envelope,deleted,changed_at from
   (select id,revision,seq,envelope,deleted,changed_at,sum(length(envelope::text)) over(order by revision desc) as size_bytes,row_number() over(order by revision desc) as row_number
    from public.work_record_history where owner_uid=u and id=p_id and revision<p_before) bounded
   where size_bytes<=3000000 or row_number=1 order by revision desc limit 50)t;
 return items;
end $$;

create function public.work_ack_cursor(p_device_id uuid,p_epoch uuid,p_cursor bigint) returns void language plpgsql security definer set search_path='' as $$
declare u uuid:=jnotes_private.require_owner();
begin
 perform jnotes_private.work_guard_cloud(p_epoch,p_cursor);
 insert into public.work_client_devices(owner_uid,device_id,last_ack_seq) values(u,p_device_id,p_cursor)
 on conflict(owner_uid,device_id) do update set last_ack_seq=greatest(public.work_client_devices.last_ack_seq,excluded.last_ack_seq),last_seen=now();
end $$;

revoke all on function jnotes_private.work_guard_cloud(uuid,bigint) from public,anon,authenticated;
revoke all on function public.work_get_vault(),public.work_get_changes(uuid,bigint,integer),
 public.work_commit_operation(uuid,bigint,uuid,uuid,uuid,bigint,jsonb,boolean),public.work_get_history(uuid,bigint),public.work_ack_cursor(uuid,uuid,bigint) from public,anon;
grant execute on function public.work_get_vault(),public.work_get_changes(uuid,bigint,integer),
 public.work_commit_operation(uuid,bigint,uuid,uuid,uuid,bigint,jsonb,boolean),public.work_get_history(uuid,bigint),public.work_ack_cursor(uuid,uuid,bigint) to authenticated;

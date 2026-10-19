-- Keep integrity counts and returned high-water state consistent with concurrent commits.
create or replace function public.get_vault() returns jsonb language plpgsql security definer set search_path='' as $$
declare u uuid:=jnotes_private.require_owner(); v jsonb; a jnotes_private.owner_account;
begin
 select * into a from jnotes_private.owner_account where owner_uid=u for share;
 if (select count(*) from public.record_history where owner_uid=u)<>a.high_water
    or (select count(distinct id) from public.record_history where owner_uid=u)<>(select count(*) from public.records where owner_uid=u)
 then raise exception 'CLOUD_STATE_CHANGED'; end if;
 select to_jsonb(t)-'owner_uid' into v from public.vaults t where owner_uid=u;
 return jsonb_build_object('vault',v,'epoch',a.epoch,'high_water',a.high_water,'history_bytes',a.history_bytes,'max_history_bytes',a.max_history_bytes);
end $$;

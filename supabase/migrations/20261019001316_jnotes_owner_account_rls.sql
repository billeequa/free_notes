-- Defense in depth for the private membership table. Clients cannot access it;
-- reviewed SECURITY DEFINER RPCs run as the table owner and enforce membership.
revoke all on jnotes_private.owner_account from public, anon, authenticated;
alter table jnotes_private.owner_account enable row level security;

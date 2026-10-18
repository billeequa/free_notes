#!/bin/sh
set -eu
archive="${1:?Usage: restore-backup.sh archive.dump --empty-development-target}"
[ "${2:-}" = "--empty-development-target" ] || { printf '%s\n' 'Refusing restore without an explicitly empty development target.' >&2; exit 1; }
# Target must already contain Supabase auth roles/extensions, and no JNotes tables.
pg_restore --no-owner --exit-on-error --dbname="${PGDATABASE:?Set PostgreSQL target environment}" "$archive"
printf '%s\n' 'Restore completed. Rotate owner_account.epoch before connecting clients; invalidate old sessions; run RLS and recovery drills.'

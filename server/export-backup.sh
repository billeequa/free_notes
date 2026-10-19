#!/bin/sh
set -eu
# Supply PGHOST, PGPORT, PGUSER, PGDATABASE, PGPASSWORD through your secret manager.
# Do not put the database password or connection URI in argv or source control.
output="${1:?Usage: export-backup.sh /private/path/jnotes.dump}"
umask 077
pg_dump --format=custom --no-owner --schema=public --schema=jnotes_private --schema=auth --file="$output"
pg_restore --list "$output" >/dev/null
printf '%s\n' "Encrypted-record database archive written and verified. Store independently of the server."

# Host JNotes anywhere

The frontend is a static React bundle. The server-side application logic is versioned PostgreSQL in `supabase/migrations/`; Supabase supplies Auth and PostgREST. No JNotes data depends on ChatGPT hosting. Use managed Supabase or the official self-hosted Supabase Docker stack on your own Linux machine. Both use the same migrations/API and client-side encryption.

For a live website without running your own server, follow [Cloudflare Pages setup](../docs/web-sync/cloudflare-pages.md). The `build:pages` command generates validated public configuration and equivalent security headers. Start with the included free `pages.dev` address and attach a purchased domain later if desired.

## Quick start: frontend + an already configured backend

At repository root, with Docker Compose:

```sh
cp server/.env.example server/.env
# Edit the public SUPABASE_URL, SUPABASE_PUBLISHABLE_KEY and WEB_URL values.
docker compose --env-file server/.env -f server/compose.yml up --build -d
```

Open **http://localhost:8080** on that computer. For your phone and browsers elsewhere, put the same service behind HTTPS on your domain, e.g. **https://notes.example.com**, and set WEB_URL to that actual address. An example Caddy configuration is included. The example domain is a placeholder, not an existing JNotes deployment. The container binds only to localhost; the reverse proxy owns HTTPS.

Without Docker:

```sh
npm --prefix web ci
npm --prefix web run build
# Export SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY in your shell.
node server/serve.mjs
```

The server returns `/config.json` from public environment values at runtime. It validates keys and sends no-store, CSP, referrer, framing and other security headers. Admin/service-role keys are refused. The runtime has no npm dependencies. For Pages use `npm --prefix web run build:pages` with the same public environment variables. For Nginx provide the public JSON and equivalent headers; do not use Vite's development server for public hosting.

Backend setup, invitation/email-link templates, migrations, Android connection and restore instructions are in [the operator guide](../docs/web-sync/operator-guide.md). The server is not ready to accept real notes merely because its login page loads.

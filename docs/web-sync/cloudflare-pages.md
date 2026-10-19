# Put JNotes on a live website

Use Cloudflare Pages for the website and Supabase for login and the encrypted database. You do not need to buy a domain or rent a Linux server to start. Pages provides an HTTPS address such as `https://YOUR-PROJECT.pages.dev`; that is the address you open in a browser and enter in Android Settings → Web Sync. These example addresses are placeholders, not an existing deployment.

The source is in `feat/encrypted-web-sync-v1` ([PR #20](https://github.com/billeequa/free_notes/pull/20)). Keep this first deployment for synthetic testing until the review and acceptance checks in [the operator guide](operator-guide.md) are complete. GitHub hosts the source, not the notebook login page. Supabase's `*.supabase.co` address is the API endpoint, not the website.

## 1. Prepare Supabase

Use a dedicated development project. Apply the versioned SQL migration, disable public signup and anonymous login, invite only the approved email, and provision that exact auth user UUID as the singleton owner. Configure OTP email delivery and test it. The [operator guide](operator-guide.md) has the exact SQL and auth settings. A hosted login page alone does not finish this setup.

From Supabase's project Connect/API settings, copy the project URL and its **publishable** key (`sb_publishable_...`). Do not use a secret/service-role key or database password. The current connected project is named `jnotes`; its API URL is `https://aqamrwetutembtrxrfso.supabase.co`. The database migration has been applied with approval; account activation and email settings remain to be configured. No personal notes were uploaded.

## 2. Connect GitHub to Cloudflare Pages

1. Create or sign in to a [Cloudflare account](https://dash.cloudflare.com/).
2. Open **Workers & Pages → Create application → Pages → Import an existing Git repository**. Choose **Pages**, which serves this static app.
3. Connect GitHub and allow access to `billeequa/free_notes`.
4. Choose the following build settings. The root directory is the repository root, not `web`.

| Setting | Value |
| --- | --- |
| Project name | Any available name, for example `my-jnotes` |
| Production branch for this development site | `feat/encrypted-web-sync-v1` |
| Framework preset | None |
| Root directory | Leave blank (repository root) |
| Build command | `npm --prefix web ci && npm --prefix web run build:pages` |
| Build output directory | `web/dist` |
| Environment variable `NODE_VERSION` | `24` |
| Environment variable `SUPABASE_URL` | `https://aqamrwetutembtrxrfso.supabase.co` for the connected development project |
| Environment variable `SUPABASE_PUBLISHABLE_KEY` | `sb_publishable_iuUx8ZFxey-Gyo_DoR7RiA_hETvoyJX` for that project (intentionally public) |

5. Save and deploy. Pages installs the pinned dependencies and builds the website. The build writes `/config.json` and `_headers`; no hand-written config file is needed. It refuses privileged keys. Set the same public environment variables for Preview deployments if you enable them.
6. Open the actual HTTPS address shown after deployment, for example `https://my-jnotes.pages.dev`. Keep that exact address. In Supabase Auth → URL Configuration, use it as the Site URL and allow the exact URLs needed by your invite/recovery flow. Complete the approved account and synthetic end-to-end tests before enabling personal-note upload.

Once this PR is reviewed and merged, change the Pages production branch to `main`. GitHub pushes to the selected branch rebuild the site automatically. Do not use a development branch as an unreviewed production release channel.

## 3. Optional: buy your own address

Buy any available domain from a registrar of your choice; check its renewal price as well as the initial price. Buying the domain reserves the address. Pages still hosts the app, and Supabase still runs the backend.

For a subdomain such as `notes.YOUR-DOMAIN.com`:

1. Open the Pages project → **Custom domains → Set up a domain** and enter `notes.YOUR-DOMAIN.com`.
2. Follow Cloudflare's DNS instructions. If DNS is managed elsewhere, add the CNAME it requests:

| Type | Name | Target |
| --- | --- | --- |
| CNAME | `notes` | `YOUR-PROJECT.pages.dev` |

3. Wait until the domain and HTTPS certificate show active, then open `https://notes.YOUR-DOMAIN.com`.
4. Update the exact allowed Site URL/redirect settings in Supabase. Use this new web address when reconnecting Android; preserve the existing backend/vault and back up before reconnecting.

Add the custom domain in Pages **before** adding a CNAME. For a bare domain such as `YOUR-DOMAIN.com`, Cloudflare requires its nameservers; a subdomain can use DNS at another registrar. A Supabase custom domain add-on is unnecessary for giving this app its own web address.

## 4. Use JNotes

On Android, install the reviewed update signed with your existing app certificate. Open Settings → Web Sync and enter the actual Pages/custom-domain address. Verify the approved email, establish the password, save the recovery code, review the upload preview, and confirm only after the review checkpoint.

In any browser, open that same website and sign in with the same email and password. To-Do, Notes and Journal appear there. Wait for **Synced** after edits; keep a tab open and use Retry/Copy/Download if a save fails. Browser access requires internet. Android local editing continues when offline or sync is disabled.

## Costs and portability

Pages static hosting has a free tier. Supabase also has a free plan, but free projects may pause after a week of inactivity and have database/usage limits. Check the current [Supabase pricing](https://supabase.com/pricing) before choosing always-available paid hosting. No paid plan or domain purchase is required to try the development setup.

To move hosts later, the repository includes the static frontend, [portable Node/Docker host](../../server/README.md), Supabase SQL migrations and backup/restore tools. Preserve Auth identity, bootstrap salt, wrapped keys, database history and secrets when moving the backend; copying only the frontend does not move your encrypted notes.

Official setup references: [React on Pages](https://developers.cloudflare.com/pages/framework-guides/deploy-a-react-site/), [custom domains](https://developers.cloudflare.com/pages/configuration/custom-domains/), [response headers](https://developers.cloudflare.com/pages/configuration/headers/).

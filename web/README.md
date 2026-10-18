# JNotes browser client

Online-only React/TypeScript client for encrypted To-Do, Notes and Journal. Documents share one editor/transport pipeline. No EPUB sync, persistent browser documents, service worker, analytics or public sharing.

Run `npm ci`, `npm test`, `npm run test:sql`, `npm run build` in this directory. Serve the production bundle with `node ../server/serve.mjs` after setting the public backend environment. Open http://localhost:8080, or your actual HTTPS host URL after deployment. Configure your Android phone with that same reachable HTTPS web address, then log in here with its email/password.

See `../server/README.md` and `../docs/web-sync/operator-guide.md` for backend provisioning, OTP email setup and portable hosting. `npm run dev` is a development build server only; it still needs a matching public config.json and is not the production hosting command.

import { writeFile, stat } from "node:fs/promises";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { publicConfig, securityHeaders } from "./serve.mjs";

// Pages serves static files, so emit the same public config and response headers
// as the portable Node host. Validation refuses privileged backend credentials.
export async function configurePages(root, env = process.env) {
  const config = publicConfig({
    ...env,
    WEB_URL: env.WEB_URL || env.CF_PAGES_URL,
  });
  await stat(resolve(root, "index.html"));
  await writeFile(resolve(root, "config.json"), JSON.stringify(config) + "\n");
  await writeFile(
    resolve(root, "_headers"),
    "/*\n" +
      Object.entries(securityHeaders(config))
        .map(([name, value]) => `  ${name}: ${value}`)
        .join("\n") +
      "\n",
  );
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  await configurePages(fileURLToPath(new URL("../web/dist", import.meta.url)));
  console.log(
    "Cloudflare Pages public configuration and security headers generated.",
  );
}

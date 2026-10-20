import { writeFile, stat, readFile, mkdir } from "node:fs/promises";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { publicConfig, securityHeaders } from "./serve.mjs";

// Emit public configuration and a dedicated Work document; never embed backend credentials.
export async function configurePages(root, env = process.env) {
  const config = publicConfig({ ...env, WEB_URL: env.WEB_URL || env.CF_PAGES_URL });
  await stat(resolve(root, "index.html"));
  const html = await readFile(resolve(root, "index.html"), "utf8");
  if (!html.includes("<head>")) throw Error("Missing HTML head for Work entry");
  const workHtml = html.replace("<head>", '<head><meta name="jnotes-workspace" content="work">')
    .replace("<title>JNotes</title>", "<title>JNotes · Work</title>");
  await mkdir(resolve(root, "work"), { recursive: true });
  await writeFile(resolve(root, "work", "index.html"), workHtml);
  // Native fallback elsewhere; rewriting Work to the root can open the wrong notebook.
  await writeFile(resolve(root, "config.json"), JSON.stringify(config) + "\n");
  await writeFile(resolve(root, "_headers"), "/*\n" +
    Object.entries(securityHeaders(config)).map(([name, value]) => `  ${name}: ${value}`).join("\n") + "\n");
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  await configurePages(fileURLToPath(new URL("../web/dist", import.meta.url)));
  console.log("Cloudflare Pages configuration, Work entry and security headers generated.");
}

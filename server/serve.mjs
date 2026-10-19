import http from "node:http";
import { readFile, stat } from "node:fs/promises";
import { resolve, extname, sep } from "node:path";
import { fileURLToPath } from "node:url";
export function publicConfig(env = process.env) {
  const supabase_url = env.SUPABASE_URL || "",
    publishable_key = env.SUPABASE_PUBLISHABLE_KEY || "";
  if (!supabase_url || !publishable_key)
    throw Error("Set SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY.");
  const url = new URL(supabase_url);
  if (
    url.protocol !== "https:" &&
    !(
      url.protocol === "http:" &&
      ["localhost", "127.0.0.1"].includes(url.hostname)
    )
  )
    throw Error("HTTPS cloud endpoint required.");
  if (
    url.username ||
    url.password ||
    url.search ||
    url.hash ||
    url.pathname !== "/"
  )
    throw Error("Use the backend origin without credentials, query, or path.");
  if (!publishable_key.startsWith("sb_publishable_")) {
    try {
      const claims = JSON.parse(
        Buffer.from(publishable_key.split(".")[1], "base64url"),
      );
      if (claims.role !== "anon") throw Error();
    } catch {
      throw Error(
        "Use a public publishable key or self-hosted legacy anon JWT, never a secret/service-role key.",
      );
    }
  }
  return {
    supabase_url: url.href.replace(/\/$/, ""),
    publishable_key,
    web_url: env.WEB_URL || "",
  };
}
export function securityHeaders(config) {
  return {
    "Content-Security-Policy": `default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self' ${new URL(config.supabase_url).origin}; base-uri 'none'; frame-ancestors 'none'; form-action 'self'; object-src 'none'`,
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "no-referrer",
    "X-Frame-Options": "DENY",
    "Permissions-Policy": "camera=(), microphone=(), geolocation=()",
    "Cross-Origin-Opener-Policy": "same-origin",
    "Cache-Control": "no-store",
    "Strict-Transport-Security": "max-age=31536000; includeSubDomains",
  };
}
export async function handle(req, res, config, root) {
  const headers = securityHeaders(config);
  try {
    if (req.method !== "GET" && req.method !== "HEAD") {
      res.writeHead(405, headers);
      res.end();
      return;
    }
    const path = decodeURIComponent(
      new URL(req.url, "http://localhost").pathname,
    );
    if (path === "/config.json") {
      res.writeHead(200, { ...headers, "Content-Type": "application/json" });
      res.end(req.method === "HEAD" ? undefined : JSON.stringify(config));
      return;
    }
    const location = resolve(root, "." + (path === "/" ? "/index.html" : path));
    if (!location.startsWith(root + sep)) {
      res.writeHead(404, headers);
      res.end();
      return;
    }
    const file = await stat(location);
    if (!file.isFile()) throw Error();
    const types = {
      ".html": "text/html; charset=utf-8",
      ".js": "application/javascript; charset=utf-8",
      ".css": "text/css; charset=utf-8",
      ".svg": "image/svg+xml",
      ".png": "image/png",
    };
    res.writeHead(200, {
      ...headers,
      "Content-Type": types[extname(location)] || "application/octet-stream",
    });
    res.end(req.method === "HEAD" ? undefined : await readFile(location));
  } catch {
    res.writeHead(404, headers);
    res.end("Not found");
  }
}
if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const config = publicConfig();
  const root = resolve(
    process.env.STATIC_ROOT || new URL("../web/dist", import.meta.url).pathname,
  );
  http
    .createServer((req, res) => void handle(req, res, config, root))
    .listen(Number(process.env.PORT || 8080), "0.0.0.0", () =>
      console.log("JNotes web listening on port " + (process.env.PORT || 8080)),
    );
}

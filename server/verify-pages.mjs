import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";

/** Reject stale deployments and Work redirects back into the Personal entry. */
export async function checkPages(index, origin, fetcher = fetch) {
  const assets = [...index.matchAll(/src="([^"]+\.js)"/g)].map(match => match[1]);
  if (!assets.length) throw Error("No built application bundle found");
  for (const [path, workOnly] of [["/", false], ["/work", true]]) {
    const response = await fetcher(origin + path + "?bundle=" + encodeURIComponent(assets[0]), {
      cache: "no-store", signal: AbortSignal.timeout(15000),
    });
    if (!response.ok) throw Error(path + " returned " + response.status);
    const target = new URL(response.url);
    if (target.origin !== origin || (workOnly && !/^\/work\/?$/.test(target.pathname)))
      throw Error("Work route redirected outside its entry");
    const html = await response.text();
    if (!assets.every(asset => html.includes(asset))) throw Error(path + " still serves a different app bundle");
    const pinned = /name=["']?jnotes-workspace["']?\s+content=["']?work["']?/.test(html);
    if (pinned !== workOnly) throw Error(path + " has the wrong notebook entry");
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const index = await readFile(fileURLToPath(new URL("../web/dist/index.html", import.meta.url)), "utf8");
  const origin = "https://j4notes.pages.dev";
  let last;
  for (let attempt = 0; attempt < 40; attempt++) {
    try {
      await checkPages(index, origin);
      console.log("Verified current Personal and pinned Work entries at " + origin);
      process.exit(0);
    } catch (error) {
      last = error;
      console.log("Waiting for Pages production: " + error.message);
    }
    if (attempt < 39) await new Promise(resolve => setTimeout(resolve, 15000));
  }
  throw last;
}

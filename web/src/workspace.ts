export type Workspace = "personal" | "work";

/** The dedicated Work entry point is pinned; main can switch scopes after locking. */
export function resolveWorkspace(pathname: string, search = "", workEntry = false): {
  workspace: Workspace; workOnly: boolean;
} {
  const workOnly = workEntry || pathname.split("/").filter(Boolean)[0] === "work";
  const workspace = workOnly || new URLSearchParams(search).get("mode") === "work"
    ? "work" : "personal";
  return { workspace, workOnly };
}

export function workspaceDestination(workOnly: boolean, workspace: Workspace): string {
  if (workOnly) throw Error("This address is dedicated to Work.");
  return workspace === "work" ? "/" : "/?mode=work";
}

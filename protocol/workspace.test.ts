import test from "node:test";
import assert from "node:assert/strict";
import { resolveWorkspace, workspaceDestination } from "../web/src/workspace";
import { completedTaskFolders } from "../web/src/todoArchive";
import { newPayload } from "./types";
import type { Entry } from "../web/src/client";

test("dedicated Work paths and their document remain pinned despite Personal query parameters", () => {
  for (const path of ["/work", "/work/", "/work/notes"]) {
    assert.deepEqual(resolveWorkspace(path, "?mode=personal"), { workspace: "work", workOnly: true });
  }
  assert.deepEqual(resolveWorkspace("/", "?mode=personal", true), { workspace: "work", workOnly: true });
  assert.throws(() => workspaceDestination(true, "work"), /dedicated to Work/);
});

test("main switches both scopes without sending users to the dedicated Work entry", () => {
  assert.deepEqual(resolveWorkspace("/", ""), { workspace: "personal", workOnly: false });
  assert.deepEqual(resolveWorkspace("/", "?mode=work"), { workspace: "work", workOnly: false });
  assert.equal(workspaceDestination(false, "personal"), "/?mode=work");
  assert.equal(workspaceDestination(false, "work"), "/");
  assert.deepEqual(resolveWorkspace("/workshop"), { workspace: "personal", workOnly: false });
});

test("completed folders use local years and months, preserve task identity and exclude open tasks", () => {
  const entry = (id: string, year: number, month: number, day: number): Entry => ({
    payload: { ...newPayload("task"), id, completed_at: new Date(year, month, day, 12).toISOString() },
    remote: { id, revision: 1, seq: 1, deleted: false },
  } as Entry);
  const older = entry("older", 2024, 5, 1), lastYear = entry("last-year", 2025, 11, 31);
  const september = entry("september", 2026, 8, 30), october = entry("october", 2026, 9, 2);
  const open = { ...october, payload: { ...october.payload, id: "open", completed_at: null } };
  const source = [october, september, lastYear, older, open];
  const folders = completedTaskFolders(source, new Date(2026, 9, 20));
  assert.deepEqual(folders.map(f => f.id), ["year:2024", "year:2025", "month:2026-09", "month:2026-10"]);
  assert.match(folders.at(-1)!.title, /this month/);
  assert.equal(folders.flatMap(f => f.items).length, 4);
  assert.equal(folders.at(-1)!.items[0], october);
  assert.deepEqual(source, [october, september, lastYear, older, open]);
});

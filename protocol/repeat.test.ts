import test from "node:test";
import assert from "node:assert/strict";
import { nextRepeatId, completeRepeatingTask } from "./repeat";
import { newPayload, validatePayload } from "./types";
import { mergeTask } from "./merge";

test("phone and web use the same successor identity and retain all task content", () => {
  const p = {...newPayload("task", "Read"), id: "12345678-1234-1234-1234-123456789abc",
    description: "Two chapters\nNotes", flags: ["important"], repeat_on_complete: true};
  assert.equal(nextRepeatId(p.id), "ea26d56b-8002-8797-9aa3-ec35e7076607");
  const now = "2026-11-17T13:00:00Z";
  const {completed, next} = completeRepeatingTask(p, now);
  assert.equal(completed.repeat_on_complete, undefined);
  assert.equal(completed.completed_at, now);
  assert.deepEqual(next, {...p, id: nextRepeatId(p.id), created_at: now, modified_at: now,
    completed_at: null, order: Date.parse(now)});
  assert.equal(completeRepeatingTask(completed, now).next, undefined);
});

test("optional repeat metadata remains compatible and merges disjoint edits", () => {
  const legacy = newPayload("task", "Read");
  assert.equal(validatePayload({...legacy, repeat_on_complete: false}, legacy.id).repeat_on_complete, undefined);
  assert.throws(() => validatePayload({...legacy, repeat_on_complete: "true"} as any, legacy.id));
  const base = {...legacy, repeat_on_complete: true};
  const stopped = {...legacy};
  const merged = mergeTask(base, stopped, {...base, description: "New detail"})!;
  assert.equal(merged.repeat_on_complete, undefined);
  assert.equal(merged.description, "New detail");
});

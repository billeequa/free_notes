import { sha256 } from "../web/node_modules/@noble/hashes/sha2.js";
import type { Payload } from "./types";

/** Custom UUIDv8 shared with Android, so concurrent completion has one successor ID. */
export function nextRepeatId(id: string): string {
  const bytes = sha256(new TextEncoder().encode(`jnotes.repeat.${id.toLowerCase()}`)).slice(0, 16);
  bytes[6] = (bytes[6] & 15) | 0x80;
  bytes[8] = (bytes[8] & 63) | 0x80;
  const hex = [...bytes].map(b => b.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export function completeRepeatingTask(payload: Payload, now = new Date().toISOString()): {completed: Payload; next?: Payload} {
  if (payload.kind !== "task" || payload.completed_at) return {completed: payload};
  const completed = {...payload, completed_at: now, modified_at: now};
  delete completed.repeat_on_complete;
  if (!payload.repeat_on_complete) return {completed};
  return {completed, next: {...payload, id: nextRepeatId(payload.id), created_at: now,
    modified_at: now, completed_at: null, order: Date.parse(now)}};
}

import type { Payload } from "./types";
const equal = (a: unknown, b: unknown) =>
  JSON.stringify(a) === JSON.stringify(b);
export function mergeTask(
  base: Payload,
  local: Payload,
  remote: Payload,
): Payload | null {
  if ([base, local, remote].some((p) => p.kind !== "task" || p.id !== base.id))
    return null;
  const result = { ...remote };
  for (const k of Object.keys(local) as (keyof Payload)[]) {
    if (k === "modified_at") continue;
    if (equal(local[k], base[k])) continue;
    if (!equal(remote[k], base[k]) && !equal(local[k], remote[k])) return null;
    Object.assign(result, { [k]: local[k] });
  }
  return { ...result, modified_at: new Date().toISOString() };
}
export function assertCloudIdentity(
  accepted: { deployment: string; epoch: string; cursor: number },
  current: { deployment: string; epoch: string; high_water: number },
) {
  if (
    accepted.deployment !== current.deployment ||
    accepted.epoch !== current.epoch ||
    current.high_water < accepted.cursor
  )
    throw Error(
      "Cloud state changed — repair required. Local data is preserved.",
    );
}

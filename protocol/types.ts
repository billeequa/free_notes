export type Kind = "note" | "journal" | "task";
export interface Payload {
  id: string;
  kind: Kind;
  title: string;
  body: string;
  created_at: string;
  modified_at: string;
  subtype: "standard" | "double_x_day";
  journal_date: string | null;
  double_x_date: string | null;
  template_version: number;
  description: string;
  completed_at: string | null;
  flagged: boolean;
  flags: string[];
  order: number;
}
export interface Envelope {
  protocol_version: 1;
  key_id: string;
  algorithm: "A256GCM";
  nonce: string;
  ciphertext_with_tag: string;
}
export interface Bootstrap {
  protocol_version: number;
  deployment_id: string;
  kdf_salt: string;
  memory_kib: number;
  iterations: number;
  parallelism: number;
}
export interface Vault {
  key_id: string;
  password_envelope: Envelope;
  recovery_envelope: Envelope;
  staged_envelope: Envelope | null;
  transition_id: string | null;
  epoch: string;
  high_water: number;
}
export interface Remote {
  id: string;
  revision: number;
  seq: number;
  envelope: Envelope;
  deleted: boolean;
}
export type AAD = [
  string,
  number,
  string,
  string,
  string,
  string,
  number,
  boolean,
];
export const recordAAD = (
  deployment: string,
  owner: string,
  key: string,
  id: string,
  revision: number,
  deleted: boolean,
): AAD => ["jnotes.record", 1, deployment, owner, key, id, revision, deleted];
export const vaultAAD = (
  deployment: string,
  owner: string,
  key: string,
  purpose: string,
): AAD => ["jnotes.vault", 1, deployment, owner, key, purpose, 0, false];
export function validatePayload(p: Payload, id: string): Payload {
  if (
    !p ||
    p.id !== id ||
    !["note", "journal", "task"].includes(p.kind) ||
    typeof p.title !== "string" ||
    typeof p.body !== "string" ||
    (p.kind !== "task" && /[\r\n]/.test(p.title)) ||
    typeof p.description !== "string" ||
    !Array.isArray(p.flags) ||
    p.flags.some((f) => !["urgent", "important", "long_term"].includes(f)) ||
    !Number.isSafeInteger(p.order) ||
    !["standard", "double_x_day"].includes(p.subtype) ||
    !Number.isSafeInteger(p.template_version) ||
    typeof p.flagged !== "boolean" ||
    !Number.isFinite(Date.parse(p.created_at)) ||
    !Number.isFinite(Date.parse(p.modified_at)) ||
    (p.completed_at !== null && !Number.isFinite(Date.parse(p.completed_at)))
  )
    throw Error("Unsupported or damaged record. Sync paused.");
  for (const date of [p.journal_date, p.double_x_date])
    if (date !== null && !/^\d{4}-\d{2}-\d{2}$/.test(date))
      throw Error("Invalid journal date");
  p.flags = ["urgent", "important", "long_term"].filter((f) =>
    p.flags.includes(f),
  );
  for (const key of ["created_at", "modified_at", "completed_at"] as const) {
    const value = p[key];
    if (value !== null)
      p[key] = value.replace(p.kind === "task" ? /\.000Z$/ : /\.\d+Z$/, "Z");
  }
  return p;
}
export function newPayload(kind: Kind, title = ""): Payload {
  const now = new Date().toISOString();
  const local = new Date();
  const date = `${local.getFullYear()}-${String(local.getMonth() + 1).padStart(2, "0")}-${String(local.getDate()).padStart(2, "0")}`;
  return {
    id: crypto.randomUUID(),
    kind,
    title: title || (kind === "journal" ? date : ""),
    body: "",
    created_at: now,
    modified_at: now,
    subtype: "standard",
    journal_date: kind === "journal" ? date : null,
    double_x_date: null,
    template_version: 0,
    description: "",
    completed_at: null,
    flagged: false,
    flags: [],
    order: Date.now(),
  };
}

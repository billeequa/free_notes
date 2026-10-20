import type { Entry } from "./client";

export interface CompletedFolder { id: string; title: string; items: Entry[] }

/** Same local-calendar year/month grouping as the phone; records stay in their original scope. */
export function completedTaskFolders(entries: Entry[], today = new Date()): CompletedFolder[] {
  const groups = new Map<string, CompletedFolder>();
  for (const entry of [...entries].filter(e => !!e.payload.completed_at)
    .sort((a, b) => Date.parse(a.payload.completed_at!) - Date.parse(b.payload.completed_at!))) {
    const date = new Date(entry.payload.completed_at!);
    const year = date.getFullYear();
    const month = date.getMonth();
    const currentYear = year === today.getFullYear();
    const id = currentYear ? `month:${year}-${String(month + 1).padStart(2, "0")}` : `year:${year}`;
    let folder = groups.get(id);
    if (!folder) {
      const title = currentYear ? date.toLocaleDateString(undefined, { month: "long" }) +
        (month === today.getMonth() ? " (this month)" : "") : String(year);
      folder = { id, title, items: [] };
      groups.set(id, folder);
    }
    folder.items.push(entry);
  }
  return [...groups.values()].sort((a, b) => {
    const yearOrder = Number(b.id.startsWith("year:")) - Number(a.id.startsWith("year:"));
    return yearOrder || a.id.localeCompare(b.id);
  });
}

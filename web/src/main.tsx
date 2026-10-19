import React, { useEffect, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import { Cloud, type Entry } from "./client";
import { newPayload, type Payload } from "../../protocol/types";
import "./style.css";
const symbols: Record<string, string> = {
  urgent: "🚨",
  important: "❗",
  long_term: "🎯",
};
function download(name: string, text: string, type = "text/plain") {
  const url = URL.createObjectURL(new Blob([text], { type }));
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
function App() {
  const [cloud, setCloud] = useState<Cloud | null>(null),
    [ready, setReady] = useState(false),
    [message, setMessage] = useState("Connecting…");
  const [email, setEmail] = useState(""),
    [password, setPassword] = useState(""),
    [recovery, setRecovery] = useState(false),
    [otp, setOtp] = useState(""),
    [code, setCode] = useState(""),
    [busy, setBusy] = useState(false);
  const [tab, setTab] = useState("task"),
    [search, setSearch] = useState(""),
    [version, update] = useState(0),
    [selected, setSelected] = useState<string | null>(null);
  const [draft, setDraft] = useState<Payload | null>(null),
    [base, setBase] = useState<Entry | undefined>(),
    [dirty, setDirty] = useState(false),
    [saveState, setSaveState] = useState("Synced");
  const [history, setHistory] = useState<
      { remote: any; payload: Payload }[] | null
    >(null),
    [completion, setCompletion] = useState<string | null>(null),
    [archive, setArchive] = useState<Set<string>>(new Set());
  const inFlight = useRef(false),
    revision = useRef(0),
    dirtyRef = useRef(false),
    draftRef = useRef(draft),
    baseRef = useRef(base),
    readyRef = useRef(ready),
    activity = useRef(Date.now());
  dirtyRef.current = dirty;
  draftRef.current = draft;
  baseRef.current = base;
  readyRef.current = ready;
  useEffect(() => {
    let cancelled = false;
    Cloud.connect()
      .then((c) => {
        if (!cancelled) {
          setCloud(c);
          setMessage("Your notebook, wherever you are.");
        }
      })
      .catch((e) => setMessage(e.message));
    return () => {
      cancelled = true;
    };
  }, []);
  useEffect(() => {
    const event = (e: BeforeUnloadEvent) => {
      if (dirtyRef.current) {
        e.preventDefault();
        e.returnValue = "";
      }
    };
    window.addEventListener("beforeunload", event);
    return () => window.removeEventListener("beforeunload", event);
  }, []);
  useEffect(() => {
    const touch = () => (activity.current = Date.now());
    window.addEventListener("pointerdown", touch);
    window.addEventListener("keydown", touch);
    const timer = setInterval(() => {
      if (!cloud || !readyRef.current) return;
      if (Date.now() - activity.current > 15 * 60 * 1000) {
        const unsaved = dirtyRef.current;
        readyRef.current = false;
        void cloud.logout().finally(() => {
          setReady(false);
          if (!unsaved) {
            setDraft(null);
            setSelected(null);
          }
          setHistory(null);
          setSearch("");
          setMessage(
            unsaved
              ? "Session locked. Sign in again to recover the unsaved draft kept in this tab."
              : "Session locked after inactivity.",
          );
        });
      } else if (!dirtyRef.current && !inFlight.current)
        void cloud
          .refresh()
          .then(() => update((v) => v + 1))
          .catch((e) => setMessage(e.message));
    }, 30000);
    return () => {
      clearInterval(timer);
      window.removeEventListener("pointerdown", touch);
      window.removeEventListener("keydown", touch);
    };
  }, [cloud]);
  async function authenticate(e: React.FormEvent) {
    e.preventDefault();
    if (!cloud) return;
    setBusy(true);
    setMessage("Unlocking your encrypted notebook…");
    try {
      if (recovery) await cloud.recover(email, otp, code, password);
      else await cloud.login(email, password);
      setPassword("");
      setCode("");
      setOtp("");
      setReady(true);
      setMessage("");
      activity.current = Date.now();
      update((v) => v + 1);
    } catch (e) {
      setMessage((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  function edit(p: Payload) {
    revision.current++;
    setDraft({ ...p, modified_at: new Date().toISOString() });
    setDirty(true);
    setSaveState("Pending upload");
  }
  async function save(): Promise<boolean> {
    if (!cloud || !draftRef.current || inFlight.current) return false;
    if (!dirtyRef.current) return true;
    inFlight.current = true;
    setSaveState("Saving…");
    const serial = revision.current;
    const p = draftRef.current;
    try {
      const entry = await cloud.save(
        p,
        baseRef.current,
        baseRef.current?.remote.deleted || false,
      );
      if (!entry) {
        setSaveState("Conflict — action needed");
        update((v) => v + 1);
        return false;
      }
      setBase(entry);
      if (serial === revision.current) {
        setDirty(false);
        setSaveState("Synced");
        setDraft(entry.payload);
      } else setSaveState("Pending upload");
      update((v) => v + 1);
      return serial === revision.current;
    } catch (e) {
      setSaveState("Not saved — Retry");
      setMessage((e as Error).message);
      return false;
    } finally {
      inFlight.current = false;
    }
  }
  useEffect(() => {
    if (!dirty || !draft || !ready || cloud?.conflicts.has(draft.id)) return;
    const timer = setTimeout(() => void save(), 800);
    return () => clearTimeout(timer);
  }, [draft, dirty, ready, version]);
  async function navigate(next: string) {
    if (dirty && !(await save())) return;
    setTab(next);
    setSelected(null);
    setDraft(null);
    setBase(undefined);
    setHistory(null);
    setSearch("");
    setMessage("");
  }
  async function openEntry(e: Entry) {
    if (dirty && !(await save())) return;
    setSelected(e.payload.id);
    setDraft({ ...e.payload });
    setBase(e);
    setDirty(false);
    setSaveState("Synced");
    setHistory(null);
  }
  async function create(kind: "note" | "journal" | "task") {
    if (dirty && !(await save())) return;
    const p = newPayload(kind);
    if (kind === "journal") {
      let i = 1;
      const titles = new Set(
        [...cloud!.entries.values()]
          .filter((e) => e.payload.kind === "journal")
          .map((e) => e.payload.title),
      );
      const stem = p.title;
      while (titles.has(p.title)) {
        i++;
        p.title = `${stem} ${roman(i)}`;
      }
    }
    setSelected(p.id);
    setBase(undefined);
    edit(p);
    setHistory(null);
  }
  async function mutate(e: Entry, p: Payload, deleted = e.remote.deleted) {
    if (!cloud) return;
    setBusy(true);
    try {
      const result = await cloud.save(
        { ...p, modified_at: new Date().toISOString() },
        e,
        deleted,
      );
      update((v) => v + 1);
      return result !== null;
    } catch (error) {
      setMessage((error as Error).message);
      return false;
    } finally {
      setBusy(false);
    }
  }
  async function showHistory() {
    if (!cloud || !selected) return;
    try {
      const rows = await cloud.history(selected);
      setHistory(
        await Promise.all(
          rows.map(async (r) => ({
            remote: r,
            payload: await cloud.decrypt(r),
          })),
        ),
      );
    } catch (e) {
      setMessage((e as Error).message);
    }
  }
  async function logout(global = false) {
    if (dirty && !(await save())) return;
    if (cloud) {
      await cloud.logout(global);
      setReady(false);
      setDraft(null);
      setBase(undefined);
      setSelected(null);
      setHistory(null);
      setSearch("");
      setMessage("Signed out. Phone files remain available.");
    }
  }
  useEffect(() => {
    if (!cloud || !selected || dirty || inFlight.current) return;
    const latest = cloud.entries.get(selected);
    if (latest && (!base || latest.remote.revision > base.remote.revision)) {
      setBase(latest);
      setDraft({ ...latest.payload });
    }
  }, [version]);
  const all = cloud ? [...cloud.entries.values()] : [];
  const entries = all.filter(
    (e) =>
      (tab === "trash"
        ? e.remote.deleted
        : !e.remote.deleted && e.payload.kind === tab) &&
      `${e.payload.title} ${e.payload.body} ${e.payload.description}`
        .toLowerCase()
        .includes(search.toLowerCase()),
  );
  const tasks = entries
    .filter((e) => !e.payload.completed_at)
    .sort((a, b) => a.payload.order - b.payload.order);
  const completed = entries
    .filter((e) => e.payload.completed_at)
    .sort(
      (a, b) =>
        Date.parse(b.payload.completed_at!) -
        Date.parse(a.payload.completed_at!),
    );
  function taskCard(e: Entry) {
    const p = e.payload;
    const mask = p.flags.reduce(
      (n, f) => n | ({ urgent: 1, important: 2, long_term: 4 }[f] || 0),
      0,
    );
    return (
      <article
        key={p.id}
        className={`task flag-${mask} ${p.completed_at ? "completed" : ""}`}
      >
        <div className="task-main">
          <button
            className="check"
            aria-label={p.completed_at ? "Reopen task" : "Complete task"}
            onClick={() =>
              p.completed_at
                ? void mutate(e, { ...p, completed_at: null })
                : setCompletion(p.id)
            }
          >
            {p.completed_at || completion === p.id ? "✓" : "○"}
          </button>
          <button className="task-title" onClick={() => void openEntry(e)}>
            <strong>
              {p.flags.map((f) => symbols[f]).join(" ") ||
                (p.flagged ? "⚑" : "")}{" "}
              {p.title || "Untitled task"}
            </strong>
            {p.description && <span>{p.description}</span>}
          </button>
          <details>
            <summary aria-label="Task actions">•••</summary>
            <div className="menu">
              {Object.entries(symbols).map(([f, icon]) => (
                <button
                  key={f}
                  onClick={() =>
                    void mutate(e, {
                      ...p,
                      flagged: false,
                      flags: p.flags.includes(f)
                        ? p.flags.filter((v) => v !== f)
                        : [...p.flags, f],
                    })
                  }
                >
                  {icon} {p.flags.includes(f) ? "Unmark" : "Mark"}{" "}
                  {f.replace("_", " ")}
                </button>
              ))}
              <button onClick={() => void mutate(e, p, true)}>
                Move to Trash
              </button>
            </div>
          </details>
        </div>
        {completion === p.id && (
          <div className="confirmation">
            <span>Complete this task?</span>
            <button
              onClick={() => {
                setCompletion(null);
                void mutate(e, {
                  ...p,
                  completed_at: new Date().toISOString(),
                });
              }}
            >
              ✓ Confirm
            </button>
            <button onClick={() => setCompletion(null)}>Cancel</button>
          </div>
        )}
      </article>
    );
  }
  if (!ready)
    return (
      <main className="login-page">
        <div className="brand">
          <span>J</span> JNotes
        </div>
        <section className="login-card">
          <p className="eyebrow">YOUR WORDS. YOUR SPACE.</p>
          <h1>{recovery ? "Recover your notebook" : "Welcome back."}</h1>
          <p className="muted">{message}</p>
          <form onSubmit={authenticate}>
            <label>
              Email
              <input
                type="email"
                autoComplete="username"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                required
              />
            </label>
            {recovery && (
              <>
                <button
                  type="button"
                  disabled={!cloud || busy}
                  onClick={() =>
                    void cloud
                      ?.sendCode(email)
                      .then(() =>
                        setMessage(
                          "If the account is approved, check your email for its verification code.",
                        ),
                      )
                      .catch((e) => setMessage(e.message))
                  }
                >
                  Send email verification code
                </button>
                <label>
                  Email code
                  <input
                    value={otp}
                    autoComplete="one-time-code"
                    onChange={(e) => setOtp(e.target.value)}
                    required
                  />
                </label>
                <label>
                  One-time recovery code
                  <input
                    value={code}
                    autoComplete="off"
                    onChange={(e) => setCode(e.target.value)}
                    required
                  />
                </label>
              </>
            )}
            <label>
              {recovery ? "New password" : "Password"}
              <input
                type="password"
                autoComplete={recovery ? "new-password" : "current-password"}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                minLength={recovery ? 12 : 1}
                required
              />
            </label>
            <button className="primary" disabled={!cloud || busy}>
              {busy
                ? "Unlocking…"
                : recovery
                  ? "Recover and sign in"
                  : "Sign in"}
            </button>
          </form>
          <button
            className="text-button"
            onClick={() => {
              setRecovery(!recovery);
              setPassword("");
            }}
          >
            {recovery ? "Back to sign in" : "Forgot your password?"}
          </button>
          <p className="footnote">
            Set up Web Sync in JNotes on your Android phone first. Your phone
            always works independently.
          </p>
        </section>
        <p className="login-footer">Encrypted before it leaves your device.</p>
      </main>
    );
  return (
    <div className="workspace">
      <aside>
        <div className="brand">
          <span>J</span> JNotes
        </div>
        <p className="eyebrow">YOUR NOTEBOOK</p>
        <nav>
          {[
            ["task", "To-Do", "✓"],
            ["note", "Notes", "≡"],
            ["journal", "Journal", "◷"],
            ["trash", "Trash", "⌫"],
            ["settings", "Settings", "⚙"],
          ].map(([id, label, icon]) => (
            <button
              key={id}
              className={tab === id ? "active" : ""}
              onClick={() => void navigate(id)}
            >
              <span>{icon}</span>
              {label}
              <small>
                {id === "settings"
                  ? ""
                  : all.filter((e) =>
                      id === "trash"
                        ? e.remote.deleted
                        : !e.remote.deleted && e.payload.kind === id,
                    ).length}
              </small>
            </button>
          ))}
        </nav>
        <div className="account">
          <span className="dot" /> Online · encrypted
          <br />
          <small>{email}</small>
          <button onClick={() => void logout()}>Sign out</button>
        </div>
      </aside>
      <main className="main">
        <header>
          <div>
            <p className="eyebrow">A LITTLE ROOM TO THINK</p>
            <h1>
              {
                (
                  {
                    task: "To-Do",
                    note: "Notes",
                    journal: "Journal",
                    trash: "Trash",
                    settings: "Settings",
                  } as Record<string, string>
                )[tab]
              }
            </h1>
          </div>
          <div className="toolbar">
            {!["settings", "trash"].includes(tab) && (
              <button
                className="primary"
                onClick={() => void create(tab as any)}
              >
                ＋{" "}
                {tab === "task"
                  ? "New task"
                  : tab === "journal"
                    ? "New entry"
                    : "New note"}
              </button>
            )}
            <button
              aria-label="Sync now"
              onClick={() =>
                void cloud!
                  .refresh()
                  .then(() => {
                    update((v) => v + 1);
                    setMessage("Synced");
                  })
                  .catch((e) => setMessage(e.message))
              }
            >
              ↻ Sync
            </button>
          </div>
        </header>
        {message && (
          <div role="status" className="notice">
            {message}
            <button aria-label="Dismiss" onClick={() => setMessage("")}>
              ×
            </button>
          </div>
        )}
        {!!cloud!.conflicts.size && (
          <section className="conflict">
            <h2>Conflicts need your attention</h2>
            {[...cloud!.conflicts].map(([id, c]) => (
              <div key={id}>
                <div className="compare">
                  <pre>
                    {c.local.title}\n{c.local.body || c.local.description}
                  </pre>
                  <pre>
                    {c.remotePayload.title}\n
                    {c.remotePayload.body || c.remotePayload.description}
                  </pre>
                </div>
                {(["local", "remote", "both"] as const).map((choice) => (
                  <button
                    key={choice}
                    onClick={() =>
                      void cloud!
                        .resolve(id, choice)
                        .then(() => {
                          setSelected(null);
                          setDraft(null);
                          setDirty(false);
                          update((v) => v + 1);
                        })
                        .catch((e) => setMessage(e.message))
                    }
                  >
                    {
                      {
                        local: "Keep this edit",
                        remote: "Keep cloud version",
                        both: "Save both",
                      }[choice]
                    }
                  </button>
                ))}
              </div>
            ))}
          </section>
        )}
        {tab === "settings" ? (
          <section className="settings panel">
            <h2>Web Sync</h2>
            <p>
              All records are encrypted on your device. Your phone keeps its own
              readable files and works even if this server is unavailable.
            </p>
            <p>Cloud: {cloud!.config.supabase_url}</p>
            <p>
              Browser storage: this session only. Unsaved writing stays in this
              tab until saved or downloaded.
            </p>
            <p>
              Devices are diagnostic only in v1. To revoke access, sign out all
              sessions.
            </p>
            <button
              onClick={() =>
                void cloud!
                  .exportEncrypted()
                  .then((v) =>
                    download(
                      "jnotes-encrypted-backup.json",
                      JSON.stringify(v, null, 2),
                      "application/json",
                    ),
                  )
                  .catch((e) => setMessage(e.message))
              }
            >
              Export encrypted cloud backup
            </button>
            <button onClick={() => void logout(true)}>
              Sign out all sessions
            </button>
            <h2>Change password</h2>
            <form
              onSubmit={(e) => {
                e.preventDefault();
                setBusy(true);
                void cloud!
                  .changePassword(password)
                  .then(() => {
                    setPassword("");
                    setMessage(
                      "Password changed. Your existing recovery code remains valid.",
                    );
                  })
                  .catch((e) => setMessage(e.message))
                  .finally(() => setBusy(false));
              }}
            >
              <label>
                New password
                <input
                  type="password"
                  autoComplete="new-password"
                  minLength={12}
                  required
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                />
              </label>
              <button disabled={busy}>Change password</button>
            </form>
            <p className="footnote">
              For hosting, recovery, and moving servers, see
              docs/web-sync/operator-guide.md in the repository.
            </p>
          </section>
        ) : (
          <>
            <div className="search">
              <span>⌕</span>
              <input
                aria-label="Search your records"
                placeholder="Search your words…"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
              />
              <small>
                {entries.length} {tab === "task" ? "tasks" : "entries"}
              </small>
            </div>
            <div className={`content ${selected ? "with-editor" : ""}`}>
              <section className="record-list">
                {tab === "task" ? (
                  <>
                    {tasks.length === 0 && (
                      <div className="empty">
                        <h2>A clear page.</h2>
                        <p>Add a task when something comes to mind.</p>
                      </div>
                    )}
                    {tasks.map(taskCard)}
                    {completed.length > 0 && (
                      <details className="archive">
                        <summary>Completed · {completed.length}</summary>
                        {Object.entries(
                          completed.reduce<Record<string, Entry[]>>(
                            (groups, e) => {
                              (groups[e.payload.completed_at!.slice(0, 7)] ??=
                                []).push(e);
                              return groups;
                            },
                            {},
                          ),
                        )
                          .sort(([a], [b]) => b.localeCompare(a))
                          .map(([month, items]) => (
                            <details key={month}>
                              <summary>
                                {month} · {items!.length}
                              </summary>
                              {items!.map(taskCard)}
                            </details>
                          ))}
                      </details>
                    )}
                  </>
                ) : (
                  entries
                    .sort(
                      (a, b) =>
                        Date.parse(b.payload.modified_at) -
                        Date.parse(a.payload.modified_at),
                    )
                    .map((e) => (
                      <button
                        key={e.payload.id}
                        onClick={() => void openEntry(e)}
                        className={`document-card ${selected === e.payload.id ? "selected" : ""}`}
                      >
                        <div className="document-date">
                          {new Date(e.payload.modified_at).toLocaleDateString(
                            undefined,
                            { month: "short", day: "numeric" },
                          )}
                          <span>
                            {e.payload.subtype === "double_x_day"
                              ? "✕✕"
                              : e.payload.kind === "task"
                                ? "✓"
                                : ""}
                          </span>
                        </div>
                        <h2>{e.payload.title || "Untitled"}</h2>
                        <p>
                          {e.payload.body.slice(0, 140) ||
                            e.payload.description.slice(0, 140) ||
                            "A little space for your thoughts."}
                        </p>
                      </button>
                    ))
                )}
                {tab !== "task" && !entries.length && (
                  <div className="empty">
                    <h2>
                      {tab === "trash"
                        ? "Nothing in the trash."
                        : "Make a little space."}
                    </h2>
                    <p>
                      {tab === "trash"
                        ? "Deleted records stay recoverable here."
                        : "Start writing. Your words will be here when you return."}
                    </p>
                  </div>
                )}
              </section>
              {selected && draft && (
                <section className="editor panel">
                  <div className="editor-toolbar">
                    <span role="status" className={dirty ? "pending" : ""}>
                      {saveState}
                    </span>
                    <div>
                      <button onClick={() => void save()}>Save</button>
                      <button onClick={() => void showHistory()}>
                        History
                      </button>
                      <button
                        aria-label="Close editor"
                        onClick={() => {
                          if (!dirty) {
                            setSelected(null);
                            setDraft(null);
                          } else
                            void save().then((ok) => {
                              if (ok) {
                                setSelected(null);
                                setDraft(null);
                              }
                            });
                        }}
                      >
                        ×
                      </button>
                    </div>
                  </div>
                  <input
                    className="title-input"
                    aria-label="Title"
                    placeholder={
                      draft.kind === "task" ? "Task title" : "Untitled"
                    }
                    value={draft.title}
                    onChange={(e) => edit({ ...draft, title: e.target.value })}
                  />
                  <div className="editor-date">
                    {new Date(draft.created_at).toLocaleString()}
                    {draft.subtype === "double_x_day" ? " · Double X Day" : ""}
                  </div>
                  {draft.kind === "task" ? (
                    <>
                      <textarea
                        aria-label="Task description"
                        placeholder="Add a few details…"
                        value={draft.description}
                        onChange={(e) =>
                          edit({ ...draft, description: e.target.value })
                        }
                      />
                      <div className="flags">
                        {Object.entries(symbols).map(([f, icon]) => (
                          <label key={f}>
                            <input
                              type="checkbox"
                              checked={draft.flags.includes(f)}
                              onChange={() =>
                                edit({
                                  ...draft,
                                  flagged: false,
                                  flags: draft.flags.includes(f)
                                    ? draft.flags.filter((v) => v !== f)
                                    : [...draft.flags, f],
                                })
                              }
                            />
                            {icon} {f.replace("_", " ")}
                          </label>
                        ))}
                      </div>
                    </>
                  ) : (
                    <textarea
                      aria-label="Document body"
                      placeholder="Start writing…"
                      value={draft.body}
                      onChange={(e) => edit({ ...draft, body: e.target.value })}
                    />
                  )}
                  <div className="editor-actions">
                    {dirty && (
                      <>
                        <button onClick={() => void save()}>Retry save</button>
                        <button
                          onClick={() =>
                            download(
                              "jnotes-unsaved-draft.txt",
                              `${draft.title}\n\n${draft.body || draft.description}`,
                            )
                          }
                        >
                          Download draft
                        </button>
                        <button
                          onClick={() =>
                            void navigator.clipboard
                              .writeText(
                                `${draft.title}\n\n${draft.body || draft.description}`,
                              )
                              .catch(() =>
                                setMessage(
                                  "Clipboard unavailable. Download the draft instead.",
                                ),
                              )
                          }
                        >
                          Copy draft
                        </button>
                      </>
                    )}
                    {base &&
                      (base.remote.deleted ? (
                        <button
                          onClick={() =>
                            void mutate(base, draft, false).then((ok) => {
                              if (ok) {
                                setSelected(null);
                                setDraft(null);
                                setDirty(false);
                              }
                            })
                          }
                        >
                          Restore from Trash
                        </button>
                      ) : (
                        <button
                          onClick={() =>
                            void (async () => {
                              if (dirty && !(await save())) return;
                              const current = cloud!.entries.get(draft.id);
                              if (
                                current &&
                                !(await mutate(current, draft, true))
                              )
                                return;
                              setSelected(null);
                              setDraft(null);
                            })()
                          }
                        >
                          Move to Trash
                        </button>
                      ))}
                  </div>
                  {history && (
                    <div className="history">
                      <h2>Previous versions</h2>
                      {history.map((h) => (
                        <details key={h.remote.revision}>
                          <summary>
                            Revision {h.remote.revision}{" "}
                            {h.remote.deleted ? "· deleted" : ""} ·{" "}
                            {new Date(h.remote.changed_at).toLocaleString()}
                          </summary>
                          <pre>
                            {h.payload.title}\n\n
                            {h.payload.body || h.payload.description}
                          </pre>
                          <button
                            onClick={() => {
                              edit({
                                ...h.payload,
                                modified_at: new Date().toISOString(),
                              });
                              setHistory(null);
                            }}
                          >
                            Restore as new revision
                          </button>
                        </details>
                      ))}
                      {history.length > 0 &&
                        history.at(-1)!.remote.revision > 1 && (
                          <button
                            onClick={() =>
                              void cloud!
                                .history(
                                  selected,
                                  history.at(-1)!.remote.revision,
                                )
                                .then(async (rows) =>
                                  setHistory([
                                    ...history,
                                    ...(await Promise.all(
                                      rows.map(async (r) => ({
                                        remote: r,
                                        payload: await cloud!.decrypt(r),
                                      })),
                                    )),
                                  ]),
                                )
                            }
                          >
                            Older versions
                          </button>
                        )}
                    </div>
                  )}
                </section>
              )}
            </div>
          </>
        )}
        <footer>JNotes · A place for what matters.</footer>
      </main>
    </div>
  );
}
function roman(n: number): string {
  let s = "";
  for (const [v, t] of [
    [1000, "M"],
    [900, "CM"],
    [500, "D"],
    [400, "CD"],
    [100, "C"],
    [90, "XC"],
    [50, "L"],
    [40, "XL"],
    [10, "X"],
    [9, "IX"],
    [5, "V"],
    [4, "IV"],
    [1, "I"],
  ] as [number, string][])
    while (n >= v) {
      s += t;
      n -= v;
    }
  return s;
}
createRoot(document.getElementById("root")!).render(<App />);

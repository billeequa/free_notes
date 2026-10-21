import { createClient, type SupabaseClient } from "@supabase/supabase-js";
import { assertCloudIdentity, mergeTask } from "../../protocol/merge";
import { emailSendError } from "./authReturn";
import {
  b64,
  checkBootstrap,
  decodeJSON,
  derive,
  expand,
  jsonBytes,
  open,
  random,
  recoveryCode,
  recoveryKey,
  seal,
} from "../../protocol/crypto";
import {
  recordAAD,
  validatePayload,
  vaultAAD,
  type Bootstrap,
  type Envelope,
  type Payload,
  type Remote,
  type Vault,
} from "../../protocol/types";
export interface Config {
  supabase_url: string;
  publishable_key: string;
  web_url?: string;
}
export type Entry = { payload: Payload; remote: Remote };
export type Conflict = {
  local: Payload;
  deleted: boolean;
  remote: Remote;
  remotePayload: Payload;
};
export class Cloud {
  readonly api: SupabaseClient;
  bootstrap!: Bootstrap;
  vault!: Vault;
  owner = "";
  key: Uint8Array = new Uint8Array();
  cursor = 0;
  private generation = 0;
  private pendingSetup?: {
    keyId: string;
    code: string;
    recoveryEnvelope: Envelope;
  };
  completeSetup() {
    this.pendingSetup = undefined;
  }
  device = crypto.randomUUID();
  entries = new Map<string, Entry>();
  conflicts = new Map<string, Conflict>();
  // Keep the exact nonce/op on retry after an ambiguous server response.
  pending = new Map<
    string,
    {
      op: string;
      envelope: Envelope;
      payload: Payload;
      deleted: boolean;
      base: number;
    }
  >();
  private constructor(readonly config: Config, readonly workspace: "personal" | "work" = "personal") {
    if (
      !config.supabase_url ||
      !config.publishable_key ||
      /^(sb_secret_|eyJ.*service_role)/.test(config.publishable_key)
    )
      throw Error("Public cloud configuration is missing.");
    if (!config.publishable_key.startsWith("sb_publishable_")) {
      try {
        const claims = JSON.parse(
          atob(
            config.publishable_key
              .split(".")[1]
              .replaceAll("-", "+")
              .replaceAll("_", "/"),
          ),
        );
        if (claims.role !== "anon") throw Error();
      } catch {
        throw Error("Use a public publishable key, never an admin key.");
      }
    }
    const url = new URL(config.supabase_url);
    if (
      url.protocol !== "https:" &&
      !["localhost", "127.0.0.1"].includes(url.hostname)
    )
      throw Error("HTTPS is required.");
    this.api = createClient(config.supabase_url, config.publishable_key, {
      auth: {
        persistSession: false,
        autoRefreshToken: true,
        detectSessionInUrl: false,
        flowType: "implicit",
      },
      global: {
        fetch: (url, init) =>
          fetch(url, {
            ...init,
            cache: "no-store",
            referrerPolicy: "no-referrer",
          }),
      },
    });
  }
  static async connect(workspace: "personal" | "work" = "personal") {
    const response = await fetch("/config.json", { cache: "no-store" });
    if (!response.ok)
      throw Error("Host configuration is missing. See server/README.md.");
    const c = new Cloud(await response.json(), workspace);
    const { data, error } = await c.api
      .from("public_bootstrap")
      .select("*")
      .single();
    if (error) throw Error("Cannot reach the configured cloud.");
    checkBootstrap(data);
    c.bootstrap = data;
    return c;
  }
  async rpc<T>(name: string, args: Record<string, unknown> = {}): Promise<T> {
    const scoped = ["get_vault", "get_changes", "commit_operation", "get_history", "ack_cursor"];
    if (this.workspace === "work") {
      if (scoped.includes(name)) name = `work_${name}`;
      else if (name !== "finish_password") throw Error("Open Personal mode and sign in to manage your account.");
    }
    const { data, error } = await this.api.rpc(name, args);
    if (error) {
      if (error.message.includes("CLOUD_STATE_CHANGED"))
        throw Error(
          "Cloud state changed — repair required. Phone files remain safe.",
        );
      if (error.message.includes("VAULT_QUOTA_REACHED"))
        throw Error(
          "Cloud storage quota reached. Your unsaved text remains in this tab.",
        );
      if (error.code === "42501")
        throw Error(
          "Account recovery needed. Verify the owner account and sign in again.",
        );
      throw Error(
        `Cloud request failed (${error.code || "network"}). Your unsaved text remains in this browser tab.`,
      );
    }
    return data as T;
  }
  async loadVault() {
    const state = await this.rpc<{
      vault: Vault | null;
      epoch: string;
      high_water: number;
    }>("get_vault");
    if (!state.vault)
      throw Error(
        "Your notebook setup is not finished. Choose Set up my password on this website.",
      );
    this.vault = {
      ...state.vault,
      epoch: state.epoch,
      high_water: state.high_water,
    };
  }
  async login(email: string, password: string) {
    const keys = await derive(password, this.bootstrap);
    try {
      const { data, error } = await this.api.auth.signInWithPassword({
        email: email.trim(),
        password: keys.auth,
      });
      if (error || !data.user)
        throw Error(
          "Email or password is incorrect. First time? Choose Set up my password below.",
        );
      this.owner = data.user.id;
      await this.loadVault();
      const aad = vaultAAD(
        this.bootstrap.deployment_id,
        this.owner,
        this.vault.key_id,
        "password",
      );
      try {
        this.key = await open(keys.wrap, this.vault.password_envelope, aad);
      } catch {
        if (!this.vault.staged_envelope) throw Error("Vault recovery needed.");
        this.key = await open(keys.wrap, this.vault.staged_envelope, aad);
        await this.rpc("finish_password", {
          p_transition_id: this.vault.transition_id,
        });
      }
      if (this.key.length !== 32) throw Error("Invalid vault key");
      if (this.workspace === "work") {
        const master = this.key;
        this.key = expand(master, "workspace/work");
        master.fill(0);
      }
      await this.refresh();
    } catch (e) {
      await this.logout();
      if (e instanceof Error && e.name === "OperationError")
        throw Error("Your notebook could not be decrypted. Your saved notes are unchanged.", { cause: e });
      throw e;
    } finally {
      keys.wrap.fill(0);
    }
  }
  async sendEmailLink(email: string, recovery: boolean, redirectTo: string) {
    const { error } = recovery
      ? await this.api.auth.resetPasswordForEmail(email.trim(), { redirectTo })
      : await this.api.auth.signInWithOtp({
          email: email.trim(),
          options: { shouldCreateUser: false, emailRedirectTo: redirectTo },
        });
    if (error) throw Error(emailSendError(error));
  }
  async acceptEmailLink(session: {
    access_token: string;
    refresh_token: string;
  }) {
    const { error } = await this.api.auth.setSession(session);
    if (error)
      throw Error(
        "This email link has expired or was already used. Request a new link.",
      );
    // Validate against Auth; URL/JWT claims alone never establish notebook ownership.
    const { data, error: userError } = await this.api.auth.getUser();
    if (userError || !data.user || !data.user.email_confirmed_at) {
      await this.logout();
      throw Error(
        "Email verification could not be completed. Request a new link.",
      );
    }
    this.owner = data.user.id;
    try {
      const state = await this.rpc<{
        vault: Vault | null;
        epoch: string;
        high_water: number;
      }>("get_vault");
      if (!state.vault && state.high_water !== 0)
        throw Error(
          "The cloud key is missing. Your existing notebook must be repaired; a new one will not be created over it.",
        );
      if (state.vault)
        this.vault = {
          ...state.vault,
          epoch: state.epoch,
          high_water: state.high_water,
        };
      return { email: data.user.email || "", hasNotebook: !!state.vault };
    } catch (e) {
      await this.logout();
      throw e;
    }
  }
  async createNotebook(password: string): Promise<string | null> {
    if (this.workspace === "work") throw Error("Open Personal mode to manage your account.");
    if (password.length < 12) throw Error("Use at least 12 characters.");
    const state = await this.rpc<{ vault: Vault | null; high_water: number }>(
      "get_vault",
    );
    if (state.vault) {
      await this.unlockFromEmail(password);
      const pending = this.pendingSetup;
      return pending &&
        pending.keyId === state.vault.key_id &&
        Object.entries(pending.recoveryEnvelope).every(
          ([name, value]) =>
            state.vault!.recovery_envelope[name as keyof Envelope] === value,
        )
        ? pending.code
        : null;
    }
    if (state.high_water !== 0)
      throw Error(
        "The cloud key is missing. Existing encrypted notes will not be replaced.",
      );
    const keys = await derive(password, this.bootstrap),
      key = random(),
      keyId = crypto.randomUUID(),
      code = recoveryCode();
    const aad = (purpose: string) =>
      vaultAAD(this.bootstrap.deployment_id, this.owner, keyId, purpose);
    try {
      const passwordEnvelope = await seal(
        keys.wrap,
        keyId,
        key,
        aad("password"),
      );
      const rk = recoveryKey(code);
      let recoveryEnvelope;
      try {
        recoveryEnvelope = await seal(rk, keyId, key, aad("recovery"));
      } finally {
        rk.fill(0);
      }
      // Keep this attempt in memory until the user confirms saving its recovery code.
      // Retrying after a lost create/read response must display the same code.
      this.pendingSetup = { keyId, code, recoveryEnvelope };
      const { error } = await this.api.auth.updateUser({ password: keys.auth });
      if (error)
        throw Error(
          "Your password could not be saved. Keep this page open and try again.",
        );
      try {
        await this.rpc("create_vault", {
          p_key_id: keyId,
          p_password_envelope: passwordEnvelope,
          p_recovery_envelope: recoveryEnvelope,
        });
      } catch (error) {
        // A lost reply may follow a successful create. Accept only this exact key/envelope.
        await this.loadVault();
        if (
          this.vault.key_id !== keyId ||
          Object.entries(recoveryEnvelope).some(
            ([name, value]) =>
              this.vault.recovery_envelope[name as keyof Envelope] !== value,
          )
        )
          throw error;
      }
      await this.loadVault();
      this.key = await open(
        keys.wrap,
        this.vault.password_envelope,
        aad("password"),
      );
      if (this.key.length !== 32) throw Error("Invalid notebook key");
      await this.refresh();
      return code;
    } catch (error) {
      this.key.fill(0);
      this.key = new Uint8Array();
      throw error;
    } finally {
      keys.wrap.fill(0);
      key.fill(0);
    }
  }
  async unlockFromEmail(password: string) {
    if (this.workspace === "work") throw Error("Open Personal mode to manage your account.");
    const keys = await derive(password, this.bootstrap);
    try {
      await this.loadVault();
      const aad = vaultAAD(
        this.bootstrap.deployment_id,
        this.owner,
        this.vault.key_id,
        "password",
      );
      try {
        this.key = await open(keys.wrap, this.vault.password_envelope, aad);
      } catch {
        if (!this.vault.staged_envelope)
          throw Error(
            "That is not your JNotes password. Try again or use your recovery code.",
          );
        this.key = await open(keys.wrap, this.vault.staged_envelope, aad);
        await this.rpc("finish_password", {
          p_transition_id: this.vault.transition_id,
        });
      }
      if (this.key.length !== 32) throw Error("Invalid notebook key");
      await this.refresh();
    } catch (e) {
      this.key.fill(0);
      this.key = new Uint8Array();
      throw e;
    } finally {
      keys.wrap.fill(0);
    }
  }
  async recoverFromEmail(code: string, password: string) {
    if (this.workspace === "work") throw Error("Open Personal mode to manage your account.");
    await this.loadVault();
    const rk = recoveryKey(code);
    try {
      this.key = await open(
        rk,
        this.vault.recovery_envelope,
        vaultAAD(
          this.bootstrap.deployment_id,
          this.owner,
          this.vault.key_id,
          "recovery",
        ),
      );
    } catch {
      throw Error(
        "That recovery code is incorrect. Check the code you saved during setup.",
      );
    } finally {
      rk.fill(0);
    }
    try {
      await this.changePassword(password);
      await this.refresh();
    } catch (e) {
      this.key.fill(0);
      this.key = new Uint8Array();
      throw e;
    }
  }
  async changePassword(password: string) {
    if (this.workspace === "work") throw Error("Open Personal mode to manage your account.");
    if (password.length < 12) throw Error("Use at least 12 characters.");
    await this.loadVault();
    const keys = await derive(password, this.bootstrap);
    try {
      // The independent recovery envelope stays intact throughout both API transactions.
      const transition = this.vault.transition_id || crypto.randomUUID();
      const e = await seal(
        keys.wrap,
        this.vault.key_id,
        this.key,
        vaultAAD(
          this.bootstrap.deployment_id,
          this.owner,
          this.vault.key_id,
          "password",
        ),
      );
      await this.rpc("stage_password", {
        p_transition_id: transition,
        p_envelope: e,
      });
      const { error } = await this.api.auth.updateUser({ password: keys.auth });
      if (error)
        throw Error(
          "Password change paused. Retry recovery; your recovery code remains valid.",
        );
      await this.rpc("finish_password", { p_transition_id: transition });
      await this.loadVault();
    } finally {
      keys.wrap.fill(0);
    }
  }
  async decrypt(r: Remote) {
    const p = decodeJSON<Payload>(
      await open(
        this.key,
        r.envelope,
        recordAAD(
          this.workspace === "work" ? `${this.bootstrap.deployment_id}/work` : this.bootstrap.deployment_id,
          this.owner,
          this.vault.key_id,
          r.id,
          r.revision,
          r.deleted,
        ),
      ),
    );
    return validatePayload(p, r.id);
  }
  async refresh() {
    const generation = this.generation;
    const state = await this.rpc<{
      vault: Vault;
      epoch: string;
      high_water: number;
    }>("get_vault");
    assertCloudIdentity(
      {
        deployment: this.bootstrap.deployment_id,
        epoch: this.vault.epoch,
        cursor: this.cursor,
      },
      {
        deployment: this.bootstrap.deployment_id,
        epoch: state.epoch,
        high_water: state.high_water,
      },
    );
    if (!state.vault || state.vault.key_id !== this.vault.key_id)
      throw Error("Cloud vault changed — repair required.");
    let more = true;
    while (more) {
      const page = await this.rpc<{
        changes: Remote[];
        high_water: number;
        epoch: string;
        next_cursor: number;
      }>("get_changes", {
        p_epoch: this.vault.epoch,
        p_after: this.cursor,
        p_limit: 50,
      });
      if (page.next_cursor < this.cursor || page.next_cursor > page.high_water)
        throw Error("Invalid cloud cursor");
      for (const r of page.changes) {
        if (r.seq !== this.cursor + 1)
          throw Error("Missing cloud change — repair required.");
        const payload = await this.decrypt(r);
        if (generation !== this.generation) throw Error("Session locked.");
        const old = this.entries.get(r.id);
        if (!old || old.remote.revision < r.revision)
          this.entries.set(r.id, { payload, remote: r });
        this.cursor = r.seq;
      }
      more = this.cursor < page.high_water;
      if (more && !page.changes.length)
        throw Error("Missing cloud history — repair required.");
    }
    if (generation !== this.generation) throw Error("Session locked.");
    await this.rpc("ack_cursor", {
      p_device_id: this.device,
      p_epoch: this.vault.epoch,
      p_cursor: this.cursor,
    });
  }
  async save(
    payload: Payload,
    base: Entry | undefined,
    deleted = false,
  ): Promise<Entry | null> {
    const generation = this.generation;
    validatePayload(payload, payload.id);
    if (this.workspace === "work" && payload.kind === "journal") throw Error("Work mode supports notes and tasks.");
    let pending = this.pending.get(payload.id);
    if (
      pending &&
      (JSON.stringify(pending.payload) !== JSON.stringify(payload) ||
        pending.deleted !== deleted)
    ) {
      // Resolve the previous possibly-committed operation before replacing it.
      const previous = await this.sendPending(pending);
      if (previous.status !== "OK") {
        if (!previous.current)
          throw Error("Cloud record disappeared — repair required.");
        const remotePayload = await this.decrypt(previous.current);
        this.conflicts.set(payload.id, {
          local: payload,
          deleted,
          remote: previous.current,
          remotePayload,
        });
        this.pending.delete(payload.id);
        return null;
      }
      this.pending.delete(payload.id);
      base = this.entries.get(payload.id);
    }
    if (!pending || !this.pending.has(payload.id)) {
      const revision = base?.remote.revision || 0;
      const e = await seal(
        this.key,
        this.vault.key_id,
        jsonBytes(payload),
        recordAAD(
          this.workspace === "work" ? `${this.bootstrap.deployment_id}/work` : this.bootstrap.deployment_id,
          this.owner,
          this.vault.key_id,
          payload.id,
          revision + 1,
          deleted,
        ),
      );
      if (generation !== this.generation) throw Error("Session locked.");
      pending = {
        op: crypto.randomUUID(),
        envelope: e,
        payload,
        deleted,
        base: revision,
      };
      this.pending.set(payload.id, pending);
    }
    const response = await this.sendPending(pending!);
    if (response.status === "OK") {
      this.pending.delete(payload.id);
      return this.entries.get(payload.id)!;
    }
    this.pending.delete(payload.id);
    if (!response.current)
      throw Error("Cloud record disappeared — repair required.");
    const remotePayload = await this.decrypt(response.current);
    const merged =
      base && !deleted && !response.current.deleted
        ? mergeTask(base.payload, payload, remotePayload)
        : null;
    if (merged)
      return this.save(
        merged,
        { payload: remotePayload, remote: response.current },
        false,
      );
    this.conflicts.set(payload.id, {
      local: payload,
      deleted,
      remote: response.current,
      remotePayload,
    });
    return null;
  }
  private async sendPending(p: {
    op: string;
    envelope: Envelope;
    payload: Payload;
    deleted: boolean;
    base: number;
  }) {
    const generation = this.generation;
    const result = await this.rpc<{
      status: string;
      revision: number;
      seq: number;
      current: Remote | null;
    }>("commit_operation", {
      p_epoch: this.vault.epoch,
      p_floor: this.cursor,
      p_op_id: p.op,
      p_device_id: this.device,
      p_id: p.payload.id,
      p_expected_revision: p.base,
      p_envelope: p.envelope,
      p_deleted: p.deleted,
    });
    if (generation !== this.generation) throw Error("Session locked.");
    if (result.status === "OK")
      this.entries.set(p.payload.id, {
        payload: p.payload,
        remote: {
          id: p.payload.id,
          revision: result.revision,
          seq: result.seq,
          envelope: p.envelope,
          deleted: p.deleted,
        },
      });
    return result;
  }
  async resolve(id: string, choice: "local" | "remote" | "both") {
    const c = this.conflicts.get(id);
    if (!c) return;
    if (choice === "remote") {
      const archived = {
        ...c.local,
        id: crypto.randomUUID(),
        title: `${c.local.title} (discarded conflict)`,
      };
      if (!(await this.save(archived, undefined, true)))
        throw Error("Conflict copy could not be preserved.");
      this.entries.set(id, { payload: c.remotePayload, remote: c.remote });
    } else {
      const result = await this.save(
        c.local,
        { payload: c.remotePayload, remote: c.remote },
        c.deleted,
      );
      if (!result) return;
      if (choice === "both")
        await this.save(
          {
            ...c.remotePayload,
            id: crypto.randomUUID(),
            title: `${c.remotePayload.title} (conflict copy)`,
          },
          undefined,
          c.remote.deleted,
        );
    }
    this.conflicts.delete(id);
  }
  async history(id: string, before?: number) {
    return this.rpc<(Remote & { changed_at: string })[]>("get_history", {
      p_id: id,
      ...(before ? { p_before: before } : {}),
    });
  }
  async exportEncrypted() {
    // A consistent full feed export carries its accepted snapshot bound, not live plaintext.
    const state = await this.rpc<{
      vault: Vault;
      epoch: string;
      high_water: number;
    }>("get_vault");
    const changes: Remote[] = [];
    let cursor = 0;
    while (cursor < state.high_water) {
      const page = await this.rpc<{ changes: Remote[]; next_cursor: number }>(
        "get_changes",
        { p_epoch: state.epoch, p_after: cursor, p_limit: 50 },
      );
      if (!page.changes.length) throw Error("Incomplete cloud export");
      changes.push(...page.changes.filter((r) => r.seq <= state.high_water));
      cursor = page.next_cursor;
    }
    return {
      format: "jnotes.encrypted-export.v1",
      workspace: this.workspace,
      bootstrap: this.bootstrap,
      owner_uid: this.owner,
      vault: state.vault,
      epoch: state.epoch,
      high_water: state.high_water,
      changes,
    };
  }
  async logout(global = false) {
    this.generation++;
    this.completeSetup();
    this.key.fill(0);
    this.key = new Uint8Array();
    this.owner = "";
    this.entries.clear();
    this.pending.clear();
    this.conflicts.clear();
    this.cursor = 0;
    await this.api.auth.signOut({ scope: global ? "global" : "local" });
  }
}
export { b64, random, recoveryCode };

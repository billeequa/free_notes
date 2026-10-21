import { argon2idAsync } from "../web/node_modules/@noble/hashes/argon2.js";
import { hkdf } from "../web/node_modules/@noble/hashes/hkdf.js";
import { sha256 } from "../web/node_modules/@noble/hashes/sha2.js";
import type { AAD, Bootstrap, Envelope } from "./types";
const utf8 = new TextEncoder();
export const b64 = (a: Uint8Array) => {
  let s = "";
  for (const v of a) s += String.fromCharCode(v);
  return btoa(s);
};
export function unb64(s: string): Uint8Array {
  const a = Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
  if (b64(a) !== s) throw Error("Noncanonical base64");
  return a;
}
const buf = (a: Uint8Array): ArrayBuffer => Uint8Array.from(a).buffer;
export const random = (n = 32) => crypto.getRandomValues(new Uint8Array(n));
export function expand(material: Uint8Array, label: string): Uint8Array {
  return hkdf(
    sha256,
    material,
    new Uint8Array(32),
    utf8.encode(`jnotes.v1/${label}`),
    32,
  );
}
/** Android's JSONStringer escapes slashes; the Work deployment contains /work. */
function aadJSON(aad: AAD): string {
  const json = JSON.stringify(aad);
  return aad[0] === "jnotes.record" && aad[2].endsWith("/work")
    ? json.replaceAll("/", "\\/") : json;
}
export function checkBootstrap(b: Bootstrap) {
  if (
    b.protocol_version !== 1 ||
    !b.deployment_id ||
    unb64(b.kdf_salt).length !== 16 ||
    b.memory_kib !== 65536 ||
    b.iterations !== 3 ||
    b.parallelism !== 1
  )
    throw Error("Unsupported cloud KDF configuration.");
}
export async function derive(password: string, b: Bootstrap) {
  checkBootstrap(b);
  // Password UTF-8 exactly; no normalization, trimming or email-dependent salt.
  const intermediate = await argon2idAsync(
    utf8.encode(password),
    unb64(b.kdf_salt),
    {
      m: b.memory_kib,
      t: b.iterations,
      p: b.parallelism,
      dkLen: 32,
      version: 0x13,
    },
  );
  try {
    return {
      auth: b64(expand(intermediate, "auth")),
      wrap: expand(intermediate, "wrap"),
    };
  } finally {
    intermediate.fill(0);
  }
}
export async function seal(
  key: Uint8Array,
  keyId: string,
  plaintext: Uint8Array,
  aad: AAD,
  nonce = random(12),
): Promise<Envelope> {
  if (key.length !== 32 || nonce.length !== 12)
    throw Error("Invalid key or nonce");
  const k = await crypto.subtle.importKey("raw", buf(key), "AES-GCM", false, [
    "encrypt",
  ]);
  const ct = await crypto.subtle.encrypt(
    {
      name: "AES-GCM",
      iv: buf(nonce),
      additionalData: buf(utf8.encode(aadJSON(aad))),
      tagLength: 128,
    },
    k,
    buf(plaintext),
  );
  return {
    protocol_version: 1,
    key_id: keyId,
    algorithm: "A256GCM",
    nonce: b64(nonce),
    ciphertext_with_tag: b64(new Uint8Array(ct)),
  };
}
export async function open(
  key: Uint8Array,
  e: Envelope,
  aad: AAD,
): Promise<Uint8Array> {
  if (
    e.protocol_version !== 1 ||
    e.algorithm !== "A256GCM" ||
    e.key_id !== aad[4] ||
    unb64(e.nonce).length !== 12 ||
    unb64(e.ciphertext_with_tag).length < 16
  )
    throw Error("Unsupported ciphertext");
  const k = await crypto.subtle.importKey("raw", buf(key), "AES-GCM", false, [
    "decrypt",
  ]);
  const decrypt = (serialized: string) => crypto.subtle.decrypt(
      {
        name: "AES-GCM",
        iv: buf(unb64(e.nonce)),
        additionalData: buf(utf8.encode(serialized)),
        tagLength: 128,
      },
      k,
      buf(unb64(e.ciphertext_with_tag)),
    );
  const serialized = aadJSON(aad);
  try {
    return new Uint8Array(await decrypt(serialized));
  } catch (error) {
    // Older web Work records used unescaped JSON. Accept both byte encodings
    // of the same AAD; never change its scope, identity, revision or deletion bit.
    if (!(error instanceof Error) || error.name !== "OperationError" || serialized === JSON.stringify(aad)) throw error;
    return new Uint8Array(await decrypt(JSON.stringify(aad)));
  }
}
export const jsonBytes = (v: unknown) => utf8.encode(JSON.stringify(v));
export const decodeJSON = <T>(a: Uint8Array): T =>
  JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(a));
export function recoveryCode(secret = random()): string {
  if (secret.length !== 32) throw Error("Invalid recovery secret");
  const hex = [...secret]
    .map((v) => v.toString(16).padStart(2, "0"))
    .join("")
    .toUpperCase();
  const checksum = [...sha256(secret).slice(0, 4)]
    .map((v) => v.toString(16).padStart(2, "0"))
    .join("")
    .toUpperCase();
  return `JN1-${(hex + checksum).match(/.{1,8}/g)!.join("-")}`;
}
export function recoveryKey(code: string): Uint8Array {
  const s = code.trim().toUpperCase().replace(/^JN1-/, "").replace(/-/g, "");
  if (!/^[0-9A-F]{72}$/.test(s)) throw Error("Invalid recovery code");
  const secret = Uint8Array.from(s.slice(0, 64).match(/../g)!, (v) =>
    parseInt(v, 16),
  );
  if (recoveryCode(secret).replace(/^JN1-/, "").replace(/-/g, "") !== s)
    throw Error("Recovery code checksum failed");
  return expand(secret, "recovery");
}

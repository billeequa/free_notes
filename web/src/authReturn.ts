export type EmailReturn = {
  flow: "login" | "setup" | "recovery";
  session?: { access_token: string; refresh_token: string };
  error?: string;
  cleanURL: string;
};

/** Capture once before any asynchronous work; never retain credentials in the URL. */
export function parseEmailReturn(address: string): EmailReturn {
  const url = new URL(address);
  const hash = new URLSearchParams(url.hash.slice(1));
  const flow =
    url.hash === "#setup"
      ? "setup"
      : url.hash === "#recover"
        ? "recovery"
        : "login";
  const result: EmailReturn = { flow, cleanURL: address };
  const secretNames = [
    "access_token",
    "refresh_token",
    "token_hash",
    "provider_token",
    "provider_refresh_token",
    "expires_in",
    "expires_at",
    "token_type",
    "type",
    "error",
    "error_code",
    "error_description",
    "code",
  ];
  const hasReturn = secretNames.some(
    (name) => hash.has(name) || url.searchParams.has(name),
  );
  if (!hasReturn) return result;
  const expired =
    hash.has("error") ||
    hash.has("error_code") ||
    url.searchParams.has("error") ||
    url.searchParams.has("error_code");
  url.hash = "";
  for (const name of secretNames) url.searchParams.delete(name);
  result.cleanURL = url.href;
  if (expired) {
    result.error =
      "This email link has expired or was already used. Request a new link below.";
    result.flow = "setup";
    return result;
  }
  const access_token = hash.get("access_token"),
    refresh_token = hash.get("refresh_token"),
    type = hash.get("type");
  if (
    !access_token ||
    !refresh_token ||
    hash.getAll("access_token").length !== 1 ||
    hash.getAll("refresh_token").length !== 1 ||
    !["invite", "magiclink", "recovery", "signup", "email"].includes(type || "")
  ) {
    result.error =
      "This sign-in link could not be completed. Request a new email link below.";
    result.flow = "setup";
    return result;
  }
  result.session = { access_token, refresh_token };
  result.flow = type === "recovery" ? "recovery" : "setup";
  return result;
}

export function emailSendError(error: {
  status?: number;
  code?: string;
}): string {
  return error.status === 429 || error.code?.includes("rate_limit")
    ? "Email sending is temporarily limited. Wait before requesting another link, and check the newest email already in your inbox."
    : "The email could not be sent. Check the address and try again. Use the email invited to JNotes.";
}

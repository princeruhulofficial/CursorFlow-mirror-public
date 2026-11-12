/**
 * Smart cleanup coordinator (PRD §9, §10, §19).
 *
 * Owns the decision tree around the LLM: consent, capability, request shape,
 * response validation and the deterministic safety guard. The transport is
 * injected so the same rules are used by the screen and by unit tests, and so
 * no provider key ever lives in the Android bundle.
 */

import { buildCleanupRequest, validateCleanupResponse, type CleanupRequest, type CleanupResponse } from "./model/cleanup";
import { analyzeCleanupSafety, describeCleanupSafetyViolations, type CleanupSafetyViolation } from "./model/cleanup-safety";
import type { Draft } from "./model/draft";

export type CleanupCapability = {
  /** Backend says the approved provider route exists. */
  available: boolean;
  requiresSignIn: boolean;
  maxChars: number;
  reason: string | null;
};

export const CAPABILITY_UNAVAILABLE: CleanupCapability = {
  available: false,
  requiresSignIn: false,
  maxChars: 2000,
  reason: "The cleanup service could not be reached, so Smart is marked unavailable.",
};

export type CleanupTransport = (request: CleanupRequest) => Promise<unknown>;

export type CleanupOutcome =
  | { status: "applied"; request: CleanupRequest; text: string }
  | { status: "held"; request: CleanupRequest; text: string; reason: string; violations: CleanupSafetyViolation[] }
  | { status: "unavailable"; reason: string }
  | { status: "failed"; reason: string }
  | { status: "skipped"; reason: string };

export type CleanupRunInput = {
  draft: Draft;
  /** The text the cleanup was asked about; used for the preservation guard. */
  sourceText: string;
  sourceRevision: number;
  enabled: boolean;
  consented: boolean;
  capability: CleanupCapability;
  operationId: string;
  language: string | null;
  vocabularyVersion?: string;
  vocabulary?: string[];
  formatStyle?: "natural" | "paragraphs" | "bullets" | "email";
  transport: CleanupTransport;
};

export async function runSmartCleanup(input: CleanupRunInput): Promise<CleanupOutcome> {
  if (!input.enabled) return { status: "skipped", reason: "Smart cleanup is off for this draft." };
  if (!input.capability.available) {
    return { status: "unavailable", reason: input.capability.reason ?? "Smart cleanup is not available on this build." };
  }
  if (input.capability.requiresSignIn && !input.consented) {
    return { status: "unavailable", reason: "Smart cleanup runs on the Cursor Flow backend and needs your confirmation and an active account." };
  }
  if (!input.sourceText.trim()) return { status: "skipped", reason: "Nothing to clean up yet." };

  const built = buildCleanupRequest({
    operationId: input.operationId,
    draftId: input.draft.id,
    draftRevision: input.sourceRevision,
    text: input.sourceText,
    language: input.language,
    vocabularyVersion: input.vocabularyVersion,
    vocabulary: input.vocabulary,
    formatStyle: input.formatStyle,
  });
  if (!built.ok) {
    const reason = built.reason === "too_long"
      ? "This draft is longer than the cleanup service accepts. Send it as Original or shorten it."
      : built.reason === "empty"
        ? "Nothing to clean up yet."
        : "The draft is not ready for cleanup yet.";
    return { status: "skipped", reason };
  }

  let payload: unknown;
  try {
    payload = await input.transport(built.request);
  } catch (error) {
    return { status: "failed", reason: describeTransportError(error, input.capability) };
  }

  const validated = validateCleanupResponse(payload, built.request);
  if (!validated.ok) return { status: "failed", reason: validated.reason };

  const safety = analyzeCleanupSafety({ original: input.sourceText, cleaned: validated.text, maxChars: input.capability.maxChars });
  if (!safety.ok) {
    return {
      status: "held",
      request: built.request,
      text: validated.text,
      reason: describeCleanupSafetyViolations(safety.violations),
      violations: safety.violations,
    };
  }
  return { status: "applied", request: built.request, text: validated.text };
}

function describeTransportError(error: unknown, capability: CleanupCapability) {
  const message = error instanceof Error ? error.message : String(error);
  if (/unauthorized|not authenticated|401/i.test(message)) {
    return capability.requiresSignIn
      ? "Sign in so the backend can authorize this cleanup request. Your text stays untouched meanwhile."
      : "The cleanup service refused this request. Your text stays untouched.";
  }
  if (/rate limit|429/i.test(message)) return "Cleanup is rate limited right now. Try again in a moment; your text stays untouched.";
  if (/timeout|abort/i.test(message)) return "Cleanup did not answer in time. Your text stays as it is.";
  return `Cleanup could not run (${message || "unknown error"}). Original text is preserved.`;
}

export function asCleanupResponse(payload: unknown): CleanupResponse | null {
  return payload && typeof payload === "object" ? (payload as CleanupResponse) : null;
}

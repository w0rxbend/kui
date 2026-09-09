/** The alerts response envelope, decoded through the kernel's single alert payload reader. */
import { decodeSection, type Section } from "@kui/api";
import { decodeAlertFeed, type AlertFeed } from "@kui/kernel";

export const FEED_KEY = "events";

export function feedSection(body: unknown): Section<AlertFeed> {
  if (typeof body !== "object" || body === null) {
    return unreadable(`expected an alerts document, got ${body === null ? "null" : typeof body}`);
  }
  const raw = (body as Record<string, unknown>)[FEED_KEY];
  if (raw === undefined) return unreadable(`the alerts document carried no "${FEED_KEY}" section`);

  const section = decodeSection<unknown>(raw);
  if (section.status !== "ok" && section.status !== "stale") return section;

  const decoded = decodeAlertFeed(section.data);
  if (!decoded.ok) return unreadable(decoded.cause);
  return section.status === "ok"
    ? { status: "ok", data: decoded.value, fetchedAt: section.fetchedAt }
    : {
        status: "stale",
        data: decoded.value,
        fetchedAt: section.fetchedAt,
        reason: section.reason,
      };
}

function unreadable(cause: string): Section<AlertFeed> {
  return { status: "unreadable", reason: { code: "unreadable", message: cause } };
}

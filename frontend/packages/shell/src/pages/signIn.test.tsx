import { describe, expect, test, vi } from "vitest";
import { flush } from "solid-js";
import { render } from "@solidjs/web";
import type { KuiApiClient } from "@kui/api";
import { SignIn } from "./SignIn.jsx";

function mount(component: () => unknown) {
  const host = document.createElement("div");
  document.body.appendChild(host);
  const dispose = render(component as never, host);
  flush();
  return {
    host,
    dispose: () => {
      dispose();
      host.remove();
    },
  };
}

/** A client whose `post` answers with whatever is handed to it, and records what it was sent. */
function client(answers: readonly unknown[]) {
  const sent: { path: string; body: unknown }[] = [];
  let call = 0;
  const post = vi.fn(async (path: string, init: { body?: unknown }) => {
    sent.push({ path, body: init?.body });
    const answer = answers[call] ?? { ok: true, value: {} };
    call += 1;
    return answer;
  });
  return {
    sent,
    api: {
      get: post,
      post,
      put: post,
      delete: post,
      patch: post,
      raw: {},
    } as unknown as KuiApiClient,
  };
}

const ok = (value: unknown) => ({ ok: true, value });
const refused = {
  ok: false,
  error: {
    kind: "envelope" as const,
    code: "KUI-UNAUTHENTICATED",
    message: "the credentials were not accepted",
    details: [],
    correlationId: "test",
    retryable: false,
  },
};

async function type(host: HTMLElement, label: RegExp, value: string): Promise<void> {
  const field = [...host.querySelectorAll("input")].find((input) => {
    const id = input.getAttribute("id");
    const text = id === null ? "" : (host.querySelector(`label[for="${id}"]`)?.textContent ?? "");
    return label.test(text);
  });
  if (field === undefined) throw new Error(`no field labelled ${label}`);
  field.value = value;
  field.dispatchEvent(new Event("input", { bubbles: true }));
  await flush();
}

describe("signing in", () => {
  test("sends the credentials and tells the shell to start again", async () => {
    const { api, sent } = client([ok({ principal: { name: "ada", kind: "user" } })]);
    const signedIn = vi.fn();
    const { host, dispose } = mount(() => (
      <SignIn authType="form" api={api} onSignedIn={signedIn} />
    ));
    await flush();

    await type(host, /username/i, "ada");
    await type(host, /^password/i, "correct horse");
    host
      .querySelector("form")
      ?.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    await flush();
    await new Promise((resolve) => setTimeout(resolve, 0));
    await flush();

    expect(sent[0]?.path).toBe("/api/v1/auth/login");
    expect(sent[0]?.body).toEqual({ username: "ada", password: "correct horse" });
    expect(signedIn).toHaveBeenCalled();
    dispose();
  });

  test("says one sentence for a refusal, whichever half was wrong", async () => {
    /*
     * Two sentences — "no such user" and "wrong password" — are an oracle that tells anybody with a
     * browser which usernames exist. The identity service already spends a decoy hash on an unknown
     * account so the *timing* cannot answer that question either; saying it in the message would
     * throw that away.
     */
    const { api } = client([refused]);
    const { host, dispose } = mount(() => (
      <SignIn authType="form" api={api} onSignedIn={() => undefined} />
    ));
    await flush();
    await type(host, /username/i, "ada");
    await type(host, /^password/i, "wrong");
    host
      .querySelector("form")
      ?.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    await new Promise((resolve) => setTimeout(resolve, 0));
    await flush();

    expect(host.textContent).toContain("That username and password were not accepted.");
    // Nothing that distinguishes the two cases.
    expect(host.textContent).not.toMatch(/no such|unknown user|does not exist|incorrect password/i);
    dispose();
  });

  test("a required password change is a third state, not a successful sign-in", async () => {
    /*
     * The server grants no session in this case, so treating it as success would drop somebody into
     * an application they are not signed in to. It ends by asking them to sign in with the new
     * password — one way to obtain a session rather than two.
     */
    const { api, sent } = client([ok({ challenge: "single-use-token" }), ok({})]);
    const signedIn = vi.fn();
    const { host, dispose } = mount(() => (
      <SignIn authType="form" api={api} onSignedIn={signedIn} />
    ));
    await flush();
    await type(host, /username/i, "ada");
    await type(host, /^password/i, "temporary");
    host
      .querySelector("form")
      ?.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    await new Promise((resolve) => setTimeout(resolve, 0));
    await flush();

    expect(signedIn).not.toHaveBeenCalled();
    expect(host.textContent).toContain("has to have a new password set");

    await type(host, /new password$/i, "a better one");
    await type(host, /again/i, "a better one");
    host
      .querySelector("form")
      ?.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    await new Promise((resolve) => setTimeout(resolve, 0));
    await flush();

    expect(sent[1]?.path).toBe("/api/v1/auth/password");
    expect(sent[1]?.body).toEqual({ challenge: "single-use-token", newPassword: "a better one" });
    // Still not signed in: the change grants no session, so the screen asks for one.
    expect(signedIn).not.toHaveBeenCalled();
    expect(host.textContent).toContain("Sign in with it.");
    dispose();
  });

  test("catches two different new passwords without a round trip", async () => {
    const { api, sent } = client([ok({ challenge: "single-use-token" })]);
    const { host, dispose } = mount(() => (
      <SignIn authType="form" api={api} onSignedIn={() => undefined} />
    ));
    await flush();
    await type(host, /username/i, "ada");
    await type(host, /^password/i, "temporary");
    host
      .querySelector("form")
      ?.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    await new Promise((resolve) => setTimeout(resolve, 0));
    await flush();

    await type(host, /new password$/i, "one thing");
    await type(host, /again/i, "another thing");
    host
      .querySelector("form")
      ?.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    await new Promise((resolve) => setTimeout(resolve, 0));
    await flush();

    expect(host.textContent).toContain("The two passwords are not the same.");
    // The server was never asked: it could not have known what was meant, and a round trip to be
    // told you typed it twice differently is a round trip for nothing.
    expect(sent).toHaveLength(1);
    dispose();
  });

  test("an OIDC deployment offers no password field at all", async () => {
    // With a provider, KUI never sees a password. A field for one would be a field that cannot work
    // and an invitation to type a password into the wrong application.
    const { api } = client([]);
    const { host, dispose } = mount(() => (
      <SignIn authType="oidc" providerLabel="Okta" api={api} onSignedIn={() => undefined} />
    ));
    await flush();
    expect(host.querySelector('input[type="password"]')).toBeNull();
    expect(host.textContent).toContain("Okta");
    expect(host.textContent).toContain("never sees your password");
    dispose();
  });

  test("renders without a client, and every control then does nothing", async () => {
    // The honest shape for a screen with no server: a story or a test can see the layout without
    // standing up a gateway, and no control silently half-works.
    const { host, dispose } = mount(() => <SignIn authType="form" onSignedIn={() => undefined} />);
    await flush();
    expect(host.querySelector("form")).not.toBeNull();
    dispose();
  });
});

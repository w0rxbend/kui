/**
 * The sign-in screen: the door into a KUI that has authentication turned on.
 *
 * ## Why this file exists
 *
 * Every server-side half of authentication was built before any browser could reach it. `POST
 * /api/v1/auth/login` works, `/auth/settings` says which kind of sign-in a deployment uses, the
 * session cookie is issued and rotated, roles are resolved and permissions come back on `/auth/me`.
 * None of it was reachable from this frontend. A deployment configured with `kui.auth.type: form`
 * served its interface to anybody who asked, as the anonymous principal, with no way to become
 * anybody else — so from a user's point of view the whole authentication feature was a set of
 * endpoints and no product.
 *
 * ## When it is shown, and when it must never be
 *
 * Exactly one condition: the deployment says it uses a sign-in (`authType !== "disabled"`) **and**
 * the current principal is anonymous. Both halves matter.
 *
 * - Without the first, a deployment that has deliberately configured no authentication — the
 *   default, and what the quickstart and every demonstration run — would meet a login screen with no
 *   account to type into. That is the product's front door, and putting a locked gate across it is
 *   the worst regression this screen could cause.
 * - Without the second, a signed-in user is asked to sign in again on every reload.
 *
 * ## Three flows, one screen
 *
 * - **form** — a username and a password, posted to `/auth/login`.
 * - **oidc** — one button, which asks the gateway where to send the browser and then goes there.
 *   No password field, because with a provider KUI never sees one.
 * - **a required password change** — what `/auth/login` answers when an account was created with
 *   `mustChangePassword`. It is a *third state* rather than a flag on success, because the server
 *   grants no session in that case: the screen collects a new password against the single-use
 *   challenge the server returned, and then asks the user to sign in with it.
 *
 * ## What it never does
 *
 * It never says which half of a rejected sign-in was wrong. "That username and password were not
 * accepted" is one sentence for an unknown account and for a wrong password, because two sentences
 * are an oracle that tells anybody with a browser which usernames exist. The identity service
 * already spends a decoy hash on an unknown account so the *timing* cannot answer that question
 * either; saying it in the message would throw that away.
 */
import { Show, createSignal, createUniqueId } from "solid-js";
import type { JSX } from "@solidjs/web";
import { userMessage, type KuiApiClient } from "@kui/api";
import { Banner, Button, TextField } from "@kui/kernel";

export interface SignInProps {
  /** What the deployment said about itself. `oidc` draws one button and no password field. */
  readonly authType: string;
  /** The provider's name, for the OIDC button: "Sign in with Okta". */
  readonly providerLabel?: string | undefined;
  /**
   * `undefined` in a story or a test that only wants the layout. Every control then renders and does
   * nothing, which is the honest shape for a screen with no server behind it.
   */
  readonly api?: KuiApiClient | undefined;
  /**
   * What to do once the server has issued a session.
   *
   * In the application this reloads the page, and that is deliberate rather than lazy: every store
   * in the shell — permissions, capabilities, the cluster list — was populated as the anonymous
   * principal, and a reload is the one way to be certain none of it survives into a session that is
   * now somebody else's.
   */
  readonly onSignedIn: () => void;
}

/**
 * The sentence for a refused sign-in, whatever the server said.
 *
 * The gateway's own message is already deliberately uninformative, but it travels as
 * `KUI-UNAUTHENTICATED`, which other screens render as "your session has ended" — correct there and
 * actively confusing on a screen where no session has begun.
 */
const REFUSED = "That username and password were not accepted.";

type Stage =
  { readonly kind: "credentials" } | { readonly kind: "changing"; readonly challenge: string };

export function SignIn(props: SignInProps): JSX.Element {
  /*
   * One value rather than three booleans. "Submitting, and also showing an error, and also asking
   * for a new password" is a state that must not be representable.
   */
  const [stage, setStage] = createSignal<Stage>({ kind: "credentials" });
  const [username, setUsername] = createSignal("");
  const [password, setPassword] = createSignal("");
  const [fresh, setFresh] = createSignal("");
  const [confirmation, setConfirmation] = createSignal("");
  const [busy, setBusy] = createSignal(false);
  const [failure, setFailure] = createSignal<string | undefined>(undefined);
  const [notice, setNotice] = createSignal<string | undefined>(undefined);

  const titleId = createUniqueId();

  const fail = (message: string): void => {
    setFailure(message);
    setNotice(undefined);
    setBusy(false);
  };

  const signIn = async (): Promise<void> => {
    const name = username().trim();
    const secret = password();
    if (name === "" || secret === "") {
      fail("Enter a username and a password.");
      return;
    }
    if (props.api === undefined) return;

    setBusy(true);
    setFailure(undefined);
    const answer = await props.api.post("/api/v1/auth/login", {
      body: { username: name, password: secret },
    });

    if (!answer.ok) {
      fail(REFUSED);
      return;
    }

    // The password leaves the page's memory the moment it is no longer needed. It buys little
    // against somebody who already has the machine, and it costs nothing.
    setPassword("");
    setBusy(false);

    const challenge = (answer.value as { challenge?: string }).challenge;
    if (typeof challenge === "string") {
      setNotice("This account has to have a new password set before it can be used.");
      setFailure(undefined);
      setStage({ kind: "changing", challenge });
      return;
    }
    props.onSignedIn();
  };

  /**
   * Completing a required change.
   *
   * The server grants no session for it, so this ends by asking the user to sign in — one way to
   * obtain a session rather than two.
   */
  const changePassword = async (challenge: string): Promise<void> => {
    if (fresh() === "") {
      fail("Enter a new password.");
      return;
    }
    if (fresh() !== confirmation()) {
      // Checked here rather than by the server, because the server cannot know what was meant — and
      // a round trip to be told you typed it twice differently is a round trip for nothing.
      fail("The two passwords are not the same.");
      return;
    }
    if (props.api === undefined) return;

    setBusy(true);
    setFailure(undefined);
    const answer = await props.api.post("/api/v1/auth/password", {
      body: { challenge, newPassword: fresh() },
    });

    if (!answer.ok) {
      fail(userMessage(answer.error));
      return;
    }
    setFresh("");
    setConfirmation("");
    setBusy(false);
    setFailure(undefined);
    setNotice("Your password has been changed. Sign in with it.");
    setStage({ kind: "credentials" });
  };

  /**
   * Handing the browser to the provider.
   *
   * The gateway answers with the URL to go to, its state and PKCE challenge already minted, so
   * nothing about the flow is decided in the browser.
   */
  const startProvider = async (): Promise<void> => {
    if (props.api === undefined) return;
    setBusy(true);
    setFailure(undefined);
    const answer = await props.api.post("/api/v1/auth/oidc/start", {});
    if (!answer.ok) {
      fail(userMessage(answer.error));
      return;
    }
    window.location.href = answer.value.authorizationUrl;
  };

  const submit = (event: Event): void => {
    event.preventDefault();
    // Ignored while a request is in flight, so holding Enter down cannot send three sign-ins.
    if (busy()) return;
    const current = stage();
    void (current.kind === "credentials" ? signIn() : changePassword(current.challenge));
  };

  return (
    <div
      class="kui-login"
      data-testid="login-page"
      /* The same role the unreachable-gateway screen uses, and for the same reason: it covers
         everything, nothing behind it is usable, and `alertdialog` is what makes a screen reader
         announce it on arrival rather than leaving somebody to discover it. */
      role="alertdialog"
      aria-modal="true"
      aria-labelledby={titleId}
    >
      {/* A real `<form>`, so Enter in either field submits. Without it the only way in is the mouse,
          which is the wrong answer on the one screen every user has to type on. */}
      <form class="kui-login__card" onSubmit={submit}>
        <h1 class="kui-login__title" id={titleId}>
          Sign in to KUI
        </h1>

        <Show when={notice()}>{(text) => <Banner tone="info" message={text()} />}</Show>
        <Show when={failure()}>{(text) => <Banner tone="danger" message={text()} />}</Show>

        <Show
          when={props.authType === "oidc"}
          fallback={
            <Show
              when={stage().kind === "credentials"}
              fallback={
                <>
                  <TextField
                    label="New password"
                    type="password"
                    value={fresh()}
                    onInput={setFresh}
                    required
                    name="new-password"
                  />
                  <TextField
                    label="New password again"
                    type="password"
                    value={confirmation()}
                    onInput={setConfirmation}
                    required
                    name="confirm-password"
                  />
                </>
              }
            >
              <TextField
                label="Username"
                value={username()}
                onInput={setUsername}
                required
                name="username"
              />
              <TextField
                label="Password"
                type="password"
                value={password()}
                onInput={setPassword}
                required
                name="password"
              />
            </Show>
          }
        >
          <p class="kui-login__provider-note">
            This deployment signs in through {props.providerLabel ?? "an identity provider"}. KUI
            never sees your password.
          </p>
        </Show>

        <div class="kui-login__actions">
          <Show
            when={props.authType === "oidc"}
            fallback={
              <Button variant="primary" type="submit" busy={busy()}>
                {stage().kind === "credentials" ? "Sign in" : "Set password"}
              </Button>
            }
          >
            <Button variant="primary" busy={busy()} onClick={() => void startProvider()}>
              Continue with {props.providerLabel ?? "your provider"}
            </Button>
          </Show>
        </div>
      </form>
    </div>
  );
}

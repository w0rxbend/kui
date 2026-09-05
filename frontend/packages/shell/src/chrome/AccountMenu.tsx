/**
 * The panel behind the avatar at the foot of the environment rail: who you are, and the way out.
 *
 * ## Why there was no way out
 *
 * `POST /api/v1/auth/logout` has existed since the gateway's session work landed and nothing in the
 * browser had ever called it. A deployment with authentication configured therefore had a front door
 * and no back one: once signed in, the only way to become somebody else was to clear a cookie by
 * hand. That is not merely an inconvenience — it is what makes a shared workstation unsafe, and it
 * is the reason this file exists rather than a menu with several entries in it.
 *
 * ## It appears only where there is a session to end
 *
 * The caller decides, and the rule is the mirror of {@link mustSignIn}'s: a deployment running with
 * `authType: "disabled"` has an anonymous principal that no `logout` can dispose of, so it gets no
 * avatar button and no panel at all. A "Sign out" that clears nothing is a control that cannot work,
 * and the project's rule about disabled controls does not apply — this is not a permission the user
 * lacks, it is a session that does not exist.
 *
 * ## Signing out reloads the page
 *
 * For exactly the reason signing in does, and the reason is worth repeating here because the two
 * call sites are far apart. Every store in the shell — the permissions, the capability fold, the
 * cluster list, each feature's own data — was populated as the principal who is now leaving. Naming
 * the ones to clear is a list somebody will one day fail to keep up to date, and its failure mode is
 * the bad one: the *next* person at this screen reading the previous person's data. A reload cannot
 * get that list wrong.
 *
 * ## A refused sign-out says so and stays open
 *
 * If the request fails the panel keeps its message and does not reload: reloading anyway would leave
 * the cookie in place and show a signed-in shell, which reads exactly like a successful sign-out and
 * is the opposite of one.
 */
import { Show, createUniqueId } from "solid-js";
import type { JSX } from "@solidjs/web";
import { Banner, Button } from "@kui/kernel";

export interface AccountMenuProps {
  /** The principal's name, as `/auth/me` gave it. */
  readonly name: string;
  /** What the deployment calls its sign-in — shown as a plain fact, not as a control. */
  readonly authType?: string | undefined;
  readonly onSignOut: () => void;
  /** True while the request is out. The button says so and refuses a second click. */
  readonly busy?: boolean | undefined;
  /** Why the last attempt did not work, when it did not. */
  readonly failure?: string | undefined;
}

export function AccountMenu(props: AccountMenuProps): JSX.Element {
  const titleId = createUniqueId();

  return (
    <div
      class="kui-account"
      /* A menu of one action is still a group of controls that appeared over the page, and the
         label is what tells a screen-reader user whose account it belongs to. `menu` would be a
         lie: nothing here is navigable with the arrow keys the role promises. */
      role="group"
      aria-labelledby={titleId}
      data-testid="account-menu"
    >
      <p class="kui-account__name" id={titleId} data-testid="account-name">
        {props.name}
      </p>
      <Show when={props.authType}>
        {(type) => <p class="kui-account__auth">Signed in through {type()}</p>}
      </Show>

      <Show when={props.failure}>{(message) => <Banner tone="danger" message={message()} />}</Show>

      <Button
        variant="secondary"
        icon="lock"
        busy={props.busy === true}
        onClick={() => props.onSignOut()}
        class="kui-account__signout"
      >
        Sign out
      </Button>
    </div>
  );
}

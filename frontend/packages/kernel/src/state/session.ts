/**
 * Who is signed in, what they may do, and whether this deployment asks anybody to sign in at all.
 *
 * ## Three separate questions, deliberately not merged
 *
 * - **Is authentication configured?** Answered by `GET /api/v1/auth/settings`, from the gateway's own
 *   configuration rather than from the identity service, so a sign-in screen can still be drawn
 *   during exactly the outage an operator needs to see.
 * - **Who am I?** Answered by `GET /api/v1/auth/me`, which is also the only place a CSRF token ever
 *   comes from.
 * - **What may I do?** The permissions on that same answer.
 *
 * ## Authentication disabled is the default, and stays invisible
 *
 * The demonstration environment, the quickstart, and every deployment until an identity provider is
 * configured run with `authType: "disabled"`, and none of them may ever meet a login screen: that is
 * the product's front door and a locked door there is worse than any other bug on the screen. So
 * {@link mustSignIn} demands **both** that the settings have arrived and do not say `disabled`, and
 * that the principal is anonymous. While the settings call is in flight, and if it never answers at
 * all, the answer is "no sign-in" — the direction this failure has to fall.
 *
 * ## Permissions disable controls rather than letting them fail at the server
 *
 * A control the caller may not use is disabled *and explains itself*. Firing the request and showing
 * the 403 tells the operator only that something went wrong; a disabled control carrying "you need
 * the topic:write permission on this cluster" tells them what to ask for. {@link permits} is what
 * every such control asks.
 */
import { createSignal, type Accessor } from "solid-js";
import type { components } from "@kui/api";

import { grantsAllow, grantsAllowAny, grantsFromWire } from "../data/permissions/store.js";

/**
 * What `/auth/me` answers with, as this store is willing to receive it.
 *
 * Every field name and every scalar type is still the generated schema's — `SchemaAuthMe` below is
 * what makes a server-side rename fail this file's compilation, which is the whole point of
 * generating the types at all. Two allowances are made for how the value arrives:
 *
 * - `permissions` is `| undefined` as well as optional, because `exactOptionalPropertyTypes`
 *   distinguishes "absent" from "present and undefined" and the client hands back the second;
 * - the lists are {@link ArrayLike} rather than arrays, because `openapi-fetch`'s response type maps
 *   `Readonly<>` over the body and a readonly-mapped array is an index-signature object, not an
 *   array. `Array.from` turns either into the one shape the rest of the browser holds.
 */
type SchemaAuthMe = components["schemas"]["AuthMeResponse"];

export type AuthMeResponse = {
  readonly authType: SchemaAuthMe["authType"];
  readonly csrfToken: SchemaAuthMe["csrfToken"];
  readonly permissions?: ArrayLike<WirePermission> | undefined;
  readonly principal: WirePrincipal;
};

type WirePermission = {
  readonly resource: PermissionDto["resource"];
  readonly actions?: ArrayLike<string> | undefined;
  readonly clusters?: ArrayLike<string> | undefined;
  readonly value?: string | undefined;
};

type WirePrincipal = {
  readonly kind: PrincipalDto["kind"];
  readonly name: PrincipalDto["name"];
  readonly roles?: ArrayLike<string> | undefined;
};
type AuthSettingsDto = components["schemas"]["AuthSettingsDto"];
type PermissionDto = components["schemas"]["PermissionDto"];
type PrincipalDto = components["schemas"]["PrincipalDto"];

/**
 * The wire value `AuthType.Disabled` serialises to, and therefore the one string that means "this
 * deployment asks nobody to sign in".
 */
export const AuthDisabled = "disabled";

/**
 * The principal kind that stands for "authentication is off".
 *
 * `/auth/me` always answers with a principal; the anonymous one is how "nobody is signed in" is
 * spelled. That is why the account control keys off the *kind* rather than off the presence of a
 * principal — a "Sign out" button that ends a session which never existed is a lie about what the
 * product is doing.
 */
export const AnonymousKind = "anonymous";

/** Everything the session knows once `/auth/me` has answered. */
export type SessionIdentity = {
  readonly principal: PrincipalDto;
  readonly authType: string;
  readonly permissions: readonly PermissionDto[];
  /** Empty when the deployment issues none. Never sent as an empty header. */
  readonly csrfToken: string | undefined;
};

export type SessionState = {
  /** `undefined` until `/auth/me` has answered. Not "nobody": "not asked yet". */
  readonly identity: Accessor<SessionIdentity | undefined>;
  /** `undefined` until `/auth/settings` has answered. */
  readonly settings: Accessor<AuthSettingsDto | undefined>;
  /** Whether to put the sign-in screen in front of everything. */
  readonly mustSignIn: Accessor<boolean>;
  /** Whether somebody is signed in, as opposed to the anonymous stand-in. */
  readonly signedIn: Accessor<boolean>;
  /**
   * Whether this principal holds a grant for an action on a resource, on this cluster.
   *
   * `name` is the resource's own name — a topic name, a group id — and passing it is what makes the
   * answer match the server's. A grant carries a *pattern*, so somebody granted `payments\..*` may
   * delete `payments.orders` and may not delete `orders`; asked without a name this can only answer
   * the weaker question "do they hold this action on anything of this kind", which is the right
   * answer for a list heading or a create button and the wrong one for a row's delete button.
   *
   * Both questions are evaluated by `../data/permissions`, which is also what the standalone
   * permission store uses, so the two cannot drift apart.
   */
  readonly permits: (
    resource: string,
    action: string,
    cluster?: string | undefined,
    name?: string | undefined,
  ) => boolean;
  /** Records what `/auth/me` answered, and hands the CSRF token to the API client. */
  readonly accept: (response: AuthMeResponse) => void;
  /** Records what `/auth/settings` answered, or that it did not. */
  readonly acceptSettings: (settings: AuthSettingsDto | undefined) => void;
  /**
   * Forgets the identity, the permissions and the token after the server said the session lapsed.
   *
   * Every write control disappears with it, so none survives the moment of signing out even for the
   * length of a reload.
   */
  readonly markExpired: () => void;
};

export type SessionOptions = {
  /**
   * Where the CSRF token goes once there is one.
   *
   * `@kui/api`'s `CsrfTokens` gate: a mutation issued before start-up waits for this call rather than
   * being sent without a header, and is released by it. Handing it in rather than importing the
   * application's own keeps a test able to watch one session without a singleton leaking into the
   * next test.
   */
  readonly settleCsrf: (token: string | undefined) => void;
  /** Closes the token gate again, so the next mutation waits for the refreshed session. */
  readonly invalidateCsrf: () => void;
};

export function createSession(options: SessionOptions): SessionState {
  const [identity, setIdentity] = createSignal<SessionIdentity | undefined>(undefined);
  const [settings, setSettings] = createSignal<AuthSettingsDto | undefined>(undefined);

  const signedIn = (): boolean => {
    const current = identity();
    return current !== undefined && current.principal.kind !== AnonymousKind;
  };

  return {
    identity,
    settings,
    signedIn,

    mustSignIn: () => {
      const configured = settings();
      // Both halves, and each guards against a different serious failure. See the module comment.
      if (configured === undefined || configured.authType === AuthDisabled) return false;
      const current = identity();
      // `undefined` means `/auth/me` has not answered yet, which is also not a reason to demand a
      // sign-in: a signed-in user reloading the page would be asked to sign in again, in a loop.
      return current !== undefined && current.principal.kind === AnonymousKind;
    },

    permits: (resource, action, cluster, name) => {
      const current = identity();
      if (current === undefined) return false;
      const grants = grantsFromWire(current.permissions);
      // The empty string stands in for "no cluster named": no cluster id is empty, so only a grant
      // scoped to every cluster can match, which is what a question about KUI itself deserves.
      const scope = cluster ?? "";
      const permission = { resource, action } as Parameters<typeof grantsAllowAny>[2];
      return name === undefined
        ? grantsAllowAny(grants, scope, permission)
        : grantsAllow(grants, scope, permission, name);
    },

    accept: (response) => {
      // An empty token is no token. Sending `X-Csrf-Token: ` is rejected exactly as a missing header
      // is, but with a far more confusing message in the gateway's log.
      const token = response.csrfToken.length > 0 ? response.csrfToken : undefined;
      setIdentity({
        principal: {
          kind: response.principal.kind,
          name: response.principal.name,
          ...(response.principal.roles === undefined ? {} : { roles: Array.from(response.principal.roles) }),
        },
        authType: response.authType,
        permissions: Array.from(response.permissions ?? []).map((grant) => ({
          resource: grant.resource,
          actions: Array.from(grant.actions ?? []),
          clusters: Array.from(grant.clusters ?? []),
          ...(grant.value === undefined ? {} : { value: grant.value }),
        })),
        csrfToken: token,
      });
      options.settleCsrf(token);
    },

    acceptSettings: (next) => {
      // A deployment that has configured no authentication must never be shown a locked door because
      // one request was slow, so a failed settings call is recorded as "disabled" rather than left
      // unanswered for ever.
      setSettings(next ?? { authType: AuthDisabled, rbacEnabled: false });
    },

    markExpired: () => {
      setIdentity(undefined);
      options.invalidateCsrf();
    },
  };
}

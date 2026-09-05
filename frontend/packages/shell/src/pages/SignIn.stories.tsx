import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { SignIn } from "./SignIn.jsx";

/**
 * The sign-in screen.
 *
 * It is shown on exactly one condition: the deployment says it uses a sign-in **and** the current
 * principal is anonymous. Both halves matter — without the first, a deployment that has deliberately
 * configured no authentication (the default, and what the quickstart runs) would meet a locked gate
 * across its own front door; without the second, a signed-in user is asked to sign in on every
 * reload.
 *
 * No story passes an `api`, so every control renders and does nothing. That is the honest shape for
 * a screen with no server behind it — better than standing up a gateway to look at a layout.
 */
const meta: Meta<typeof SignIn> = {
  title: "Screens/Sign in",
  component: SignIn,
  parameters: { layout: "fullscreen" },
};

export default meta;
type Story = StoryObj<typeof meta>;

export const Form: Story = {
  args: { authType: "form", onSignedIn: () => undefined },
};

/**
 * A provider. One button and no password field, because with OIDC KUI never sees a password — and a
 * field for one would be an invitation to type a password into the wrong application.
 */
export const Provider: Story = {
  args: { authType: "oidc", providerLabel: "Okta", onSignedIn: () => undefined },
};

/** A provider the deployment did not name. The sentence still reads. */
export const UnnamedProvider: Story = {
  args: { authType: "oidc", onSignedIn: () => undefined },
};

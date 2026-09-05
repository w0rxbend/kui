import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { AccountMenu } from "./AccountMenu.jsx";

/**
 * The account panel, and the sign-out that had no call site for four milestones.
 *
 * The state worth looking at is `SignOutRefused`. A sign-out that fails and *looks* like it worked
 * is the worst outcome this panel can produce — the next person at the workstation gets the previous
 * person's session — so the panel stays open, keeps its message, and does not reload the page.
 */
const meta: Meta<typeof AccountMenu> = {
  title: "Chrome/AccountMenu",
  component: AccountMenu,
  decorators: [
    (Story) => (
      <div style={{ padding: "24px", background: "var(--kui-color-surface)" }}>
        {Story() as never}
      </div>
    ),
  ],
};

export default meta;
type Story = StoryObj<typeof AccountMenu>;

/** Somebody signed in through the form provider. */
export const Default: Story = {
  render: () => (
    <AccountMenu name="olena.petrenko" authType="form" onSignOut={() => undefined} />
  ),
};

/**
 * A principal name long enough to be a service account or an email address from a provider.
 *
 * It wraps rather than being cut off: a truncated identity on the one panel whose job is to say who
 * you are is the panel failing at its only job.
 */
export const LongName: Story = {
  render: () => (
    <AccountMenu
      name="platform-operations-service-account@identity.example.internal"
      authType="oidc"
      onSignOut={() => undefined}
    />
  ),
};

/** The request is out. The button says so and refuses a second click. */
export const SigningOut: Story = {
  render: () => <AccountMenu name="olena.petrenko" authType="form" busy onSignOut={() => undefined} />,
};

/** The gateway refused. The panel says the session is still live rather than reloading into a lie. */
export const SignOutRefused: Story = {
  render: () => (
    <AccountMenu
      name="olena.petrenko"
      authType="form"
      failure="Signing out did not work. You are still signed in."
      onSignOut={() => undefined}
    />
  ),
};

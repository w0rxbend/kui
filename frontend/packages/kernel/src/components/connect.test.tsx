import { describe, expect, test } from "vitest";
import { flush } from "solid-js";
import { render } from "@solidjs/web";
import { TaskBar, describeTasks } from "./TaskBar.jsx";
import { connectorChip } from "./ConnectorCard.jsx";

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

describe("a connector's task bar", () => {
  test("a connector with no tasks does not look like one whose tasks all failed", async () => {
    /*
     * The distinction the component exists to keep. "This connector has no tasks" and "every one of
     * this connector's tasks has failed" are different problems with different causes, and a bar
     * that drew them alike would send somebody to look at the wrong one — at the connector's
     * configuration instead of at the worker, or the other way round.
     */
    const none = mount(() => <TaskBar tasks={[]} />);
    await flush();
    expect(none.host.querySelector(".kui-taskbar__track--empty")).not.toBeNull();
    expect(none.host.querySelectorAll(".kui-taskbar__task--failed")).toHaveLength(0);
    none.dispose();

    const failed = mount(() => <TaskBar tasks={["failed", "failed"]} />);
    await flush();
    expect(failed.host.querySelector(".kui-taskbar__track--empty")).toBeNull();
    expect(failed.host.querySelectorAll(".kui-taskbar__task--failed")).toHaveLength(2);
    failed.dispose();
  });

  test("the bar carries its meaning in words as well as in colour", async () => {
    // A row of coloured rectangles carries nothing at all to somebody who cannot see it, and nothing
    // useful to somebody who cannot tell red from green.
    const { host, dispose } = mount(() => <TaskBar tasks={["running", "running", "failed"]} />);
    await flush();
    expect(host.textContent).toContain("3 tasks");
    expect(host.textContent).toContain("2 running");
    expect(host.textContent).toContain("1 failed");
    dispose();
  });

  test("the sentence names only the states that are present", () => {
    expect(describeTasks(["running", "running"])).toBe("2 tasks: 2 running.");
    expect(describeTasks(["running"])).toBe("1 task: 1 running.");
    expect(describeTasks([])).toBe("This connector has no tasks.");
  });
});

describe("a connector's state chip", () => {
  test("an unreported state is never drawn as running", () => {
    // Guessing healthy is this product telling somebody their pipeline is fine on no evidence, which
    // is the single worst thing a monitoring screen can do.
    const unknown = connectorChip("UNKNOWN");
    expect(unknown.label).not.toMatch(/running/i);
    expect(unknown.tone).not.toBe("success");
  });

  test("unassigned is not a failure", () => {
    /*
     * It means the connector exists and Connect has not handed it to a worker yet. That resolves on
     * its own and is not something to page anybody about — drawing it in danger colours produces
     * exactly that page.
     */
    expect(connectorChip("UNASSIGNED").tone).not.toBe("danger");
    expect(connectorChip("FAILED").tone).toBe("danger");
  });
});

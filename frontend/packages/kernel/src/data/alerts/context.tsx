/** The single alert store shared by the frame, dashboard and lazy alerts feature. */
import { createContext, useContext } from "solid-js";
import type { JSX } from "@solidjs/web";

import type { Alerts } from "./store.js";

const AlertsContext = createContext<Alerts>();

export function AlertsProvider(props: {
  readonly value: Alerts;
  readonly children: JSX.Element;
}): JSX.Element {
  return <AlertsContext value={props.value}>{props.children}</AlertsContext>;
}

/** Reads the store installed once by the shell. Tests and stories provide their own store. */
export function useAlerts(): Alerts {
  const value = useContext(AlertsContext);
  if (value === undefined) {
    throw new Error(
      "useAlerts() was called outside <AlertsProvider>. The shell provides the shared alert " +
        "store around every route; a story or test must provide its own.",
    );
  }
  return value;
}

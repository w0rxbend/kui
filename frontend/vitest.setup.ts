/**
 * What every test file gets for free.
 *
 * Only one thing: jsdom does not implement `matchMedia`, and several components ask it whether the
 * user has requested reduced motion. Without a stub the first such call throws, and the failure
 * looks like a rendering bug rather than like a missing browser API.
 */
if (typeof window !== "undefined" && window.matchMedia === undefined) {
  Object.defineProperty(window, "matchMedia", {
    writable: true,
    value: (query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addEventListener: () => {},
      removeEventListener: () => {},
      addListener: () => {},
      removeListener: () => {},
      dispatchEvent: () => false,
    }),
  });
}

/**
 * The clusters feature — a placeholder that exists to prove the loading seam, not the screen.
 *
 * The real cluster list, dashboard and broker screens are SOL-027 and SOL-028. What matters
 * today is that this module is reached *only* through `lazy(() => import("@kui/feature-clusters"))`
 * in the shell, so Vite gives it a chunk of its own and `dist/.vite/manifest.json` records it
 * under the entry's `dynamicImports` rather than its static `imports` (ADR-012, ADR-048 §4).
 */
export default function Clusters() {
  return (
    <p class="kui-proof__loaded">
      The clusters feature module was downloaded on demand, after the shell had already rendered.
    </p>
  );
}

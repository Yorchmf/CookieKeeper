import path from "node:path";
import { defineConfig } from "vitest/config";

// Mirrors the widget package's vitest setup (happy-dom, test/ directory).
// No @vitejs/plugin-react needed: Vite's esbuild transforms TSX with the
// automatic JSX runtime, which is all the test suite requires.
export default defineConfig({
  test: {
    environment: "happy-dom",
    include: ["test/**/*.test.{ts,tsx}"],
  },
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "src"),
    },
  },
});

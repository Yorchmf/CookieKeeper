import { defineConfig } from 'vitest/config';

/**
 * Builds the widget as a single self-executing file: dist/v1.js.
 * Hard constraints (see docs/ARCHITECTURE.md §4.3):
 *   - IIFE, no code splitting, no runtime dependencies
 *   - target es2018, minified
 *   - <=20KB gzipped (enforced by `pnpm size` / CI)
 */
export default defineConfig({
  build: {
    target: 'es2018',
    minify: 'esbuild',
    sourcemap: false,
    lib: {
      entry: 'src/main.ts',
      name: 'ComplyrWidget',
      formats: ['iife'],
      fileName: () => 'v1.js',
    },
    rollupOptions: {
      output: {
        // Single file — never split the widget bundle.
        inlineDynamicImports: true,
      },
    },
  },
  test: {
    environment: 'happy-dom',
    include: ['test/**/*.test.ts'],
  },
});

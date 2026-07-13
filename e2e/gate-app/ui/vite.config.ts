import { defineConfig } from 'vite';
import { resolve } from 'node:path';

// The gate frontend is served from the app jar under jdesk://app/ (ClasspathAssetSource),
// exactly as a packaged app serves it. Relative base so asset URLs resolve under the custom
// scheme; ES module workers so Monaco's workers load same-origin (the WKWebView question the
// gate exists to answer). No blob: worker shim, no AMD loader, no CDN.
export default defineConfig({
  base: './',
  worker: {
    format: 'es',
  },
  build: {
    outDir: resolve(__dirname, '../src/main/resources/web'),
    emptyOutDir: true,
    target: 'es2022',
    rollupOptions: {
      input: {
        index: resolve(__dirname, 'index.html'),
        semantic: resolve(__dirname, 'semantic.html'),
      },
    },
  },
});

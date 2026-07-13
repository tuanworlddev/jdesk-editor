import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { resolve } from 'node:path';

// Production bundle served from the app jar over jdesk://app/ (packaged-identical). Relative base
// so assets resolve under the custom scheme; ES-module workers — the strategy the Phase-0 gate
// proved on WKWebView (docs/MONACO_WORKER_STRATEGY.md). No blob workers, no CDN, no eval.
export default defineConfig({
  base: './',
  plugins: [react()],
  worker: { format: 'es' },
  build: {
    outDir: resolve(__dirname, 'dist'),
    emptyOutDir: true,
    target: 'es2022',
    chunkSizeWarningLimit: 4096,
  },
  server: { port: 5173, strictPort: true },
});

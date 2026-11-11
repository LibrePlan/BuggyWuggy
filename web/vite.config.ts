/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// During development the UI runs on Vite's dev server and forwards API calls to the
// backend (BUGGYWUGGY_API_URL, default http://localhost:8080). The production build is
// written into the Maven module's classes, so the server jar serves it from /static.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: process.env.BUGGYWUGGY_API_URL ?? 'http://localhost:8080',
        changeOrigin: false,
      },
    },
  },
  build: {
    outDir: 'target/classes/static',
    emptyOutDir: true,
    sourcemap: true,
    // One bundle for now (~240 kB gzipped); split per route once the UI grows.
    chunkSizeWarningLimit: 1024,
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
});

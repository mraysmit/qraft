/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { fileURLToPath } from 'node:url';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// The build writes below target/, which Maven stages as generated resources, so the files land below
// META-INF/qraft/ui/ in target/qraft.jar (design section 12.4.1). Nothing it writes is committed.
const outDir = fileURLToPath(new URL('../../../target/generated-resources/ui/META-INF/qraft/ui', import.meta.url));

// A local server for `npm run dev`: the interface calls the API on the listener that serves it.
const server = 'http://127.0.0.1:8080';

export default defineConfig({
  plugins: [react()],
  // Links relative to the page: the server's <base> resolves them below whatever path server.ui.path names.
  base: './',
  build: {
    outDir,
    emptyOutDir: true,
    assetsDir: 'assets',
    manifest: 'asset-manifest.json',
    sourcemap: false,
    // The application and its libraries in chunks of their own, so none passes Vite's 500 kB warning (UI-3 task
    // list, decision 4): the libraries' chunks keep their names, and their hashes, while only the application changes.
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) return undefined;
          if (/node_modules[\\/](react|react-dom|scheduler)[\\/]/.test(id)) return 'react';
          if (/node_modules[\\/]antd[\\/]/.test(id)) return 'antd';
          if (/node_modules[\\/](@ant-design|@rc-component|rc-[^\\/]+)[\\/]/.test(id)) return 'antd-parts';
          if (/node_modules[\\/]zod[\\/]/.test(id)) return 'zod';
          return 'vendor';
        },
      },
    },
  },
  server: {
    proxy: { '/v1': server, '/raft': server, '/health': server },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/setupTests.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
  },
});

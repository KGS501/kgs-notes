import { defineConfig } from 'vite'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  base: './',
  build: {
    outDir: fileURLToPath(new URL('../src/main/assets/kgs-editor', import.meta.url)),
    emptyOutDir: true,
    sourcemap: true,
  },
})

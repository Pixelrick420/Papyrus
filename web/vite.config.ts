import path from 'node:path';
import { sveltekit } from '@sveltejs/kit/vite';
import tailwindcss from '@tailwindcss/vite';
import { defineConfig } from 'vite';
import adapter from '@sveltejs/adapter-static';

export default defineConfig({
  plugins: [
    tailwindcss(),
    sveltekit({
      adapter: adapter({
        pages: 'build',
        assets: 'build',
        fallback: 'index.html',
        precompress: false,
        strict: true
      }),
      paths: {
        base: ''
      },
      prerender: {
        handleHttpError: 'warn',
        handleMissingId: 'warn'
      }
    })
  ],
  resolve: {
    alias: {
      '$lib': path.resolve('src/lib')
    }
  },
  build: {
    minify: 'esbuild',
    cssMinify: true,
    rollupOptions: {
      output: {
        manualChunks: undefined
      }
    }
  },
  optimizeDeps: {
    include: ['@lucide/svelte']
  }
});

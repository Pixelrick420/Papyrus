import path from 'node:path';
import { sveltekit } from '@sveltejs/kit/vite';
import tailwindcss from '@tailwindcss/vite';
import { defineConfig } from 'vite';
import adapter from '@sveltejs/adapter-static';

const siteUrl = (process.env.PUBLIC_SITE_URL ?? 'https://papyrus-viewer.vercel.app/').replace(
  /\/+$/,
  ''
);
process.env.PUBLIC_SITE_URL = siteUrl;

export default defineConfig({
  define: {
    'import.meta.env.PUBLIC_SITE_URL': JSON.stringify(siteUrl),
  },
  plugins: [
    tailwindcss(),
    sveltekit({
      adapter: adapter(),
      paths: {
        base: '',
      },
      prerender: {
        handleHttpError: 'fail',
        handleMissingId: 'fail',
      },
    }),
  ],
  resolve: {
    alias: {
      $lib: path.resolve('src/lib'),
    },
  },
  build: {
    minify: 'esbuild',
    cssMinify: true,
  },
  optimizeDeps: {
    include: ['@lucide/svelte'],
  },
});

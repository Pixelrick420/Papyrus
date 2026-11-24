import { defineEnvVars } from '@sveltejs/kit/env';

export const variables = defineEnvVars({
  PUBLIC_SITE_URL: {
    public: true,
    static: true,
    schema: (value) => value ?? 'https://papyrus-viewer.vercel.app/',
  },
});

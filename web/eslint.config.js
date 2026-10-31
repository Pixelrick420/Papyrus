import js from '@eslint/js';
import svelte from 'eslint-plugin-svelte';
import typescriptEslint from 'typescript-eslint';
import prettierConfig from 'eslint-config-prettier';
import globals from 'globals';

export default typescriptEslint.config(
  {
    ignores: ['build/', 'node_modules/', '.svelte-kit/', '.vercel/', '*.config.js', '*.config.ts'],
  },
  js.configs.recommended,
  ...typescriptEslint.configs.recommended,
  ...svelte.configs['flat/recommended'],
  prettierConfig,
  ...svelte.configs['flat/prettier'],
  {
    languageOptions: {
      globals: {
        ...globals.browser,
        ...globals.es2022,
        ...globals.node,
      },
    },
  },
  {
    files: ['**/*.svelte'],
    languageOptions: {
      parserOptions: {
        parser: typescriptEslint.parser,
      },
    },
  },
  {
    rules: {
      'svelte/no-at-html-tags': 'off',
      'svelte/no-navigation-without-resolve': 'off',
      'svelte/valid-compile': ['error', { ignoreWarnings: true }],
      '@typescript-eslint/no-unused-vars': ['warn', { argsIgnorePattern: '^_' }],
      '@typescript-eslint/no-explicit-any': 'warn',
    },
  }
);

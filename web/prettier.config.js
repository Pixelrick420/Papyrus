/** @type {import('prettier').Config} */
const config = {
  plugins: ['prettier-plugin-svelte', 'prettier-plugin-tailwindcss'],
  printWidth: 100,
  singleQuote: true,
  tabWidth: 2,
  trailingComma: 'es5',
  semi: true,
  bracketSpacing: true,
  arrowParens: 'avoid',
  endOfLine: 'lf',
  overrides: [
    {
      files: '*.svelte',
      options: {
        parser: 'svelte'
      }
    }
  ]
};

export default config;
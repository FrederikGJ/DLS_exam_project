// ESLint flat config for the static frontend (dev plan DP-23). Run from this directory:
//     npm ci && npx eslint js/
// The frontend is plain browser JavaScript served by nginx - no bundler, no transpiler - so the rules are the
// ESLint "recommended" set plus browser globals. Two file groups:
//   * js/**/*.js   ES modules (index.html loads js/app.js with type="module"; pages import from app.js/api.js)
//   * js/config.js a classic script that runs before the module and defines window.AIRPORT_CONFIG; in
//                  Kubernetes the same file is replaced by a ConfigMap, so it must stay a plain script.
import js from '@eslint/js';
import globals from 'globals';

export default [
  { ignores: ['node_modules/'] },
  js.configs.recommended,
  {
    files: ['js/**/*.js'],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: 'module',
      globals: { ...globals.browser },
    },
    rules: {
      // An unused parameter or caught error is allowed when it is named with a leading underscore - the code
      // base's convention is `catch (_) { ... }` for deliberately ignored errors; everything else unused is an error.
      'no-unused-vars': ['error', { args: 'after-used', argsIgnorePattern: '^_', caughtErrorsIgnorePattern: '^_' }],
    },
  },
  {
    files: ['js/config.js'],
    languageOptions: { sourceType: 'script' },
  },
];

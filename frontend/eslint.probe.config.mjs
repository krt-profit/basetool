/**
 * Lint rules for the probe script extracted out of TouchClassLayoutE2eTest.
 *
 * A small set of correctness rules, separate from `eslint.config.mjs`; the script runs inside
 * `page.evaluate`, so undefined browser globals are not checked.
 */

export default [
  {
    files: ['**/*.js'],
    languageOptions: {
      ecmaVersion: 2023,
      sourceType: 'module',
    },
    rules: {
      'no-use-before-define': ['error', { variables: true, functions: false, classes: false }],
      'no-dupe-keys': 'error',
      'no-dupe-args': 'error',
      'no-unreachable': 'error',
      'no-const-assign': 'error',
      'no-self-compare': 'error',
      'no-constant-condition': 'error',
      'no-sparse-arrays': 'error',
      'valid-typeof': 'error',
      'no-undef': 'off',
    },
  },
];

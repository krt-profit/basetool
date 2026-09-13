/**
 * Lint rules for the probe script extracted out of TouchClassLayoutE2eTest.
 *
 * Deliberately small, and separate from `eslint.config.mjs`, for the same reason
 * `.stylelintrc.templates.json` is separate: the value is in a handful of rules that catch defects
 * the Java compiler cannot see, not in style agreement with the browser modules. The script is
 * written to run inside `page.evaluate`, so it uses browser globals this config does not enumerate
 * and it is not module code.
 *
 * `no-use-before-define` is the one that earns its place: the dense-floor guard referenced
 * `badControls` a hundred lines above its `const`, which is a temporal-dead-zone ReferenceError on
 * every page at every device class, and the only thing that noticed was a CI shard running the
 * whole suite.
 */

export default [
  {
    files: ['**/*.js'],
    languageOptions: {
      ecmaVersion: 2023,
      sourceType: 'module',
    },
    rules: {
      // The defect this file exists for.
      'no-use-before-define': ['error', { variables: true, functions: false, classes: false }],
      // Cheap correctness rules that a Java string hides just as well.
      'no-dupe-keys': 'error',
      'no-dupe-args': 'error',
      'no-unreachable': 'error',
      'no-const-assign': 'error',
      'no-self-compare': 'error',
      'no-constant-condition': 'error',
      'no-sparse-arrays': 'error',
      'valid-typeof': 'error',
      // Browser globals are not declared here, and the probe never runs through this file.
      'no-undef': 'off',
    },
  },
];

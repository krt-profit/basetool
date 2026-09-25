import js from "@eslint/js";
import globals from "globals";
import nounsanitized from "eslint-plugin-no-unsanitized";

const RAW_FETCH_WRITE = [
  "CallExpression[callee.name='fetch'] > ObjectExpression > Property[key.name='method']:not([value.value=/^get$/i])",
  "CallExpression[callee.object.name='window'][callee.property.name='fetch'] > ObjectExpression > Property[key.name='method']:not([value.value=/^get$/i])",
].map((selector) => ({
  selector,
  message:
    "Raw fetch write: use krtFetch.write / krtFetch.submitForm (REQ-FE-002) so CSRF, the 403 retry, re-auth and the double-submit guard apply.",
}));

export default [
  {
    ignores: [
      "src/main/resources/static/js/vendor/**",
      "build/**",
      "node_modules/**",
    ],
  },
  js.configs.recommended,
  {
    files: ["src/main/resources/static/js/**/*.js"],
    languageOptions: {
      ecmaVersion: 2023,
      sourceType: "script",
      globals: {
        ...globals.browser,
        escapeHtml: "readonly",
        escapeAttr: "readonly",
      },
    },
    rules: {
      "no-var": "error",
      "no-empty": ["error", { allowEmptyCatch: true }],
      "prefer-const": "error",
      "object-shorthand": ["error", "always"],
      eqeqeq: ["error", "smart"],
      "no-unused-vars": [
        "warn",
        {
          argsIgnorePattern: "^_",
          caughtErrorsIgnorePattern: "^_",
          varsIgnorePattern: "^_",
        },
      ],
      "no-undef": "error",
      "no-restricted-syntax": ["error", ...RAW_FETCH_WRITE],
    },
  },
  {
    files: ["src/main/resources/static/js/**/*.js"],
    plugins: { "no-unsanitized": nounsanitized },
    rules: {
      "no-unsanitized/method": [
        "error",
        { escape: { taggedTemplates: [], methods: ["escapeHtml", "escapeAttr"] } },
      ],
      "no-unsanitized/property": [
        "error",
        { escape: { taggedTemplates: [], methods: ["escapeHtml", "escapeAttr"] } },
      ],
    },
  },
  {
    files: [
      "src/main/resources/static/js/krt-fetch.js",
      "src/main/resources/static/js/krt-client-error.js",
    ],
    rules: {
      "no-restricted-syntax": "off",
    },
  },
  {
    files: ["scripts/**/*.mjs"],
    languageOptions: {
      ecmaVersion: 2023,
      sourceType: "module",
      globals: { ...globals.node },
    },
    rules: {
      "no-var": "error",
      "no-empty": ["error", { allowEmptyCatch: true }],
      "prefer-const": "error",
      "object-shorthand": ["error", "always"],
      eqeqeq: ["error", "smart"],
      "no-unused-vars": [
        "warn",
        {
          argsIgnorePattern: "^_",
          caughtErrorsIgnorePattern: "^_",
          varsIgnorePattern: "^_",
        },
      ],
      "no-undef": "error",
    },
  },
];

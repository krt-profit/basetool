import js from "@eslint/js";
import globals from "globals";
import nounsanitized from "eslint-plugin-no-unsanitized";

const ECMA_VERSION = 2025;

const TRANSPORT_FILES = [
  "src/main/resources/static/js/krt-fetch.js",
  "src/main/resources/static/js/krt-client-error.js",
];

const RAW_FETCH_MESSAGE =
  "Raw fetch: read through krtFetch.get / krtFetch.getJson (REQ-FE-031) and write through krtFetch.write / krtFetch.submitForm (REQ-FE-002), so re-authentication, the terms gate and the redirect refusal apply.";

const XHR_MESSAGE =
  "XMLHttpRequest is banned: every request goes through krtFetch (REQ-FE-002, REQ-FE-031).";

const ABOVE_BASELINE_MESSAGE =
  "Above the browser baseline (Chrome 122, Firefox 131, Safari/iOS 18.4; ADR-0239, REQ-FE-018).";

const ABOVE_BASELINE_GLOBALS = [{ name: "Float16Array", message: ABOVE_BASELINE_MESSAGE }];

const XHR_GLOBALS = [{ name: "XMLHttpRequest", message: XHR_MESSAGE }];

const FETCH_GLOBALS = [{ name: "fetch", message: RAW_FETCH_MESSAGE }];

const ABOVE_BASELINE_PROPERTIES = [
  { object: "Promise", property: "try", message: ABOVE_BASELINE_MESSAGE },
  { object: "RegExp", property: "escape", message: ABOVE_BASELINE_MESSAGE },
];

const GLOBAL_OBJECTS = ["window", "globalThis", "self"];

const XHR_PROPERTIES = GLOBAL_OBJECTS.map((object) => ({
  object,
  property: "XMLHttpRequest",
  message: XHR_MESSAGE,
}));

const FETCH_PROPERTIES = GLOBAL_OBJECTS.map((object) => ({
  object,
  property: "fetch",
  message: RAW_FETCH_MESSAGE,
}));

const MODERN_SYNTAX_RULES = {
  "prefer-template": "error",
  "prefer-arrow-callback": "error",
  "prefer-object-has-own": "error",
  radix: "error",
  "logical-assignment-operators": ["error", "always"],
};

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
      ecmaVersion: ECMA_VERSION,
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
      ...MODERN_SYNTAX_RULES,
      "no-restricted-globals": [
        "error",
        ...ABOVE_BASELINE_GLOBALS,
        ...XHR_GLOBALS,
        ...FETCH_GLOBALS,
      ],
      "no-restricted-properties": [
        "error",
        ...ABOVE_BASELINE_PROPERTIES,
        ...XHR_PROPERTIES,
        ...FETCH_PROPERTIES,
      ],
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
    files: TRANSPORT_FILES,
    rules: {
      "no-restricted-globals": ["error", ...ABOVE_BASELINE_GLOBALS, ...XHR_GLOBALS],
      "no-restricted-properties": ["error", ...ABOVE_BASELINE_PROPERTIES, ...XHR_PROPERTIES],
    },
  },
  {
    files: ["scripts/**/*.mjs"],
    languageOptions: {
      ecmaVersion: ECMA_VERSION,
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
      ...MODERN_SYNTAX_RULES,
    },
  },
];

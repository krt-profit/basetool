import js from "@eslint/js";
import globals from "globals";
import nounsanitized from "eslint-plugin-no-unsanitized";

// REQ-FE-002: every write goes through krtFetch / krtCsrf. A `fetch` call whose init object names
// a non-GET `method` is a hand-rolled write — it skips the shared CSRF header, the bare-403
// refresh-and-retry, the X-Reauthenticate redirect and the double-submit guard. The selector
// matches both `fetch(...)` and `window.fetch(...)`; a method passed through a variable is caught
// too, because only a literal "GET" (any case) is exempt. The two files that ARE the transport
// (krt-fetch.js, krt-client-error.js) are exempted below.
const RAW_FETCH_WRITE = [
  "CallExpression[callee.name='fetch'] > ObjectExpression > Property[key.name='method']:not([value.value=/^get$/i])",
  "CallExpression[callee.object.name='window'][callee.property.name='fetch'] > ObjectExpression > Property[key.name='method']:not([value.value=/^get$/i])",
].map((selector) => ({
  selector,
  message:
    "Raw fetch write: use krtFetch.write / krtFetch.submitForm (REQ-FE-002) so CSRF, the 403 retry, re-auth and the double-submit guard apply.",
}));

// ESLint flat config for the frontend's hand-written browser scripts under
// static/js. The static/js/vendor/ directory (minified third-party bundles,
// if any) is excluded — such files are not ours to lint and would drown the
// report.
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
      // The scripts are loaded as classic <script> tags, not ES modules.
      sourceType: "script",
      globals: {
        ...globals.browser,
        // Cross-file helpers declared at script scope in escape-html.js and
        // consumed by other scripts loaded as separate <script> tags.
        escapeHtml: "readonly",
        escapeAttr: "readonly",
      },
    },
    rules: {
      "no-var": "error",
      // FE-MOD-03: a binding that is never reassigned is `const`, and an object literal spells a
      // same-named property / function-valued member in its short form. Both are autofixable
      // (`eslint --fix`), and both were applied across static/js when the rules were added.
      "prefer-const": "error",
      "object-shorthand": ["error", "always"],
      eqeqeq: ["error", "smart"],
      // Honour the codebase's "_"-prefix convention for intentionally unused
      // bindings: unused function args and caught errors named `_e` / `_ignored`
      // are deliberate signals, not dead code.
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
    // FE-SEC-05: an innerHTML / outerHTML / insertAdjacentHTML / document.write sink must be fed
    // either a constant or a value passed through an explicit escaper. `escapeHtml` / `escapeAttr`
    // (escape-html.js) are the one shared pair; the plugin accepts their result as sanitized.
    // `krtFetch.swap` is the one sanctioned sink for server-rendered Thymeleaf fragments (the
    // markup is escaped by the template engine, not by JS) and is listed as such.
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
    // The transport itself: krt-fetch.js implements krtFetch.write / submitForm on top of `fetch`,
    // and krt-client-error.js posts the client-error beacon with a bare `fetch` on purpose (it must
    // work when krtFetch itself failed to load).
    files: [
      "src/main/resources/static/js/krt-fetch.js",
      "src/main/resources/static/js/krt-client-error.js",
    ],
    rules: {
      "no-restricted-syntax": "off",
    },
  },
  {
    // Build-time Node scripts (the OpenAPI -> .d.ts emitter, ADR-0130). Unlike the browser
    // scripts these ARE ES modules and run under Node, so they need the node globals and
    // `sourceType: module` — linting them under the browser block above would flag every
    // `process` / `console` as `no-undef`. They are not served and not type-checked.
    files: ["scripts/**/*.mjs"],
    languageOptions: {
      ecmaVersion: 2023,
      sourceType: "module",
      globals: { ...globals.node },
    },
    rules: {
      "no-var": "error",
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

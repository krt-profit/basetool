import { readdirSync, readFileSync } from "node:fs";
import js from "@eslint/js";
import globals from "globals";
import nounsanitized from "eslint-plugin-no-unsanitized";

const ECMA_VERSION = 2025;

const SCRIPT_ROOT = "src/main/resources/static/js";

const CLASSIC_SCRIPTS = JSON.parse(
  readFileSync(new URL("./module-ratchet.json", import.meta.url), "utf8"),
).classic.map((script) => `${SCRIPT_ROOT}/${script}`);

const SCRIPT_DOMAINS = readdirSync(new URL(`./${SCRIPT_ROOT}`, import.meta.url), {
  withFileTypes: true,
})
  .filter((entry) => entry.isDirectory() && entry.name !== "vendor")
  .map((entry) => entry.name);

const IMPORT_BOUNDARY_MESSAGE =
  "A module imports only /js/<its domain>/<file>.js or /js/kernel/<file>.js, by that absolute path: the import map keys the content-hashed URLs by it (REQ-FE-035).";

const IMPORT_EXPRESSION_MESSAGE =
  "Dynamic import() is not in the import map's module graph; use a static import (REQ-FE-035).";

const TRANSPORT_FILES = [
  "src/main/resources/static/js/kernel/krt-fetch.js",
  "src/main/resources/static/js/kernel/krt-client-error.js",
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

const TRUSTED_TYPES_MESSAGE =
  "HTML and script sinks are Trusted Types sinks (ADR-0239): write markup with krtHtml.set(el, krtHtml`…`), a server fragment with krtFetch.setTrustedHtml / parseTrustedDocument, and clear with el.replaceChildren().";

const HTML_SINK_FILES = [
  "src/main/resources/static/js/kernel/krt-html.js",
  "src/main/resources/static/js/kernel/krt-fetch.js",
];

const TRUSTED_TYPES_SINKS = [
  {
    selector:
      "AssignmentExpression[left.type='MemberExpression'][left.property.name=/^(innerHTML|outerHTML|srcdoc)$/]",
    message: TRUSTED_TYPES_MESSAGE,
  },
  {
    selector:
      "CallExpression[callee.property.name=/^(insertAdjacentHTML|createContextualFragment|setHTMLUnsafe|parseHTMLUnsafe|parseFromString)$/]",
    message: TRUSTED_TYPES_MESSAGE,
  },
  {
    selector: "CallExpression[callee.property.name='createPolicy']",
    message:
      "Only krt-html.js and krt-fetch.js create the two Trusted Types policies (ADR-0239).",
  },
];

const ALWAYS_BANNED_SINKS = [
  {
    selector: "CallExpression[callee.object.name='document'][callee.property.name=/^(write|writeln)$/]",
    message: TRUSTED_TYPES_MESSAGE,
  },
  {
    selector: "CallExpression[callee.property.name='createElement'][arguments.0.value='script']",
    message: "Scripts are loaded by the templates with the CSP nonce, never created (ADR-0239).",
  },
  {
    selector: "CallExpression[callee.name='krtHtml'], CallExpression[callee.property.name='krtHtml']",
    message: "krtHtml is a template tag: krtHtml`<li>${value}</li>` (ADR-0239).",
  },
  {
    selector: "ImportExpression",
    message: IMPORT_EXPRESSION_MESSAGE,
  },
];

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
        krtHtml: "readonly",
      },
    },
    rules: {
      "no-eval": "error",
      "no-implied-eval": "error",
      "no-new-func": "error",
      "no-restricted-syntax": ["error", ...TRUSTED_TYPES_SINKS, ...ALWAYS_BANNED_SINKS],
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
    files: [`${SCRIPT_ROOT}/**/*.js`],
    ignores: CLASSIC_SCRIPTS,
    languageOptions: { sourceType: "module" },
  },
  ...SCRIPT_DOMAINS.map((domain) => ({
    files: [`${SCRIPT_ROOT}/${domain}/**/*.js`],
    ignores: CLASSIC_SCRIPTS,
    rules: {
      "no-restricted-imports": [
        "error",
        {
          patterns: [
            {
              regex:
                domain === "kernel"
                  ? "^(?!/js/kernel/[a-z0-9-]+\\.js$)"
                  : `^(?!/js/(?:${domain}|kernel)/[a-z0-9-]+\\.js$)`,
              message: IMPORT_BOUNDARY_MESSAGE,
            },
          ],
        },
      ],
    },
  })),
  {
    files: ["src/main/resources/static/js/**/*.js"],
    plugins: { "no-unsanitized": nounsanitized },
    rules: {
      "no-unsanitized/method": [
        "error",
        { escape: { taggedTemplates: ["krtHtml"], methods: [] } },
      ],
      "no-unsanitized/property": [
        "error",
        { escape: { taggedTemplates: ["krtHtml"], methods: [] } },
      ],
    },
  },
  {
    files: HTML_SINK_FILES,
    rules: {
      "no-restricted-syntax": ["error", ...ALWAYS_BANNED_SINKS],
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

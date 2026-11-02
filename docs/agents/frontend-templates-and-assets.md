# Frontend templates, CSS, scripts and dialogs

Read this before changing a template, a stylesheet, a script tag, a DOM write or a dialog.

## What a template may send

A rendered page carries no developer text and no inline page CSS (`REQ-UI-023`,
`TemplateCommentHygieneTest`):

- **No plain HTML comments.** A template never carries `<!-- … -->` — it would be sent with every
  response, which `TemplateCommentHygieneTest` fails. A short technical comment (ADR-0214) is a
  Thymeleaf parser-level `<!--/* … */-->` block, which the engine drops at parse time; history,
  restated markup and TODOs stay out, the reasoning goes into the commit message and the PR.
  `<!--/*/ … /*/-->` is Thymeleaf's prototype-only markup, not a comment.
- **Page CSS goes into `static/css/pages/<domain>/<page>.css`**, linked with `<link rel="stylesheet">` where
  a `<style>` block would stand (in the `extraLinks` fragment for the head). No `<style>` element in
  a template. It is linted by `:frontend:lintCss` with the standard rule set, like every other
  stylesheet, and formatted by Prettier.
- **Colours and stacking go through tokens** (REQ-UI-001, ADR-0243): a colour token's value is
  `var(--color-x)`, its alpha variant `color-mix(in srgb, var(--color-x) N%, transparent)` — never a
  hand-written `rgb()`/hex copy (`ColourTokenCopyTest`). A `z-index` that competes across the page
  is a `--z-*` token from the scale on `:root` in `styles.css`; a literal stays below 50 and orders
  one component's parts only (`ZIndexScaleTest`).

## CSS: the layer decides, not the load order (binding)

Every stylesheet starts with `@layer base, components, page, utilities;` and puts every rule inside
one of those layers (`REQ-UI-024`, ADR-0212, `CascadeLayerOrderTest`). Between layers the order
decides, before specificity:

- `styles.css` owns `base` (fonts, tokens), `components` and `utilities`; every page or area
  stylesheet is `page`. `utilities` holds only the two state classes, `.is-hidden` and
  `.krt-modal-overlay.is-open`.
- A page rule beats a design-system rule as an ordinary rule. Do not bump specificity
  (`main .x`, `div.x`, `.x.x`) and do not add `!important` for it.
- A design-system declaration that has to beat page CSS goes into the `@layer page` block at the end
  of `styles.css`. Never into `utilities`, which would also beat the page rules that out-specify it.
- `inline-migration.css` and its `krtm-*` classes are retired (REQ-UI-027 phase 4): a one-off style
  is a design-system class or a page rule, never a new `krtm-*` class (`NoMigrationClassTest`).
  `data-krtm-width` with `inline-style-apply.js` stays — it is the CSP-safe width mechanism.
- A rule outside any layer beats every layer. That is why the test fails on one.

## Script load order (binding — it has regressed three times)

Every external `<script>` is a module (`type="module"`, no `defer` — a module is deferred) except
`krt-client-error.js`, which stays the head's first, synchronous classic script (`REQ-FE-023`,
`REQ-FE-035`). Module scripts run in document order after parsing, with the other deferred ones, so a
page module may use every head global at load.

**An inline script is a data bootstrap and nothing else** (ADR-0069). It is a
`th:inline="javascript"` block of `const` / `let` / `var NAME = literal` and `window.NAME = literal`
statements whose literals (strings, numbers, booleans, `null`, arrays and objects of those) come
from `/*[[…]]*/` natural-template values. A function, a call, a listener, a merge of two
dictionaries or a reference to another global goes into the page's module under `static/js`, where
it is linted and type-checked; a module that needs a server value reads it from such a bootstrap or
a `data-*` attribute. Never `'[[#{key}]]'` inside a string literal — the inliner adds its own
quotes. `InlineScriptDataOnlyTest` parses every inline block and fails the build on anything else;
the head's `krtEvents` stub is its one reviewed exception. `InlineScriptLoadOrderTest` and
`ScriptLoadOrderE2eTest` keep the load order.

## Trusted Types: every DOM sink takes a policy value (binding)

The CSP sends `require-trusted-types-for 'script'` (ADR-0239, REQ-FE-022, REQ-SEC-064) —
report-only by default, enforced with `APP_SECURITY_TRUSTED_TYPES=enforce`. Under enforcement a
string written into an HTML sink throws, even `innerHTML = ''`. So:

- **Markup built in script:** `import { krtHtml } from '/js/kernel/krt-html.js';`, then
  ``krtHtml.set(el, krtHtml`<li data-id="${id}">${name}</li>`)``. Every
  interpolation is escaped unless it is itself `krtHtml` markup; pass arrays of `krtHtml` pieces
  (`items.map((it) => krtHtml`…`)`) instead of concatenating strings, and never pre-escape a value.
  `krtHtml.set` writes anything that is not `krtHtml` markup as text.
- **A server fragment:** `krtFetch.setTrustedHtml`, `replaceWithTrustedHtml` or
  `parseTrustedDocument` — only with the body of a same-origin fragment response, never with markup
  built in script.
- **Clearing:** `el.replaceChildren()`.
- Never create a Trusted Types policy (there is no `default` one), never call `krtHtml` as a
  function, never use `eval`, `new Function`, a string timer or a created `<script>`.

`:frontend:lintJs` rejects every other sink write outside `krt-html.js` and `krt-fetch.js`, and the
dialog page walk (`DialogA11yE2eTest`) fails on any violation the browser reports.

## Dialogs: one contract (binding)

Every dialog is a `<dialog class="krt-modal-overlay">` > `.krt-modal` (REQ-UI-013, ADR-0177) and
opens and closes **only** through `krtModal.open(el | id)` / `krtModal.close(el | id)` (imported from
`/js/kernel/krt-modal.js`)
— or the shared `data-trigger="open-modal-display"` / `"close-modal-display"` triggers, which call
it. Never write `overlay.style.display`, never toggle `is-open` yourself: the contract
calls `showModal()`, moves focus in and back, handles Escape, and keeps the class state that live
sync and the tests read. While a dialog is open the page behind it is inert, so anything you append
for the user to see or click — a toast, a confirm, a download link — goes into
`krtModal.layerRoot()`, not `document.body`.

**Never write a dialog shell by hand.** Every dialog is a call of `fragments/modal-wrapper :: modal`
(`SingleModalShapeTest` fails on `.krt-modal-overlay`, `.krt-modal`, `.krt-modal-head` or
`.krt-modal-close` anywhere else). The page supplies the body:

```html
<th:block th:replace="~{kernel/fragments/modal-wrapper :: modal(modalId='x-modal', titleKey='x.title',
    variant='krt-modal--wide', body=~{::x-modal-body})}">
    <th:block th:ref="x-modal-body">
        <form …><div class="krt-modal-body">…</div><div class="krt-modal-foot">…</div></form>
    </th:block>
</th:block>
```

The fragment declares no signature; a call names only the parameters it needs:

| Parameter | Meaning |
| --- | --- |
| `modalId` | Required. The `<dialog>` id, also the close control's `data-modal-id`. |
| `titleKey` | Required. i18n key of the `<h2>` title. |
| `body` | Required. Fragment expression for everything below the head: `.krt-modal-body` and `.krt-modal-foot`, or a `<form>` wrapping both. |
| `variant` | Extra class on `.krt-modal`: `krt-modal--wide`, `--xwide`, `--danger`, or a page's own frame class. |
| `titleId` | Id of the `<h2>` for a script that retitles the dialog; the dialog is then labelled by the heading (`aria-labelledby`), otherwise it carries the title as `aria-label`. |
| `open` | `true` renders the dialog open (e.g. after a server-side validation error). |
| `closeTrigger` | The ✕'s `data-trigger`; default `close-modal-display`. A page handler must end in `krtModal.close`. `''` when a script binds the close by `closeClass`. |
| `closeClass` / `closeId` | Class / id on the close control for a page script. |

A condition or iteration goes on a `<th:block>` around the call. In a fragment file, name the body with its template
(`~{bank/fragments/x :: x-modal-body}`). A new dialog id also needs `DialogA11yE2eTest` to reach it, or an
`UNREACHED` entry with the reason. Render a dialog under the same condition as its openers and the
script that drives it: a dialog nothing can open, or whose handlers were never loaded, is dead
markup (the promotion admin all-squadrons view and the bare admin blueprint page were).

## Page templates: one layout fragment (binding)

Every page template is composed through `fragments/layout :: page` (REQ-FE-033, plan F5): the page
hands over its parts and **never calls the head, sidebar, header or toast fragment itself**
(`PageLayoutCompositionTest`). The shape of a page:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" th:replace="~{kernel/fragments/layout :: page(pageTitle=~{::title},
    pageLinks=~{::extraLinks}, pageContent=~{::pageContent}, pageToast=true, pageTail=~{::pageTail})}">
<head>
    <title th:text="#{x.title} + ' - ' + ${appTitle}">X</title>
    <th:block th:fragment="extraLinks">
        <link rel="stylesheet" th:href="@{/css/pages/bank/x.css}">
    </th:block>
</head>
<body>
<th:block th:fragment="pageContent"><main>…</main></th:block>
<th:block th:fragment="pageTail"><script type="module" th:src="@{/js/bank/x.js}" th:attr="nonce=${cspNonce}"></script></th:block>
</body>
</html>
```

`pageTitle` and `pageContent` are required; `pageLinks` and `pageTail` are left out when the page has
none. `pageToast=true` renders the toast between the content and the tail, so the page's own scripts
go into the tail to keep loading after `toast.js`. `pageBare=true` leaves out sidebar and header (the
error pages, pending approval, terms acceptance); `pageShowModal=${…}` sets the body's
`data-show-modal`. A different chrome is a new parameter of the layout, never a copy into a page, and
changing the layout means rewriting the golden file `src/test/resources/templates/page-chrome-snapshot.txt`
(`PageChromeSnapshotTest`, `-PupdateSnapshots`) and saying so in the review. A fragment a controller
returns (`view :: fragment`) is unaffected.

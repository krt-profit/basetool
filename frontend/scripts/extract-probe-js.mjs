/**
 * Extracts the browser-side probe script out of TouchClassLayoutE2eTest so a linter can see it.
 *
 * That test measures layout by evaluating ~500 lines of JavaScript in the page. The script lives in
 * a Java text block, which means **no JavaScript tool has ever read it** — not eslint, not prettier,
 * not `node --check`. Compiling the Java only proves the string is a valid Java string.
 *
 * That blind spot has now cost twice on the same file:
 *
 *  - `replaceAll("\s+", " ")` compiled happily, because `\s` is a legal Java escape for a SPACE
 *    since Java 15 — so the regex silently became `" +"`;
 *  - the dense-floor guard referenced `badControls` about a hundred lines above its `const`, which
 *    is a temporal-dead-zone `ReferenceError` on every page of every device class. It turned all 66
 *    routes into "could not be measured" and was invisible until a CI shard ran the suite.
 *
 * Usage: node scripts/extract-probe-js.mjs <TouchClassLayoutE2eTest.java> <out.js>
 *
 * The `%d` / `%s` placeholders that `String.formatted` fills are replaced with syntactically valid
 * stand-ins rather than the real constants: the point is to lint the SHAPE of the script, and
 * resolving the arguments would mean parsing Java.
 */

import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

const [source, target] = process.argv.slice(2);
if (!source || !target) {
  console.error('usage: node scripts/extract-probe-js.mjs <source.java> <target.js>');
  process.exit(2);
}

const java = readFileSync(source, 'utf8');

// The probe is the text block assigned to PROBE_JS and terminated by `.formatted(`.
//
// It used to be anchored on the FIRST `.formatted(` in the file, on the reasoning that the constant
// had been renamed once already. That held only while the probe was the file's sole formatted
// string. It no longer is: the modal sweep added a formatted one-liner (`MODAL_SHAPES_JS`) and two
// smaller formatted text blocks ABOVE the probe, and "first" then resolved to a fragment — or, since
// the one-liner precedes every text block, to no delimiters at all, which is the error this replaces.
// A rename now fails loudly here instead of silently linting the wrong block.
const declAt = java.indexOf('String PROBE_JS');
if (declAt === -1) {
  console.error(
    'extract-probe-js: no `String PROBE_JS` in ' + source + ' — has the probe been renamed?',
  );
  process.exit(1);
}
const opening = java.indexOf('"""', declAt);
const closing = opening === -1 ? -1 : java.indexOf('"""', opening + 3);
if (opening === -1 || closing === -1) {
  console.error('extract-probe-js: could not find the text block delimiters');
  process.exit(1);
}
// The probe carries placeholders, so its block must be the one `.formatted(` fills. Anything else
// means PROBE_JS stopped being that block, and linting it would measure the wrong thing.
if (!java.slice(closing + 3, closing + 64).trimStart().startsWith('.formatted(')) {
  console.error('extract-probe-js: the PROBE_JS text block is not terminated by `.formatted(`');
  process.exit(1);
}

let js = java.slice(opening + 3, closing);

// `%d` is an int (a pixel floor), `%s` a string (the hit-area marker). Anything else is unexpected
// and would mean the probe grew a placeholder this script does not understand.
const placeholders = js.match(/%./g) ?? [];
const unknown = placeholders.filter((p) => p !== '%d' && p !== '%s');
if (unknown.length > 0) {
  console.error('extract-probe-js: unsupported format placeholders: ' + unknown.join(', '));
  process.exit(1);
}
// `%d` appears bare (a pixel count). `%s` appears in two positions now, and the substitution has
// to match the position or the extracted file will not parse:
//
//  - INSIDE a JS string literal (`'%s'` — the hit-area marker, a selector): plain text. Substituting
//    a quoted empty string there produced `''''` and a parse error.
//  - BARE, in expression position (`const MODAL_SHAPES = %s;` — the modal shapes as an array
//    literal): plain text there produced `const MODAL_SHAPES = mark: ;`, a parse error that stopped
//    the probe being linted at all. An empty array literal keeps the shape the probe uses it in.
//
// The test is local on purpose: whether the placeholder is wrapped in its own quotes. A scanner that
// tracked string state would trip over the apostrophes in the probe's own comments.
js = js.replaceAll('%d', '0').replace(/(.?)%s(.?)/gs, (_match, before, after) => {
  const ownString =
    (before === "'" && after === "'") ||
    (before === '"' && after === '"') ||
    (before === '`' && after === '`');
  return before + (ownString ? 'mark: ' : '[]') + after;
});

// A Java text block keeps its escapes; the two that appear here are the JS regex ones.
//
// ONE pass, not two. Unescaping `\\` and then `\"` in sequence is
// DOUBLE-unescaping: the first pass can PRODUCE a backslash that the second then consumes, so
// the content `\\"` (an escaped backslash followed by a quote) collapses to
// `"` and the backslash is lost. A single regex whose match consumes BOTH characters
// cannot re-read what it just wrote. CodeQL flags the two-pass form, and it is right to.
js = js.replace(/\\([\\"])/g, '$1');

mkdirSync(dirname(target), { recursive: true });
writeFileSync(target, 'export const probe = ' + js.trim() + ';\n', 'utf8');
console.log(
  'extract-probe-js: wrote ' + target + ' (' + js.length + ' chars, ' +
  placeholders.length + ' placeholders substituted)',
);

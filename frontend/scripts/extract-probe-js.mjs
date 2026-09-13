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

// The probe is the text block terminated by `.formatted(`. Anchored on that rather than on a name,
// because the constant it is assigned to has been renamed once already.
const formattedAt = java.indexOf('.formatted(');
if (formattedAt === -1) {
  console.error('extract-probe-js: no `.formatted(` in ' + source + ' — has the probe moved?');
  process.exit(1);
}
const closing = java.lastIndexOf('"""', formattedAt);
const opening = java.lastIndexOf('"""', closing - 1);
if (opening === -1 || closing === -1 || opening >= closing) {
  console.error('extract-probe-js: could not find the text block delimiters');
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
// `%d` appears bare (a pixel count); `%s` appears INSIDE a JS string literal, so it takes
// plain text. Substituting a quoted empty string there produced `''''` and a parse error.
js = js.replaceAll('%d', '0').replaceAll('%s', 'mark: ');

// A Java text block keeps its escapes; the two that appear here are the JS regex ones.
js = js.replaceAll('\\\\', '\\').replaceAll('\\"', '"');

mkdirSync(dirname(target), { recursive: true });
writeFileSync(target, 'export const probe = ' + js.trim() + ';\n', 'utf8');
console.log(
  'extract-probe-js: wrote ' + target + ' (' + js.length + ' chars, ' +
  placeholders.length + ' placeholders substituted)',
);

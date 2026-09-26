/**
 * Extracts the browser-side probe script (the `PROBE_JS` text block) out of
 * TouchClassLayoutE2eTest so a linter can see it.
 *
 * The `%d` / `%s` placeholders are replaced with syntactically valid stand-ins, not the real values.
 *
 * Usage: node scripts/extract-probe-js.mjs <TouchClassLayoutE2eTest.java> <out.js>
 */

import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

const [source, target] = process.argv.slice(2);
if (!source || !target) {
  console.error('usage: node scripts/extract-probe-js.mjs <source.java> <target.js>');
  process.exit(2);
}

const java = readFileSync(source, 'utf8');

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
if (!java.slice(closing + 3, closing + 64).trimStart().startsWith('.formatted(')) {
  console.error('extract-probe-js: the PROBE_JS text block is not terminated by `.formatted(`');
  process.exit(1);
}

let js = java.slice(opening + 3, closing);

const placeholders = js.match(/%./g) ?? [];
const unknown = placeholders.filter((p) => p !== '%d' && p !== '%s');
if (unknown.length > 0) {
  console.error('extract-probe-js: unsupported format placeholders: ' + unknown.join(', '));
  process.exit(1);
}
js = js.replaceAll('%d', '0').replace(/(.?)%s(.?)/gs, (_match, before, after) => {
  const ownString =
    (before === "'" && after === "'") ||
    (before === '"' && after === '"') ||
    (before === '`' && after === '`');
  return before + (ownString ? 'mark: ' : '[]') + after;
});

js = js.replace(/\\([\\"])/g, '$1');

mkdirSync(dirname(target), { recursive: true });
writeFileSync(target, 'export const probe = ' + js.trim() + ';\n', 'utf8');
console.log(
  'extract-probe-js: wrote ' + target + ' (' + js.length + ' chars, ' +
  placeholders.length + ' placeholders substituted)',
);

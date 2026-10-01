/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

/**
 * Regression tests for the CSV cell escaper pmCsvEscape in promotion-manage.js (formula-injection
 * neutralisation). The classic script touches the DOM at load time, so the function is lifted out
 * of the source text and evaluated in isolation.
 */

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const SOURCE = readFileSync(
  fileURLToPath(new URL("../src/main/resources/static/js/promotion-manage.js", import.meta.url)),
  "utf8",
);

const match = /function pmCsvEscape\(value\) \{[\s\S]*?\n\}\n/.exec(SOURCE);
assert.ok(match, "pmCsvEscape not found in promotion-manage.js");
const pmCsvEscape = new Function(`${match[0]}\nreturn pmCsvEscape;`)();

let failures = 0;

/** Runs one named assertion, recording rather than throwing so every case reports. */
function test(name, fn) {
  try {
    fn();
    console.log(`ok   ${name}`);
  } catch (error) {
    failures += 1;
    console.error(`FAIL ${name}\n${error.message}`);
  }
}

test("plain text is left alone", () => {
  assert.equal(pmCsvEscape("Alice"), "Alice");
});

test("null and undefined become empty cells", () => {
  assert.equal(pmCsvEscape(null), "");
  assert.equal(pmCsvEscape(undefined), "");
});

for (const lead of ["=", "+", "-", "@", "\t"]) {
  test(`leading ${JSON.stringify(lead)} is neutralised with an apostrophe`, () => {
    assert.equal(pmCsvEscape(`${lead}SUM(A1)`), `'${lead}SUM(A1)`);
  });
}

test("leading carriage return is neutralised and then quoted", () => {
  assert.equal(pmCsvEscape("\r=1+1"), `"'\r=1+1"`);
});

test("a formula with a comma is neutralised and quoted", () => {
  assert.equal(pmCsvEscape('=HYPERLINK("http://x","y")'), `"'=HYPERLINK(""http://x"",""y"")"`);
});

test("an operator inside the cell is not touched", () => {
  assert.equal(pmCsvEscape("A->B"), "A->B");
  assert.equal(pmCsvEscape("a=b"), "a=b");
});

test("commas and quotes are still quoted", () => {
  assert.equal(pmCsvEscape('a,"b"'), '"a,""b"""');
});

if (failures > 0) {
  console.error(`${failures} test(s) failed`);
  process.exit(1);
}

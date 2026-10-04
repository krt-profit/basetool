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
 * Proves that the ESLint bans of eslint.config.mjs fire: planted sources are linted as if they lived
 * under static/js, and each banned shape must be reported (REQ-FE-031, ADR-0239).
 */

import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { ESLint } from 'eslint';

const CWD = fileURLToPath(new URL('..', import.meta.url));
const eslint = new ESLint({ cwd: CWD });

let failures = 0;

/** Runs one named async case, recording rather than throwing so every case reports. */
async function test(name, fn) {
    try {
        await fn();
        console.log(`ok - ${name}`);
    } catch (e) {
        failures += 1;
        console.error(`not ok - ${name}\n${e.stack}`);
    }
}

/**
 * Lints `code` as the given static/js file and returns the rule ids it reported.
 *
 * @param {string} code the planted source
 * @param {string} file the file name under static/js the source pretends to be
 * @returns {Promise<string[]>} the reported rule ids
 */
async function rulesFor(code, file = 'planted.js') {
    const [result] = await eslint.lintText(code, {
        filePath: `${CWD}src/main/resources/static/js/${file}`,
    });
    return result.messages.map((m) => m.ruleId);
}

const BANNED = {
    'a bare fetch': ["fetch('/a');", 'no-restricted-globals'],
    'window.fetch': ["window.fetch('/a');", 'no-restricted-properties'],
    'globalThis.fetch': ["globalThis.fetch('/a');", 'no-restricted-properties'],
    'an XMLHttpRequest': ['new XMLHttpRequest();', 'no-restricted-globals'],
    'window.XMLHttpRequest': ['new window.XMLHttpRequest();', 'no-restricted-properties'],
    'Promise.try': ['Promise.try(() => 1);', 'no-restricted-properties'],
    'RegExp.escape': ["RegExp.escape('a');", 'no-restricted-properties'],
    'a Float16Array': ['new Float16Array(1);', 'no-restricted-globals'],
};

for (const [name, [code, rule]] of Object.entries(BANNED)) {
    await test(`${name} is rejected in a page script`, async () => {
        assert.ok((await rulesFor(code)).includes(rule), `${rule} not reported for ${code}`);
    });
}

await test('the transport may call fetch but still not XMLHttpRequest', async () => {
    assert.deepEqual(await rulesFor("fetch('/a');", 'krt-fetch.js'), []);
    assert.ok(
        (await rulesFor('new XMLHttpRequest();', 'krt-fetch.js')).includes('no-restricted-globals'),
    );
});

await test('the shared read path and ES2025 syntax pass', async () => {
    const code = [
        "window.krtFetch.getJson('/a').then((list) => list);",
        'const s = new Set([1]).union(new Set([2]));',
        'const it = [1, 2].values().map((n) => n * 2).toArray();',
        'console.log(s, it);',
    ].join('\n');
    assert.deepEqual(await rulesFor(code), []);
});

await test('the modern-syntax rules report their shapes', async () => {
    const rules = await rulesFor(
        [
            "const a = 'x' + window.name;",
            'parseInt(a);',
            'const b = Object.prototype.hasOwnProperty.call({}, a);',
            'let c = window.c;',
            'c = c || 1;',
            '[1].forEach(function (n) { console.log(n, b, c); });',
        ].join('\n'),
    );
    for (const rule of [
        'prefer-template',
        'radix',
        'prefer-object-has-own',
        'logical-assignment-operators',
        'prefer-arrow-callback',
    ]) {
        assert.ok(rules.includes(rule), `${rule} not reported`);
    }
});

if (failures > 0) {
    console.error(`${failures} failed`);
    process.exit(1);
}

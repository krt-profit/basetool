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
 * Proves the type-check ratchet (REQ-FE-018): the real tree satisfies it, and each rule fires on a
 * planted tree that breaks it.
 */

import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { REQUIRED_OPTIONS, evaluate, layoutViolations, loadTree } from './typecheck-ratchet.mjs';

let failures = 0;

/** Runs one named case, recording rather than throwing so every case reports. */
function test(name, fn) {
    try {
        fn();
        console.log(`ok - ${name}`);
    } catch (e) {
        failures += 1;
        console.error(`not ok - ${name}\n${e.stack}`);
    }
}

const SOURCE = 'const a = 1;\n';

const TSCONFIG = {
    compilerOptions: { ...REQUIRED_OPTIONS },
    include: ['src/main/resources/static/js/**/*.js', 'types/**/*.d.ts'],
    exclude: ['src/main/resources/static/js/vendor/**', 'node_modules', 'build'],
};

/**
 * A small valid tree with two domains.
 *
 * @returns {Parameters<typeof evaluate>[0]} a tree the ratchet accepts
 */
function validTree() {
    return {
        scripts: new Map([
            ['a-one.js', SOURCE],
            ['a-two.js', SOURCE],
            ['b-one.js', SOURCE],
        ]),
        manifest: {
            domains: { alpha: ['a-one.js', 'a-two.js'], beta: ['b-one.js'] },
        },
        tsconfig: structuredClone(TSCONFIG),
    };
}

/**
 * Evaluates a valid tree after a mutation and returns the violations.
 *
 * @param {(tree: Parameters<typeof evaluate>[0]) => void} mutate the planted breakage
 * @returns {string[]} the reported violations
 */
function violationsAfter(mutate) {
    const tree = validTree();
    mutate(tree);
    return evaluate(tree);
}

test('the real tree satisfies the ratchet', () => {
    const root = fileURLToPath(new URL('..', import.meta.url));
    const tree = loadTree(root);
    assert.deepEqual(evaluate(tree), []);
    assert.deepEqual(layoutViolations(tree.manifest), []);
});

test('the required options are the strict set', () => {
    assert.deepEqual(Object.keys(REQUIRED_OPTIONS).sort(), [
        'checkJs',
        'noImplicitAny',
        'strict',
        'useUnknownInCatchVariables',
    ]);
    assert.ok(Object.values(REQUIRED_OPTIONS).every((value) => value === true));
});

test('a script outside its domain folder fails', () => {
    const manifest = { domains: { alpha: ['alpha/a.js', 'beta/b.js', 'c.js', 'alpha/sub/d.js'] } };
    assert.deepEqual(
        layoutViolations(manifest).map((v) => v.split(' ')[0]),
        ['beta/b.js', 'c.js', 'alpha/sub/d.js'],
    );
});

test('a valid tree passes', () => {
    assert.deepEqual(evaluate(validTree()), []);
});

test('a file in no domain fails', () => {
    const violations = violationsAfter((t) => t.scripts.set('stray.js', SOURCE));
    assert.ok(violations.some((v) => /stray\.js belongs to no domain/.test(v)));
});

test('a file listed under two domains fails', () => {
    const violations = violationsAfter((t) => t.manifest.domains.beta.push('a-one.js'));
    assert.ok(violations.some((v) => /a-one\.js is listed under both/.test(v)));
});

test('a listed file that does not exist fails', () => {
    const violations = violationsAfter((t) => t.manifest.domains.beta.push('gone.js'));
    assert.ok(violations.some((v) => /lists gone\.js, which does not exist/.test(v)));
});

test('a domain name that is not lower-case kebab fails', () => {
    const violations = violationsAfter((t) => {
        t.manifest.domains.Gamma = [];
    });
    assert.ok(violations.some((v) => /"Gamma" is not lower-case kebab/.test(v)));
});

test('a script in a subfolder must be in a domain', () => {
    const violations = violationsAfter((t) => t.scripts.set('sub/deep.js', SOURCE));
    assert.ok(violations.some((v) => /sub\/deep\.js belongs to no domain/.test(v)));
});

test('a script of a subfolder in a domain passes', () => {
    const violations = violationsAfter((t) => {
        t.scripts.set('sub/deep.js', SOURCE);
        t.manifest.domains.beta.push('sub/deep.js');
    });
    assert.deepEqual(violations, []);
});

test('loadTree reads subfolders and skips vendor', () => {
    const root = mkdtempSync(`${tmpdir()}/ratchet-`);
    try {
        const js = `${root}/src/main/resources/static/js`;
        mkdirSync(`${js}/sub/deeper`, { recursive: true });
        mkdirSync(`${js}/vendor`, { recursive: true });
        writeFileSync(`${js}/top.js`, SOURCE);
        writeFileSync(`${js}/sub/mid.js`, SOURCE);
        writeFileSync(`${js}/sub/deeper/low.js`, SOURCE);
        writeFileSync(`${js}/vendor/lib.js`, SOURCE);
        writeFileSync(`${js}/sub/readme.txt`, 'x');
        writeFileSync(`${root}/typecheck-ratchet.json`, '{"domains":{}}');
        writeFileSync(`${root}/tsconfig.json`, '{}');
        const { scripts } = loadTree(`${root}/`);
        assert.deepEqual([...scripts.keys()].sort(), ['sub/deeper/low.js', 'sub/mid.js', 'top.js']);
    } finally {
        rmSync(root, { recursive: true, force: true });
    }
});

test('a suppression comment fails', () => {
    for (const pragma of ['@ts-nocheck', '@ts-ignore', '@ts-expect-error']) {
        const violations = violationsAfter((t) =>
            t.scripts.set('b-one.js', `${SOURCE}// ${pragma}\n`),
        );
        assert.ok(
            violations.some((v) => /b-one\.js suppresses the type check/.test(v)),
            pragma,
        );
    }
});

test('a tsconfig that excludes scripts fails', () => {
    const violations = violationsAfter((t) =>
        t.tsconfig.exclude.push('src/main/resources/static/js/a-*.js'),
    );
    assert.ok(
        violations.some((v) => /excludes src\/main\/resources\/static\/js\/a-\*\.js/.test(v)),
    );
});

test('a tsconfig that turns a required option off fails', () => {
    for (const option of Object.keys(REQUIRED_OPTIONS)) {
        const violations = violationsAfter((t) => {
            t.tsconfig.compilerOptions[option] = false;
        });
        assert.ok(
            violations.some((v) => v.includes(`must keep "${option}": true`)),
            option,
        );
    }
});

test('a tsconfig that drops a required option fails', () => {
    for (const option of Object.keys(REQUIRED_OPTIONS)) {
        const violations = violationsAfter((t) => {
            delete t.tsconfig.compilerOptions[option];
        });
        assert.ok(
            violations.some((v) => v.includes(`must keep "${option}": true`)),
            option,
        );
    }
});

test('a tsconfig that stops including the scripts fails', () => {
    const violations = violationsAfter((t) => {
        t.tsconfig.include = ['types/**/*.d.ts'];
    });
    assert.ok(violations.some((v) => /must include/.test(v)));
});

if (failures > 0) {
    console.error(`${failures} failure(s)`);
    process.exit(1);
}

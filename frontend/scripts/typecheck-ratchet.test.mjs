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
import {
    CHECKED_FILES_FLOOR,
    MIGRATED_FLOOR,
    evaluate,
    isChecked,
    loadTree,
} from './typecheck-ratchet.mjs';

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

const CHECKED = '// @ts-check\nconst a = 1;\n';
const UNCHECKED = 'const a = 1;\n';

const TSCONFIG = {
    compilerOptions: { strict: true, checkJs: false },
    include: ['src/main/resources/static/js/**/*.js', 'types/**/*.d.ts'],
    exclude: ['src/main/resources/static/js/vendor/**', 'node_modules', 'build'],
};

/**
 * A small valid tree: `alpha` and `beta` are both migrated and fully checked.
 *
 * @returns {Parameters<typeof evaluate>[0]} a tree the ratchet accepts
 */
function validTree() {
    return {
        scripts: new Map([
            ['a-one.js', CHECKED],
            ['a-two.js', CHECKED],
            ['b-one.js', CHECKED],
        ]),
        manifest: {
            domains: { alpha: ['a-one.js', 'a-two.js'], beta: ['b-one.js'] },
            migrated: ['alpha', 'beta'],
        },
        tsconfig: structuredClone(TSCONFIG),
        migratedFloor: ['alpha'],
        checkedFloor: 3,
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
    const violations = evaluate({
        ...loadTree(root),
        migratedFloor: MIGRATED_FLOOR,
        checkedFloor: CHECKED_FILES_FLOOR,
    });
    assert.deepEqual(violations, []);
});

test('a valid tree passes', () => {
    assert.deepEqual(evaluate(validTree()), []);
});

test('an unchecked file in a migrated domain fails', () => {
    const violations = violationsAfter((t) => t.scripts.set('a-two.js', UNCHECKED));
    assert.ok(violations.some((v) => /a-two\.js is in the migrated domain alpha/.test(v)));
});

test('a new unchecked file listed in a migrated domain fails', () => {
    const violations = violationsAfter((t) => {
        t.scripts.set('a-three.js', UNCHECKED);
        t.manifest.domains.alpha.push('a-three.js');
    });
    assert.ok(violations.some((v) => /a-three\.js is in the migrated domain alpha/.test(v)));
});

test('a file in no domain fails', () => {
    const violations = violationsAfter((t) => t.scripts.set('stray.js', CHECKED));
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

test('dropping a domain from the migrated list fails', () => {
    const violations = violationsAfter((t) => {
        t.manifest.migrated = ['beta'];
    });
    assert.ok(violations.some((v) => /alpha was migrated and left the "migrated" list/.test(v)));
    assert.ok(violations.some((v) => /alpha is a domain but is not migrated/.test(v)));
});

test('an unchecked file in the second migrated domain fails', () => {
    const violations = violationsAfter((t) => t.scripts.set('b-one.js', UNCHECKED));
    assert.ok(violations.some((v) => /b-one\.js is in the migrated domain beta/.test(v)));
});

test('migrating a domain that is not declared fails', () => {
    const violations = violationsAfter((t) => t.manifest.migrated.push('ghost'));
    assert.ok(violations.some((v) => /names ghost, which is not a domain/.test(v)));
});

test('a new domain that is not migrated fails, checked or not', () => {
    for (const source of [CHECKED, UNCHECKED]) {
        const violations = violationsAfter((t) => {
            t.scripts.set('g-one.js', source);
            t.manifest.domains.gamma = ['g-one.js'];
        });
        assert.ok(
            violations.some((v) => /gamma is a domain but is not migrated/.test(v)),
            source,
        );
    }
});

test('a new domain that is migrated and fully checked passes', () => {
    const violations = violationsAfter((t) => {
        t.scripts.set('g-one.js', CHECKED);
        t.manifest.domains.gamma = ['g-one.js'];
        t.manifest.migrated.push('gamma');
        t.checkedFloor = 4;
    });
    assert.deepEqual(violations, []);
});

test('a script in a subfolder must be in a domain', () => {
    const violations = violationsAfter((t) => t.scripts.set('sub/deep.js', CHECKED));
    assert.ok(violations.some((v) => /sub\/deep\.js belongs to no domain/.test(v)));
});

test('an unchecked script of a subfolder in a domain fails', () => {
    const violations = violationsAfter((t) => {
        t.scripts.set('sub/deep.js', UNCHECKED);
        t.manifest.domains.beta.push('sub/deep.js');
    });
    assert.ok(violations.some((v) => /sub\/deep\.js is in the migrated domain beta/.test(v)));
});

test('a checked script of a subfolder in a domain passes', () => {
    const violations = violationsAfter((t) => {
        t.scripts.set('sub/deep.js', CHECKED);
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
        writeFileSync(`${js}/top.js`, CHECKED);
        writeFileSync(`${js}/sub/mid.js`, CHECKED);
        writeFileSync(`${js}/sub/deeper/low.js`, UNCHECKED);
        writeFileSync(`${js}/vendor/lib.js`, UNCHECKED);
        writeFileSync(`${js}/sub/readme.txt`, 'x');
        writeFileSync(`${root}/typecheck-ratchet.json`, '{"domains":{},"migrated":[]}');
        writeFileSync(`${root}/tsconfig.json`, '{}');
        const { scripts } = loadTree(`${root}/`);
        assert.deepEqual([...scripts.keys()].sort(), ['sub/deeper/low.js', 'sub/mid.js', 'top.js']);
    } finally {
        rmSync(root, { recursive: true, force: true });
    }
});

test('removing a pragma below the checked floor fails', () => {
    const violations = violationsAfter((t) => {
        t.scripts.set('a-one.js', UNCHECKED);
    });
    assert.ok(violations.some((v) => /fewer than the floor of 3/.test(v)));
});

test('a suppression comment fails', () => {
    for (const pragma of ['@ts-nocheck', '@ts-ignore', '@ts-expect-error']) {
        const violations = violationsAfter((t) =>
            t.scripts.set('b-one.js', `${CHECKED}// ${pragma}\n`),
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

test('a tsconfig that is not strict fails', () => {
    const violations = violationsAfter((t) => {
        t.tsconfig.compilerOptions.strict = false;
    });
    assert.ok(violations.some((v) => /"strict": true/.test(v)));
});

test('a tsconfig that stops including the scripts fails', () => {
    const violations = violationsAfter((t) => {
        t.tsconfig.include = ['types/**/*.d.ts'];
    });
    assert.ok(violations.some((v) => /must include/.test(v)));
});

test('the pragma is read only from the leading comments', () => {
    assert.equal(isChecked('// @ts-check\n'), true);
    assert.equal(isChecked('/* licence */\n\n// @ts-check\nconst a = 1;\n'), true);
    assert.equal(isChecked('// licence\n// @ts-check\r\nconst a = 1;\n'), true);
    assert.equal(isChecked('const a = 1;\n// @ts-check\n'), false);
    assert.equal(isChecked('/* // @ts-check */\nconst a = 1;\n'), false);
    assert.equal(isChecked('const s = "// @ts-check";\n'), false);
    assert.equal(isChecked(''), false);
});

if (failures > 0) {
    console.error(`${failures} failure(s)`);
    process.exit(1);
}

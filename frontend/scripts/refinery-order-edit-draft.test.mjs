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
 * Regression tests for the part of a refinery-order edit draft the page keeps itself (REQ-FE-041):
 * the good rows and changed parts read defensively, and the rows matched by good id, in a vm context.
 */

import assert from 'node:assert/strict';
import vm from 'node:vm';
import { flattenModule } from './flatten-module.mjs';

const SOURCE = flattenModule('refinery/refinery-order-edit-draft.js');

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

/**
 * Evaluates an expression over the module in a fresh context and copies the result out.
 *
 * @param {string} expression the expression to evaluate
 * @param {object} values the names the expression reads
 */
function run(expression, values = {}) {
    const context = { ...values };
    vm.createContext(context);
    vm.runInContext(SOURCE, context);
    return JSON.parse(JSON.stringify(vm.runInContext(expression, context)));
}

test('a stored part yields its good rows and changed parts', () => {
    assert.deepEqual(
        run('readRefineryEditDraft(extra)', {
            extra: { goods: ['g-1', '', 7], changed: { gameData: true, finances: 'yes' } },
        }),
        { goods: ['g-1', '', ''], changed: { gameData: true, finances: false } },
    );
});

test('anything else yields no rows and no changed part', () => {
    const empty = { goods: [], changed: {} };
    assert.deepEqual(run('readRefineryEditDraft(null)'), empty);
    assert.deepEqual(run("readRefineryEditDraft({ goods: 'all', changed: 3 })"), empty);
});

test('rows keep their goods, an added row gets none and a removed good is left out', () => {
    assert.deepEqual(
        run('matchGoodRows(rendered, stored)', {
            rendered: ['g-1', 'g-2', 'g-3'],
            stored: ['g-1', 'g-3', ''],
        }),
        [0, 2, null],
    );
});

test('a good the page no longer renders becomes an added row, and each row is used once', () => {
    assert.deepEqual(
        run('matchGoodRows(rendered, stored)', {
            rendered: ['g-1', ''],
            stored: ['g-9', 'g-1', 'g-1'],
        }),
        [null, 0, null],
    );
});

test('the form key names the detail form', () => {
    assert.equal(run('REFINERY_EDIT_DRAFT_KEY'), 'refinery-order-edit');
});

if (failures > 0) {
    console.error(`${failures} failed`);
    process.exit(1);
}

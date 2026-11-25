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
 * Regression tests for reading the part of a mission template create draft the editor keeps itself
 * (REQ-FE-041): the shown parts and the planning rows, read defensively in a vm context.
 */

import assert from 'node:assert/strict';
import vm from 'node:vm';
import { flattenModule } from './flatten-module.mjs';

const SOURCE = flattenModule('mission/mission-template-draft.js');
const PARTS = ['description', 'schedule', 'objectives', 'units'];
const SECTIONS = ['objectives', 'units'];

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
 * Reads a stored part through the module in a fresh context and copies the result out.
 *
 * @param {unknown} extra the stored part
 */
function read(extra) {
    const context = { extra, parts: PARTS, sections: SECTIONS };
    vm.createContext(context);
    vm.runInContext(SOURCE, context);
    return JSON.parse(
        JSON.stringify(
            vm.runInContext('readMissionTemplateDraft(extra, parts, sections)', context),
        ),
    );
}

test('a stored part yields its shown parts in the known order and the rows of every section', () => {
    assert.deepEqual(
        read({
            parts: ['units', 'description'],
            rows: { units: [{ name: 'Alpha', frequency: 123.45, highValueUnit: true }] },
        }),
        {
            parts: ['description', 'units'],
            rows: {
                objectives: [],
                units: [{ name: 'Alpha', frequency: 123.45, highValueUnit: true }],
            },
        },
    );
});

test('an unknown part is dropped', () => {
    assert.deepEqual(read({ parts: ['schedule', 'weather', 7] }).parts, ['schedule']);
});

test('anything else yields no shown part and empty sections', () => {
    const empty = { parts: [], rows: { objectives: [], units: [] } };
    assert.deepEqual(read(null), empty);
    assert.deepEqual(read({ parts: 'all', rows: 'many' }), empty);
});

test('the form key names the create editor', () => {
    const context = {};
    vm.createContext(context);
    vm.runInContext(SOURCE, context);
    assert.equal(vm.runInContext('TEMPLATE_DRAFT_KEY', context), 'mission-template-create');
});

if (failures > 0) {
    console.error(`${failures} failed`);
    process.exit(1);
}

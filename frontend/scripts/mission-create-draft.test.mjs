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
 * Regression tests for reading the part of a mission create draft the page keeps itself
 * (REQ-FE-041): the template link and the planning rows, read defensively in a vm context.
 */

import assert from 'node:assert/strict';
import vm from 'node:vm';
import { flattenModule } from './flatten-module.mjs';

const SOURCE = flattenModule('mission/mission-create-draft.js');
const SECTIONS = ['objective', 'step', 'unit', 'frequency'];

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
    const context = { extra, sections: SECTIONS };
    vm.createContext(context);
    vm.runInContext(SOURCE, context);
    return JSON.parse(
        JSON.stringify(vm.runInContext('readMissionCreateDraft(extra, sections)', context)),
    );
}

test('a stored part yields its template link and the rows of every section', () => {
    assert.deepEqual(
        read({
            templateId: 'tpl-1',
            templateVersion: '4',
            rows: {
                objective: [{ title: 'Hold the relay', kind: 'PRIMARY' }],
                unit: [{ name: 'Alpha', frequency: 123.45, highValueUnit: true, note: null }],
            },
        }),
        {
            templateId: 'tpl-1',
            templateVersion: '4',
            rows: {
                objective: [{ title: 'Hold the relay', kind: 'PRIMARY' }],
                step: [],
                unit: [{ name: 'Alpha', frequency: 123.45, highValueUnit: true, note: null }],
                frequency: [],
            },
        },
    );
});

test('anything else yields no template link and empty sections', () => {
    const empty = {
        templateId: '',
        templateVersion: '',
        rows: { objective: [], step: [], unit: [], frequency: [] },
    };
    assert.deepEqual(read(null), empty);
    assert.deepEqual(read({ templateId: 7, rows: 'many' }), empty);
});

test('a stored row keeps only plain field values', () => {
    const draft = read({
        rows: { step: [{ title: 'Muster', meta: { at: 'Olisar' }, order: 2 }, 'junk'] },
    });
    assert.deepEqual(draft.rows.step, [{ title: 'Muster', order: 2 }, {}]);
});

if (failures > 0) {
    console.error(`${failures} failed`);
    process.exit(1);
}

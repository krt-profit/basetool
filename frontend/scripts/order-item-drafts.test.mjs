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
 * Regression tests for the item lines of a job-order create draft (REQ-FE-041): capturing the
 * lines with their parent positions, reading a stored draft defensively and rebuilding the lines
 * with fresh client line ids, in a vm context over fake line elements.
 */

import assert from 'node:assert/strict';
import vm from 'node:vm';
import { flattenModule } from './flatten-module.mjs';

const SOURCE = flattenModule('joborder/order-item-drafts.js');

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

/** An element that answers the selectors the module asks from fixed parts and lists. */
class FakeElement {
    constructor(parts = {}, lists = {}) {
        this.parts = parts;
        this.lists = lists;
        this.dataset = {};
    }

    querySelector(selector) {
        return this.parts[selector] || null;
    }

    querySelectorAll(selector) {
        return this.lists[selector] || [];
    }
}

/** An input with a value. */
class FakeInput extends FakeElement {
    constructor(value) {
        super();
        this.value = value;
    }
}

/** A select with a value. */
class FakeSelect extends FakeElement {
    constructor(value) {
        super();
        this.value = value;
    }
}

/**
 * Builds an item line as the create form renders it.
 *
 * @param {number} lineIndex the line's client line id
 * @param {object} [values] the item, its label, blueprint, amount, parent client id and qualities
 */
function line(lineIndex, values = {}) {
    const {
        item = '',
        label = '',
        blueprint = '',
        amount = '1',
        parent = '',
        qualities = {},
    } = values;
    const picker = new FakeInput(item);
    picker.krtCombobox = { selection: () => ({ value: item, label, data: {} }) };
    const materials = Object.entries(qualities).map(
        ([materialId, code]) =>
            new FakeElement({
                'input[name$=".materialId"]': new FakeInput(materialId),
                'select[name$=".quality"]': new FakeSelect(code),
            }),
    );
    const row = new FakeElement(
        {
            '[data-role="item-select"]': picker,
            'input[name$=".parentClientLineId"]': new FakeInput(parent),
            '[data-role="blueprint-select"]': new FakeSelect(blueprint),
            '[data-role="amount"]': new FakeInput(amount),
        },
        { '.oc-material-line': materials },
    );
    row.dataset.lineIndex = String(lineIndex);
    return row;
}

/** Loads the module into a fresh context with the fake element classes. */
function load() {
    const context = {
        HTMLElement: FakeElement,
        HTMLInputElement: FakeInput,
        HTMLSelectElement: FakeSelect,
    };
    vm.createContext(context);
    vm.runInContext(SOURCE, context);
    return context;
}

/** Copies a value out of the vm realm, so deep equality compares plain data. */
function plain(value) {
    return JSON.parse(JSON.stringify(value));
}

test('the lines are captured in order with the position of their parent line', () => {
    const m = load();
    const container = new FakeElement(
        {},
        {
            '.item-line': [
                line(3, {
                    item: 'widget',
                    label: 'E2E Widget',
                    blueprint: 'bp-1',
                    amount: '2',
                    qualities: { 'mat-1': 'Q700', 'mat-2': '' },
                }),
                line(7, { item: 'gear', label: 'Gear', amount: '4', parent: '3' }),
                line(9, { parent: '42' }),
            ],
        },
    );
    assert.deepEqual(plain(m.itemLineDrafts(container)), [
        {
            gameItemId: 'widget',
            gameItemName: 'E2E Widget',
            blueprintId: 'bp-1',
            amount: '2',
            parent: null,
            qualities: { 'mat-1': 'Q700' },
        },
        {
            gameItemId: 'gear',
            gameItemName: 'Gear',
            blueprintId: '',
            amount: '4',
            parent: 0,
            qualities: {},
        },
        {
            gameItemId: '',
            gameItemName: '',
            blueprintId: '',
            amount: '1',
            parent: null,
            qualities: {},
        },
    ]);
});

test('a stored draft is read defensively', () => {
    const m = load();
    assert.deepEqual(plain(m.readItemLineDrafts(null)), []);
    assert.deepEqual(plain(m.readItemLineDrafts({ lines: 'many' })), []);
    assert.deepEqual(
        plain(
            m.readItemLineDrafts({
                lines: [
                    { gameItemId: 'widget', amount: 3, parent: '0', qualities: { a: 'Q1', b: 7 } },
                    null,
                ],
            }),
        ),
        [
            {
                gameItemId: 'widget',
                gameItemName: '',
                blueprintId: '',
                amount: '',
                parent: null,
                qualities: { a: 'Q1' },
            },
            {
                gameItemId: '',
                gameItemName: '',
                blueprintId: '',
                amount: '',
                parent: null,
                qualities: {},
            },
        ],
    );
});

test('a restore rebuilds the lines in order and links each to its parent’s new client id', () => {
    const m = load();
    const prefills = [];
    let next = 10;
    const addLine = (prefill) => {
        prefills.push(plain(prefill));
        const row = new FakeElement();
        row.dataset.lineIndex = String(next++);
        return row;
    };
    m.restoreItemLines(
        [
            {
                gameItemId: 'widget',
                gameItemName: 'E2E Widget',
                blueprintId: 'bp-1',
                amount: '2',
                parent: null,
                qualities: { 'mat-1': 'Q700' },
            },
            {
                gameItemId: 'gear',
                gameItemName: 'Gear',
                blueprintId: '',
                amount: '4',
                parent: 0,
                qualities: {},
            },
            {
                gameItemId: '',
                gameItemName: '',
                blueprintId: '',
                amount: '',
                parent: 5,
                qualities: {},
            },
        ],
        addLine,
    );
    assert.deepEqual(prefills, [
        {
            gameItemId: 'widget',
            gameItemName: 'E2E Widget',
            amount: '2',
            blueprintId: 'bp-1',
            qualities: { 'mat-1': 'Q700' },
        },
        { gameItemId: 'gear', gameItemName: 'Gear', amount: '4', qualities: {}, parentId: '10' },
        { qualities: {} },
    ]);
});

if (failures > 0) {
    console.error(`${failures} failed`);
    process.exit(1);
}

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
 * Regression tests for the hangar dialogs' ship-type picker (REQ-P4K-022): the manufacturer filter,
 * the manufacturer mark and the choice of a ship type the filter had hidden.
 */

import assert from 'node:assert/strict';
import vm from 'node:vm';
import { flattenModule } from './flatten-module.mjs';

/** A ship-type option carrying its manufacturer. */
class FakeOption {
    /**
     * @param {string} value the ship-type id, empty for the empty choice
     * @param {string | null} manufacturerId the manufacturer of the type
     */
    constructor(value, manufacturerId) {
        this.value = value;
        this.manufacturerId = manufacturerId;
    }

    getAttribute(name) {
        return name === 'data-manufacturer-id' ? this.manufacturerId : null;
    }
}

/** A select that, like the browser's, holds only a value one of its current options carries. */
class FakeSelect {
    /** @param {FakeOption[]} options the options */
    constructor(options) {
        this.children = options;
        this.chosen = '';
        this.listeners = {};
    }

    get options() {
        return this.children;
    }

    get value() {
        return this.chosen;
    }

    set value(v) {
        this.chosen = this.children.some((o) => o.value === v) ? v : '';
    }

    get selectedOptions() {
        const option = this.children.find((o) => o.value === this.chosen);
        return option ? [option] : [];
    }

    replaceChildren(...options) {
        this.children = options;
        if (!options.some((o) => o.value === this.chosen)) this.chosen = '';
    }

    addEventListener(type, listener) {
        this.listeners[type] = listener;
    }

    fire(type) {
        this.listeners[type]();
    }
}

/** A server-rendered manufacturer mark. */
class FakeMark {
    /** @param {string} id the manufacturer id */
    constructor(id) {
        this.id = id;
        this.hidden = true;
    }

    getAttribute() {
        return this.id;
    }
}

/** Builds a picker over two manufacturers with one ship type each. */
function picker() {
    const manufacturer = new FakeSelect([
        new FakeOption('', null),
        new FakeOption('m-1', null),
        new FakeOption('m-2', null),
    ]);
    const shipType = new FakeSelect([
        new FakeOption('', null),
        new FakeOption('t-1', 'm-1'),
        new FakeOption('t-2', 'm-2'),
    ]);
    const marks = [new FakeMark('m-1'), new FakeMark('m-2')];
    const preview = { querySelectorAll: () => marks };
    const context = vm.createContext({});
    vm.runInContext(flattenModule('hangar/ship-type-picker.js'), context);
    const choose = vm.runInContext('bindShipTypePicker', context)(manufacturer, shipType, preview);
    const shown = () => marks.filter((m) => !m.hidden).map((m) => m.id);
    return { manufacturer, shipType, choose, shown };
}

let failures = 0;

/**
 * Runs one named case, recording rather than throwing so every case reports.
 *
 * @param {string} name the case
 * @param {() => void} fn the case body
 */
function test(name, fn) {
    try {
        fn();
        console.log(`ok - ${name}`);
    } catch (e) {
        failures += 1;
        console.error(`not ok - ${name}\n${e.stack}`);
    }
}

test('the manufacturer filter narrows the ship types and shows its mark', () => {
    const p = picker();
    p.manufacturer.value = 'm-2';
    p.manufacturer.fire('change');
    assert.deepEqual(
        p.shipType.options.map((o) => o.value),
        ['', 't-2'],
    );
    assert.deepEqual(p.shown(), ['m-2']);
});

test("the edit dialog selects and marks the ship's type although the filter hid it", () => {
    const p = picker();
    p.manufacturer.value = 'm-2';
    p.manufacturer.fire('change');
    p.choose('t-1');
    assert.equal(p.manufacturer.value, '', 'the filter is cleared');
    assert.equal(p.shipType.value, 't-1', "the ship's own type is selected");
    assert.deepEqual(p.shown(), ['m-1'], "the mark is the type's manufacturer");
});

test('the add dialog starts without a type and without a mark', () => {
    const p = picker();
    p.choose('t-2');
    p.choose('');
    assert.equal(p.shipType.value, '');
    assert.deepEqual(p.shown(), []);
});

if (failures > 0) {
    console.error(`${failures} ship-type picker test(s) failed`);
    process.exit(1);
}

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
 * Regression tests for the shared sortable table headers: the value order, the click cycle, and
 * the row order and `aria-sort` marks a sorter writes into a table (REQ-UI-029).
 */

import assert from 'node:assert/strict';
import vm from 'node:vm';
import { flattenModule } from './flatten-module.mjs';

/** A minimal element with attributes, children and click listeners. */
class FakeElement {
    constructor(tag, attrs = {}) {
        this.tag = tag;
        this.attrs = { ...attrs };
        this.children = [];
        this.parent = null;
        this.listeners = [];
    }

    getAttribute(name) {
        return Object.hasOwn(this.attrs, name) ? this.attrs[name] : null;
    }

    setAttribute(name, value) {
        this.attrs[name] = String(value);
    }

    appendChild(child) {
        if (child.parent) {
            child.parent.children = child.parent.children.filter((c) => c !== child);
        }
        child.parent = this;
        this.children.push(child);
        return child;
    }

    matches(selector) {
        const match = /^(\w+)\[([\w-]+)\]$/.exec(selector);
        return match !== null && this.tag === match[1] && this.getAttribute(match[2]) !== null;
    }

    closest(selector) {
        for (let el = this; el; el = el.parent) {
            if (el.matches(selector)) return el;
        }
        return null;
    }

    contains(other) {
        for (let el = other; el; el = el.parent) {
            if (el === this) return true;
        }
        return false;
    }

    querySelectorAll(selector) {
        const found = [];
        const walk = (el) => {
            for (const child of el.children) {
                if (child.matches(selector)) found.push(child);
                walk(child);
            }
        };
        walk(this);
        return found;
    }

    addEventListener(type, listener) {
        if (type === 'click') this.listeners.push(listener);
    }

    get rows() {
        return this.children;
    }
}

const context = vm.createContext({ Element: FakeElement });
vm.runInContext(flattenModule('kernel/krt-table-sort.js'), context);
const compareSortValues = vm.runInContext('compareSortValues', context);
const nextSortState = vm.runInContext('nextSortState', context);
const krtTableSort = vm.runInContext('krtTableSort', context);

/**
 * Builds a table with one sortable header per column and one body row per record.
 *
 * @param {Array<{key: string, first?: string}>} columns the sortable columns
 * @param {Array<Record<string, string>>} records the rows' data attributes
 */
function buildTable(columns, records) {
    const table = new FakeElement('table');
    const head = table.appendChild(new FakeElement('thead'));
    const headRow = head.appendChild(new FakeElement('tr'));
    const buttons = {};
    for (const column of columns) {
        const th = headRow.appendChild(
            new FakeElement('th', { 'data-sort-col': column.key, 'aria-sort': 'none' }),
        );
        const attrs = { 'data-sort-key': column.key };
        if (column.first) attrs['data-sort-first'] = column.first;
        buttons[column.key] = th.appendChild(new FakeElement('button', attrs));
    }
    const body = table.appendChild(new FakeElement('tbody'));
    for (const record of records) {
        body.appendChild(new FakeElement('tr', record));
    }
    table.tBodies = [body];
    const click = (key) => {
        for (const listener of table.listeners) listener({ target: buttons[key] });
    };
    const order = () => body.children.map((row) => row.getAttribute('data-id')).join(',');
    const marks = () =>
        headRow.children
            .map((th) => `${th.getAttribute('data-sort-col')}:${th.getAttribute('aria-sort')}`)
            .join(' ');
    return { table, click, order, marks };
}

/**
 * A row's value: the name as text, every other column as a number or null.
 *
 * @param {FakeElement} row the row
 * @param {string} key the column
 */
function valueOf(row, key) {
    const raw = row.getAttribute(`data-${key}`);
    if (key === 'name') return raw || '';
    return raw === null || raw === '' ? null : Number(raw);
}

/** The original order, kept on the row as its rank. */
function byRank(a, b) {
    return Number(a.getAttribute('data-rank')) - Number(b.getAttribute('data-rank'));
}

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

test('numbers sort by value, text by locale ignoring case', () => {
    assert.ok(compareSortValues(2, 10, 'asc') < 0);
    assert.ok(compareSortValues(2, 10, 'desc') > 0);
    assert.ok(compareSortValues('alpha', 'Beta', 'asc') < 0);
    assert.equal(compareSortValues('Agricium', 'agricium', 'asc'), 0);
});

test('a missing or non-finite value sorts last in both directions', () => {
    for (const dir of ['asc', 'desc']) {
        assert.ok(compareSortValues(null, 5, dir) > 0, dir);
        assert.ok(compareSortValues(5, null, dir) < 0, dir);
        assert.ok(compareSortValues(Number.NaN, 5, dir) > 0, dir);
        assert.equal(compareSortValues(null, null, dir), 0, dir);
    }
});

test('a click reverses the active column and starts another in its first direction', () => {
    const state = { key: 'profit', dir: 'desc' };
    assert.deepEqual({ ...nextSortState(state, 'profit', 'desc') }, { key: 'profit', dir: 'asc' });
    assert.deepEqual({ ...nextSortState(state, 'name', 'asc') }, { key: 'name', dir: 'asc' });
    assert.deepEqual({ ...nextSortState(state, 'margin', 'desc') }, { key: 'margin', dir: 'desc' });
});

const COLUMNS = [{ key: 'name' }, { key: 'profit', first: 'desc' }];
const RECORDS = [
    { 'data-id': 'b', 'data-rank': '2', 'data-name': 'Beryl', 'data-profit': '50' },
    { 'data-id': 'n', 'data-rank': '4', 'data-name': 'Nichts', 'data-profit': '' },
    { 'data-id': 'a', 'data-rank': '1', 'data-name': 'agricium', 'data-profit': '90' },
    { 'data-id': 'c', 'data-rank': '3', 'data-name': 'Corundum', 'data-profit': '50' },
];

test('attaching applies the initial order and marks only its header', () => {
    const t = buildTable(COLUMNS, RECORDS);
    krtTableSort.attach(t.table, {
        initial: { key: 'profit', dir: 'desc' },
        valueOf,
        tiebreak: byRank,
    });
    assert.equal(t.order(), 'a,b,c,n');
    assert.equal(t.marks(), 'name:none profit:descending');
});

test('header clicks reorder in place, keep missing values last and report the change', () => {
    const t = buildTable(COLUMNS, RECORDS);
    const seen = [];
    krtTableSort.attach(t.table, {
        initial: { key: 'profit', dir: 'desc' },
        valueOf,
        tiebreak: byRank,
        onChange: (state) => seen.push(`${state.key}:${state.dir}`),
    });
    t.click('profit');
    assert.equal(t.order(), 'b,c,a,n');
    assert.equal(t.marks(), 'name:none profit:ascending');
    t.click('name');
    assert.equal(t.order(), 'a,b,c,n');
    assert.equal(t.marks(), 'name:ascending profit:none');
    t.click('name');
    assert.equal(t.order(), 'n,c,b,a');
    assert.equal(t.marks(), 'name:descending profit:none');
    assert.deepEqual(seen, ['profit:asc', 'name:asc', 'name:desc']);
});

test('apply re-sorts new rows in the chosen order', () => {
    const t = buildTable(COLUMNS, RECORDS);
    const sorter = krtTableSort.attach(t.table, {
        initial: { key: 'profit', dir: 'desc' },
        valueOf,
        tiebreak: byRank,
    });
    t.click('name');
    const body = t.table.tBodies[0];
    body.children = [];
    for (const record of [RECORDS[2], RECORDS[0]].reverse()) {
        body.appendChild(new FakeElement('tr', { ...record, 'data-id': `${record['data-id']}2` }));
    }
    sorter.apply();
    assert.equal(t.order(), 'a2,b2');
    assert.deepEqual({ ...sorter.state() }, { key: 'name', dir: 'asc' });
});

test('set adopts a stored order only when it names a sortable column', () => {
    const t = buildTable(COLUMNS, RECORDS);
    const sorter = krtTableSort.attach(t.table, {
        initial: { key: 'profit', dir: 'desc' },
        valueOf,
        tiebreak: byRank,
    });
    for (const stale of [null, 'name', { key: 'route', dir: 'asc' }, { key: 'name', dir: 'up' }]) {
        assert.equal(sorter.set(stale), false, JSON.stringify(stale));
    }
    assert.equal(t.order(), 'a,b,c,n');
    assert.equal(sorter.set({ key: 'name', dir: 'desc' }), true);
    assert.equal(t.order(), 'n,c,b,a');
    assert.equal(t.marks(), 'name:descending profit:none');
});

test('a click outside a sort button changes nothing', () => {
    const t = buildTable(COLUMNS, RECORDS);
    const seen = [];
    krtTableSort.attach(t.table, {
        initial: { key: 'profit', dir: 'desc' },
        valueOf,
        tiebreak: byRank,
        onChange: () => seen.push('change'),
    });
    for (const listener of t.table.listeners) listener({ target: t.table.tBodies[0].children[0] });
    for (const listener of t.table.listeners) listener({ target: null });
    assert.equal(t.order(), 'a,b,c,n');
    assert.deepEqual(seen, []);
});

if (failures > 0) {
    console.error(`${failures} test(s) failed`);
    process.exit(1);
}

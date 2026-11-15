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
 * Regression tests for the order money editors' Bank account choice (REQ-ORDERS-067): an editor that
 * opens suggests the responsible unit's first account, also when a live refresh of the money
 * section reloads the accounts before the opening read answers, and never replaces a choice the
 * member made.
 */

import assert from 'node:assert/strict';
import vm from 'node:vm';
import { flattenModule } from './flatten-module.mjs';

const STUBS = {
    '/js/kernel/krt-fetch.js': 'const krtFetch = { getJson(url) { return __bank.read(url); } };',
    '/js/joborder/orders-money-holders.js': [
        'function setMoneyOptions(select, rows) { select.setOptions(rows.map(({ id }) => id)); }',
        'function bindMoneyPartyChoice() { return {}; }',
        'function orderMoneyProjection() { return null; }',
    ].join('\n'),
    '/js/joborder/orders-money-movement-parties.js': 'function showMovementField() {}',
};

/** A select that, like the browser's, keeps a value only when one of its options carries it. */
class FakeSelect {
    constructor() {
        this.options = [''];
        this.current = '';
        this.listeners = {};
    }

    get value() {
        return this.current;
    }

    set value(value) {
        this.current = this.options.includes(value) ? value : '';
    }

    /** @param {string[]} ids the account options after the placeholder */
    setOptions(ids) {
        const kept = this.current;
        this.options = ['', ...ids];
        this.value = kept;
    }

    closest() {
        return null;
    }

    addEventListener(type, fn) {
        (this.listeners[type] ||= []).push(fn);
    }

    /** @param {string} value the option a member picks */
    pick(value) {
        this.value = value;
        (this.listeners.change || []).forEach((fn) => fn());
    }
}

/**
 * Loads the module over a fake transport whose reads answer only when the test settles them.
 *
 * @returns {object} the bound account choice, its select and the pending reads
 */
function load() {
    /** @type {{ url: string, settle: (accounts: object) => void }[]} */
    const reads = [];
    const context = vm.createContext({
        __bank: {
            read: (url) =>
                new Promise((resolve) => {
                    reads.push({ url, settle: resolve });
                }),
        },
        HTMLElement: class {},
        HTMLSelectElement: FakeSelect,
        Error,
    });
    vm.runInContext(flattenModule('joborder/orders-money-bank.js', STUBS), context);
    const select = new FakeSelect();
    const choice = context.bindBankAccounts(
        '/orders/o-1/money',
        select,
        () => 'collection',
        () => {},
    );
    return { choice, select, reads };
}

/** The accounts the order's money route offers: the responsible unit's first, then another. */
const ACCOUNTS = {
    collection: [
        { id: 'unit-account', name: 'Unit' },
        { id: 'other', name: 'Other' },
    ],
};

let failures = 0;

/** Runs one named case, recording rather than throwing so every case reports. */
async function test(name, fn) {
    try {
        await fn();
        console.log(`ok - ${name}`);
    } catch (e) {
        failures += 1;
        console.error(`not ok - ${name}\n${e.stack}`);
    }
}

await test('an opened editor suggests the first account', async () => {
    const { choice, select, reads } = load();
    const opened = choice.load('', true);
    reads[0].settle(ACCOUNTS);
    await opened;

    assert.equal(select.value, 'unit-account');
});

await test('a live refresh during the opening read keeps the suggestion', async () => {
    const { choice, select, reads } = load();
    const opened = choice.load('', true);
    const refreshed = choice.load();
    reads[0].settle(ACCOUNTS);
    reads[1].settle(ACCOUNTS);
    await Promise.all([opened, refreshed]);

    assert.equal(select.value, 'unit-account');
});

await test('a live refresh after the opening read keeps the member choice', async () => {
    const { choice, select, reads } = load();
    const opened = choice.load('', true);
    reads[0].settle(ACCOUNTS);
    await opened;
    select.pick('other');

    const refreshed = choice.load();
    reads[1].settle(ACCOUNTS);
    await refreshed;

    assert.equal(select.value, 'other');
});

await test('a live refresh after the member cleared the choice suggests nothing', async () => {
    const { choice, select, reads } = load();
    const opened = choice.load('', true);
    reads[0].settle(ACCOUNTS);
    await opened;
    select.pick('');

    const refreshed = choice.load();
    reads[1].settle(ACCOUNTS);
    await refreshed;

    assert.equal(select.value, '');
});

if (failures) {
    process.exitCode = 1;
} else {
    console.log('orders-money-bank tests passed');
}

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
 * Regression tests for the production modal's book-in org-unit picker (REQ-ORDERS-025): the page
 * script runs in a vm context over a fake modal, so the preselection, the required empty choice,
 * the dropped late answer and the booking gate are proven at the script's own seam.
 */

import assert from 'node:assert/strict';
import vm from 'node:vm';
import { flattenModule, UI_STUBS } from './flatten-module.mjs';

const STUBS = {
    '/js/kernel/krt-modal.js': UI_STUBS['/js/kernel/krt-modal.js'],
    '/js/kernel/toast.js': [
        'function showFrontendErrorToast(message) { __net.errors.push(message); }',
        'function showFrontendSuccessToast() {}',
    ].join('\n'),
    '/js/kernel/krt-number-format.js': 'function formatNumber(value) { return String(value); }',
    '/js/kernel/krt-fetch.js': 'const krtFetch = { getJson: (url) => __net.getJson(url) };',
    '/js/kernel/krt-page-sections.js': 'const krtPageSections = { publish() {} };',
    '/js/kernel/krt-html.js': 'const krtHtml = { set() {} };',
    '/js/joborder/orders-detail-edit.js':
        'function krtOrderWrite(opts) { __net.writes.push(opts); }',
};

/** An element read through its attributes: the picker's wrapper, the form, the line's button. */
class FakeElement {
    /** @param {Record<string, string>} attrs the element's attributes */
    constructor(attrs) {
        this.attrs = new Map(Object.entries(attrs));
        this.hidden = false;
        this.disabled = false;
    }

    getAttribute(name) {
        return this.attrs.has(name) ? this.attrs.get(name) : null;
    }

    closest() {
        return null;
    }
}

/** An option of the org-unit picker. */
class FakeOption {
    constructor() {
        this.value = '';
        this.textContent = '';
    }
}

/** A single-choice select that, like the browser's, selects the first option it receives. */
class FakeSelect {
    constructor() {
        this.options = [];
        this.selectedIndex = -1;
        this.required = false;
    }

    get value() {
        return this.selectedIndex < 0 ? '' : this.options[this.selectedIndex].value;
    }

    set value(v) {
        this.selectedIndex = this.options.findIndex((o) => o.value === v);
    }

    replaceChildren() {
        this.options = [];
        this.selectedIndex = -1;
    }

    appendChild(option) {
        this.options.push(option);
        if (this.selectedIndex < 0) {
            this.selectedIndex = 0;
        }
        return option;
    }
}

/**
 * Loads the page script over a fresh fake production modal whose default owner is the acting user.
 *
 * @param {{memberships?: string[], membershipsByOwner?: Record<string, string[]>, active?: string,
 *     actor?: string, deferred?: boolean}} setup the direct memberships every owner has, or those
 *     of each owner; the page's active org unit; the acting user, empty when the page could not
 *     name one; and whether each memberships read waits until the test answers it
 * @returns {object} the harness
 */
function load({ memberships = [], membershipsByOwner, active, actor = 'actor', deferred = false }) {
    const net = {
        urls: [],
        writes: [],
        errors: [],
        answers: [],
        getJson(url) {
            this.urls.push(url);
            const owner = decodeURIComponent(url.split('/')[2]);
            const ids = membershipsByOwner ? membershipsByOwner[owner] || [] : memberships;
            const body = ids.map((id) => ({ orgUnitId: id, orgUnitName: `Unit ${id}` }));
            if (!deferred) {
                return Promise.resolve(body);
            }
            return new Promise((resolve) => {
                this.answers.push(() => resolve(body));
            });
        },
    };
    const wrapper = new FakeElement(active ? { 'data-owner-picker-active': active } : {});
    wrapper.hidden = true;
    const form = new FakeElement({
        'data-acting-user-id': actor,
        'data-acting-user-name': 'Actor',
    });
    const select = new FakeSelect();
    const owner = { value: '' };
    const location = { value: '' };
    const bookButton = new FakeElement({});
    const elements = new Map([
        ['production-form', form],
        ['production-orgunit-wrapper', wrapper],
        ['production-orgunit', select],
        ['production-owner', owner],
        ['production-location', location],
        ['production-amount', { value: '' }],
        ['production-book-btn', bookButton],
    ]);
    const context = vm.createContext({
        __net: net,
        PRODUCTION_I18N: { orgUnitPlaceholder: '-- Bitte wählen --' },
        document: {
            getElementById: (id) => elements.get(id) || null,
            createElement: () => new FakeOption(),
            querySelector: () => null,
            addEventListener() {},
        },
    });
    vm.runInContext(flattenModule('joborder/orders-detail-production.js', STUBS), context);
    const line = new FakeElement({
        'data-order-id': 'order',
        'data-item-id': 'item',
        'data-amount': '3',
        'data-manufactured': '0',
        'data-version': '4',
    });
    return {
        net,
        wrapper,
        select,
        owner,
        location,
        bookButton,
        refresh: vm.runInContext('_prodRefreshOrgUnitPicker', context),
        open: () => vm.runInContext('openProductionModal', context)(line),
        reconcile: vm.runInContext('_prodReconcile', context),
        book: vm.runInContext('bookProduction', context),
    };
}

/**
 * Lets the memberships read and the picker's render settle.
 *
 * @returns {Promise<void>} settles once the pending promise callbacks ran
 */
function settle() {
    return new Promise((resolve) => setImmediate(resolve));
}

/**
 * Loads the page script and lets the acting user's org-unit picker render.
 *
 * @param {{memberships?: string[], active?: string}} setup as for `load`
 * @returns {Promise<object>} the harness after the render
 */
async function picker(setup) {
    const harness = load(setup);
    harness.refresh();
    await settle();
    return harness;
}

/**
 * Lists the values of a picker's options in order.
 *
 * @param {FakeSelect} select the picker
 * @returns {string[]} the option values, the empty choice as an empty string
 */
function values(select) {
    return select.options.map((o) => o.value);
}

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

await test('the active org unit is preselected when the owner is a direct member of it', async () => {
    const h = await picker({ memberships: ['a', 'b', 'r'], active: 'b' });
    assert.deepEqual(h.net.urls, ['/users/actor/memberships?allKinds=true']);
    assert.deepEqual(values(h.select), ['a', 'b', 'r'], 'no empty choice');
    assert.equal(h.select.value, 'b');
    assert.equal(h.select.required, false);
    assert.equal(h.wrapper.hidden, false);
});

await test('without an active org unit a member of several units has to choose', async () => {
    const h = await picker({ memberships: ['a', 'r'] });
    assert.deepEqual(values(h.select), ['', 'a', 'r'], 'the empty choice leads');
    assert.equal(h.select.value, '');
    assert.equal(h.select.required, true);
});

await test('the only membership is preselected when the active unit is none of them', async () => {
    const h = await picker({ memberships: ['a'], active: 'x' });
    assert.deepEqual(values(h.select), ['a']);
    assert.equal(h.select.value, 'a');
    assert.equal(h.select.required, false);
});

await test('several memberships without the active unit among them ask for a choice', async () => {
    const h = await picker({ memberships: ['a', 'd'], active: 'x' });
    assert.deepEqual(values(h.select), ['', 'a', 'd'], 'the empty choice leads');
    assert.equal(h.select.options[0].textContent, '-- Bitte wählen --');
    assert.equal(h.select.value, '', 'the first unit is not chosen silently');
    assert.equal(h.select.required, true);
    assert.equal(h.wrapper.hidden, false);
});

await test('an owner without a membership gets no picker', async () => {
    const h = await picker({ memberships: [] });
    assert.equal(h.wrapper.hidden, true);
    assert.deepEqual(values(h.select), []);
});

await test('the booking waits for the required choice and posts the chosen unit', async () => {
    const h = load({ memberships: ['a', 'd'], active: 'x' });
    h.open();
    await settle();
    h.location.value = 'loc';
    h.reconcile();
    assert.equal(h.bookButton.disabled, true, 'no org unit chosen yet');
    h.select.value = 'd';
    h.reconcile();
    assert.equal(h.bookButton.disabled, false);
    h.book();
    assert.deepEqual(h.net.errors, []);
    assert.equal(h.net.writes.length, 1);
    assert.equal(h.net.writes[0].payload.bookIn.owningOrgUnitId, 'd');
});

await test('the booking gate re-runs once the memberships have loaded', async () => {
    const h = load({ memberships: ['a', 'd'], active: 'x' });
    h.open();
    h.location.value = 'loc';
    h.reconcile();
    await settle();
    assert.equal(h.bookButton.disabled, true, 'the loaded picker asks for a choice');
});

await test('the booking gate stays shut while the memberships load', async () => {
    const h = load({ memberships: ['a'], deferred: true });
    h.open();
    h.location.value = 'loc';
    h.reconcile();
    assert.equal(h.bookButton.disabled, true, "the owner's units are still loading");
    h.net.answers[0]();
    await settle();
    assert.equal(h.bookButton.disabled, false);
    h.book();
    assert.equal(h.net.writes[0].payload.bookIn.owningOrgUnitId, 'a');
});

await test('a late answer for an earlier owner is dropped', async () => {
    const h = load({
        membershipsByOwner: { actor: ['a1'], member: ['m1', 'm2'] },
        deferred: true,
    });
    h.refresh();
    h.owner.value = 'member';
    h.refresh();
    h.net.answers[1]();
    await settle();
    h.net.answers[0]();
    await settle();
    assert.deepEqual(values(h.select), ['', 'm1', 'm2'], "only the member's units are offered");
    assert.equal(h.select.required, true);
});

await test('an owner cleared without an acting user hides the picker and re-runs the gate', async () => {
    const h = load({ memberships: ['a', 'd'], actor: '' });
    h.open();
    h.owner.value = 'member';
    h.refresh();
    await settle();
    assert.deepEqual(h.net.urls, ['/users/member/memberships?allKinds=true']);
    h.location.value = 'loc';
    h.reconcile();
    assert.equal(h.bookButton.disabled, true, 'the member has to choose');
    h.owner.value = '';
    h.refresh();
    assert.equal(h.wrapper.hidden, true);
    assert.equal(h.bookButton.disabled, false, 'no picker is left to wait for');
});

await test('the preselected active org unit is posted as the explicit pick', async () => {
    const h = load({ memberships: ['a', 'b'], active: 'b' });
    h.open();
    await settle();
    assert.equal(h.bookButton.disabled, true, 'no storage location yet');
    h.location.value = 'loc';
    h.reconcile();
    assert.equal(h.bookButton.disabled, false);
    h.book();
    assert.deepEqual(h.net.errors, []);
    assert.equal(h.net.writes[0].payload.bookIn.owningOrgUnitId, 'b');
});

if (failures) {
    process.exitCode = 1;
} else {
    console.log('orders-detail-production tests passed');
}

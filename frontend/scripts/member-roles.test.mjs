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
 * Regression tests for the role tab of the member page (REQ-SEC-088): the page script runs in a vm
 * context over a fake panel, with the page-section seam and the confirm dialog recorded, so a
 * toggle's confirm, write, revert and refusal message are proven at the script's own seam.
 */

import assert from 'node:assert/strict';
import vm from 'node:vm';
import { flattenModule } from './flatten-module.mjs';

const STUBS = {
    '/js/kernel/krt-page-sections.js': [
        'const krtPageSections = {',
        '  create(cfg) { __ui.created.push(cfg); return { write(opts) { __ui.writes.push(opts); }, notify() {} }; },',
        '  publish(topic, keys) { __ui.published.push({ topic, keys }); },',
        '};',
    ].join('\n'),
    '/js/kernel/toast.js': [
        'function showFrontendErrorToast(m) { __ui.errors.push(m); }',
        'function showKrtConfirm(title, message, confirmLabel) {',
        '  __ui.confirms.push({ title, message, confirmLabel });',
        '  return Promise.resolve(__ui.answer);',
        '}',
    ].join('\n'),
};

class FakeElement {
    constructor(attrs = {}) {
        this.attrs = new Map(Object.entries(attrs));
    }

    getAttribute(name) {
        return this.attrs.has(name) ? this.attrs.get(name) : null;
    }

    setAttribute(name, value) {
        this.attrs.set(name, String(value));
    }

    hasAttribute(name) {
        return this.attrs.has(name);
    }
}

class FakeInput extends FakeElement {
    constructor(value, checked, attrs = {}) {
        super({ 'data-role-toggle': 'true', ...attrs });
        this.value = value;
        this.checked = checked;
    }
}

/**
 * Loads the page script over a fresh fake role tab.
 *
 * @returns {{ ui: object, toggles: Record<string, FakeInput>, change: Function }} the harness
 */
function load() {
    const toggles = {
        ADMIN: new FakeInput('ADMIN', true, { 'data-withdraw-confirm': 'own admin' }),
        KRT_MEMBER: new FakeInput('KRT_MEMBER', true, { 'data-withdraw-confirm': 'departs' }),
        BANK_EMPLOYEE: new FakeInput('BANK_EMPLOYEE', true),
    };
    const roleSet = new FakeElement({ 'data-member-role-set': 'true', 'data-roles-version': '4' });
    roleSet.querySelectorAll = () => Object.values(toggles);
    const listeners = [];
    const panel = new FakeElement({
        'data-member-roles': 'true',
        'data-user-id': 'member-1',
        'data-confirm-title': 'Withdraw role?',
        'data-confirm-label': 'Withdraw',
    });
    panel.querySelector = (selector) => (selector === '[data-member-role-set]' ? roleSet : null);
    panel.addEventListener = (type, fn) => listeners.push(fn);
    const ui = {
        created: [],
        writes: [],
        published: [],
        errors: [],
        confirms: [],
        answer: true,
    };
    const context = vm.createContext({
        __ui: ui,
        HTMLElement: FakeElement,
        HTMLInputElement: FakeInput,
        document: {
            querySelector: (selector) => (selector === '[data-member-roles]' ? panel : null),
        },
        window: { location: { pathname: '/members/member-1/edit' } },
    });
    vm.runInContext(flattenModule('identity/member-roles.js', STUBS), context);
    /**
     * Flips a toggle as a click would and lets the confirm settle.
     *
     * @param {FakeInput} toggle the toggle
     * @returns {Promise<void>} settles once the handler ran
     */
    async function change(toggle) {
        toggle.checked = !toggle.checked;
        listeners.forEach((fn) => fn({ target: toggle }));
        await new Promise((resolve) => setImmediate(resolve));
    }
    return { ui, toggles, change };
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

await test('a declined withdrawal of KRT Member writes nothing and keeps the role ticked', async () => {
    const { ui, toggles, change } = load();
    ui.answer = false;
    await change(toggles.KRT_MEMBER);
    assert.deepEqual(JSON.parse(JSON.stringify(ui.confirms)), [
        { title: 'Withdraw role?', message: 'departs', confirmLabel: 'Withdraw' },
    ]);
    assert.equal(ui.writes.length, 0);
    assert.equal(toggles.KRT_MEMBER.checked, true);
});

await test('a confirmed withdrawal of the own Admin role writes it', async () => {
    const { ui, toggles, change } = load();
    await change(toggles.ADMIN);
    assert.equal(ui.confirms.length, 1);
    assert.equal(ui.confirms[0].message, 'own admin');
    assert.equal(ui.writes.length, 1);
    assert.deepEqual(JSON.parse(JSON.stringify(ui.writes[0].payload())), {
        assigned: false,
        version: 4,
    });
});

await test('a withdrawal without consequences and any assignment write at once', async () => {
    const { ui, toggles, change } = load();
    await change(toggles.BANK_EMPLOYEE);
    toggles.KRT_MEMBER.checked = false;
    await change(toggles.KRT_MEMBER);
    assert.equal(ui.confirms.length, 0);
    assert.equal(ui.writes.length, 2);
    assert.equal(ui.writes[1].payload().assigned, true);
});

if (failures) {
    process.exitCode = 1;
} else {
    console.log('member-roles tests passed');
}

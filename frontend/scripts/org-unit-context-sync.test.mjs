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
 * Regression tests for the cross-tab follow of the active org-unit context (REQ-ORG-032): each page
 * load runs the script in a fresh vm context over a fake channel and the tab's session storage, so
 * the reload, the unsaved-input dialog and the reload-loop guard are proven at the script's seam.
 */

import assert from 'node:assert/strict';
import vm from 'node:vm';
import { flattenModule } from './flatten-module.mjs';

const STUBS = {
    '/js/kernel/krt-modal.js': [
        'const krtModal = {',
        '    open(el) { __page.opened.push(el); __page.modalOpen = true; return el; },',
        '    close() { __page.modalOpen = false; return null; },',
        '    isOpen() { return __page.modalOpen; },',
        '};',
    ].join('\n'),
    '/js/kernel/unsaved-changes.js': [
        'function hasUnsavedChanges() { return __page.dirty; }',
        'function resetUnsavedChanges() { __page.dirty = false; }',
    ].join('\n'),
};

const SCRIPT = flattenModule('kernel/org-unit-context-sync.js', STUBS);

const HEL = 'ORG_UNIT:hel';
const ARGON = 'ORG_UNIT:argon';
const XENON = 'ORG_UNIT:xenon';

/** An element the script reads: the context marker, the dialog and its reload button. */
class FakeElement {
    /** @param {Record<string, string>} [dataset] the element's data attributes */
    constructor(dataset = {}) {
        this.dataset = dataset;
        this.clickHandlers = [];
    }

    addEventListener(type, handler) {
        if (type === 'click') {
            this.clickHandlers.push(handler);
        }
    }

    click() {
        this.clickHandlers.forEach((handler) => handler({}));
    }
}

/**
 * Loads one page into a tab: runs the script in a fresh context, as a navigation does, and fires
 * `DOMContentLoaded`.
 *
 * @param {{ reloads: number, storage: Map<string, string> }} tab the tab whose session storage the
 *     page shares and whose reloads it counts
 * @param {string} context the context the page renders; empty for a page without the marker
 * @param {boolean} dirty whether the page holds unsaved input
 * @returns {object} the page's harness
 */
function loadPage(tab, context, dirty) {
    const state = { dirty, modalOpen: false, opened: [] };
    const marker = new FakeElement({ activeOrgUnitContext: context });
    const modal = new FakeElement();
    const reloadButton = new FakeElement();
    const elements = new Map([
        ['org-unit-changed-modal', modal],
        ['org-unit-changed-reload-btn', reloadButton],
    ]);
    const channels = [];
    const loadListeners = [];

    class FakeBroadcastChannel {
        constructor(name) {
            this.name = name;
            this.posted = [];
            this.messageHandlers = [];
            channels.push(this);
        }

        addEventListener(type, handler) {
            if (type === 'message') {
                this.messageHandlers.push(handler);
            }
        }

        postMessage(data) {
            this.posted.push(data);
        }
    }

    const sandbox = vm.createContext({
        __page: state,
        HTMLElement: FakeElement,
        BroadcastChannel: FakeBroadcastChannel,
        document: {
            querySelector: (selector) =>
                selector === '[data-active-org-unit-context]' && context ? marker : null,
            getElementById: (id) => elements.get(id) ?? null,
            addEventListener(type, handler) {
                if (type === 'DOMContentLoaded') {
                    loadListeners.push(handler);
                }
            },
        },
        window: {
            sessionStorage: {
                getItem: (key) => (tab.storage.has(key) ? tab.storage.get(key) : null),
                setItem: (key, value) => tab.storage.set(key, String(value)),
                removeItem: (key) => tab.storage.delete(key),
            },
            location: {
                reload() {
                    tab.reloads += 1;
                },
            },
        },
    });
    vm.runInContext(SCRIPT, sandbox);
    loadListeners.forEach((handler) => handler());
    return {
        state,
        modal,
        channels,
        posted: () => channels.flatMap((channel) => channel.posted.map((data) => ({ ...data }))),
        receive: (data) => {
            channels.forEach((channel) => {
                channel.messageHandlers.forEach((handler) => handler({ data }));
            });
        },
        clickReload: () => reloadButton.click(),
    };
}

/**
 * Opens a tab whose session storage outlives its page loads, as the browser's does.
 *
 * @returns {{ reloads: number, storage: Map<string, string>, load: Function }} the tab
 */
function openTab() {
    const tab = {
        reloads: 0,
        storage: new Map(),
        load: (context, { dirty = false } = {}) => loadPage(tab, context, dirty),
    };
    return tab;
}

/**
 * Builds another tab's announcement.
 *
 * @param {string} context the announced context
 * @param {boolean} followed whether the announcing page is a reload that followed another tab
 * @returns {{ context: string, followed: boolean }} the channel message
 */
function announced(context, followed = false) {
    return { context, followed };
}

let failures = 0;

/**
 * Runs one named case, recording rather than throwing so every case reports.
 *
 * @param {string} name the case's name
 * @param {() => void} fn the case
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

test('a page announces its context on the channel, a navigation as no followed reload', () => {
    const tab = openTab();
    const page = tab.load(HEL);
    assert.equal(page.channels.length, 1);
    assert.equal(page.channels[0].name, 'krt-active-org-unit');
    assert.deepEqual(page.posted(), [{ context: HEL, followed: false }]);
});

test('a page without a context takes no part', () => {
    const tab = openTab();
    const page = tab.load('');
    assert.equal(page.channels.length, 0);
});

test('a clean tab that shows another context reloads for the announced one', () => {
    const tab = openTab();
    tab.load(HEL).receive(announced(ARGON));
    assert.equal(tab.reloads, 1);
    assert.deepEqual(tab.load(ARGON).posted(), [{ context: ARGON, followed: true }]);
});

test('an announcement of the own context, or of none, changes nothing', () => {
    const tab = openTab();
    const page = tab.load(HEL);
    page.receive(announced(HEL));
    page.receive(announced(HEL, true));
    page.receive(announced(''));
    page.receive(null);
    page.receive(ARGON);
    assert.equal(tab.reloads, 0);
    assert.equal(page.state.modalOpen, false);
});

test('a tab with unsaved input asks through the KRT dialog instead of reloading', () => {
    const tab = openTab();
    const page = tab.load(HEL, { dirty: true });
    page.receive(announced(ARGON));
    assert.equal(tab.reloads, 0, 'the unsaved input is kept');
    assert.deepEqual(page.state.opened, [page.modal]);
    page.receive(announced(ARGON, true));
    assert.equal(page.state.opened.length, 1, 'an open dialog is not opened again');
    assert.equal(tab.reloads, 0);
});

test('the dialog reloads for the latest announcement and drops the unsaved input first', () => {
    const tab = openTab();
    const page = tab.load(HEL, { dirty: true });
    page.receive(announced(ARGON));
    page.receive(announced(XENON));
    page.clickReload();
    assert.equal(page.state.dirty, false, 'the unsaved-changes guard is reset before the reload');
    assert.equal(tab.reloads, 1);
    const next = tab.load(HEL);
    next.receive(announced(XENON, true));
    assert.equal(tab.reloads, 1, 'the reload followed the latest announcement');
    next.receive(announced(ARGON, true));
    assert.equal(tab.reloads, 2);
});

test('the dialog closes and keeps the input when the other tab returns to this context', () => {
    const tab = openTab();
    const page = tab.load(HEL, { dirty: true });
    page.receive(announced(ARGON));
    assert.equal(page.state.modalOpen, true);
    page.receive(announced(HEL));
    assert.equal(page.state.modalOpen, false, 'the page shows the context again');
    assert.equal(page.state.dirty, true, 'the unsaved input is kept');
    assert.equal(tab.reloads, 0);
    page.clickReload();
    assert.equal(tab.reloads, 1, 'a later reload renders the own context');
    assert.deepEqual(tab.load(HEL).posted(), [{ context: HEL, followed: true }]);
});

test('a reload that missed the context ignores the followed announcements of it', () => {
    const tab = openTab();
    tab.load(HEL).receive(announced(ARGON));
    const page = tab.load(HEL);
    assert.deepEqual(page.posted(), [{ context: HEL, followed: true }]);
    page.receive(announced(ARGON, true));
    assert.equal(tab.reloads, 1, 'two tabs that disagree stop after one round');
});

test('a later switch to the context a reload once missed still reloads the tab', () => {
    const tab = openTab();
    tab.load(HEL).receive(announced(ARGON));
    const page = tab.load(HEL);
    page.receive(announced(ARGON));
    assert.equal(tab.reloads, 2);
});

test('the reload-loop guard lives only for the page that reload rendered', () => {
    const tab = openTab();
    tab.load(HEL).receive(announced(ARGON));
    tab.load(HEL);
    const later = tab.load(HEL);
    assert.deepEqual(later.posted(), [{ context: HEL, followed: false }]);
    later.receive(announced(ARGON, true));
    assert.equal(tab.reloads, 2);
});

if (failures) {
    process.exitCode = 1;
} else {
    console.log('org-unit-context-sync tests passed');
}

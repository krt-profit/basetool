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
 * Regression tests for the P4K catalogue admin pages (REQ-P4K-037): each page module runs in a vm
 * context over a fake DOM, with the transport, the modal, the toasts and the section seam replaced by
 * recording stand-ins, and is driven through the functions its event handlers call.
 */

import assert from 'node:assert/strict';
import vm from 'node:vm';
import { flattenModule } from './flatten-module.mjs';

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

/** The stand-ins of the modules that reach the network, the DOM chrome or the live-sync socket. */
const STUBS = {
    '/js/kernel/toast.js': [
        'function showFrontendErrorToast(m) { __ui.errors.push(m); }',
        'function showFrontendSuccessToast(m) { __ui.successes.push(m); }',
    ].join('\n'),
    '/js/kernel/krt-modal.js':
        'const krtModal = { open(id) { __ui.opened.push(id); }, close(id) { __ui.closed.push(id); } };',
    '/js/kernel/krt-fetch.js': [
        'const krtFetch = {',
        '  getJson(url, opts) { return __net.read(url, opts || {}); },',
        '  write(opts) { __net.writes.push(opts); return Promise.resolve({ ok: false }); },',
        '};',
    ].join('\n'),
    '/js/kernel/krt-html.js': [
        'function krtHtml(strings, ...values) { return { strings, values }; }',
        'krtHtml.set = (el, markup) => { el.markup = markup; };',
    ].join('\n'),
    '/js/kernel/krt-i18n.js':
        'function krtI18nText(value, key) { return value == null ? key : value; }',
    '/js/kernel/krt-page-sections.js': [
        'const krtPageSections = { create(cfg) { __live.sections.push(cfg);',
        '  return { write(opts) { return krtFetch.write(opts); } }; } };',
    ].join('\n'),
};

/**
 * The shared helpers under the local names the starmap and shops pages import them as; the
 * flattened script has one scope, so the pages' own functions of the helpers' names would clash.
 */
const LINK_ADMIN_AS_IMPORTED = [
    'const $ = (id) => document.getElementById(id);',
    'const dictionaryLabels = () => (key) => key;',
    'const filterValue = (id) => ($(id) ? $(id).value.trim() : "");',
    'const idOf = (entry) => entry.id || "";',
    'const readJsonOr = (url, label, target) => krtFetch.getJson(url, { key: target });',
    'const sendLinkChange = () => Promise.resolve(false);',
    'const showCandidates = () => {};',
    'const swapRow = () => null;',
    'const bindTypedFilter = () => {};',
    'const bindCandidateClick = () => {};',
].join('\n');

/** A fake element with the properties the page modules read and write. */
class FakeElement {
    /** @param {string} id the element id */
    constructor(id) {
        this.id = id;
        this.value = '';
        this.textContent = '';
        this.hidden = false;
        this.dataset = {};
        this.markup = null;
    }

    addEventListener() {}

    querySelectorAll() {
        return [];
    }

    reset() {
        this.value = '';
    }
}

class FakeInput extends FakeElement {}
class FakeSelect extends FakeElement {}
class FakeTextArea extends FakeElement {}
class FakeForm extends FakeElement {
    /** @param {string} id the element id @param {FakeElement[]} fields the fields it resets */
    constructor(id, fields) {
        super(id);
        this.fields = fields;
    }

    reset() {
        this.fields.forEach((f) => f.reset());
    }
}

/**
 * Loads a page module into a fresh context whose document is still loading, so its init does not
 * run and the test drives the handlers itself.
 *
 * @param {string} entry the module below `static/js`
 * @param {Record<string, FakeElement>} elements the elements by id
 * @param {(url: string, opts: object) => Promise<unknown>} read answers the transport's reads
 * @param {Record<string, string>} [extraStubs] further modules to replace, keyed by path
 */
function load(entry, elements, read = () => new Promise(() => {}), extraStubs = {}) {
    const ui = { errors: [], successes: [], opened: [], closed: [] };
    const net = { read, writes: [] };
    const live = { sections: [] };
    const context = vm.createContext({
        console,
        Promise,
        Map,
        Set,
        URLSearchParams,
        __ui: ui,
        __net: net,
        __live: live,
        HTMLElement: FakeElement,
        Element: FakeElement,
        HTMLInputElement: FakeInput,
        HTMLSelectElement: FakeSelect,
        HTMLTextAreaElement: FakeTextArea,
        HTMLFormElement: FakeForm,
        HTMLButtonElement: class extends FakeElement {},
        CSS: { escape: (s) => s },
        window: { setTimeout, krtStarmapI18n: {}, krtShopsI18n: {}, krtP4kImportI18n: {} },
        document: {
            readyState: 'loading',
            documentElement: { lang: 'de' },
            addEventListener() {},
            getElementById: (id) => elements[id] || null,
            querySelector: () => null,
            createElement: () => new FakeElement(''),
        },
    });
    vm.runInContext(flattenModule(entry, { ...STUBS, ...extraStubs }), context);
    return { fn: (name) => vm.runInContext(name, context), ui, net };
}

await test('the storage-place dialog starts with an empty name, never the game-text P4K name', async () => {
    const name = new FakeInput('starmap-storage-name');
    const description = new FakeTextArea('starmap-storage-description');
    const elements = {
        'starmap-storage-form': new FakeForm('starmap-storage-form', [name, description]),
        'starmap-storage-object': new FakeElement('starmap-storage-object'),
        'starmap-storage-name': name,
        'starmap-storage-description': description,
    };
    const page = load('catalogue/starmap.js', elements, undefined, {
        '/js/catalogue/catalogue-link-admin.js': LINK_ADMIN_AS_IMPORTED,
    });
    page.fn('openStorageDialog')({
        id: 'o-1',
        name: 'Seraphim Station',
        nameDe: 'Seraphim-Station',
        className: 'station_seraphim',
    });
    assert.equal(name.value, '', 'the admin types the curated name');
    assert.equal(
        elements['starmap-storage-object'].textContent,
        'Seraphim-Station',
        'the dialog still shows which object the place goes on',
    );
    assert.deepEqual(page.ui.opened, ['starmap-storage-modal']);
});

/**
 * A read the test settles by hand.
 *
 * @returns {{ promise: Promise<unknown>, resolve: (v: unknown) => void, reject: (e: unknown) => void }}
 */
function pending() {
    let resolve;
    let reject;
    const promise = new Promise((res, rej) => {
        resolve = res;
        reject = rej;
    });
    return { promise, resolve, reject };
}

/**
 * Loads the shared link-admin module with a transport whose reads the test settles in any order.
 */
function linkAdmin() {
    const reads = [];
    const page = load('catalogue/catalogue-link-admin.js', {}, (url, opts) => {
        const read = pending();
        reads.push({ url, key: opts.key, ...read });
        return read.promise;
    });
    return { readJson: page.fn('readJson'), forgetRead: page.fn('forgetRead'), reads, ui: page.ui };
}

await test('a slower earlier read of a render target never overwrites the newer one', async () => {
    const admin = linkAdmin();
    const older = admin.readJson('/a?page=0', 'load failed', 'list');
    const newer = admin.readJson('/a?page=1', 'load failed', 'list');
    assert.deepEqual(
        admin.reads.map((r) => r.key),
        ['list', 'list'],
        'both reads carry the supersede key',
    );
    admin.reads[1].resolve({ page: 1 });
    admin.reads[0].resolve({ page: 0 });
    assert.deepEqual(await newer, { page: 1 });
    assert.equal(await older, null, 'the superseded answer is dropped');
    assert.deepEqual(admin.ui.errors, []);
});

await test('a superseded read that fails shows no toast; a current one does', async () => {
    const admin = linkAdmin();
    const older = admin.readJson('/a', 'load failed', 'list');
    const newer = admin.readJson('/b', 'load failed', 'list');
    admin.reads[0].reject(new Error('AbortError'));
    admin.reads[1].reject(new Error('boom'));
    assert.equal(await older, null);
    assert.equal(await newer, null);
    assert.deepEqual(admin.ui.errors, ['load failed']);
});

await test('reads of different render targets do not supersede each other', async () => {
    const admin = linkAdmin();
    const objects = admin.readJson('/objects', 'load failed', 'objects');
    const places = admin.readJson('/places', 'load failed', 'places');
    admin.reads[1].resolve(['p']);
    admin.reads[0].resolve(['o']);
    assert.deepEqual(await objects, ['o']);
    assert.deepEqual(await places, ['p']);
});

await test('a target emptied without a read drops the answer still pending', async () => {
    const admin = linkAdmin();
    const pendingRead = admin.readJson('/terminals?query=a', 'load failed', 'terminals');
    admin.forgetRead('terminals');
    admin.reads[0].resolve(['late']);
    assert.equal(await pendingRead, null);
});

if (failures > 0) {
    console.error(`${failures} catalogue admin test(s) failed`);
    process.exit(1);
}

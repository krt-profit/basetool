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
    'const jsonReader = () => (url, target) => krtFetch.getJson(url, { key: target });',
    'const sendLinkChange = (change) => { __ui.linkChanges.push(change); return Promise.resolve(false); };',
    'const showCandidates = () => {};',
    'const swapRow = () => null;',
    'const bindTypedFilter = () => {};',
    'const bindLinkRowActions = () => {};',
    'const createLinkPicker = (cfg) => {',
    '  const picker = { cfg, opened: [], dismissed: 0,',
    '    open(entry) { picker.opened.push(entry); }, dismiss() { picker.dismissed += 1; } };',
    '  __ui.pickers.push(picker);',
    '  return picker;',
    '};',
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
    const ui = { errors: [], successes: [], opened: [], closed: [], pickers: [], linkChanges: [] };
    const net = { read, writes: [] };
    const live = { sections: [] };
    const context = vm.createContext({
        console,
        Promise,
        Map,
        Set,
        URLSearchParams,
        FormData: class {
            append() {}
        },
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
        window: {
            setTimeout,
            setInterval: () => 1,
            clearInterval() {},
            krtStarmapI18n: {},
            krtShopsI18n: {},
            krtP4kImportI18n: {},
        },
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
    return { fn: (name) => vm.runInContext(name, context), ui, net, live };
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
        'Seraphim Station',
        'the dialog shows the English game name of the object the place goes on',
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

/**
 * Loads the P4K report module with a transport whose reads the test settles by URL.
 */
function p4kReport() {
    const reads = [];
    const page = load(
        'catalogue/p4k-report.js',
        {},
        (url) => {
            const read = pending();
            reads.push({ url, ...read });
            return read.promise;
        },
        {
            '/js/catalogue/catalogue-link-admin.js': LINK_ADMIN_AS_IMPORTED,
            '/js/catalogue/p4k-due.js':
                'function refreshDueBanner() { __ui.banners = (__ui.banners || 0) + 1; }',
        },
    );
    const answer = async (path, body) => {
        const read = reads.find((r) => r.url.startsWith(`/admin/p4k-import${path}`) && !r.done);
        assert.ok(read, `a read of ${path}`);
        read.done = true;
        read.resolve(body);
        await new Promise((resolve) => setTimeout(resolve, 0));
    };
    return { fn: page.fn, reads, answer, ui: page.ui, live: page.live };
}

await test('after an APPLY the assignment options reload before the refreshed cases render', async () => {
    const report = p4kReport();
    report.fn('refreshAfterApply')();
    const paths = () => report.reads.map((r) => r.url.replace('/admin/p4k-import', ''));
    assert.ok(paths().includes('/resource-groups'), 'the resource groups reload');
    assert.ok(paths().includes('/materials'), 'the materials reload');
    assert.ok(
        !paths().some((p) => p.startsWith('/review-cases')),
        'the cases wait for the options',
    );
    await report.answer('/resource-groups', [{ id: 'g-new', name: 'New group' }]);
    await report.answer('/materials', [{ id: 'm-new', name: 'New ore' }]);
    assert.ok(paths().some((p) => p.startsWith('/review-cases')));
    assert.deepEqual(
        report.fn('groupOptions').map((o) => o.values[0]),
        ['g-new'],
    );
    assert.deepEqual(
        report.fn('materialOptions').map((o) => o.values[0]),
        ['m-new'],
    );
});

await test('a peer admin resolution or APPLY reloads the review cases and the runs in place', async () => {
    const report = p4kReport();
    const room = report.live.sections.find((s) => s.topicClass === 'CATALOGUE_ADMIN');
    assert.ok(room, 'the page joins the admin catalogue room');
    assert.equal(room.topic, 'catalogue-admin');
    assert.deepEqual(Object.keys(room.sections).sort(), ['review', 'runs']);
    const paths = () => report.reads.map((r) => r.url.replace('/admin/p4k-import', ''));

    room.receiver.refresh(['review']);
    assert.ok(
        paths().some((p) => p.startsWith('/review-cases')),
        'a resolution reloads the cases',
    );
    assert.ok(!paths().includes('/resource-groups'), 'without refetching the options');
    assert.ok(!paths().some((p) => p.startsWith('/runs')), 'the runs stay');

    room.receiver.refresh(['runs', 'review']);
    assert.ok(
        paths().some((p) => p.startsWith('/runs')),
        'an APPLY reloads the runs',
    );
    assert.ok(paths().includes('/resource-groups'), 'and the options its cases offer');
    assert.equal(report.ui.banners, 1, 'and the import-due banner');
});

await test('resolving the last case of the last page reloads the page before it', async () => {
    const report = p4kReport();
    report.fn('loadCases')(2);
    await report.answer('/review-cases?page=2', { content: [], page: 2, totalPages: 2 });
    const paths = report.reads.map((r) => r.url.replace('/admin/p4k-import', ''));
    assert.ok(paths.includes('/review-cases?page=1'), 'the last page that still holds cases');
    await report.answer('/review-cases?page=1', { content: [], page: 1, totalPages: 2 });
    assert.equal(report.fn('casesPage'), 1);
});

/**
 * Loads the shops page with the shared link-admin stand-ins and a transport the test settles.
 */
function shopsPage() {
    const reads = [];
    const page = load(
        'catalogue/shops.js',
        {},
        (url) => {
            const read = pending();
            reads.push({ url, ...read });
            return read.promise;
        },
        {
            '/js/catalogue/catalogue-link-admin.js': `${STUBS['/js/kernel/krt-fetch.js']}\n${LINK_ADMIN_AS_IMPORTED}`,
        },
    );
    return { fn: page.fn, reads, ui: page.ui };
}

await test('a link conflict closes the picker, so no further pick is sent against the stale link', () => {
    const shops = shopsPage();
    const picker = shops.ui.pickers[0];
    assert.ok(picker, 'the page wires its terminal picker');
    const shop = { id: 's-1', terminalId: 't-1', manual: false };
    picker.open(shop);
    shops.fn('changeLink')(shop, 'SET', 't-2', null);
    shops.ui.linkChanges[0].reload();
    assert.equal(picker.dismissed, 1, 'the picker is dismissed');
    assert.ok(
        shops.reads.some((r) => r.url.startsWith('/admin/shops/entries')),
        'and the list reloads',
    );
});

await test('a shop list that shrank below the asked page reloads its last page', async () => {
    const shops = shopsPage();
    shops.fn('loadShops')(3);
    shops.reads[0].resolve({ content: [], page: 3, totalPages: 3 });
    await new Promise((resolve) => setTimeout(resolve, 0));
    assert.ok(shops.reads[1].url.includes('page=2'), 'the last page is read');
});

/** A list whose click listener the test calls itself. */
class FakeList extends FakeElement {
    addEventListener(type, listener) {
        this.listener = listener;
    }
}

await test('the shared link picker sends a pick only for the entry it is open for', async () => {
    const candidates = new FakeList('cands');
    const page = load('catalogue/catalogue-link-admin.js', {
        cands: candidates,
        subject: new FakeElement('subject'),
        search: new FakeInput('search'),
    });
    const links = [];
    let answer = false;
    const picker = page.fn('createLinkPicker')({
        modalId: 'modal',
        subjectId: 'subject',
        searchId: 'search',
        candidatesId: 'cands',
        subject: (e) => e.name,
        search: (e) => e.name,
        loadCandidates() {},
        link(entry, candidateId) {
            links.push([entry.id, candidateId]);
            return Promise.resolve(answer);
        },
    });
    const Button = page.fn('HTMLButtonElement');
    const pick = (id) => {
        const btn = new Button('pick');
        btn.dataset.candidateId = id;
        btn.closest = () => btn;
        candidates.listener({ target: btn });
    };
    picker.dismiss();
    assert.deepEqual(page.ui.closed, [], 'a picker that is not open closes nothing');

    picker.open({ id: 'e-1', name: 'Area18' });
    assert.deepEqual(page.ui.opened, ['modal']);
    pick('c-1');
    await Promise.resolve();
    assert.deepEqual(page.ui.closed, [], 'a refused pick keeps the dialog');

    picker.dismiss();
    assert.deepEqual(page.ui.closed, ['modal']);
    pick('c-2');
    assert.deepEqual(links, [['e-1', 'c-1']], 'no pick after the dismiss');

    answer = true;
    picker.open({ id: 'e-2', name: 'Lorville' });
    pick('c-3');
    await new Promise((resolve) => setTimeout(resolve, 0));
    assert.deepEqual(page.ui.closed, ['modal', 'modal'], 'a saved pick closes the dialog');
});

await test('a slower earlier read of the due signal never overwrites the newer banner', async () => {
    const reads = [];
    const banner = new FakeElement('krt-p4k-due');
    banner.hidden = true;
    const page = load(
        'catalogue/p4k-due.js',
        { 'krt-p4k-due': banner, 'krt-p4k-due-detail': new FakeElement('detail') },
        (url, opts) => {
            const read = pending();
            reads.push({ key: opts.key, ...read });
            return read.promise;
        },
    );
    const older = page.fn('refreshDueBanner')();
    const newer = page.fn('refreshDueBanner')();
    assert.deepEqual(
        reads.map((r) => r.key),
        ['p4k-due', 'p4k-due'],
        'both reads carry the supersede key',
    );
    reads[1].resolve({ due: true });
    await newer;
    reads[0].resolve({ due: false });
    await older;
    assert.equal(banner.hidden, false, 'the banner shows the newer signal');
});

/**
 * Loads the P4K import module with the report module replaced by a recorder.
 *
 * @param {string} [fetchStub] the transport stand-in
 */
function p4kImport(fetchStub) {
    const extra = {
        '/js/catalogue/p4k-report.js': [
            'function openReport() {}',
            'function refreshAfterApply() { __ui.applied = (__ui.applied || 0) + 1; }',
        ].join('\n'),
    };
    if (fetchStub) extra['/js/kernel/krt-fetch.js'] = fetchStub;
    return load('catalogue/p4k-import.js', {}, undefined, extra);
}

await test('a finished PREVIEW leaves the runs and cases alone; a finished APPLY refreshes them', () => {
    const page = p4kImport();
    const poll = page.fn('pollControl');
    poll([{ id: 'p-1', kind: 'PREVIEW', status: 'RUNNING' }]);
    poll([{ id: 'p-1', kind: 'PREVIEW', status: 'SUCCEEDED' }]);
    assert.equal(page.ui.applied, undefined, 'a preview changes no run');

    poll([
        { id: 'a-1', kind: 'APPLY', status: 'RUNNING' },
        { id: 'p-2', kind: 'PREVIEW', status: 'RUNNING' },
    ]);
    poll([
        { id: 'a-1', kind: 'APPLY', status: 'RUNNING' },
        { id: 'p-2', kind: 'PREVIEW', status: 'SUCCEEDED' },
    ]);
    assert.equal(page.ui.applied, undefined, 'the APPLY still runs');
    poll([{ id: 'a-1', kind: 'APPLY', status: 'SUCCEEDED' }]);
    assert.equal(page.ui.applied, 1, 'the finished APPLY refreshes once');
});

await test('an upload above the multipart limit names the size limit', async () => {
    const page = p4kImport(
        [
            'const krtFetch = {',
            '  submitForm(opts) { opts.onError(413, { code: "UPLOAD_TOO_LARGE" });',
            '    return Promise.resolve({ ok: false }); },',
            '};',
        ].join('\n'),
    );
    page.fn('fileInput = { files: [{ name: "catalogue.json.zst" }], value: "" }');
    page.fn('upload')();
    await new Promise((resolve) => setTimeout(resolve, 0));
    assert.deepEqual(page.ui.errors, ['krtP4kImportI18n.tooLarge']);
});

if (failures > 0) {
    console.error(`${failures} catalogue admin test(s) failed`);
    process.exit(1);
}

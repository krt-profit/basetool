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
 * Regression tests for the page-section module (ADR-0289): the real transport of krt-fetch.js runs
 * in a vm context over a fake DOM with a scripted fetch and a recording live-sync stand-in, so a
 * write's re-render, broadcast, version propagation and conflict handling are proven together.
 */

import assert from 'node:assert/strict';
import vm from 'node:vm';
import { flattenModule } from './flatten-module.mjs';

const STUBS = {
    '/js/kernel/krt-modal.js':
        'const krtModal = { open() {}, close() {}, isOpen() { return false; }, topmost() { return null; }, layerRoot() { return null; } };',
    '/js/kernel/toast.js': [
        'function showFrontendErrorToast(m) { __ui.errors.push(m); }',
        'function showFrontendSuccessToast(m) { __ui.successes.push(m); }',
        'function showKrtConfirm(title) { __ui.confirms.push(title); return Promise.resolve(false); }',
    ].join('\n'),
    '/js/kernel/krt-live-sync.js': [
        'const krtLiveSync = {',
        '  sendChanged(topic, keys) { __live.sent.push({ topic, keys: [].concat(keys) }); },',
        '  createReceiver(cfg) {',
        '    __live.receivers.push(cfg);',
        '    return {',
        '      apply(keys) { cfg.refresh(keys && keys.length ? keys : Object.keys(cfg.sections)); },',
        '      retarget() {},',
        '    };',
        '  },',
        '};',
    ].join('\n'),
};

const SOURCE = `${flattenModule('kernel/krt-page-sections.js', STUBS)}\nwindow.krtPageSections = krtPageSections;`;

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

const JSON_TYPE = { 'Content-Type': 'application/json' };
const PROBLEM_TYPE = { 'Content-Type': 'application/problem+json' };

/**
 * A fake element: attributes, an innerHTML sink and a fixed list of version-carrying children.
 *
 * @param {string} id the element id
 * @param {number} children how many [data-version] descendants it holds
 */
function fakeElement(id, children = 0) {
    const attrs = new Map([['data-version', '1']]);
    const versioned = Array.from({ length: children }, () => {
        const child = {
            attrs: new Map([['data-version', '1']]),
            setAttribute(k, v) {
                child.attrs.set(k, v);
            },
            getAttribute: (k) => (child.attrs.has(k) ? child.attrs.get(k) : null),
        };
        return child;
    });
    return {
        id,
        attrs,
        versioned,
        innerHTML: `old ${id}`,
        setAttribute(k, v) {
            attrs.set(k, v);
        },
        getAttribute: (k) => (attrs.has(k) ? attrs.get(k) : null),
        querySelectorAll: (sel) => (sel === '[data-version]' ? versioned : []),
        addEventListener() {},
        contains: () => false,
        closest: () => null,
    };
}

/**
 * Builds a fresh script context whose fetch answers writes from `writes` and fragments from the
 * container the fragment query names.
 *
 * @param {Array<Response>} writes the responses to hand the PATCHes, one per call
 */
function harness(writes = []) {
    const calls = [];
    const elements = {
        '#a': fakeElement('a', 2),
        '#b': fakeElement('b', 1),
    };
    const ui = { errors: [], successes: [], confirms: [] };
    const live = { sent: [], receivers: [] };
    const context = {
        console: { warn() {}, log() {}, error() {} },
        Response,
        Headers,
        AbortController,
        AbortSignal,
        URL,
        Promise,
        Map,
        Error,
        Array,
        Object,
        CustomEvent: class {},
        __ui: ui,
        __live: live,
        document: {
            querySelector: (sel) => elements[sel] || null,
            querySelectorAll: () => [],
            addEventListener() {},
            dispatchEvent() {},
            createElement: () => ({ setAttribute() {} }),
            head: { appendChild() {} },
        },
        fetch(url, init) {
            calls.push({ url, method: init.method });
            if (init.method === 'GET') {
                const fragment = new URL(url, 'https://example.test').searchParams.get('fragment');
                return Promise.resolve(new Response(`fresh ${fragment}`, { status: 200 }));
            }
            return Promise.resolve(writes.shift());
        },
    };
    context.window = context;
    context.location = { origin: 'https://example.test', reload() {}, assign() {} };
    context.window.location = context.location;
    context.window.scrollY = 0;
    context.window.scrollTo = () => {};
    vm.createContext(context);
    vm.runInContext(SOURCE, context);
    context.window.krtI18nText = (v, k) => v || k;
    return { krtPageSections: context.krtPageSections, calls, elements, ui, live };
}

/** The declaration every case starts from. */
function declaration(overrides = {}) {
    return Object.assign(
        {
            dictName: 'DICT',
            dict: () => ({
                ok: 'Saved',
                err: 'Failed',
                'conflict.title': 'Conflict',
                'refresh.error': 'Refresh failed',
            }),
            keys: {
                successKey: 'ok',
                errorKey: 'err',
                conflictTitleKey: 'conflict.title',
                refreshErrorKey: 'refresh.error',
            },
            sections: {
                a: { container: '#a', fragmentValue: 'frag-a' },
                b: { container: '#b', fragmentValue: 'frag-b' },
            },
            pageUrl: () => '/page/1',
            topic: 'room:1',
        },
        overrides,
    );
}

/** Copies a value out of the vm realm so deep equality compares plain structure. */
const plain = (value) => JSON.parse(JSON.stringify(value));

const fragmentCalls = (h) => h.calls.filter((c) => c.method === 'GET');

await test('a successful write re-renders the named sections and broadcasts once', async () => {
    const h = harness([new Response('{"version":7}', { status: 200, headers: JSON_TYPE })]);
    const sections = h.krtPageSections.create(declaration());
    const result = await sections.write({
        method: 'PATCH',
        url: '/api/x',
        payload: {},
        sectionKey: 'a',
        refresh: ['a', 'b'],
    });
    assert.equal(result.ok, true);
    assert.equal(h.live.sent.length, 1);
    assert.deepEqual(plain(h.live.sent[0]), { topic: 'room:1', keys: ['a', 'b'] });
    assert.equal(fragmentCalls(h).length, 2);
    assert.equal(h.elements['#a'].innerHTML, 'fresh frag-a');
    assert.equal(h.elements['#b'].innerHTML, 'fresh frag-b');
    assert.deepEqual(h.ui.successes, ['Saved']);
});

await test('a 409 shows the conflict confirm and neither re-renders nor broadcasts', async () => {
    const h = harness([
        new Response('{"code":"OPTIMISTIC_LOCK","detail":"stale"}', {
            status: 409,
            headers: PROBLEM_TYPE,
        }),
    ]);
    const sections = h.krtPageSections.create(declaration());
    const result = await sections.write({
        method: 'PATCH',
        url: '/api/x',
        payload: {},
        sectionKey: 'a',
        refresh: ['a'],
    });
    assert.equal(result.ok, false);
    assert.equal(result.status, 409);
    assert.deepEqual(h.ui.confirms, ['Conflict']);
    assert.equal(h.live.sent.length, 0);
    assert.equal(fragmentCalls(h).length, 0);
    assert.equal(h.elements['#a'].innerHTML, 'old a');
});

await test('propagateVersion writes the response version to every element of the section', async () => {
    const h = harness([new Response('{"version":9}', { status: 200, headers: JSON_TYPE })]);
    const sections = h.krtPageSections.create(declaration());
    await sections.write({
        method: 'PATCH',
        url: '/api/x',
        payload: {},
        sectionKey: 'a',
        propagateVersion: true,
    });
    const a = h.elements['#a'];
    assert.equal(a.getAttribute('data-version'), '9');
    assert.deepEqual(
        a.versioned.map((el) => el.getAttribute('data-version')),
        ['9', '9'],
    );
    assert.equal(h.elements['#b'].getAttribute('data-version'), '1');
    assert.equal(h.live.sent.length, 0);
});

await test('without propagateVersion the section versions stay untouched', async () => {
    const h = harness([new Response('{"version":9}', { status: 200, headers: JSON_TYPE })]);
    const sections = h.krtPageSections.create(declaration());
    await sections.write({ method: 'PATCH', url: '/api/x', payload: {}, sectionKey: 'a' });
    assert.equal(h.elements['#a'].getAttribute('data-version'), '1');
});

await test('refresh runs before the caller onSuccess', async () => {
    const h = harness([new Response('{}', { status: 200, headers: JSON_TYPE })]);
    const sections = h.krtPageSections.create(declaration());
    let seen = null;
    await sections.write({
        method: 'PATCH',
        url: '/api/x',
        payload: {},
        sectionKey: 'a',
        refresh: 'a',
        onSuccess() {
            seen = h.elements['#a'].innerHTML;
        },
    });
    assert.equal(seen, 'fresh frag-a');
});

await test('a peer frame re-renders only the named section and does not echo', async () => {
    const h = harness();
    const sections = h.krtPageSections.create(declaration({ receiver: {} }));
    assert.equal(h.live.receivers.length, 1);
    assert.equal(h.live.receivers[0].topic, 'room:1');
    sections.receiver.apply(['b']);
    await new Promise((resolve) => setTimeout(resolve, 0));
    assert.equal(fragmentCalls(h).length, 1);
    assert.match(fragmentCalls(h)[0].url, /fragment=frag-b/);
    assert.equal(h.elements['#a'].innerHTML, 'old a');
    assert.equal(h.elements['#b'].innerHTML, 'fresh frag-b');
    assert.equal(h.live.sent.length, 0);
});

await test('the receiver can widen the sections a peer change re-renders', async () => {
    const h = harness();
    const sections = h.krtPageSections.create(
        declaration({ receiver: { expand: (keys) => keys.concat(['a']) } }),
    );
    sections.receiver.apply(['b']);
    await new Promise((resolve) => setTimeout(resolve, 0));
    assert.equal(fragmentCalls(h).length, 2);
});

await test('refresh and notify honour alsoBroadcast and the broadcast opt-out', async () => {
    const h = harness();
    const sections = h.krtPageSections.create(declaration({ alsoBroadcast: ['chart'] }));
    await sections.refresh(['a']);
    sections.notify('b');
    await sections.refresh(['a'], { broadcast: false });
    assert.deepEqual(plain(h.live.sent), [
        { topic: 'room:1', keys: ['a', 'chart'] },
        { topic: 'room:1', keys: ['b', 'chart'] },
    ]);
});

await test('a page without an entity id neither fetches nor broadcasts nor subscribes', async () => {
    const h = harness();
    const sections = h.krtPageSections.create(
        declaration({ pageUrl: () => null, topic: () => null, receiver: {} }),
    );
    assert.equal(sections.receiver, null);
    assert.deepEqual(await sections.refresh(['a']), [false]);
    assert.equal(h.calls.length, 0);
    assert.equal(h.live.sent.length, 0);
});

await test('a custom broadcast replaces the topic broadcast', async () => {
    const h = harness();
    const seen = [];
    const sections = h.krtPageSections.create(
        declaration({ topic: undefined, broadcast: (keys) => seen.push(keys) }),
    );
    sections.notify(['a']);
    assert.deepEqual(plain(seen), [['a']]);
    assert.equal(h.live.sent.length, 0);
});

await test('a receiver driven by events needs no topic', async () => {
    const h = harness();
    h.krtPageSections.create(
        declaration({ topic: undefined, receiver: { events: { changed: 'krt:x-changed' } } }),
    );
    assert.equal(h.live.receivers.length, 1);
});

await test('a page that declares neither dict nor keys still refreshes and receives', async () => {
    const h = harness();
    const sections = h.krtPageSections.create(
        declaration({ dict: undefined, keys: undefined, receiver: {} }),
    );
    sections.receiver.apply(['b']);
    await new Promise((resolve) => setTimeout(resolve, 0));
    assert.equal(h.elements['#b'].innerHTML, 'fresh frag-b');
    await sections.refresh(['a']);
    assert.equal(h.elements['#a'].innerHTML, 'fresh frag-a');
});

await test('sections sharing a container and fragment are fetched once', async () => {
    const h = harness();
    const sections = h.krtPageSections.create(
        declaration({
            sections: {
                a: { container: '#a', fragmentValue: 'frag-a' },
                alias: { container: '#a', fragmentValue: 'frag-a' },
                b: { container: '#b', fragmentValue: 'frag-b' },
            },
        }),
    );
    const flags = await sections.refresh(['a', 'alias', 'b']);
    assert.deepEqual(plain(flags), [true, true, true]);
    assert.equal(fragmentCalls(h).length, 2);
});

await test('a retargetable receiver is built before the topic is known', async () => {
    const h = harness();
    const sections = h.krtPageSections.create(
        declaration({ topic: () => null, receiver: { retargetable: true } }),
    );
    assert.equal(h.live.receivers.length, 1);
    assert.equal(typeof sections.receiver.retarget, 'function');
});

await test('a receiver refresh hook wraps the re-render of a peer change', async () => {
    const h = harness();
    const seen = [];
    const sections = h.krtPageSections.create(
        declaration({
            receiver: {
                refresh(keys, run) {
                    seen.push(keys);
                    return run(keys);
                },
            },
        }),
    );
    sections.receiver.apply(['b']);
    await new Promise((resolve) => setTimeout(resolve, 0));
    assert.deepEqual(plain(seen), [['b']]);
    assert.equal(h.elements['#b'].innerHTML, 'fresh frag-b');
    assert.equal(h.live.sent.length, 0);
});

await test('publish is a no-op without a topic', async () => {
    const h = harness();
    h.krtPageSections.publish(null, ['a']);
    h.krtPageSections.publish('room:2', ['a']);
    assert.deepEqual(plain(h.live.sent), [{ topic: 'room:2', keys: ['a'] }]);
});

if (failures > 0) {
    console.error(`${failures} failed`);
    process.exit(1);
}

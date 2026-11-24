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
 * Regression tests for the write of krtFetch.swap — the press guard (REQ-FE-038, ADR-0405) and the
 * scroll position it keeps (REQ-FE-039): the transport and krt-press.js run in a vm context over one
 * fake container, with scripted pointer events, a scripted fetch, a scriptable window scroll and
 * manual timers, so the moment of each fragment write is observable.
 */

import assert from 'node:assert/strict';
import vm from 'node:vm';
import { flattenModule, UI_STUBS } from './flatten-module.mjs';

const SOURCE = flattenModule('kernel/krt-fetch.js', {
    ...UI_STUBS,
    '/js/kernel/krt-i18n.js': 'function krtI18nText(value, key) { return value || key; }',
});

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

/** Lets every pending promise reaction of the context run. */
function idle() {
    return new Promise((resolve) => setImmediate(resolve));
}

/**
 * Builds a fresh script context with one swappable container, `#section`, holding `inside`.
 * Each fragment request answers with the body `fragment <n>`, n counting the requests from 1.
 */
function harness() {
    class FakeNode {}
    const inside = new FakeNode();
    const outside = new FakeNode();
    const writes = [];
    const swapped = [];
    const scrolls = [];
    const timers = [];
    const listeners = new Map();
    let requests = 0;
    const container = {
        id: 'section',
        set innerHTML(markup) {
            writes.push(String(markup));
        },
        contains: (node) => node === inside,
        querySelectorAll: () => [],
        addEventListener() {},
    };
    const context = {
        console: { warn() {}, log() {}, error() {} },
        AbortController,
        AbortSignal,
        URL,
        Promise,
        Map,
        Set,
        Error,
        Node: FakeNode,
        CustomEvent: class {
            constructor(type, options) {
                this.type = type;
                this.detail = options && options.detail;
            }
        },
        document: {
            querySelector: (selector) => (selector === '#section' ? container : null),
            querySelectorAll: () => [],
            addEventListener() {},
            dispatchEvent: (event) => swapped.push(event.type),
            createElement: () => ({ setAttribute() {} }),
            head: { appendChild() {} },
            body: { dataset: {} },
        },
        addEventListener(type, fn) {
            if (!listeners.has(type)) {
                listeners.set(type, []);
            }
            listeners.get(type).push(fn);
        },
        setTimeout(fn, ms) {
            const timer = { fn, ms, live: true };
            timers.push(timer);
            return timer;
        },
        clearTimeout(timer) {
            if (timer) {
                timer.live = false;
            }
        },
        fetch() {
            requests += 1;
            const body = `fragment ${requests}`;
            return Promise.resolve({
                ok: true,
                status: 200,
                redirected: false,
                headers: new Headers(),
                text: () => Promise.resolve(body),
            });
        },
        scrollY: 0,
        scrollTo(x, y) {
            scrolls.push([x, y]);
        },
    };
    context.window = context;
    context.location = { origin: 'https://example.test' };
    vm.createContext(context);
    vm.runInContext(SOURCE, context);

    /**
     * Dispatches an event to the window listeners of one type.
     *
     * @param {string} type the event type
     * @param {object} [event] the event's fields
     */
    function fire(type, event = {}) {
        for (const fn of listeners.get(type) || []) {
            fn(event);
        }
    }

    return {
        writes,
        swapped,
        scrolls,
        /** Moves the window's vertical scroll position, as a user's scroll does. */
        scrollWindow(y) {
            context.scrollY = y;
        },
        live: () => timers.filter((timer) => timer.live),
        /** Runs the timers that are due now and lets their effects settle. */
        async runTimers() {
            const due = timers.splice(0).filter((timer) => timer.live);
            due.forEach((timer) => timer.fn());
            await idle();
        },
        swap: () => context.krtFetch.swap({ url: '/page', container: '#section' }),
        press: (target = inside, extra = {}) =>
            fire('pointerdown', { isPrimary: true, button: 0, target, ...extra }),
        release: () => fire('pointerup', { isPrimary: true }),
        cancel: () => fire('pointercancel', { isPrimary: true }),
        click: () => fire('click'),
        blur: () => fire('blur'),
        inside,
        outside,
    };
}

await test('a swap during a press inside its container writes only after the click', async () => {
    const h = harness();
    h.press();
    const swap = h.swap();
    await idle();
    assert.deepEqual(h.writes, []);

    h.release();
    h.click();
    await idle();
    assert.deepEqual(h.writes, [], 'the click handlers run before the write');

    await h.runTimers();
    assert.deepEqual(h.writes, ['fragment 1']);
    assert.deepEqual(h.swapped, ['krt:swapped']);
    assert.equal(await swap, true);
});

await test('an ended press settles in the macrotask after its click', async () => {
    const h = harness();
    h.press();
    const swap = h.swap();
    await idle();
    h.release();
    assert.deepEqual(
        h.live().map((timer) => timer.ms),
        [100],
    );
    h.click();
    assert.deepEqual(
        h.live().map((timer) => timer.ms),
        [0],
    );
    await h.runTimers();
    assert.equal(await swap, true);
});

await test('a press without a click releases the swap after the grace period', async () => {
    const h = harness();
    h.press();
    const swap = h.swap();
    await idle();
    h.release();
    await idle();
    assert.deepEqual(h.writes, []);
    await h.runTimers();
    assert.deepEqual(h.writes, ['fragment 1']);
    assert.equal(await swap, true);
});

await test('a press outside the container holds nothing', async () => {
    const h = harness();
    h.press(h.outside);
    assert.equal(await h.swap(), true);
    assert.deepEqual(h.writes, ['fragment 1']);
});

await test('a secondary button or a second pointer holds nothing', async () => {
    for (const extra of [{ button: 2 }, { isPrimary: false }]) {
        const h = harness();
        h.press(h.inside, extra);
        assert.equal(await h.swap(), true);
        assert.deepEqual(h.writes, ['fragment 1']);
    }
});

await test('a pointercancel or a window blur ends the press', async () => {
    for (const end of ['cancel', 'blur']) {
        const h = harness();
        h.press();
        const swap = h.swap();
        await idle();
        assert.deepEqual(h.writes, [], end);
        h[end]();
        await h.runTimers();
        assert.deepEqual(h.writes, ['fragment 1'], end);
        assert.equal(await swap, true, end);
    }
});

await test('a newer swap during the hold wins and the held one writes nothing', async () => {
    const h = harness();
    h.press();
    const older = h.swap();
    await idle();
    const newer = h.swap();
    await idle();
    h.release();
    h.click();
    await h.runTimers();
    assert.equal(await older, false);
    assert.equal(await newer, true);
    assert.deepEqual(h.writes, ['fragment 2']);
    assert.deepEqual(h.swapped, ['krt:swapped']);
});

await test('a new press inside the container keeps holding a swap the last press ended', async () => {
    const h = harness();
    h.press();
    const swap = h.swap();
    await idle();
    h.release();
    h.press();
    await h.runTimers();
    assert.deepEqual(h.writes, []);

    h.release();
    h.click();
    await h.runTimers();
    assert.deepEqual(h.writes, ['fragment 1']);
    assert.equal(await swap, true);
});

await test('a scroll made while the fragment loads is kept by the write', async () => {
    const h = harness();
    const swap = h.swap();
    h.scrollWindow(640);
    assert.equal(await swap, true);
    assert.deepEqual(h.writes, ['fragment 1']);
    assert.deepEqual(h.scrolls, [[0, 640]]);
});

await test('a swap held by a press keeps the scroll position found at its write', async () => {
    const h = harness();
    h.press();
    const swap = h.swap();
    await idle();
    h.scrollWindow(640);
    h.release();
    h.click();
    await h.runTimers();
    assert.equal(await swap, true);
    assert.deepEqual(h.scrolls, [[0, 640]]);
});

if (failures > 0) {
    console.error(`${failures} failed`);
    process.exit(1);
}

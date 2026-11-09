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
 * Regression tests for the live-sync socket's re-subscribe of topics denied as indeterminate and
 * for the receiver's signal sections (REQ-FE-015, ADR-0094): the script is run in a vm context with
 * a fake WebSocket, fake timers and a fake page.
 */

import assert from 'node:assert/strict';
import vm from 'node:vm';
import { flattenModule, UI_STUBS } from './flatten-module.mjs';

const SOURCE = flattenModule('kernel/krt-live-sync.js', UI_STUBS);

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

/** Builds a fresh script context with a controllable socket, clock, random source and page. */
function harness(randomValue = 0.5, page = {}) {
    const sockets = [];
    const timers = [];
    class FakeSocket {
        constructor(url) {
            this.url = url;
            this.readyState = 0;
            this.sent = [];
            this.listeners = {};
            sockets.push(this);
        }
        addEventListener(type, fn) {
            this.listeners[type] = fn;
        }
        send(data) {
            this.sent.push(JSON.parse(data));
        }
        fire(type, ev) {
            this.listeners[type](ev);
        }
        open() {
            this.readyState = FakeSocket.OPEN;
            this.fire('open', {});
        }
        message(obj) {
            this.fire('message', { data: JSON.stringify(obj) });
        }
        close(code) {
            this.readyState = 3;
            this.fire('close', { code });
        }
    }
    FakeSocket.OPEN = 1;
    FakeSocket.CONNECTING = 0;
    const math = Object.create(Math);
    math.random = () => randomValue;
    const context = {
        Math: math,
        WebSocket: FakeSocket,
        document: Object.assign({ querySelectorAll: () => [], addEventListener() {} }, page),
        console,
        getComputedStyle: () => ({ display: 'flex' }),
    };
    context.window = context;
    context.location = { protocol: 'https:', host: 'example.test' };
    context.window.location = context.location;
    context.setTimeout = (fn, ms) => {
        const timer = { fn, ms, live: true };
        timers.push(timer);
        return timer;
    };
    context.clearTimeout = (timer) => {
        if (timer) {
            timer.live = false;
        }
    };
    vm.createContext(context);
    vm.runInContext(SOURCE, context);
    return {
        sync: context.krtLiveSync,
        sockets,
        live: () => timers.filter((t) => t.live),
        fire(timer) {
            timer.live = false;
            timer.fn();
        },
    };
}

const indeterminate = (topic) => ({ type: 'denied', topic, reason: 'indeterminate' });

test('an indeterminate deny schedules a jittered re-subscribe and re-sends it', () => {
    const h = harness();
    h.sync.subscribe('operation:1', {});
    const ws = h.sockets[0];
    ws.open();
    ws.sent.length = 0;
    ws.message(indeterminate('operation:1'));

    const pending = h.live();
    assert.equal(pending.length, 1);
    assert.ok(pending[0].ms >= 1000 && pending[0].ms <= 31000, `delay ${pending[0].ms}`);
    assert.equal(ws.sent.length, 0);
    h.fire(pending[0]);
    assert.deepEqual(ws.sent, [{ type: 'subscribe', topic: 'operation:1' }]);
});

test('re-subscribe attempts are bounded and then the topic stays denied', () => {
    const h = harness(1);
    h.sync.subscribe('operation:1', {});
    const ws = h.sockets[0];
    ws.open();
    let attempts = 0;
    let last = 0;
    for (let i = 0; i < 20; i += 1) {
        ws.message(indeterminate('operation:1'));
        const pending = h.live();
        if (pending.length === 0) {
            break;
        }
        assert.ok(pending[0].ms >= last || pending[0].ms === 31000, 'delay grows to its ceiling');
        last = pending[0].ms;
        attempts += 1;
        h.fire(pending[0]);
    }
    assert.equal(attempts, 5);
    assert.equal(h.live().length, 0);
});

test('an authorization deny is terminal and schedules nothing', () => {
    const h = harness();
    h.sync.subscribe('operation:1', {});
    const ws = h.sockets[0];
    ws.open();
    ws.message({ type: 'denied', topic: 'operation:1', reason: 'authz' });

    assert.equal(h.live().length, 0);
});

test('a successful subscribe resets the attempt budget and cancels a pending retry', () => {
    const h = harness();
    h.sync.subscribe('operation:1', {});
    const ws = h.sockets[0];
    ws.open();
    for (let i = 0; i < 3; i += 1) {
        ws.message(indeterminate('operation:1'));
        h.fire(h.live()[0]);
    }
    ws.message({ type: 'subscribed', topic: 'operation:1' });
    assert.equal(h.live().length, 0);
    for (let i = 0; i < 5; i += 1) {
        ws.message(indeterminate('operation:1'));
        assert.equal(h.live().length, 1, `attempt ${i + 1} still scheduled`);
        h.fire(h.live()[0]);
    }
});

test('a socket close cancels the pending retry and the reconnect starts a fresh budget', () => {
    const h = harness();
    h.sync.subscribe('operation:1', {});
    const first = h.sockets[0];
    first.open();
    for (let i = 0; i < 5; i += 1) {
        first.message(indeterminate('operation:1'));
        h.fire(h.live()[0]);
    }
    first.message(indeterminate('operation:1'));
    assert.equal(h.live().length, 0);

    first.close(1006);
    const reconnect = h.live();
    assert.equal(reconnect.length, 1);
    h.fire(reconnect[0]);
    const second = h.sockets[1];
    second.open();
    assert.deepEqual(second.sent, [{ type: 'subscribe', topic: 'operation:1' }]);
    second.message(indeterminate('operation:1'));
    assert.equal(h.live().length, 1);
});

test('a retry does nothing once the topic was unsubscribed', () => {
    const h = harness();
    const handle = h.sync.subscribe('operation:1', {});
    const ws = h.sockets[0];
    ws.open();
    ws.message(indeterminate('operation:1'));
    const timer = h.live()[0];
    handle.unsubscribe();
    ws.sent.length = 0;
    if (timer.live) {
        h.fire(timer);
    }
    assert.equal(ws.sent.length, 0);
});

/**
 * A page with an order section and a signal section, a toggleable open modal and a capturing pill.
 *
 * @returns {{page: object, modal: {open: boolean}, pills: object[]}} the fake page and its state
 */
function sectionPage() {
    const modal = { open: false };
    const pills = [];
    const containers = {
        '#order': { contains: () => false },
        '#deleted': { contains: () => false },
    };
    const page = {
        querySelectorAll: (sel) => (sel === '.krt-modal-overlay' && modal.open ? [{}] : []),
        querySelector: (sel) => containers[sel] || null,
        getElementById: () => null,
        createElement: () => {
            const pill = { listeners: {}, remove() {} };
            pill.addEventListener = (type, fn) => {
                pill.listeners[type] = fn;
            };
            return pill;
        },
        body: { appendChild: (el) => pills.push(el) },
        activeElement: null,
    };
    return { page, modal, pills };
}

/** Builds a receiver on `refinery-order:1` over {@link sectionPage} that records its refreshes. */
function signalReceiver() {
    const fake = sectionPage();
    const h = harness(0.5, fake.page);
    const refreshed = [];
    h.sync.createReceiver({
        topic: 'refinery-order:1',
        sections: {
            order: { container: '#order' },
            deleted: { container: '#deleted', signal: true },
        },
        refresh: (keys) => refreshed.push(Array.from(keys)),
    });
    const ws = h.sockets[0];
    ws.open();
    ws.message({ type: 'subscribed', topic: 'refinery-order:1' });
    const flush = () => h.live().forEach((timer) => h.fire(timer));
    return { h, ws, fake, refreshed, flush };
}

test('a resync refreshes every section except a signal section', () => {
    const r = signalReceiver();
    r.ws.message({ type: 'subscribed', topic: 'refinery-order:1' });
    r.flush();
    assert.deepEqual(r.refreshed, [['order']]);
});

test('a signal section is applied while a modal defers the others', () => {
    const r = signalReceiver();
    r.fake.modal.open = true;
    r.ws.message({ type: 'changed', topic: 'refinery-order:1', sections: ['deleted', 'order'] });
    r.flush();
    assert.deepEqual(r.refreshed, [['deleted']]);
    assert.equal(r.fake.pills.length, 1);
});

test('a subscribe denial and its pill never refresh a signal section', () => {
    const r = signalReceiver();
    r.ws.message({ type: 'denied', topic: 'refinery-order:1', reason: 'forbidden' });
    assert.equal(r.fake.pills.length, 1);
    r.fake.pills[0].listeners.click();
    assert.deepEqual(r.refreshed, [['order']]);
});

if (failures > 0) {
    console.error(`${failures} failed`);
    process.exit(1);
}

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
 * Regression tests for the live-sync socket's re-subscribe of topics denied as indeterminate
 * (REQ-FE-015, ADR-0094): the script is run in a vm context with a fake WebSocket and fake timers.
 */

import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const SOURCE = readFileSync(
    fileURLToPath(new URL('../src/main/resources/static/js/krt-live-sync.js', import.meta.url)),
    'utf8',
);

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

/** Builds a fresh script context with a controllable socket, clock and random source. */
function harness(randomValue = 0.5) {
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
        document: { querySelectorAll: () => [], addEventListener() {} },
        console,
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

if (failures > 0) {
    console.error(`${failures} failed`);
    process.exit(1);
}

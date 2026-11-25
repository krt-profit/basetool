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
 * Regression tests for the live-sync socket's re-subscribe of topics denied as indeterminate, for
 * the receiver's signal sections (REQ-FE-015, ADR-0094) and for the sections an edited, unsaved form
 * holds back (REQ-FE-024): the script is run in a vm context with a fake WebSocket, fake timers and
 * a fake page.
 */

import assert from 'node:assert/strict';
import vm from 'node:vm';
import { flattenModule, UI_STUBS } from './flatten-module.mjs';

const SOURCE = flattenModule('kernel/krt-live-sync.js', {
    ...UI_STUBS,
    '/js/kernel/krt-maintenance.js':
        'const krtMaintenance = { entered: 0, enter() { this.entered += 1; }, observe() { return false; }, isActive() { return false; }, isMaintenanceResponse() { return false; } };',
});

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
        CustomEvent: class {
            constructor(type, init) {
                this.type = type;
                this.detail = init && init.detail;
            }
        },
        document: Object.assign({ querySelectorAll: () => [], addEventListener() {} }, page),
        console,
        getComputedStyle: () => ({ display: 'flex' }),
        addEventListener() {},
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
        document: context.document,
        marks: { mark: context.markUnsaved, clear: context.clearUnsaved },
        maintenance: vm.runInContext('krtMaintenance', context),
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

test('a fallback-first room carries the flag on every subscribe frame it sends', () => {
    const h = harness();
    h.sync.subscribe('bank:1', {}, { fallbackFirst: true });
    const first = h.sockets[0];
    first.open();
    assert.deepEqual(first.sent, [{ type: 'subscribe', topic: 'bank:1', fallbackFirst: true }]);

    first.sent.length = 0;
    first.message(indeterminate('bank:1'));
    h.fire(h.live()[0]);
    assert.deepEqual(first.sent, [{ type: 'subscribe', topic: 'bank:1', fallbackFirst: true }]);

    first.close(1006);
    h.fire(h.live()[0]);
    const second = h.sockets[1];
    second.open();
    assert.deepEqual(second.sent, [{ type: 'subscribe', topic: 'bank:1', fallbackFirst: true }]);
});

test('a close for a restart (1012) raises the maintenance banner and still reconnects', () => {
    const h = harness();
    h.sync.subscribe('bank:1', {});
    const first = h.sockets[0];
    first.open();

    first.close(1012);

    assert.equal(h.maintenance.entered, 1);
    assert.equal(h.live().length, 1, 'the reconnect stays scheduled');
});

test('an ordinary drop (1006) leaves the maintenance banner alone', () => {
    const h = harness();
    h.sync.subscribe('bank:1', {});
    const first = h.sockets[0];
    first.open();

    first.close(1006);

    assert.equal(h.maintenance.entered, 0);
});

test('a room without the option sends the plain subscribe frame', () => {
    const h = harness();
    h.sync.subscribe('bank:1', {});
    const ws = h.sockets[0];
    ws.open();
    assert.deepEqual(ws.sent, [{ type: 'subscribe', topic: 'bank:1' }]);
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
 * A form that keeps its attributes, so the unsaved mark can be set and cleared on it.
 *
 * @param {object[]} [controls] the nodes inside the form
 * @returns {{hasAttribute(name: string): boolean, setAttribute(name: string): void,
 *     removeAttribute(name: string): void, contains(node: object): boolean}} the fake form
 */
function fakeForm(controls = []) {
    const attributes = new Set();
    return {
        hasAttribute: (name) => attributes.has(name),
        setAttribute: (name) => attributes.add(name),
        removeAttribute: (name) => attributes.delete(name),
        contains: (node) => controls.includes(node),
    };
}

/**
 * A container that answers the unsaved-form query from the forms it holds.
 *
 * @param {object[]} [forms] the fake forms inside the container
 * @param {object[]} [others] further nodes inside the container, outside every form
 * @returns {object} the fake container
 */
function fakeContainer(forms = [], others = []) {
    return {
        contains: (node) => others.includes(node) || forms.some((form) => form.contains(node)),
        matches: () => false,
        querySelector: (sel) =>
            sel === 'form[data-krt-unsaved]'
                ? forms.find((form) => form.hasAttribute('data-krt-unsaved')) || null
                : null,
    };
}

/**
 * A page with an order section and a signal section, a toggleable open modal, a capturing pill, the
 * page's sticky form action bars and document listeners that `dispatchEvent` reaches. The order
 * section holds a form with a save button and, outside the form, a filter field.
 *
 * @returns {{page: object, modal: {open: boolean}, pills: object[], orderForm: object,
 *     saveButton: object, filterField: object, stickyBars: Array<{offsetHeight: number}>}} the
 *     fake page and its state
 */
function sectionPage() {
    const modal = { open: false };
    const pills = [];
    const stickyBars = [];
    const saveButton = {};
    const filterField = {};
    const orderForm = fakeForm([saveButton]);
    const containers = {
        '#order': fakeContainer([orderForm], [filterField]),
        '#deleted': fakeContainer(),
    };
    const listeners = {};
    const page = {
        addEventListener: (type, fn) => {
            (listeners[type] ||= []).push(fn);
        },
        dispatchEvent: (event) => {
            (listeners[event.type] || []).forEach((fn) => fn(event));
            return true;
        },
        querySelectorAll: (sel) => {
            if (sel === '.form-actions--sticky') {
                return stickyBars;
            }
            return sel === '.krt-modal-overlay' && modal.open ? [{}] : [];
        },
        querySelector: (sel) => containers[sel] || null,
        getElementById: () => null,
        createElement: () => {
            const style = {
                props: {},
                setProperty(name, value) {
                    style.props[name] = value;
                },
            };
            const pill = { listeners: {}, style, remove() {} };
            pill.addEventListener = (type, fn) => {
                pill.listeners[type] = fn;
            };
            return pill;
        },
        body: { appendChild: (el) => pills.push(el) },
        activeElement: null,
    };
    return { page, modal, pills, orderForm, saveButton, filterField, stickyBars };
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

test('a resync defers a section holding an edited, unfocused form behind the pill', () => {
    const r = signalReceiver();
    r.h.marks.mark(r.fake.orderForm);
    r.ws.message({ type: 'subscribed', topic: 'refinery-order:1' });
    r.flush();
    assert.deepEqual(r.refreshed, []);
    assert.equal(r.fake.pills.length, 1);
});

test('a pill click leaves a section with an edited form deferred', () => {
    const r = signalReceiver();
    r.h.marks.mark(r.fake.orderForm);
    r.ws.message({ type: 'subscribed', topic: 'refinery-order:1' });
    r.fake.pills[0].listeners.click();
    r.flush();
    assert.deepEqual(r.refreshed, []);
});

test('saving or discarding the form runs the deferred refresh', () => {
    const r = signalReceiver();
    r.h.marks.mark(r.fake.orderForm);
    r.ws.message({ type: 'changed', topic: 'refinery-order:1', sections: ['order'] });
    r.flush();
    assert.deepEqual(r.refreshed, []);
    r.h.marks.clear(r.fake.orderForm);
    assert.deepEqual(r.refreshed, [['order']]);
});

test('the focus on the cleared form’s own button does not hold its section back', () => {
    const r = signalReceiver();
    r.h.marks.mark(r.fake.orderForm);
    r.ws.message({ type: 'changed', topic: 'refinery-order:1', sections: ['order'] });
    r.flush();
    r.h.document.activeElement = r.fake.saveButton;
    r.h.marks.clear(r.fake.orderForm);
    assert.deepEqual(r.refreshed, [['order']]);
});

test('the focus elsewhere in the section still holds it back once the form is cleared', () => {
    const r = signalReceiver();
    r.h.marks.mark(r.fake.orderForm);
    r.ws.message({ type: 'changed', topic: 'refinery-order:1', sections: ['order'] });
    r.flush();
    r.h.document.activeElement = r.fake.filterField;
    r.h.marks.clear(r.fake.orderForm);
    assert.deepEqual(r.refreshed, []);
    assert.equal(r.fake.pills.length, 1);
});

test('the pill rises above the tallest sticky form action bar', () => {
    const r = signalReceiver();
    r.fake.stickyBars.push({ offsetHeight: 0 }, { offsetHeight: 69 });
    r.h.marks.mark(r.fake.orderForm);
    r.ws.message({ type: 'subscribed', topic: 'refinery-order:1' });
    assert.deepEqual({ ...r.fake.pills[0].style.props }, { '--krt-livesync-pill-lift': '69px' });
});

test('a pill on a page without a sticky action bar keeps its place', () => {
    const r = signalReceiver();
    r.h.marks.mark(r.fake.orderForm);
    r.ws.message({ type: 'subscribed', topic: 'refinery-order:1' });
    assert.deepEqual({ ...r.fake.pills[0].style.props }, {});
});

test('clearing an unmarked form refreshes nothing', () => {
    const r = signalReceiver();
    r.h.marks.clear(r.fake.orderForm);
    r.flush();
    assert.deepEqual(r.refreshed, []);
});

if (failures > 0) {
    console.error(`${failures} failed`);
    process.exit(1);
}

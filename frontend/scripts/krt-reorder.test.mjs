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
 * Regression tests for the shared accessible reorder: the up/down controls, Alt+ArrowUp/ArrowDown,
 * the boundary marks, focus retention, the polite announcement and the coarse-pointer drag stop
 * (REQ-UI-031).
 */

import assert from 'node:assert/strict';
import vm from 'node:vm';
import { flattenModule } from './flatten-module.mjs';

const SIMPLE =
    /^(\w+)?((?:\.[\w-]+)*)((?:\[[\w-]+(?:="[^"]*")?\])*)((?::not\(\[[\w-]+="[^"]*"\]\))*)$/;
const ATTR = /\[([\w-]+)(?:="([^"]*)")?\]/g;

/**
 * Tests one simple selector against an element.
 *
 * @param {FakeElement} el the element
 * @param {string} selector a tag, classes, attribute tests and `:not([a="v"])`
 * @returns {boolean} whether it matches
 */
function matchesSimple(el, selector) {
    const m = SIMPLE.exec(selector.trim());
    if (!m) throw new Error(`unsupported selector ${selector}`);
    const [, tag, classes, attrs, nots] = m;
    if (tag && el.tag !== tag) return false;
    for (const cls of classes.split('.').filter(Boolean)) {
        if (!(el.attrs.class || '').split(' ').includes(cls)) return false;
    }
    for (const [, name, value] of attrs.matchAll(ATTR)) {
        const actual = el.getAttribute(name);
        if (actual === null || (value !== undefined && actual !== value)) return false;
    }
    for (const [, name, value] of nots.matchAll(ATTR)) {
        if (el.getAttribute(name) === value) return false;
    }
    return true;
}

const doc = {
    activeElement: null,
    root: null,
    querySelector(selector) {
        return this.root ? this.root.querySelector(selector) : null;
    },
};

/** A minimal element: attributes, children, sibling moves, listeners and focus. */
class FakeElement {
    constructor(tag, attrs = {}) {
        this.tag = tag;
        this.attrs = { ...attrs };
        this.children = [];
        this.parent = null;
        this.listeners = {};
        this.textContent = '';
    }

    get dataset() {
        const out = {};
        for (const [k, v] of Object.entries(this.attrs)) {
            if (k.startsWith('data-')) {
                out[k.slice(5).replace(/-(\w)/g, (_, c) => c.toUpperCase())] = v;
            }
        }
        return out;
    }

    get isConnected() {
        let el = this;
        while (el.parent) el = el.parent;
        return el === doc.root;
    }

    getAttribute(name) {
        return Object.hasOwn(this.attrs, name) ? this.attrs[name] : null;
    }

    setAttribute(name, value) {
        this.attrs[name] = String(value);
    }

    append(...kids) {
        for (const kid of kids) {
            kid.detach();
            kid.parent = this;
            this.children.push(kid);
        }
        return this;
    }

    detach() {
        if (this.parent) {
            this.parent.children = this.parent.children.filter((c) => c !== this);
            this.parent = null;
        }
    }

    before(node) {
        const parent = this.parent;
        node.detach();
        node.parent = parent;
        parent.children.splice(parent.children.indexOf(this), 0, node);
        if (doc.activeElement && node.contains(doc.activeElement)) doc.activeElement = null;
    }

    after(node) {
        const parent = this.parent;
        node.detach();
        node.parent = parent;
        parent.children.splice(parent.children.indexOf(this) + 1, 0, node);
        if (doc.activeElement && node.contains(doc.activeElement)) doc.activeElement = null;
    }

    matches(selector) {
        return selector.split(',').some((part) => matchesSimple(this, part));
    }

    closest(selector) {
        for (let el = this; el; el = el.parent) {
            if (el.matches(selector)) return el;
        }
        return null;
    }

    contains(other) {
        for (let el = other; el; el = el.parent) {
            if (el === this) return true;
        }
        return false;
    }

    querySelectorAll(selector) {
        const found = [];
        const walk = (el) => {
            for (const child of el.children) {
                if (child.matches(selector)) found.push(child);
                walk(child);
            }
        };
        walk(this);
        return found;
    }

    querySelector(selector) {
        return this.querySelectorAll(selector)[0] ?? null;
    }

    focus() {
        doc.activeElement = this;
    }

    addEventListener(type, listener) {
        (this.listeners[type] ??= []).push(listener);
    }

    fire(type, init = {}) {
        const event = {
            type,
            target: this,
            defaultPrevented: false,
            stopped: false,
            preventDefault() {
                this.defaultPrevented = true;
            },
            stopPropagation() {
                this.stopped = true;
            },
            ...init,
        };
        for (let el = this; el; el = el.parent) {
            for (const listener of el.listeners[type] ?? []) listener(event);
        }
        return event;
    }
}

let pointerFine = true;
const context = vm.createContext({
    document: doc,
    window: { matchMedia: () => ({ matches: pointerFine }) },
});
vm.runInContext(flattenModule('kernel/krt-reorder.js'), context);
const krtReorder = vm.runInContext('krtReorder', context);
const formatReorderAnnouncement = vm.runInContext('formatReorderAnnouncement', context);
const reorderTarget = vm.runInContext('reorderTarget', context);
const reorderKeyDirection = vm.runInContext('reorderKeyDirection', context);

/**
 * Builds a list of rows with a link and an up/down pair each, plus a live region.
 *
 * @param {string[]} ids the row ids in their order
 */
function buildList(ids) {
    const root = new FakeElement('main');
    const container = new FakeElement('div', { id: 'list' });
    const live = new FakeElement('p', {
        'data-reorder-live': '',
        'data-reorder-announce': '{name} moved from {from} to {to} of {total}.',
    });
    root.append(container, live);
    for (const id of ids) {
        const row = new FakeElement('tr', {
            class: 'draggable-row',
            'data-id': id,
            'data-reorder-name': `Item ${id}`,
        });
        row.append(
            new FakeElement('a', { href: `/x/${id}` }),
            new FakeElement('button', { 'data-reorder-move': 'up' }),
            new FakeElement('button', { 'data-reorder-move': 'down' }),
        );
        container.append(row);
    }
    doc.root = root;
    doc.activeElement = null;
    const row = (id) => container.children.find((r) => r.getAttribute('data-id') === id);
    const button = (id, dir) => row(id).querySelector(`[data-reorder-move="${dir}"]`);
    const order = () => container.children.map((r) => r.getAttribute('data-id')).join(',');
    return { container, live, row, button, order };
}

/**
 * Simulates a server re-render: every row is replaced by a fresh copy in the given order.
 *
 * @param {{container: FakeElement}} list the list
 * @param {string[]} ids the order the server renders
 */
function rerender(list, ids) {
    list.container.children.forEach((c) => {
        c.parent = null;
    });
    list.container.children = [];
    ids.forEach((id, i) => {
        const row = new FakeElement('tr', {
            class: 'draggable-row',
            'data-id': id,
            'data-reorder-name': `Item ${id}`,
        });
        row.append(
            new FakeElement('a', { href: `/x/${id}` }),
            new FakeElement('button', {
                'data-reorder-move': 'up',
                'aria-disabled': String(i === 0),
            }),
            new FakeElement('button', {
                'data-reorder-move': 'down',
                'aria-disabled': String(i === ids.length - 1),
            }),
        );
        list.container.append(row);
    });
}

let failures = 0;

/** Runs one named case, recording rather than throwing so every case reports. */
async function test(name, fn) {
    try {
        await fn();
        console.log(`ok - ${name}`);
    } catch (e) {
        failures += 1;
        console.error(`not ok - ${name}\n${e.stack}`);
    }
}

const settle = () => new Promise((resolve) => setImmediate(resolve));

await test('the pattern fills its four placeholders and keeps unknown ones', () => {
    assert.equal(
        formatReorderAnnouncement('{name}: {from} -> {to} / {total} {x}', {
            name: 'A',
            from: 2,
            to: 1,
            total: 3,
        }),
        'A: 2 -> 1 / 3 {x}',
    );
});

await test('a move past either end has no target', () => {
    assert.equal(reorderTarget(0, 'up', 3), -1);
    assert.equal(reorderTarget(2, 'down', 3), -1);
    assert.equal(reorderTarget(1, 'up', 3), 0);
    assert.equal(reorderTarget(1, 'down', 3), 2);
    assert.equal(reorderTarget(-1, 'down', 3), -1);
});

await test('only a bare Alt+ArrowUp/ArrowDown asks for a move', () => {
    const key = (k, mods = {}) => ({
        key: k,
        altKey: false,
        ctrlKey: false,
        metaKey: false,
        shiftKey: false,
        ...mods,
    });
    assert.equal(reorderKeyDirection(key('ArrowUp', { altKey: true })), 'up');
    assert.equal(reorderKeyDirection(key('ArrowDown', { altKey: true })), 'down');
    assert.equal(reorderKeyDirection(key('ArrowUp')), null);
    assert.equal(reorderKeyDirection(key('ArrowUp', { altKey: true, shiftKey: true })), null);
    assert.equal(reorderKeyDirection(key('ArrowUp', { altKey: true, ctrlKey: true })), null);
    assert.equal(reorderKeyDirection(key('ArrowLeft', { altKey: true })), null);
});

await test('attaching marks the first up and the last down control as unavailable', () => {
    const list = buildList(['a', 'b', 'c']);
    krtReorder.attach({
        container: list.container,
        itemSelector: 'tr.draggable-row',
        persist: async () => true,
    });
    assert.equal(list.button('a', 'up').getAttribute('aria-disabled'), 'true');
    assert.equal(list.button('a', 'down').getAttribute('aria-disabled'), 'false');
    assert.equal(list.button('c', 'down').getAttribute('aria-disabled'), 'true');
    assert.equal(list.button('b', 'up').getAttribute('aria-disabled'), 'false');
});

await test('a click on down moves the row, persists the new order and announces it', async () => {
    const list = buildList(['a', 'b', 'c']);
    const seen = [];
    krtReorder.attach({
        container: list.container,
        itemSelector: 'tr.draggable-row',
        async persist(move) {
            seen.push({
                id: move.row.getAttribute('data-id'),
                from: move.from,
                to: move.to,
                order: move.rows.map((r) => r.getAttribute('data-id')).join(','),
            });
            return true;
        },
    });
    list.button('a', 'down').focus();
    list.button('a', 'down').fire('click');
    await settle();
    assert.equal(list.order(), 'b,a,c');
    assert.deepEqual(seen, [{ id: 'a', from: 0, to: 1, order: 'b,a,c' }]);
    assert.equal(list.live.textContent, 'Item a moved from 1 to 2 of 3.');
    assert.equal(doc.activeElement, list.button('a', 'down'));
});

await test('Alt+ArrowUp on a focused link moves the row and keeps the focus on that link', async () => {
    const list = buildList(['a', 'b', 'c']);
    krtReorder.attach({
        container: list.container,
        itemSelector: 'tr.draggable-row',
        persist: async () => true,
    });
    const link = list.row('c').querySelector('a[href]');
    link.focus();
    const event = link.fire('keydown', {
        key: 'ArrowUp',
        altKey: true,
        ctrlKey: false,
        metaKey: false,
        shiftKey: false,
    });
    await settle();
    assert.equal(event.defaultPrevented, true);
    assert.equal(list.order(), 'a,c,b');
    assert.equal(doc.activeElement, link);
    assert.equal(list.live.textContent, 'Item c moved from 3 to 2 of 3.');
});

await test('the focus follows the item onto the re-rendered row', async () => {
    const list = buildList(['a', 'b', 'c']);
    krtReorder.attach({
        container: list.container,
        itemSelector: 'tr.draggable-row',
        async persist() {
            rerender(list, ['b', 'a', 'c']);
            return true;
        },
    });
    list.button('a', 'down').focus();
    list.button('a', 'down').fire('click');
    await settle();
    assert.equal(doc.activeElement, list.button('a', 'down'));
    assert.notEqual(doc.activeElement, null);
});

await test('an unavailable control and a move past the end change nothing', async () => {
    const list = buildList(['a', 'b']);
    let calls = 0;
    krtReorder.attach({
        container: list.container,
        itemSelector: 'tr.draggable-row',
        async persist() {
            calls += 1;
            return true;
        },
    });
    list.button('a', 'up').fire('click');
    list.row('b').querySelector('a[href]').fire('keydown', {
        key: 'ArrowDown',
        altKey: true,
        ctrlKey: false,
        metaKey: false,
        shiftKey: false,
    });
    await settle();
    assert.equal(list.order(), 'a,b');
    assert.equal(calls, 0);
    assert.equal(list.live.textContent, '');
});

await test('a refused move is not announced', async () => {
    const list = buildList(['a', 'b']);
    krtReorder.attach({
        container: list.container,
        itemSelector: 'tr.draggable-row',
        persist: async () => false,
    });
    list.button('b', 'up').fire('click');
    await settle();
    assert.equal(list.live.textContent, '');
});

await test('a second move while one is saving is ignored', async () => {
    const list = buildList(['a', 'b', 'c']);
    let release;
    let calls = 0;
    krtReorder.attach({
        container: list.container,
        itemSelector: 'tr.draggable-row',
        persist() {
            calls += 1;
            return new Promise((resolve) => {
                release = resolve;
            });
        },
    });
    list.button('c', 'up').fire('click');
    list.button('c', 'up').fire('click');
    await settle();
    assert.equal(calls, 1);
    release(true);
    await settle();
    assert.equal(list.order(), 'a,c,b');
});

await test('a drag on a coarse pointer is stopped, on a fine pointer it passes', () => {
    const list = buildList(['a', 'b']);
    krtReorder.attach({
        container: list.container,
        itemSelector: 'tr.draggable-row',
        persist: async () => true,
    });
    pointerFine = false;
    const stopped = list.row('a').fire('dragstart');
    assert.equal(stopped.defaultPrevented, true);
    assert.equal(stopped.stopped, true);
    pointerFine = true;
    const passed = list.row('a').fire('dragstart');
    assert.equal(passed.defaultPrevented, false);
});

if (failures > 0) {
    console.error(`${failures} test(s) failed`);
    process.exit(1);
}

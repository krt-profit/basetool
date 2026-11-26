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
 * Regression tests for the tab-scoped form drafts (REQ-FE-041): the store, the form snapshot, the
 * row-count adapter and the page wiring run in a vm context over a small fake DOM, a fake session
 * storage, manual timers and recorded document listeners.
 */

import assert from 'node:assert/strict';
import vm from 'node:vm';
import { flattenModule, UI_STUBS } from './flatten-module.mjs';

const SOURCE = [
    flattenModule('kernel/krt-drafts.js', UI_STUBS),
    flattenModule('kernel/krt-draft-rows.js'),
    flattenModule('kernel/krt-draft-lead.js'),
].join('\n');

const PATH = '/refinery-orders/create';
const SCOPE = 'scope-a';
const ORG_UNIT = 'ORG_UNIT:1';
const FORM_KEY = 'refinery-order-create';
const FIRST_FIELD =
    'input:not([type="hidden"]):not([disabled]), select:not([disabled]), textarea:not([disabled])';
const SIMPLE_SELECTOR = /^(?<tag>[a-z]+)?(?<cls>\.[\w-]+)?(?<attr>\[[\w-]+\])?$/;

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

/** An element of the fake DOM with attributes, classes, children and recorded events. */
class FakeElement {
    constructor(tagName, attributes = {}, classes = []) {
        this.tagName = tagName.toUpperCase();
        this.attrs = new Map(Object.entries(attributes));
        this.classList = Object.assign([...classes], {
            contains(name) {
                return this.includes(name);
            },
        });
        this.parent = null;
        this.children = [];
        this.fired = [];
        this.listeners = {};
        this.hidden = false;
        this.connected = true;
    }

    /** The `data-*` attributes under their camel-case names, read and written through. */
    get dataset() {
        const attribute = (key) =>
            `data-${String(key).replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`)}`;
        return new Proxy(
            {},
            {
                get: (_, key) => this.getAttribute(attribute(key)) ?? undefined,
                set: (_, key, value) => {
                    this.setAttribute(attribute(key), value);
                    return true;
                },
                deleteProperty: (_, key) => {
                    this.removeAttribute(attribute(key));
                    return true;
                },
            },
        );
    }

    /** The attributes as the DOM lists them, each with its name and value. */
    get attributes() {
        return Array.from(this.attrs, ([name, value]) => ({ name, value }));
    }

    get id() {
        return this.getAttribute('id') || '';
    }

    get isConnected() {
        return this.connected;
    }

    prepend(child) {
        child.parent = this;
        this.children.unshift(child);
    }

    after(node) {
        const siblings = this.parent.children;
        siblings.splice(siblings.indexOf(this) + 1, 0, node);
        node.parent = this.parent;
    }

    get nextElementSibling() {
        if (!this.parent) {
            return null;
        }
        const siblings = this.parent.children;
        return siblings[siblings.indexOf(this) + 1] || null;
    }

    remove() {
        if (this.parent) {
            this.parent.children.splice(this.parent.children.indexOf(this), 1);
            this.parent = null;
        }
    }

    click() {
        this.clicked = (this.clicked || 0) + 1;
    }

    append(...children) {
        children.forEach((child) => {
            child.parent = this;
            this.children.push(child);
        });
        return this;
    }

    descendants() {
        return this.children.flatMap((child) => [child, ...child.descendants()]);
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

    removeAttribute(name) {
        this.attrs.delete(name);
    }

    matches(selector) {
        return selector.split(',').some((part) => {
            const match = SIMPLE_SELECTOR.exec(part.trim());
            if (!match) {
                throw new Error(`unsupported selector ${part}`);
            }
            const { tag, cls, attr } = match.groups;
            return (
                (!tag || this.tagName === tag.toUpperCase()) &&
                (!cls || this.classList.includes(cls.slice(1))) &&
                (!attr || this.hasAttribute(attr.slice(1, -1)))
            );
        });
    }

    closest(selector) {
        for (let node = this; node; node = node.parent) {
            if (node.matches(selector)) {
                return node;
            }
        }
        return null;
    }

    contains(node) {
        return node === this || this.descendants().includes(node);
    }

    querySelectorAll(selector) {
        const all = this.descendants();
        if (selector === '.krt-combobox > input[type="hidden"]') {
            return all.filter(
                (el) => el.type === 'hidden' && el.parent && el.parent.matches('.krt-combobox'),
            );
        }
        if (selector === '.krt-modal-head h2') {
            return all.filter(
                (el) => el.tagName === 'H2' && el.parent && el.parent.matches('.krt-modal-head'),
            );
        }
        if (selector === FIRST_FIELD) {
            return all.filter(
                (el) =>
                    (el.tagName === 'INPUT' && el.type !== 'hidden' && !el.disabled) ||
                    ((el.tagName === 'SELECT' || el.tagName === 'TEXTAREA') && !el.disabled),
            );
        }
        return all.filter((el) => el.matches(selector));
    }

    querySelector(selector) {
        return this.querySelectorAll(selector)[0] || null;
    }

    addEventListener(type, fn) {
        (this.listeners[type] ||= []).push(fn);
    }

    dispatchEvent(event) {
        this.fired.push(event.type);
        return true;
    }

    focus() {
        this.focused = true;
    }
}

/** An `<input>` with the properties the draft code reads. */
class FakeInput extends FakeElement {
    constructor(type, attributes = {}, props = {}) {
        super('input', { type, ...attributes });
        this.type = type;
        this.value = props.value ?? '';
        this.checked = !!props.checked;
        this.disabled = !!props.disabled;
        this.readOnly = !!props.readOnly;
    }
}

/** A `<select>` over a list of option values. */
class FakeSelect extends FakeElement {
    constructor(attributes = {}, values = [], props = {}) {
        super('select', attributes);
        this.multiple = !!props.multiple;
        this.disabled = !!props.disabled;
        this.options = values.map((value) => ({ value, selected: false }));
    }

    get selectedOptions() {
        return this.options.filter((option) => option.selected);
    }

    get value() {
        const chosen = this.options.find((option) => option.selected);
        return chosen ? chosen.value : '';
    }

    set value(value) {
        this.options.forEach((option) => {
            option.selected = option.value === value;
        });
    }
}

/** A `<textarea>`. */
class FakeTextArea extends FakeElement {
    constructor(attributes = {}, props = {}) {
        super('textarea', attributes);
        this.value = props.value ?? '';
        this.disabled = false;
        this.readOnly = false;
    }
}

/** A `<button>` that can be disabled. */
class FakeButton extends FakeElement {
    constructor(attributes = {}) {
        super('button', attributes);
        this.disabled = false;
    }
}

/** A `<template>` whose content clones a prepared element. */
class FakeTemplate extends FakeElement {
    constructor(attributes, clone) {
        super('template', attributes);
        this.content = { firstElementChild: { cloneNode: clone } };
    }
}

/** A `<form>` whose `elements` are its controls in document order. */
class FakeForm extends FakeElement {
    constructor(attributes = {}) {
        super('form', attributes);
    }

    get elements() {
        return this.descendants().filter(
            (el) =>
                el instanceof FakeInput || el instanceof FakeSelect || el instanceof FakeTextArea,
        );
    }
}

/**
 * A combobox wrapper with its hidden value input, its visible text input and a recording
 * controller.
 *
 * @param {string} name the hidden input's name
 * @param {{value: string, label: string, data: object}} selection the committed selection
 */
function combobox(name, selection) {
    const wrapper = new FakeElement('div', {}, ['krt-combobox']);
    const hidden = new FakeInput('hidden', { name, id: name });
    const text = new FakeInput('text', { id: `${name}-input` }, { value: selection.label });
    const calls = [];
    hidden.krtCombobox = {
        selection: () => ({ ...selection, data: { ...selection.data } }),
        setValue: (value, label, data) => calls.push({ value, label, data: { ...data } }),
    };
    wrapper.append(hidden, text);
    return { wrapper, hidden, text, calls };
}

/** A session storage over a map, which can be told to refuse writes. */
function fakeStorage(entries = {}) {
    const map = new Map(Object.entries(entries));
    return {
        map,
        refuse: false,
        get length() {
            return map.size;
        },
        key: (i) => Array.from(map.keys())[i] ?? null,
        getItem: (k) => (map.has(k) ? map.get(k) : null),
        setItem(k, v) {
            if (this.refuse) {
                throw new Error('QuotaExceededError');
            }
            map.set(k, String(v));
        },
        removeItem: (k) => map.delete(k),
    };
}

/**
 * Builds the refinery-create page: a sidebar carrying the member scope and org unit, a drafted
 * form with its restore offer and every kind of control, and a logout form.
 */
function page() {
    const sidebar = new FakeElement('div', {
        'data-krt-draft-scope': SCOPE,
        'data-active-org-unit-context': ORG_UNIT,
    });
    const form = new FakeForm({ 'data-krt-draft': FORM_KEY, 'data-krt-draft-version': '1' });
    const offer = new FakeElement('div', { 'data-krt-draft-offer': '' });
    offer.hidden = true;
    const restore = new FakeElement('button', { 'data-krt-draft-action': 'restore' });
    const discard = new FakeElement('button', { 'data-krt-draft-action': 'discard' });
    offer.append(discard, restore);
    const controls = {
        location: new FakeSelect({ name: 'locationId', id: 'locationId' }, ['', 'hub-1', 'hub-2']),
        tags: new FakeSelect({ name: 'tags' }, ['a', 'b', 'c'], { multiple: true }),
        hours: new FakeInput('text', { name: 'durationHours', id: 'durationHours' }),
        date: new FakeInput('date', { id: 'startedAtDate' }),
        note: new FakeTextArea({ name: 'note' }),
        urgent: new FakeInput('checkbox', { name: 'urgent' }, { value: 'on' }),
        sizeS: new FakeInput('radio', { name: 'size' }, { value: 's' }),
        sizeL: new FakeInput('radio', { name: 'size' }, { value: 'l' }),
        source: new FakeInput('hidden', { name: 'source' }, { value: 'MANUAL' }),
        csrf: new FakeInput('text', { name: '_csrf' }, { value: 'token' }),
        version: new FakeInput('text', { name: 'goods[0].version' }, { value: '7' }),
        secret: new FakeInput('password', { name: 'secret' }, { value: 'pw' }),
        file: new FakeInput('file', { name: 'file' }),
        scu: new FakeInput('text', { id: 'scu_0' }, { readOnly: true, value: '0.8' }),
        owner: new FakeSelect({ name: 'ownerId' }, ['me'], { disabled: true }),
        skipped: new FakeInput('text', { name: 'search', 'data-krt-draft-skip': '' }),
        nameless: new FakeInput('text'),
    };
    const material = combobox('goods[0].inputMaterialId', {
        value: 'ore-1',
        label: 'Quantanium (Raw)',
        data: { refinedName: 'Quantanium' },
    });
    form.append(offer, ...Object.values(controls), material.wrapper);
    const logout = new FakeForm({ action: '/logout' });
    const body = new FakeElement('body').append(sidebar, form, logout);
    return { body, form, offer, restore, discard, controls, material, logout };
}

/**
 * Loads the draft scripts over a page and a storage, with manual timers and recorded document and
 * window listeners; a page still loading runs them only once `DOMContentLoaded` is fired.
 */
function harness(storageEntries = {}, dom = page(), readyState = 'complete') {
    const storage = fakeStorage(storageEntries);
    const timers = [];
    const docListeners = {};
    const winListeners = {};
    const observers = [];
    class FakeEvent {
        constructor(type, init = {}) {
            this.type = type;
            this.bubbles = !!init.bubbles;
            this.isTrusted = false;
        }
    }
    class FakeCustomEvent extends FakeEvent {
        constructor(type, init = {}) {
            super(type, init);
            this.detail = init.detail;
        }
    }
    const document = {
        readyState,
        visibilityState: 'visible',
        querySelector: (selector) => dom.body.querySelector(selector),
        querySelectorAll: (selector) => dom.body.querySelectorAll(selector),
        getElementById: (id) => dom.body.descendants().find((el) => el.id === id) || null,
        addEventListener(type, fn) {
            (docListeners[type] ||= []).push(fn);
        },
        dispatchEvent(event) {
            (docListeners[event.type] || []).forEach((fn) => fn(event));
            return true;
        },
    };
    const context = {
        document,
        sessionStorage: storage,
        location: { pathname: PATH, href: `https://example.test${PATH}` },
        URL,
        Event: FakeEvent,
        CustomEvent: FakeCustomEvent,
        Element: FakeElement,
        HTMLElement: FakeElement,
        HTMLFormElement: FakeForm,
        HTMLInputElement: FakeInput,
        HTMLSelectElement: FakeSelect,
        HTMLTextAreaElement: FakeTextArea,
        HTMLButtonElement: FakeButton,
        HTMLTemplateElement: FakeTemplate,
        MutationObserver: class {
            constructor(callback) {
                this.callback = callback;
                this.targets = [];
                observers.push(this);
            }

            observe(target) {
                this.targets.push(target);
            }

            disconnect() {
                this.targets = [];
            }
        },
        setTimeout(fn) {
            const timer = { fn, live: true };
            timers.push(timer);
            return timer;
        },
        clearTimeout(timer) {
            if (timer) {
                timer.live = false;
            }
        },
        addEventListener(type, fn) {
            (winListeners[type] ||= []).push(fn);
        },
        encodeURIComponent,
        JSON,
    };
    context.window = context;
    vm.createContext(context);
    vm.runInContext(SOURCE, context);
    const key = vm.runInContext(
        `draftKey(${JSON.stringify(SCOPE)}, ${JSON.stringify(ORG_UNIT)}, ${JSON.stringify(FORM_KEY)}, ${JSON.stringify(PATH)})`,
        context,
    );
    return {
        ...dom,
        storage,
        key,
        context,
        run: (code) => vm.runInContext(code, context),
        edit(control, type = 'input') {
            (docListeners[type] || []).forEach((fn) =>
                fn({ type, target: control, isTrusted: true }),
            );
        },
        fire(type, event) {
            (docListeners[type] || []).forEach((fn) => fn({ type, ...event }));
        },
        windowFire(type) {
            (winListeners[type] || []).forEach((fn) => fn({ type }));
        },
        click(button) {
            (dom.offer.listeners.click || []).forEach((fn) => fn({ target: button }));
        },
        flushTimers() {
            timers
                .filter((t) => t.live)
                .forEach((t) => {
                    t.live = false;
                    t.fn();
                });
        },
        stored() {
            const raw = storage.getItem(this.key);
            return raw === null ? null : JSON.parse(raw);
        },
        changed(target) {
            observers
                .filter((observer) => observer.targets.includes(target))
                .forEach((observer) => observer.callback([{ target }]));
        },
    };
}

/** A stored draft for the refinery create of `SCOPE`, with the given parts. */
function storedDraft(overrides = {}) {
    return JSON.stringify({
        version: '1',
        snapshot: { fields: { note: 'from the draft' }, combos: {} },
        extra: { rows: 2 },
        ...overrides,
    });
}

/** The storage key of a draft, built like the module builds it. */
function keyFor(scope, orgUnit = ORG_UNIT, formKey = FORM_KEY, path = PATH) {
    return `krt-draft:${[scope, orgUnit, formKey, path].map(encodeURIComponent).join(':')}`;
}

test('a snapshot keeps visible values and the combobox selection, never a guarded field', () => {
    const h = harness();
    const c = h.controls;
    c.location.value = 'hub-2';
    c.tags.options[0].selected = true;
    c.tags.options[2].selected = true;
    c.hours.value = '2';
    c.date.value = '2026-11-25';
    c.note.value = 'twelve rows';
    c.urgent.checked = true;
    c.sizeL.checked = true;
    const snapshot = JSON.parse(JSON.stringify(h.context.snapshotForm(h.form)));
    assert.deepEqual(snapshot.fields, {
        locationId: 'hub-2',
        tags: ['a', 'c'],
        durationHours: '2',
        startedAtDate: '2026-11-25',
        note: 'twelve rows',
        'urgent=on': true,
        size: 'l',
    });
    assert.deepEqual(snapshot.combos, {
        'goods[0].inputMaterialId': {
            value: 'ore-1',
            label: 'Quantanium (Raw)',
            data: { refinedName: 'Quantanium' },
        },
    });
});

test('a restore writes the values and the combobox selection and announces each control', () => {
    const h = harness();
    h.context.restoreForm(h.form, {
        fields: { locationId: 'hub-1', tags: ['b'], note: 'back', 'urgent=on': true, size: 's' },
        combos: {
            'goods[0].inputMaterialId': {
                value: 'ore-2',
                label: 'Agricium (Raw)',
                data: { refinedName: 'Agricium' },
            },
        },
    });
    const c = h.controls;
    assert.equal(c.location.value, 'hub-1');
    assert.deepEqual(
        c.tags.selectedOptions.map((o) => o.value),
        ['b'],
    );
    assert.equal(c.note.value, 'back');
    assert.equal(c.urgent.checked, true);
    assert.equal(c.sizeS.checked, true);
    assert.equal(c.sizeL.checked, false);
    assert.deepEqual(h.material.calls, [
        { value: 'ore-2', label: 'Agricium (Raw)', data: { refinedName: 'Agricium' } },
    ]);
    assert.deepEqual(c.note.fired, ['input', 'change']);
    assert.deepEqual(h.material.hidden.fired, ['input', 'change']);
    assert.deepEqual(c.hours.fired, []);
    assert.deepEqual(c.source.fired, []);
});

test('a page drops the drafts of every other member and keeps its own', () => {
    const own = keyFor(SCOPE);
    const foreign = keyFor('scope-b');
    const h = harness({ [own]: storedDraft(), [foreign]: storedDraft(), unrelated: 'kept' });
    assert.ok(h.storage.map.has(own));
    assert.ok(!h.storage.map.has(foreign));
    assert.equal(h.storage.getItem('unrelated'), 'kept');
});

test('a stored draft is offered on a pristine form and a restore rebuilds rows before values', () => {
    const h = harness({ [keyFor(SCOPE)]: storedDraft() });
    assert.equal(h.offer.hidden, false);
    const seen = [];
    h.context.registerDraftAdapter(FORM_KEY, {
        serialize: () => null,
        restore: (form, extra) => seen.push({ rows: extra.rows, note: h.controls.note.value }),
    });
    h.click(h.restore);
    assert.deepEqual(seen, [{ rows: 2, note: '' }]);
    assert.equal(h.controls.note.value, 'from the draft');
    assert.equal(h.offer.hidden, true);
    assert.ok(h.controls.location.focused);
});

test('discarding the offer removes the stored draft', () => {
    const h = harness({ [keyFor(SCOPE)]: storedDraft() });
    h.click(h.discard);
    assert.equal(h.stored(), null);
    assert.equal(h.offer.hidden, true);
    assert.equal(h.controls.note.value, '');
});

test('a draft of another schema version is dropped and never offered', () => {
    const h = harness({ [keyFor(SCOPE)]: storedDraft({ version: '0' }) });
    assert.equal(h.offer.hidden, true);
    assert.equal(h.stored(), null);
});

test('a form that already holds unsaved edits is not offered its draft', () => {
    const dom = page();
    dom.form.setAttribute('data-krt-unsaved', '');
    const h = harness({ [keyFor(SCOPE)]: storedDraft() }, dom);
    assert.equal(h.offer.hidden, true);
});

test('an edit writes the draft after the pause only while the form holds unsaved edits', () => {
    const h = harness();
    h.controls.note.value = 'typed';
    h.edit(h.controls.note);
    h.flushTimers();
    assert.equal(h.stored(), null);

    h.form.setAttribute('data-krt-unsaved', '');
    h.context.registerDraftAdapter(FORM_KEY, { serialize: () => ({ rows: 3 }), restore() {} });
    h.edit(h.controls.note);
    assert.equal(h.stored(), null);
    h.flushTimers();
    const draft = h.stored();
    assert.equal(draft.version, '1');
    assert.equal(draft.snapshot.fields.note, 'typed');
    assert.deepEqual(draft.extra, { rows: 3 });
    const keys = Object.keys(draft.snapshot.fields);
    ['source', '_csrf', 'goods[0].version', 'secret', 'file', 'scu_0', 'ownerId', 'search'].forEach(
        (k) => {
            assert.ok(!keys.includes(k), `${k} must never be stored`);
        },
    );
});

test('a pending draft is written when the page is hidden', () => {
    const h = harness();
    h.form.setAttribute('data-krt-unsaved', '');
    h.controls.hours.value = '5';
    h.edit(h.controls.hours);
    h.windowFire('pagehide');
    assert.equal(h.stored().snapshot.fields.durationHours, '5');
});

test('an edit before choosing withdraws the offer and starts a new draft', () => {
    const h = harness({ [keyFor(SCOPE)]: storedDraft() });
    h.form.setAttribute('data-krt-unsaved', '');
    h.controls.note.value = 'new input';
    h.edit(h.controls.note);
    assert.equal(h.offer.hidden, true);
    assert.equal(h.stored(), null);
    h.flushTimers();
    assert.equal(h.stored().snapshot.fields.note, 'new input');
});

test('no draft is written while the offer stands', () => {
    const h = harness({ [keyFor(SCOPE)]: storedDraft() });
    h.form.setAttribute('data-krt-unsaved', '');
    h.controls.note.value = 'script input';
    h.fire('input', { target: h.controls.note, isTrusted: false });
    h.flushTimers();
    assert.equal(h.stored().snapshot.fields.note, 'from the draft');
    assert.equal(h.offer.hidden, false);
});

test('a cleared unsaved mark removes the draft', () => {
    const h = harness({ [keyFor(SCOPE)]: storedDraft() });
    h.fire('krt:unsaved-cleared', { detail: { form: h.form } });
    assert.equal(h.stored(), null);
});

/**
 * Takes a node out of the fake tree, as a fragment swap does with the form it replaces.
 *
 * @param {FakeElement} node the replaced node
 */
function detach(node) {
    node.parent.children.splice(node.parent.children.indexOf(node), 1);
    node.parent = null;
    node.connected = false;
}

/**
 * Adds the form a fragment swap puts under the drafted form's key: its restore offer with the
 * stale notice, a note field and a lock version.
 *
 * @param {object} dom the page from {@link page}
 * @param {string} version the lock version the new form was rendered with
 */
function successorForm(dom, version) {
    const form = new FakeForm({ 'data-krt-draft': FORM_KEY, 'data-krt-draft-version': '1' });
    const offer = new FakeElement('div', { 'data-krt-draft-offer': '' });
    offer.hidden = true;
    const stale = new FakeElement('p', { 'data-krt-draft-stale': '' });
    stale.hidden = true;
    offer.append(stale);
    const note = new FakeTextArea({ name: 'note' });
    const base = new FakeInput(
        'hidden',
        { name: 'gameDataVersion', 'data-krt-draft-base': '' },
        { value: version },
    );
    form.append(offer, note, base);
    dom.body.append(form);
    return { form, offer, stale, note, base };
}

/**
 * Gives the drafted form of a page a lock version and its offer the stale notice, as an edit form
 * carries them.
 *
 * @param {object} dom the page from {@link page}
 * @param {string} version the lock version the form was rendered with
 */
function asEditForm(dom, version) {
    const base = new FakeInput(
        'hidden',
        { name: 'gameDataVersion', 'data-krt-draft-base': '' },
        { value: version },
    );
    const stale = new FakeElement('p', { 'data-krt-draft-stale': '' });
    stale.hidden = true;
    dom.offer.append(stale);
    dom.form.append(base);
    return { base, stale };
}

test('a swap that replaces a drafted form holding edits removes its draft', () => {
    const h = harness();
    h.form.setAttribute('data-krt-unsaved', '');
    h.controls.note.value = 'typed';
    h.edit(h.controls.note);
    h.flushTimers();
    assert.ok(h.stored());
    detach(h.form);
    successorForm(h, '1');
    h.fire('krt:swapped', { detail: {} });
    assert.equal(h.stored(), null);
});

test('a swap that removes the drafted form removes its draft', () => {
    const h = harness({ [keyFor(SCOPE)]: storedDraft() });
    detach(h.form);
    h.fire('krt:swapped', { detail: {} });
    assert.equal(h.stored(), null);
});

test('a swap that replaces a form whose offer stands offers the draft again', () => {
    const h = harness({ [keyFor(SCOPE)]: storedDraft({ base: { gameDataVersion: '3' } }) });
    detach(h.form);
    const next = successorForm(h, '4');
    h.fire('krt:swapped', { detail: {} });
    assert.equal(next.offer.hidden, false);
    assert.equal(next.stale.hidden, false);
    assert.equal(h.stored().snapshot.fields.note, 'from the draft');
});

test('an edit form records the lock versions its edits were made against', () => {
    const h = harness();
    asEditForm(h, '3');
    h.form.setAttribute('data-krt-unsaved', '');
    h.controls.note.value = 'typed';
    h.edit(h.controls.note);
    h.flushTimers();
    assert.deepEqual({ ...h.stored().base }, { gameDataVersion: '3' });
    assert.ok(!('gameDataVersion' in h.stored().snapshot.fields));
});

test('an offer says so when the entry changed since the draft was made', () => {
    const fresh = page();
    const current = asEditForm(fresh, '3');
    harness({ [keyFor(SCOPE)]: storedDraft({ base: { gameDataVersion: '3' } }) }, fresh);
    assert.equal(fresh.offer.hidden, false);
    assert.equal(current.stale.hidden, true);

    const changed = page();
    const meanwhile = asEditForm(changed, '4');
    harness({ [keyFor(SCOPE)]: storedDraft({ base: { gameDataVersion: '3' } }) }, changed);
    assert.equal(changed.offer.hidden, false);
    assert.equal(meanwhile.stale.hidden, false);
});

test('a restore writes back the lock versions the edits were made against', () => {
    const dom = page();
    const edit = asEditForm(dom, '4');
    const h = harness({ [keyFor(SCOPE)]: storedDraft({ base: { gameDataVersion: '3' } }) }, dom);
    h.click(h.restore);
    assert.equal(h.controls.note.value, 'from the draft');
    assert.equal(edit.base.value, '3');
});

test('drafts of two entities edited in one tab stay apart', () => {
    const h = harness();
    const first = h.run(`draftKey('s', 'o', 'refinery-order-edit', '/refinery-orders/a-1')`);
    const second = h.run(`draftKey('s', 'o', 'refinery-order-edit', '/refinery-orders/b-2')`);
    assert.notEqual(first, second);
});

test('a create form matches a draft without versions', () => {
    const h = harness({ [keyFor(SCOPE)]: storedDraft() });
    assert.equal(h.run('sameBase(undefined, {})'), true);
    assert.equal(h.run("sameBase({ v: '1' }, {})"), false);
    assert.equal(h.run("sameBase('junk', { v: '1' })"), false);
    assert.equal(h.offer.hidden, false);
});

test('a logout removes every draft and writes none afterwards', () => {
    const other = keyFor(SCOPE, ORG_UNIT, 'job-order-create', '/orders/create');
    const h = harness({ [other]: storedDraft() });
    h.form.setAttribute('data-krt-unsaved', '');
    h.controls.note.value = 'pending';
    h.edit(h.controls.note);
    h.fire('submit', { target: h.logout });
    h.windowFire('pagehide');
    assert.equal(h.storage.length, 0);
});

test('submitting the form past a standing offer drops the stored draft', () => {
    const h = harness({ [keyFor(SCOPE)]: storedDraft() });
    h.fire('submit', { target: h.form });
    assert.equal(h.stored(), null);
    assert.equal(h.offer.hidden, true);
});

test('a malformed entry is removed and a refused write keeps no draft', () => {
    const h = harness({ [keyFor(SCOPE)]: '{not json' });
    assert.equal(h.stored(), null);
    h.storage.refuse = true;
    const written = h.run(
        `writeDraft(${JSON.stringify(h.key)}, { version: '1', snapshot: { fields: {}, combos: {} }, extra: null })`,
    );
    assert.equal(written, false);
});

/**
 * Adds a second drafted form with its own offer to a page.
 *
 * @param {object} dom the page from {@link page}
 * @param {string} formKey the second form's key
 * @returns {{form: object, offer: object}} the second form and its offer
 */
function addSecondForm(dom, formKey) {
    const form = new FakeForm({ 'data-krt-draft': formKey, 'data-krt-draft-version': '1' });
    const offer = new FakeElement('div', { 'data-krt-draft-offer': '' });
    offer.hidden = true;
    form.append(offer, new FakeInput('text', { name: 'handle' }));
    dom.body.append(form);
    return { form, offer };
}

test('a loading page offers its drafts on DOMContentLoaded, after the page registered adapters', () => {
    const h = harness({ [keyFor(SCOPE)]: storedDraft() }, page(), 'interactive');
    assert.equal(h.offer.hidden, true);
    const revealed = [];
    h.context.registerDraftAdapter(FORM_KEY, {
        serialize: () => null,
        restore() {},
        reveal: (form) => revealed.push(form),
    });
    h.fire('DOMContentLoaded', {});
    assert.equal(h.offer.hidden, false);
    assert.deepEqual(revealed, [h.form]);
});

test('only the first offer of a page reveals its form', () => {
    const dom = page();
    const second = addSecondForm(dom, 'second-form');
    const h = harness(
        {
            [keyFor(SCOPE)]: storedDraft(),
            [keyFor(SCOPE, ORG_UNIT, 'second-form')]: storedDraft(),
        },
        dom,
        'interactive',
    );
    const revealed = [];
    [FORM_KEY, 'second-form'].forEach((formKey) => {
        h.context.registerDraftAdapter(formKey, {
            serialize: () => null,
            restore() {},
            reveal: () => revealed.push(formKey),
        });
    });
    h.fire('DOMContentLoaded', {});
    assert.deepEqual(revealed, [FORM_KEY]);
    assert.equal(h.offer.hidden, false);
    assert.equal(second.offer.hidden, false);
});

test('a form switched to editing withdraws its offer and keeps its draft until it creates again', () => {
    const h = harness({ [keyFor(SCOPE)]: storedDraft() });
    assert.equal(h.offer.hidden, false);
    h.context.setDraftKey(h.form, null);
    assert.equal(h.offer.hidden, true);
    assert.equal(h.form.hasAttribute('data-krt-draft'), false);

    h.form.setAttribute('data-krt-unsaved', '');
    h.controls.note.value = 'editing';
    h.edit(h.controls.note);
    h.flushTimers();
    h.fire('krt:unsaved-cleared', { detail: { form: h.form } });
    assert.equal(h.stored().snapshot.fields.note, 'from the draft');

    h.form.removeAttribute('data-krt-unsaved');
    h.context.setDraftKey(h.form, FORM_KEY);
    assert.equal(h.offer.hidden, false);
    assert.equal(h.offer.listeners.click.length, 1);
    h.click(h.restore);
    assert.equal(h.controls.note.value, 'from the draft');
});

test('a pending snapshot is dropped when the form switches to editing', () => {
    const h = harness();
    h.form.setAttribute('data-krt-unsaved', '');
    h.controls.note.value = 'typed';
    h.edit(h.controls.note);
    h.context.setDraftKey(h.form, null);
    h.windowFire('pagehide');
    h.flushTimers();
    assert.equal(h.stored(), null);
});

test('switching a form before the page wires its drafts only sets its key', () => {
    const h = harness({ [keyFor(SCOPE)]: storedDraft() }, page(), 'interactive');
    const revealed = [];
    h.context.registerDraftAdapter(FORM_KEY, {
        serialize: () => null,
        restore() {},
        reveal: (form) => {
            revealed.push(form);
            h.context.setDraftKey(form, FORM_KEY);
        },
    });
    h.context.setDraftKey(h.form, null);
    h.context.setDraftKey(h.form, FORM_KEY);
    assert.equal(h.offer.hidden, true);
    h.fire('DOMContentLoaded', {});
    assert.equal(h.offer.hidden, false);
    assert.deepEqual(revealed, [h.form]);
    assert.equal(h.offer.listeners.click.length, 1);
});

/**
 * Builds rows a row-count adapter works on: a list that `add` grows and `remove` shrinks while
 * more than `floor` rows remain.
 *
 * @param {number} count the rows at the start
 * @param {number} floor the rows `remove` never goes below
 */
function rowList(count, floor = 1) {
    const rows = Array.from({ length: count }, (_, i) => ({ i }));
    return {
        rows,
        api: {
            rows: () => rows.slice(),
            add: () => rows.push({ i: rows.length }),
            remove(row) {
                if (rows.length > floor) {
                    rows.splice(rows.indexOf(row), 1);
                }
            },
        },
    };
}

test('a row-count adapter stores the rows and adds or removes rows to match on restore', () => {
    const h = harness();
    const list = rowList(3);
    const adapter = h.context.rowCountAdapter(list.api);
    assert.deepEqual({ ...adapter.serialize() }, { rows: 3 });
    adapter.restore(null, { rows: 5 });
    assert.equal(list.rows.length, 5);
    adapter.restore(null, { rows: 2 });
    assert.equal(list.rows.length, 2);
    adapter.restore(null, { rows: 'many' });
    assert.equal(list.rows.length, 1);
    assert.equal(adapter.reveal, undefined);
});

test('a row-count adapter stops when a row cannot be removed and passes reveal on', () => {
    const h = harness();
    const list = rowList(4, 3);
    const revealed = [];
    const adapter = h.context.rowCountAdapter({
        ...list.api,
        reveal: (form) => revealed.push(form),
    });
    adapter.restore(null, { rows: 1 });
    assert.equal(list.rows.length, 3);
    adapter.reveal('form');
    assert.deepEqual(revealed, ['form']);
});

test('a row-count adapter with a minimum of zero restores an empty list', () => {
    const h = harness();
    const list = rowList(2, 0);
    const adapter = h.context.rowCountAdapter({ ...list.api, minimum: 0 });
    adapter.restore(null, { rows: 0 });
    assert.equal(list.rows.length, 0);
    adapter.restore(null, { rows: 'none' });
    assert.equal(list.rows.length, 0);
    adapter.restore(null, { rows: 3 });
    assert.equal(list.rows.length, 3);
});

test('an adapter finishes the restore after the values are written and announced', () => {
    const h = harness({ [keyFor(SCOPE)]: storedDraft() });
    const steps = [];
    h.context.registerDraftAdapter(FORM_KEY, {
        serialize: () => null,
        restore: () => steps.push(`restore:${h.controls.note.value}`),
        finish: (form, extra) =>
            steps.push(
                `finish:${h.controls.note.value}:${h.controls.note.fired.join('+')}:${extra.rows}`,
            ),
    });
    h.click(h.restore);
    assert.deepEqual(steps, ['restore:', 'finish:from the draft:input+change:2']);
});

test('a leading-controls adapter keeps its controls and sets the enabled ones first, in order', () => {
    const h = harness();
    const form = new FakeForm();
    const type = new FakeSelect({ 'data-type': '' }, ['DEPOSIT', 'TRANSFER']);
    type.value = 'TRANSFER';
    const split = new FakeInput('checkbox', { 'data-split': '' }, { checked: true });
    const locked = new FakeSelect({ 'data-locked': '' }, ['a', 'b'], { disabled: true });
    locked.value = 'a';
    form.append(type, split, locked);
    const adapter = h.context.leadingControlsAdapter([
        '[data-type]',
        '[data-split]',
        '[data-locked]',
    ]);
    assert.deepEqual([...adapter.serialize(form)], ['TRANSFER', true, 'a']);

    type.value = 'DEPOSIT';
    split.checked = false;
    locked.value = 'b';
    adapter.restore(form, ['TRANSFER', true, 'a']);
    assert.equal(type.value, 'TRANSFER');
    assert.equal(split.checked, true);
    assert.equal(locked.value, 'b');
    assert.deepEqual(type.fired, ['change']);
    assert.deepEqual(split.fired, ['change']);
    assert.deepEqual(locked.fired, []);
    adapter.restore(form, 'junk');
    assert.deepEqual(type.fired, ['change']);
});

/**
 * Moves the drafted form of a page into a dialog, adds an opener of the dialog with the given
 * context, and the page-level offer template with the elements a test clicks.
 *
 * @param {object} dom the page from {@link page}
 * @param {object} [context] the opener's `data-field-*` attributes; no opener when null
 */
function inDialog(dom, context = {}) {
    detach(dom.form);
    dom.form.connected = true;
    const dialog = new FakeElement('dialog', { id: 'bank-movement-modal' }, ['krt-modal-overlay']);
    const heading = new FakeElement('h2');
    heading.textContent = 'Kontobewegung';
    const head = new FakeElement('div', {}, ['krt-modal-head']).append(heading);
    dialog.append(head, dom.form);
    const opener =
        context === null
            ? null
            : new FakeButton({ 'data-modal-id': 'bank-movement-modal', ...context });
    const notice = new FakeElement('div');
    const name = new FakeElement('strong', { 'data-krt-draft-dialog-name': '' });
    const restore = new FakeElement('button', { 'data-krt-draft-action': 'restore' });
    const discard = new FakeElement('button', { 'data-krt-draft-action': 'discard' });
    notice.append(name, restore, discard);
    const template = new FakeTemplate({ id: 'krt-draft-dialog-offer' }, () => notice);
    const main = new FakeElement('main');
    dom.body.append(main, dialog, template);
    if (opener) {
        opener.click = () => {
            opener.clicked = (opener.clicked || 0) + 1;
            dialog.classList.push('is-open');
        };
        main.append(opener);
    }
    return { dialog, opener, notice, name, restore, discard, main };
}

test('an edit in an untracked dialog form marks it, and its draft names the opener', () => {
    const dom = page();
    const dialog = inDialog(dom, { 'data-field-accountid': 'acc-1' });
    const h = harness({}, dom);
    h.flushTimers();
    h.fire('click', { target: dialog.opener });
    dialog.dialog.classList.push('is-open');
    h.changed(dialog.dialog);
    h.controls.note.value = 'typed';
    h.edit(h.controls.note);
    assert.ok(h.form.hasAttribute('data-krt-draft-edited'));
    assert.ok(!h.form.hasAttribute('data-krt-unsaved'));
    h.flushTimers();
    assert.equal(h.stored().opener, h.context.openerKey(dialog.opener));
});

test('an opener whose dialog opens later, after the page loaded its content, is still named', () => {
    const dom = page();
    const dialog = inDialog(dom, { 'data-need-id': 'need-7' });
    const other = new FakeButton({ 'data-trigger': 'other' });
    dialog.main.append(other);
    const h = harness({}, dom);
    h.fire('click', { target: dialog.opener });
    h.flushTimers();
    dialog.dialog.classList.push('is-open');
    h.changed(dialog.dialog);
    h.controls.note.value = 'typed';
    h.edit(h.controls.note);
    h.flushTimers();
    assert.equal(h.stored().opener, h.context.openerKey(dialog.opener));
    assert.notEqual(h.context.openerKey(dialog.opener), h.context.openerKey(other));
});

/**
 * Clicks a button of a page-level dialog offer.
 *
 * @param {object} dialog the parts from {@link inDialog}
 * @param {FakeElement} button the offer's button
 */
function clickOffer(dialog, button) {
    (dialog.notice.listeners.click || []).forEach((fn) => fn({ target: button }));
}

test('a dialog draft is offered on the page, and restore reopens the dialog through its opener', () => {
    const dom = page();
    const dialog = inDialog(dom);
    const key = harness().context.openerKey(dialog.opener);
    const h = harness({ [keyFor(SCOPE)]: storedDraft({ opener: key }) }, dom);
    assert.equal(dialog.main.children[0], dialog.notice);
    assert.equal(dialog.name.textContent, 'Kontobewegung');
    assert.equal(h.offer.hidden, true);
    clickOffer(dialog, dialog.restore);
    assert.equal(dialog.opener.clicked, 1);
    assert.equal(h.controls.note.value, 'from the draft');
    assert.ok(h.form.hasAttribute('data-krt-draft-edited'));
    assert.ok(!dialog.main.children.includes(dialog.notice));
});

test('restore writes a dialog draft only once the opened dialog is done loading', () => {
    const dom = page();
    const dialog = inDialog(dom);
    dialog.opener.click = () => {
        dialog.dialog.classList.push('is-open');
        dialog.dialog.setAttribute('aria-busy', 'true');
    };
    const key = harness().context.openerKey(dialog.opener);
    const h = harness({ [keyFor(SCOPE)]: storedDraft({ opener: key }) }, dom);
    clickOffer(dialog, dialog.restore);
    h.changed(dialog.dialog);
    assert.equal(h.controls.note.value, '');
    dialog.dialog.removeAttribute('aria-busy');
    h.changed(dialog.dialog);
    assert.equal(h.controls.note.value, 'from the draft');
});

test('a dialog draft whose opener the page no longer has is dropped', () => {
    const dom = page();
    inDialog(dom, null);
    const h = harness({ [keyFor(SCOPE)]: storedDraft({ opener: 'deadbeef' }) }, dom);
    assert.equal(h.stored(), null);
});

test('a saved dialog form loses its mark and its draft', () => {
    const dom = page();
    inDialog(dom);
    const h = harness({}, dom);
    h.controls.note.value = 'typed';
    h.edit(h.controls.note);
    h.flushTimers();
    assert.ok(h.stored());
    h.context.clearUnsaved(h.form);
    assert.ok(!h.form.hasAttribute('data-krt-draft-edited'));
    assert.equal(h.stored(), null);
});

test('a tracked dialog form closed without saving keeps its draft under the draft mark', () => {
    const dom = page();
    inDialog(dom);
    const h = harness({}, dom);
    h.controls.note.value = 'typed';
    h.form.setAttribute('data-krt-unsaved', '');
    h.edit(h.controls.note);
    h.flushTimers();
    h.context.keepAsDraft(h.form);
    assert.ok(!h.form.hasAttribute('data-krt-unsaved'));
    assert.ok(h.form.hasAttribute('data-krt-draft-edited'));
    assert.equal(h.stored().snapshot.fields.note, 'typed');
    h.controls.note.value = 'typed on';
    h.edit(h.controls.note);
    h.flushTimers();
    assert.equal(h.stored().snapshot.fields.note, 'typed on');
});

test('a discard on the page drops a dialog draft and leaves the dialog closed', () => {
    const dom = page();
    const dialog = inDialog(dom);
    const key = harness().context.openerKey(dialog.opener);
    const h = harness({ [keyFor(SCOPE)]: storedDraft({ opener: key }) }, dom);
    clickOffer(dialog, dialog.discard);
    assert.equal(h.stored(), null);
    assert.ok(!dialog.main.children.includes(dialog.notice));
    assert.equal(dialog.opener.clicked, undefined);
});

test('an opener with an id keeps its key when a page script marks it after binding it', () => {
    const context = harness().context;
    const opener = new FakeButton({ id: 'add-unit-btn' });
    const before = context.openerKey(opener);
    opener.setAttribute('data-au-bound', '1');
    assert.equal(context.openerKey(opener), before);
    assert.notEqual(context.openerKey(new FakeButton({ id: 'add-participant-btn' })), before);
});

test('a page without a main element shows a dialog offer at the top of its page wrapper', () => {
    const dom = page();
    const dialog = inDialog(dom);
    dialog.main.tagName = 'DIV';
    dialog.main.classList.push('page-wrapper');
    const key = harness().context.openerKey(dialog.opener);
    harness({ [keyFor(SCOPE)]: storedDraft({ opener: key }) }, dom);
    assert.equal(dialog.main.children[0], dialog.notice);
});

test('a dialog offer sits below the page head and its tabs, where the page shows its notices', () => {
    const dom = page();
    const dialog = inDialog(dom);
    const head = new FakeElement('div', {}, ['page-head']);
    const tabs = new FakeElement('nav', {}, ['tab-nav']);
    dialog.main.prepend(tabs);
    dialog.main.prepend(head);
    const key = harness().context.openerKey(dialog.opener);
    harness({ [keyFor(SCOPE)]: storedDraft({ opener: key }) }, dom);
    assert.deepEqual(dialog.main.children.slice(0, 3), [head, tabs, dialog.notice]);

    const plain = page();
    const untabbed = inDialog(plain);
    const plainHead = new FakeElement('div', {}, ['page-head']);
    untabbed.main.prepend(plainHead);
    const plainKey = harness().context.openerKey(untabbed.opener);
    harness({ [keyFor(SCOPE)]: storedDraft({ opener: plainKey }) }, plain);
    assert.deepEqual(untabbed.main.children.slice(0, 2), [plainHead, untabbed.notice]);
});

if (failures > 0) {
    console.error(`${failures} failed`);
    process.exit(1);
}

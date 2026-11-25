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
        this.attributes = new Map(Object.entries(attributes));
        this.classList = classes;
        this.parent = null;
        this.children = [];
        this.fired = [];
        this.listeners = {};
        this.dataset = {};
        this.hidden = false;
        this.connected = true;
    }

    get id() {
        return this.getAttribute('id') || '';
    }

    get isConnected() {
        return this.connected;
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
        return this.attributes.has(name) ? this.attributes.get(name) : null;
    }

    setAttribute(name, value) {
        this.attributes.set(name, String(value));
    }

    hasAttribute(name) {
        return this.attributes.has(name);
    }

    removeAttribute(name) {
        this.attributes.delete(name);
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
    const form = new FakeForm({ 'data-krt-draft': FORM_KEY });
    form.dataset = { krtDraft: FORM_KEY, krtDraftVersion: '1' };
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
        MutationObserver: class {
            observe() {}
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

test('a swap that replaces the drafted form removes its draft', () => {
    const h = harness({ [keyFor(SCOPE)]: storedDraft() });
    h.form.connected = false;
    h.fire('krt:swapped', { detail: {} });
    assert.equal(h.stored(), null);
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
    const form = new FakeForm({ 'data-krt-draft': formKey });
    form.dataset = { krtDraft: formKey, krtDraftVersion: '1' };
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

if (failures > 0) {
    console.error(`${failures} failed`);
    process.exit(1);
}

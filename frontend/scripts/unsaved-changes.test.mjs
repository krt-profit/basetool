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
 * Regression tests for the per-form unsaved mark the unsaved-changes guard keeps (REQ-FE-024): the
 * guard runs in a vm context over fake forms and a document whose listeners the cases fire.
 */

import assert from 'node:assert/strict';
import vm from 'node:vm';
import { flattenModule, UI_STUBS } from './flatten-module.mjs';

const SOURCE = flattenModule('kernel/unsaved-changes.js', UI_STUBS);

const MARK = 'data-krt-unsaved';

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

/** A control inside a form. */
class FakeElement {
    /** @param {FakeForm | null} form the form the control belongs to */
    constructor(form) {
        this.form = form;
    }

    closest(selector) {
        return selector === 'form' ? this.form : null;
    }
}

/** A form with the attribute and class API the guard reads. */
class FakeForm extends FakeElement {
    /** @param {{ method?: string, noTrack?: boolean }} [options] the form's method and class */
    constructor({ method = 'post', noTrack = false } = {}) {
        super(null);
        this.form = this;
        this.attributes = new Map([['method', method]]);
        this.classList = { contains: (name) => name === 'no-track' && noTrack };
    }

    getAttribute(name) {
        return this.attributes.has(name) ? this.attributes.get(name) : null;
    }

    setAttribute(name, value) {
        this.attributes.set(name, String(value));
    }

    removeAttribute(name) {
        this.attributes.delete(name);
    }

    hasAttribute(name) {
        return this.attributes.has(name);
    }
}

/**
 * Loads the guard over the given forms and fires `DOMContentLoaded`.
 *
 * @param {FakeForm[]} forms the page's forms
 * @returns {object} the harness: `fire(type, target)`, the dispatched events and the guard's exports
 */
function harness(forms) {
    const listeners = {};
    const dispatched = [];
    const context = vm.createContext({
        Element: FakeElement,
        HTMLFormElement: FakeForm,
        CustomEvent: class {
            constructor(type, init) {
                this.type = type;
                this.detail = init && init.detail;
            }
        },
        document: {
            addEventListener(type, handler) {
                (listeners[type] ||= []).push(handler);
            },
            dispatchEvent(event) {
                dispatched.push(event.type);
                return true;
            },
            getElementById: () => null,
            querySelectorAll: (selector) =>
                selector === `form[${MARK}]` ? forms.filter((form) => form.hasAttribute(MARK)) : [],
        },
        addEventListener() {},
    });
    context.window = context;
    vm.runInContext(SOURCE, context);
    const fire = (type, target) => {
        (listeners[type] || []).forEach((handler) => handler({ type, target }));
    };
    fire('DOMContentLoaded', null);
    return {
        fire,
        dispatched,
        isDirty: () => context.hasUnsavedChanges(),
        reset: () => context.resetUnsavedChanges(),
    };
}

test('an edit in a tracked form marks that form and arms the guard', () => {
    const form = new FakeForm();
    const other = new FakeForm();
    const h = harness([form, other]);
    h.fire('input', new FakeElement(form));
    assert.equal(form.hasAttribute(MARK), true);
    assert.equal(other.hasAttribute(MARK), false);
    assert.equal(h.isDirty(), true);
});

test('an edit in a no-track or GET form marks nothing', () => {
    const filter = new FakeForm({ noTrack: true });
    const search = new FakeForm({ method: 'get' });
    const h = harness([filter, search]);
    h.fire('change', new FakeElement(filter));
    h.fire('input', new FakeElement(search));
    assert.equal(filter.hasAttribute(MARK), false);
    assert.equal(search.hasAttribute(MARK), false);
    assert.equal(h.isDirty(), false);
});

test('a submit disarms the guard but keeps the form marked until a save clears it', () => {
    const form = new FakeForm();
    const h = harness([form]);
    h.fire('input', new FakeElement(form));
    h.fire('submit', form);
    assert.equal(h.isDirty(), false);
    assert.equal(form.hasAttribute(MARK), true);
    assert.deepEqual(h.dispatched, []);
});

test('a reset clears the form’s mark and announces it', () => {
    const form = new FakeForm();
    const h = harness([form]);
    h.fire('input', new FakeElement(form));
    h.fire('reset', form);
    assert.equal(form.hasAttribute(MARK), false);
    assert.deepEqual(h.dispatched, ['krt:unsaved-cleared']);
});

test('resetting the guard clears the mark of every form', () => {
    const first = new FakeForm();
    const second = new FakeForm();
    const h = harness([first, second]);
    h.fire('input', new FakeElement(first));
    h.fire('input', new FakeElement(second));
    h.reset();
    assert.equal(first.hasAttribute(MARK), false);
    assert.equal(second.hasAttribute(MARK), false);
    assert.equal(h.isDirty(), false);
    assert.deepEqual(h.dispatched, ['krt:unsaved-cleared', 'krt:unsaved-cleared']);
});

if (failures > 0) {
    console.error(`${failures} failed`);
    process.exit(1);
}

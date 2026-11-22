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
 * Regression tests for the focus on a form row the user just added: the row is scrolled into view,
 * the first editable control takes the focus, and reduced motion scrolls without animation
 * (REQ-UI-036).
 */

import assert from 'node:assert/strict';
import vm from 'node:vm';
import { flattenModule } from './flatten-module.mjs';

class HTMLElement {}
class HTMLInputElement extends HTMLElement {}
class HTMLSelectElement extends HTMLElement {}
class HTMLTextAreaElement extends HTMLElement {}

/**
 * A form control that records the focus calls it receives.
 *
 * @param {Function} Kind the control's element class
 * @param {{disabled?: boolean, readOnly?: boolean, rendered?: boolean}} [state] its state
 * @returns {HTMLElement & {focusCalls: object[]}} the fake control
 */
function control(Kind, { disabled = false, readOnly = false, rendered = true } = {}) {
    const element = new Kind();
    element.disabled = disabled;
    element.readOnly = readOnly;
    element.focusCalls = [];
    element.getClientRects = () => (rendered ? [{}] : []);
    element.focus = (options) => element.focusCalls.push(options);
    return element;
}

/**
 * A row that answers its control query and records how it was scrolled.
 *
 * @param {HTMLElement[]} controls the row's controls in document order
 * @returns {HTMLElement & {scrollCalls: object[], selectors: string[]}} the fake row
 */
function row(controls) {
    const element = new HTMLElement();
    element.scrollCalls = [];
    element.selectors = [];
    element.scrollIntoView = (options) => element.scrollCalls.push(options);
    element.querySelectorAll = (selector) => {
        element.selectors.push(selector);
        return controls;
    };
    return element;
}

/**
 * Copies the option objects the module built inside the `vm` realm into plain objects of this realm,
 * so a deep comparison sees their values and not their foreign prototype.
 *
 * @param {object[]} calls the recorded option objects
 * @returns {object[]} the same values as plain objects
 */
const plain = (calls) => calls.map((options) => ({ ...options }));

let reducedMotion = false;
const context = vm.createContext({
    HTMLElement,
    HTMLInputElement,
    HTMLSelectElement,
    HTMLTextAreaElement,
    window: { matchMedia: () => ({ matches: reducedMotion }) },
});
vm.runInContext(flattenModule('kernel/krt-row-focus.js'), context);
const krtFocusNewRow = vm.runInContext('krtFocusNewRow', context);

const disabled = control(HTMLSelectElement, { disabled: true });
const readOnly = control(HTMLInputElement, { readOnly: true });
const hidden = control(HTMLInputElement, { rendered: false });
const editable = control(HTMLInputElement);
const later = control(HTMLTextAreaElement);
const added = row([disabled, readOnly, hidden, editable, later]);

krtFocusNewRow(added);
assert.deepEqual(plain(added.scrollCalls), [{ block: 'center', behavior: 'smooth' }]);
assert.match(added.selectors[0], /input:not\(\[type="hidden"\]\)/, 'hidden inputs are skipped');
assert.deepEqual(
    plain(editable.focusCalls),
    [{ preventScroll: true }],
    'the first editable control',
);
for (const skipped of [disabled, readOnly, hidden, later]) {
    assert.equal(skipped.focusCalls.length, 0, 'no other control takes the focus');
}

const select = control(HTMLSelectElement);
krtFocusNewRow(row([select]));
assert.equal(select.focusCalls.length, 1, 'a select is focusable');

reducedMotion = true;
const calm = row([control(HTMLInputElement)]);
krtFocusNewRow(calm);
assert.deepEqual(
    plain(calm.scrollCalls),
    [{ block: 'center', behavior: 'auto' }],
    'reduced motion',
);

const empty = row([control(HTMLInputElement, { disabled: true })]);
assert.doesNotThrow(() => krtFocusNewRow(empty), 'a row without an editable control');
assert.equal(empty.scrollCalls.length, 1, 'is still brought into view');

assert.doesNotThrow(() => krtFocusNewRow(null), 'an absent row changes nothing');
assert.doesNotThrow(() => krtFocusNewRow(undefined));
assert.doesNotThrow(() => krtFocusNewRow({}), 'nor does something that is no element');

console.log('krt-row-focus: all assertions passed');

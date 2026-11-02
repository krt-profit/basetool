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
 * Regression tests for the icon rail's flyouts: an item that survives a navigation refresh is bound
 * once, so its click opens the flyout instead of opening and closing it (REQ-UI-026).
 */

import assert from 'node:assert/strict';
import vm from 'node:vm';
import { flattenModule } from './flatten-module.mjs';

const STUBS = {
    '/js/kernel/event-delegation.js': 'const krtEvents = { on() {} };',
    '/js/kernel/krt-chrome.js': 'const krtChrome = { nav: null };',
    '/js/kernel/nav-preferences.js': `
        const NAV_EDIT_EVENT = 'krt:nav-edit-changed';
        const NAV_FAVOURITES_ORDER_EVENT = 'krt:nav-favourites-reordered';
        const NAV_RENDERED_EVENT = 'krt:nav-rendered';
        function enterAdminViaStartPage() { return false; }`,
};

/**
 * A minimal element: records listeners and answers the queries the rail script makes.
 *
 * @param {Record<string, unknown>} [parts] the elements its selectors resolve to
 * @returns {object} the fake element
 */
function fakeElement(parts = {}) {
    const listeners = {};
    const attributes = {};
    return {
        hidden: true,
        style: { setProperty() {} },
        classList: { contains: () => false, toggle() {} },
        listeners,
        attributes,
        addEventListener(type, handler) {
            (listeners[type] ??= []).push(handler);
        },
        setAttribute(name, value) {
            attributes[name] = String(value);
        },
        getAttribute: (name) => attributes[name] ?? null,
        querySelector: (selector) => parts[selector] ?? null,
        querySelectorAll: (selector) => parts[`all:${selector}`] ?? [],
        contains: () => false,
        getBoundingClientRect: () => ({ top: 0 }),
        focus() {},
    };
}

const button = fakeElement();
const flyout = fakeElement();
const avatar = fakeElement({ '.nav-rail-btn': button, '.nav-rail-flyout': flyout });
const rail = fakeElement({ 'all:.nav-rail-item': [avatar] });
const documentListeners = {};
const fakeDocument = {
    getElementById: (id) => (id === 'nav-rail' ? rail : null),
    querySelector: () => null,
    querySelectorAll: () => [],
    addEventListener(type, handler) {
        (documentListeners[type] ??= []).push(handler);
    },
};
const context = vm.createContext({
    document: fakeDocument,
    window: { getComputedStyle: () => ({ display: 'block' }) },
    Node: class {},
    Element: class {},
    HTMLElement: class {},
    clearTimeout() {},
    setTimeout() {},
});
vm.runInContext(flattenModule('kernel/nav-rail.js', STUBS), context);

const fire = (type) => (documentListeners[type] ?? []).forEach((handler) => handler({}));
const click = () => (button.listeners.click ?? []).forEach((handler) => handler({}));

fire('DOMContentLoaded');
assert.equal(button.listeners.click.length, 1, 'the avatar button has one click handler');

fire('krt:nav-rendered');
fire('krt:nav-rendered');
assert.equal(button.listeners.click.length, 1, 'a refresh does not bind the avatar again');
assert.equal(avatar.listeners.pointerenter.length, 1, 'nor its hover handler');

click();
assert.equal(flyout.hidden, false, 'a click after a refresh opens the flyout');
assert.equal(button.attributes['aria-expanded'], 'true');

console.log('nav-rail tests passed');

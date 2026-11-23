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
 * How long an ended press that produced no `click` keeps holding; a tap's `click` can follow its
 * `pointerup` as a separate input event.
 */
const PRESS_CLICK_GRACE_MS = 100;

/**
 * The press of the primary pointer's main button, from its `pointerdown` until it settles.
 *
 * @typedef {{ target: Node, ended: boolean }} PointerPress
 */

/** @type {PointerPress | null} */
let pointerPress = null;
/** @type {number | null} */
let settleTimer = null;
/** @type {Set<{ element: Element, release: () => void }>} */
const waiters = new Set();

/**
 * Tells whether a press that began inside the element has not settled yet.
 *
 * @param {Element} element the element
 * @returns {boolean} true while such a press holds the element
 */
function pressHolds(element) {
    return pointerPress !== null && element.contains(pointerPress.target);
}

/** Forgets an ended press and releases every waiter no press holds any longer. */
function settlePress() {
    settleTimer = null;
    if (pointerPress && pointerPress.ended) {
        pointerPress = null;
    }
    for (const waiter of waiters) {
        if (!pressHolds(waiter.element)) {
            waiters.delete(waiter);
            waiter.release();
        }
    }
}

/**
 * Marks the current press as ended and settles it after `delay`, replacing a settle already due.
 *
 * @param {number} delay milliseconds until the press settles
 */
function endPress(delay) {
    if (!pointerPress) {
        return;
    }
    pointerPress.ended = true;
    if (settleTimer !== null) {
        window.clearTimeout(settleTimer);
    }
    settleTimer = window.setTimeout(settlePress, delay);
}

window.addEventListener(
    'pointerdown',
    (event) => {
        if (event.isPrimary && event.button === 0 && event.target instanceof Node) {
            pointerPress = { target: event.target, ended: false };
        }
    },
    true,
);
window.addEventListener(
    'pointerup',
    (event) => {
        if (event.isPrimary) {
            endPress(PRESS_CLICK_GRACE_MS);
        }
    },
    true,
);
window.addEventListener(
    'pointercancel',
    (event) => {
        if (event.isPrimary) {
            endPress(0);
        }
    },
    true,
);
window.addEventListener(
    'click',
    () => {
        if (pointerPress && pointerPress.ended) {
            endPress(0);
        }
    },
    true,
);
window.addEventListener('blur', () => endPress(0));

/**
 * Resolves once no press of the primary pointer's main button that began inside the element is in
 * progress; an ended press settles in the macrotask after its `click`, so the pressed control's
 * click handlers run first (REQ-FE-038).
 *
 * @param {Element} element the element about to be re-rendered
 * @returns {Promise<void>} resolved at once when no press holds the element
 */
export function pressSettled(element) {
    if (!pressHolds(element)) {
        return Promise.resolve();
    }
    return new Promise((release) => {
        waiters.add({ element, release });
    });
}

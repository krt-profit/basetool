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

import { krtEvents } from '/js/kernel/event-delegation.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';
import { reloadMenu, stateElement } from '/js/kernel/nav-preferences.js';
import { showFrontendErrorToast } from '/js/kernel/toast.js';

/** Number of slots the member chooses. */
const SLOTS = 3;

/** The marker the state attributes use for an automatic slot. */
const AUTOMATIC = '-';

/** Milliseconds a pointer stays down on a tab before its picker opens. */
const LONG_PRESS_MS = 500;

/** Pixels the pointer may travel before the press no longer counts as a long press. */
const LONG_PRESS_SLOP = 10;

const WRITE_LOCK = 'nav-preferences';

/**
 * Parses a space-separated slot list of the state element.
 *
 * @param {string | undefined} value the attribute value, e.g. `missions.list - orders.list`
 * @returns {(string | null)[]} three entries, `null` for an automatic slot
 */
export function parseSlots(value) {
    const parts = (value || '').split(' ').filter((part) => part.length > 0);
    return Array.from({ length: SLOTS }, (_unused, index) =>
        parts[index] && parts[index] !== AUTOMATIC ? parts[index] : null,
    );
}

/**
 * The assignment an edit starts from: the stored slots, or, while the bar is automatic, the
 * assignment it shows now, so the first edit stores all three slots.
 *
 * @param {(string | null)[]} stored the stored slots
 * @param {(string | null)[]} shown the slots as shown now
 * @returns {(string | null)[]} a copy to change
 */
function base(stored, shown) {
    return stored.every((key) => key === null) ? [...shown] : [...stored];
}

/**
 * The assignment after the member picked an entry for a slot. An entry that already sits in
 * another slot swaps the two slots.
 *
 * @param {(string | null)[]} stored the stored slots
 * @param {(string | null)[]} shown the slots as shown now
 * @param {number} index the slot being set
 * @param {string} key the chosen entry key
 * @returns {(string | null)[]} the new assignment
 */
export function chooseSlot(stored, shown, index, key) {
    const next = base(stored, shown);
    const other = next.indexOf(key);
    if (other >= 0 && other !== index) {
        [next[index], next[other]] = [next[other], next[index]];
    } else {
        next[index] = key;
    }
    return next;
}

/**
 * The assignment after a slot swapped places with its neighbour.
 *
 * @param {(string | null)[]} stored the stored slots
 * @param {(string | null)[]} shown the slots as shown now
 * @param {number} index the slot that moves
 * @param {number} direction `-1` for the left neighbour, `1` for the right one
 * @returns {(string | null)[]} the new assignment
 */
export function swapSlots(stored, shown, index, direction) {
    const next = base(stored, shown);
    const target = index + direction;
    if (target >= 0 && target < SLOTS) {
        [next[index], next[target]] = [next[target], next[index]];
    }
    return next;
}

/**
 * The mode a control belongs to: the `member` or `admin` of its section or tab set.
 *
 * @param {Element} element a control of the editor, the picker or a tab
 * @returns {string | null} the mode, or null outside any mode's element
 */
function modeOf(element) {
    const holder = element.closest('[data-tabbar-mode]');
    return holder instanceof HTMLElement ? holder.dataset.tabbarMode || null : null;
}

/**
 * Says something to assistive technology without moving focus.
 *
 * @param {string} dataKey the dataset key of the message pattern on the live region
 * @param {Record<string, string>} [values] the values that replace `{name}` placeholders
 */
function announce(dataKey, values = {}) {
    const region = document.querySelector('[data-nav-live]');
    const pattern = region instanceof HTMLElement ? region.dataset[dataKey] : undefined;
    if (region && pattern) {
        region.textContent = Object.entries(values).reduce(
            (text, [name, value]) => text.replace(`{${name}}`, value),
            pattern,
        );
    }
}

/**
 * The state attributes of one mode's bar.
 *
 * @param {HTMLElement} state the state element
 * @param {string} mode `member` or `admin`
 * @returns {{ stored: (string | null)[], shown: (string | null)[], version: number }} the bar
 */
function barOf(state, mode) {
    const prefix = `tabbar${mode === 'admin' ? 'Admin' : 'Member'}`;
    return {
        stored: parseSlots(state.dataset[`${prefix}Stored`]),
        shown: parseSlots(state.dataset[`${prefix}Shown`]),
        version: Number(state.dataset[`${prefix}Version`] || '0'),
    };
}

/**
 * Writes one mode's bar through the relay, computing the new assignment when the write is sent so
 * a queued write starts from the state the previous one left. A version conflict drops the change,
 * reloads the lists and says so.
 *
 * @param {string} mode `member` or `admin`
 * @param {(stored: (string | null)[], shown: (string | null)[]) => (string | null)[]} compute
 *     derives the new assignment from the stored and the shown slots
 * @param {object} [options] write options
 * @param {HTMLElement} [options.submitter] the control to disable while the write is in flight
 * @param {() => void} [options.onDone] runs after the write and the reload succeeded
 */
function writeTabBar(mode, compute, options = {}) {
    const state = stateElement();
    if (!state) {
        return;
    }
    krtFetch.write({
        method: 'PUT',
        url: `/navigation/tab-bar/${mode}`,
        payload: () => {
            const bar = barOf(state, mode);
            return { slots: compute(bar.stored, bar.shown), version: bar.version };
        },
        serialize: WRITE_LOCK,
        submitter: options.submitter,
        toast: false,
        errorMessage: state.dataset.tabbarError,
        onSuccess: async () => {
            if ((await reloadMenu()) && options.onDone) {
                options.onDone();
            }
        },
        onError: (status) => {
            if (status !== 409) {
                return false;
            }
            showFrontendErrorToast(state.dataset.conflict || '');
            reloadMenu();
            return true;
        },
    });
}

/**
 * The label of the entry a slot shows now.
 *
 * @param {string} mode `member` or `admin`
 * @param {number} index the slot
 * @returns {string} the visible label, or an empty string
 */
function slotLabel(mode, index) {
    const tab = document.querySelector(
        `.mobile-tab-slots[data-tabbar-mode="${mode}"] .mobile-tab[data-slot-index="${index}"]`,
    );
    return tab ? (tab.textContent || '').trim() : '';
}

/**
 * Moves focus to the first of the selectors that matches a visible element, so the keyboard
 * position survives the controls being swapped.
 *
 * @param {string[]} selectors candidates in order of preference
 */
function focusFirst(selectors) {
    for (const selector of selectors) {
        const element = document.querySelector(selector);
        if (element instanceof HTMLElement && element.getClientRects().length > 0) {
            element.focus({ preventScroll: false });
            return;
        }
    }
}

/**
 * Sets a slot from its select in „Anpassen“.
 *
 * @param {HTMLElement} select the changed select
 */
function setFromSelect(select) {
    const mode = modeOf(select);
    const key = select instanceof HTMLSelectElement ? select.value : '';
    const index = Number(select.dataset.slotIndex);
    if (!mode || !key) {
        return;
    }
    writeTabBar(mode, (stored, shown) => chooseSlot(stored, shown, index, key), {
        submitter: select,
        onDone: () => {
            announce('announceTabbarSet', {
                slot: String(index + 1),
                name: slotLabel(mode, index),
            });
            focusFirst([`#tabbar-${mode}-slot-${index}`]);
        },
    });
}

/**
 * Swaps a slot with its neighbour.
 *
 * @param {HTMLElement} button the pressed swap control
 */
function swap(button) {
    const mode = modeOf(button);
    const index = Number(button.dataset.slotIndex);
    const direction = Number(button.dataset.direction);
    if (!mode) {
        return;
    }
    writeTabBar(mode, (stored, shown) => swapSlots(stored, shown, index, direction), {
        submitter: button,
        onDone: () => {
            announce('announceTabbarSwapped', {
                slot: String(index + direction + 1),
                name: slotLabel(mode, index + direction),
            });
            focusFirst([
                `[data-tabbar-mode="${mode}"] [data-trigger="tabbar-swap"][data-slot-index="${
                    index + direction
                }"][data-direction="${direction}"]:not(:disabled)`,
                `[data-tabbar-mode="${mode}"] [data-trigger="tabbar-swap"][data-slot-index="${
                    index + direction
                }"]:not(:disabled)`,
            ]);
        },
    });
}

/**
 * Returns one mode's bar to automatic.
 *
 * @param {HTMLElement} button the pressed reset control
 */
function reset(button) {
    const mode = modeOf(button);
    if (!mode) {
        return;
    }
    writeTabBar(mode, () => Array(SLOTS).fill(null), {
        submitter: button,
        onDone: () => {
            announce('announceTabbarReset');
            focusFirst([`#tabbar-${mode}-slot-0`, '[data-trigger="nav-edit-toggle"]']);
        },
    });
}

/** @type {{ mode: string, index: number, opener: HTMLElement } | null} */
let openPicker = null;

/**
 * Closes the slot picker and returns focus to the tab it opened from.
 *
 * @param {boolean} restoreFocus whether focus returns to the tab
 */
function closePicker(restoreFocus) {
    document.querySelectorAll('.tabbar-picker').forEach((picker) => {
        if (picker instanceof HTMLElement) {
            picker.hidden = true;
        }
    });
    if (restoreFocus && openPicker && openPicker.opener.isConnected) {
        openPicker.opener.focus();
    }
    openPicker = null;
}

/**
 * Opens the picker of the slot a tab shows: the choices of its mode with the current one checked
 * and the entries that sit in another slot marked as swapping.
 *
 * @param {HTMLElement} tab the long-pressed tab
 */
function showPicker(tab) {
    const mode = modeOf(tab);
    const state = stateElement();
    const picker = mode
        ? document.querySelector(`.tabbar-picker[data-tabbar-mode="${mode}"]`)
        : null;
    if (!mode || !state || state.dataset.loaded !== 'true' || !(picker instanceof HTMLElement)) {
        return;
    }
    closePicker(false);
    const index = Number(tab.dataset.slotIndex);
    const shown = barOf(state, mode).shown;
    const title = picker.querySelector('[data-tabbar-picker-title]');
    if (title) {
        title.textContent = (title.getAttribute('data-pattern') || '').replace(
            '{slot}',
            String(index + 1),
        );
    }
    picker.querySelectorAll('.tabbar-picker-item').forEach((item) => {
        if (!(item instanceof HTMLElement)) {
            return;
        }
        const key = item.dataset.entryKey || '';
        const at = shown.indexOf(key);
        item.setAttribute('aria-checked', String(at === index));
        item.querySelectorAll('.tabbar-picker-swaps').forEach((hint) => hint.remove());
        if (at >= 0 && at !== index) {
            const hint = document.createElement('span');
            hint.className = 'tabbar-picker-swaps';
            hint.textContent = (picker.dataset.swaps || '').replace('{slot}', String(at + 1));
            item.append(hint);
        }
    });
    picker.dataset.slotIndex = String(index);
    picker.hidden = false;
    openPicker = { mode, index, opener: tab };
    const current = picker.querySelector('[aria-checked="true"]');
    const first = current || picker.querySelector('.tabbar-picker-item');
    if (first instanceof HTMLElement) {
        first.focus();
    }
}

/**
 * Sets the picker's slot to the pressed entry.
 *
 * @param {HTMLElement} item the pressed picker entry
 */
function pick(item) {
    const key = item.dataset.entryKey;
    if (!openPicker || !key) {
        return;
    }
    const { mode, index } = openPicker;
    closePicker(false);
    writeTabBar(mode, (stored, shown) => chooseSlot(stored, shown, index, key), {
        onDone: () => {
            announce('announceTabbarSet', {
                slot: String(index + 1),
                name: slotLabel(mode, index),
            });
            focusFirst([
                `.mobile-tab-slots[data-tabbar-mode="${mode}"] .mobile-tab[data-slot-index="${index}"]`,
            ]);
        },
    });
}

/** @type {number | null} */
let pressTimer = null;
let pressStart = { x: 0, y: 0 };
let longPressFired = false;

/** Drops a running long-press timer. */
function cancelPress() {
    if (pressTimer !== null) {
        window.clearTimeout(pressTimer);
        pressTimer = null;
    }
}

/**
 * @param {EventTarget | null} target an event target
 * @returns {HTMLElement | null} the configurable tab the target belongs to
 */
function slotTab(target) {
    const tab = target instanceof Element ? target.closest('.mobile-tab[data-slot-index]') : null;
    return tab instanceof HTMLElement ? tab : null;
}

document.addEventListener('pointerdown', (event) => {
    const tab = slotTab(event.target);
    longPressFired = false;
    cancelPress();
    if (!tab || (event.pointerType === 'mouse' && event.button !== 0)) {
        return;
    }
    pressStart = { x: event.clientX, y: event.clientY };
    pressTimer = window.setTimeout(() => {
        pressTimer = null;
        longPressFired = true;
        showPicker(tab);
    }, LONG_PRESS_MS);
});

document.addEventListener('pointermove', (event) => {
    if (
        pressTimer !== null &&
        Math.hypot(event.clientX - pressStart.x, event.clientY - pressStart.y) > LONG_PRESS_SLOP
    ) {
        cancelPress();
    }
});

['pointerup', 'pointercancel'].forEach((type) => document.addEventListener(type, cancelPress));

document.addEventListener(
    'click',
    (event) => {
        if (longPressFired && slotTab(event.target)) {
            event.preventDefault();
            event.stopPropagation();
        }
        longPressFired = false;
        const insidePicker =
            event.target instanceof Element && event.target.closest('.tabbar-picker');
        if (openPicker && !insidePicker && !slotTab(event.target)) {
            closePicker(false);
        }
    },
    true,
);

document.addEventListener('contextmenu', (event) => {
    if (slotTab(event.target)) {
        event.preventDefault();
    }
});

document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && openPicker) {
        event.preventDefault();
        closePicker(true);
    }
});

krtEvents.on('change', 'tabbar-set', setFromSelect);
krtEvents.on('click', 'tabbar-swap', swap);
krtEvents.on('click', 'tabbar-reset', reset);
krtEvents.on('click', 'tabbar-pick', pick);
krtEvents.on('click', 'tabbar-picker-close', () => closePicker(true));

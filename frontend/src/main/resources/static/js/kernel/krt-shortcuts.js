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
 * The decision logic of the navigation shortcuts Alt+1 to Alt+9 (favourites) and Alt+0 (the
 * dashboard), free of the DOM so a node test can drive it (REQ-UI-035).
 */

/** The slot of the dashboard shortcut, Alt+0. */
export const DASHBOARD_SLOT = 0;

/** The last favourite that has a key; favourites 10 to 12 get none. */
export const LAST_FAVOURITE_SLOT = 9;

const DIGIT_CODE = /^Digit([0-9])$/;

/**
 * @typedef {object} ShortcutKeyEvent
 * @property {boolean} altKey whether Alt (Option) is held
 * @property {boolean} ctrlKey whether Ctrl is held; AltGr reports Ctrl and Alt together
 * @property {boolean} metaKey whether Meta (Command, Windows key) is held
 * @property {boolean} shiftKey whether Shift is held
 * @property {boolean} [repeat] whether the key is auto-repeating
 * @property {string} [code] the physical key
 */

/**
 * @typedef {object} ShortcutContext
 * @property {boolean} enabled whether the member's „Darstellung“ switch is on
 * @property {boolean} adminMode whether the navigation is in administration mode
 * @property {boolean} dialogOpen whether any dialog is open
 * @property {boolean[]} favourites per favourite of the member group, in order, whether it is
 *     available in the active context
 */

/**
 * @typedef {{ action: 'ignore' }
 *     | { action: 'dashboard' }
 *     | { action: 'open', slot: number }
 *     | { action: 'unavailable', slot: number }
 *     | { action: 'empty', slot: number }} ShortcutDecision
 */

/**
 * The slot a key press addresses. Only the digit row counts, matched on the physical key so
 * Option+digit works on macOS; the numpad is Windows Alt-code entry and never matches.
 *
 * @param {ShortcutKeyEvent} event the key press
 * @returns {number | null} 0 to 9, or null when the press is no navigation shortcut
 */
export function shortcutSlot(event) {
    if (!event.altKey || event.ctrlKey || event.metaKey || event.shiftKey || event.repeat) {
        return null;
    }
    const match = DIGIT_CODE.exec(event.code || '');
    return match ? Number(match[1]) : null;
}

/**
 * What a slot does in the current state of the page.
 *
 * @param {number} slot the addressed slot, 0 to 9
 * @param {ShortcutContext} context the state of the page
 * @returns {ShortcutDecision} what to do
 */
export function decideShortcut(slot, context) {
    if (!context.enabled || context.adminMode || context.dialogOpen) {
        return { action: 'ignore' };
    }
    if (slot === DASHBOARD_SLOT) {
        return { action: 'dashboard' };
    }
    const available = context.favourites[slot - 1];
    if (available === undefined) {
        return { action: 'empty', slot };
    }
    return { action: available ? 'open' : 'unavailable', slot };
}

/**
 * The keys of a slot as the `aria-keyshortcuts` attribute spells them.
 *
 * @param {number} slot the slot, 0 to 9
 * @returns {string} for example `Alt+1`
 */
export function ariaKeys(slot) {
    return `Alt+${slot}`;
}

/**
 * The keys of a slot as a member reads them.
 *
 * @param {number} slot the slot, 0 to 9
 * @param {boolean} mac whether the platform is macOS or iOS, where the key is called Option
 * @returns {string} for example `Alt+1`, or `⌥ 1` on a Mac
 */
export function keyLabel(slot, mac) {
    return mac ? `⌥ ${slot}` : `Alt+${slot}`;
}

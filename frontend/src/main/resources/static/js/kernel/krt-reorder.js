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
 * One move of a list item, handed to the page that persists it.
 *
 * @typedef {object} KrtReorderMove
 * @property {HTMLElement} row the moved item, already at its new place in the DOM
 * @property {HTMLElement[]} rows every item of the list in the new order
 * @property {number} from the zero-based position before the move
 * @property {number} to the zero-based position after the move
 */

/**
 * The declaration of a reorderable list.
 *
 * @typedef {object} KrtReorderOptions
 * @property {HTMLElement} container a stable ancestor of the items that survives fragment swaps
 * @property {string} itemSelector selects the reorderable items inside the container
 * @property {HTMLElement | null} [liveRegion] the polite live region; defaults to the
 *     `[data-reorder-live]` element in the document
 * @property {(move: KrtReorderMove) => Promise<boolean>} persist saves the move and re-renders
 *     the list; resolves true when the server accepted it
 * @property {() => boolean} [dragAllowed] whether a drag may start; defaults to a fine pointer
 */

/**
 * The handle of an attached list.
 *
 * @typedef {object} KrtReorderHandle
 * @property {(row: HTMLElement, direction: 'up' | 'down') => Promise<boolean>} move moves an item
 *     one place; resolves true when the move was persisted
 * @property {() => void} syncBounds marks the first item's up and the last item's down control
 *     as unavailable
 */

const MOVE_ATTR = 'data-reorder-move';
const FOCUSABLE =
    'a[href], button, input, select, textarea, [tabindex]:not([tabindex="-1"]), [data-reorder-move]';

/**
 * Fills the `{name}`, `{from}`, `{to}` and `{total}` placeholders of an announcement pattern.
 *
 * @param {string} pattern the localized pattern
 * @param {Record<string, string | number>} values the placeholder values
 * @returns {string} the announcement
 */
export function formatReorderAnnouncement(pattern, values) {
    return pattern.replace(/\{(\w+)\}/g, (whole, key) =>
        Object.hasOwn(values, key) ? String(values[key]) : whole,
    );
}

/**
 * Computes the position an item moves to.
 *
 * @param {number} from the zero-based current position
 * @param {'up' | 'down'} direction the direction of the move
 * @param {number} total the number of items
 * @returns {number} the new position, or -1 when the item is already at that end
 */
export function reorderTarget(from, direction, total) {
    const to = direction === 'up' ? from - 1 : from + 1;
    return from < 0 || to < 0 || to >= total ? -1 : to;
}

/**
 * Reads the move direction a key press asks for.
 *
 * @param {{key: string, altKey: boolean, ctrlKey: boolean, metaKey: boolean, shiftKey: boolean}} e
 *     the keydown event
 * @returns {'up' | 'down' | null} the direction, or null for any other key
 */
export function reorderKeyDirection(e) {
    if (!e.altKey || e.ctrlKey || e.metaKey || e.shiftKey) return null;
    if (e.key === 'ArrowUp') return 'up';
    if (e.key === 'ArrowDown') return 'down';
    return null;
}

/**
 * Lists the focusable elements of an item in document order.
 *
 * @param {HTMLElement} row the item
 * @returns {HTMLElement[]} its focusable descendants
 */
function focusablesOf(row) {
    return /** @type {HTMLElement[]} */ (Array.from(row.querySelectorAll(FOCUSABLE)));
}

/**
 * Remembers which control of an item holds the focus, so it can take it again after a re-render.
 *
 * @param {HTMLElement} row the item
 * @param {Element | null} active the focused element
 * @returns {{move: string | null, index: number}} the control's move direction, or its position
 *     among the item's focusable elements
 */
function focusSlot(row, active) {
    if (!active || !row.contains(active)) return { move: null, index: -1 };
    const move = active.getAttribute(MOVE_ATTR);
    return { move, index: focusablesOf(row).indexOf(/** @type {HTMLElement} */ (active)) };
}

export const krtReorder = {
    /**
     * Makes the items of a list movable by their up/down controls and by Alt+ArrowUp/ArrowDown,
     * keeps the focus on the moved item, announces the move and stops a drag on a coarse pointer.
     *
     * @param {KrtReorderOptions} options the list declaration
     * @returns {KrtReorderHandle} the handle
     */
    attach(options) {
        const { container, itemSelector, persist } = options;
        const dragAllowed =
            options.dragAllowed ??
            (() =>
                typeof window.matchMedia !== 'function' ||
                window.matchMedia('(pointer: fine)').matches);
        let busy = false;

        /** @returns {HTMLElement[]} the items in their current order */
        const items = () =>
            /** @type {HTMLElement[]} */ (Array.from(container.querySelectorAll(itemSelector)));

        /** @returns {HTMLElement | null} the live region */
        const liveRegion = () =>
            options.liveRegion ??
            /** @type {HTMLElement | null} */ (document.querySelector('[data-reorder-live]'));

        /** Marks the controls that would move an item past either end. */
        const syncBounds = () => {
            const rows = items();
            rows.forEach((row, i) => {
                row.querySelectorAll(`[${MOVE_ATTR}]`).forEach((btn) => {
                    const dir = btn.getAttribute(MOVE_ATTR);
                    const blocked =
                        (dir === 'up' && i === 0) || (dir === 'down' && i === rows.length - 1);
                    btn.setAttribute('aria-disabled', String(blocked));
                });
            });
        };

        /**
         * Gives the focus back to the same control of an item after the list re-rendered.
         *
         * @param {string | undefined} id the item's `data-id`
         * @param {{move: string | null, index: number}} slot the remembered control
         * @param {HTMLElement} fallback the item to use when no item carries the id
         */
        const restoreFocus = (id, slot, fallback) => {
            const row =
                (id ? items().find((r) => r.dataset.id === id) : undefined) ??
                (fallback.isConnected ? fallback : undefined);
            if (!row) return;
            /** @type {HTMLElement | null} */
            let target = null;
            if (slot.move) {
                target = row.querySelector(`[${MOVE_ATTR}="${slot.move}"]`);
            } else if (slot.index >= 0) {
                target = focusablesOf(row)[slot.index] ?? null;
            }
            (target ?? focusablesOf(row)[0] ?? row).focus();
        };

        /**
         * Writes an announcement into the live region.
         *
         * @param {HTMLElement} row the moved item
         * @param {number} from the zero-based old position
         * @param {number} to the zero-based new position
         * @param {number} total the number of items
         */
        const announce = (row, from, to, total) => {
            const region = liveRegion();
            const pattern = region?.dataset.reorderAnnounce;
            if (!region || !pattern) return;
            region.textContent = formatReorderAnnouncement(pattern, {
                name: row.dataset.reorderName ?? '',
                from: from + 1,
                to: to + 1,
                total,
            });
        };

        /**
         * Moves an item one place, persists the order and restores the focus.
         *
         * @param {HTMLElement} row the item
         * @param {'up' | 'down'} direction the direction
         * @returns {Promise<boolean>} whether the move was persisted
         */
        const move = async (row, direction) => {
            if (busy) return false;
            const rows = items();
            const from = rows.indexOf(row);
            const to = reorderTarget(from, direction, rows.length);
            if (to === -1) return false;
            const slot = focusSlot(row, document.activeElement);
            const id = row.dataset.id;
            if (direction === 'up') rows[to].before(row);
            else rows[to].after(row);
            syncBounds();
            restoreFocus(id, slot, row);
            busy = true;
            const ok = await persist({ row, rows: items(), from, to }).finally(() => {
                busy = false;
            });
            restoreFocus(id, slot, row);
            if (ok) announce(row, from, to, rows.length);
            return ok;
        };

        container.addEventListener('click', (e) => {
            const btn = /** @type {Element | null} */ (e.target)?.closest?.(`[${MOVE_ATTR}]`);
            if (!btn || !container.contains(btn)) return;
            e.preventDefault();
            if (btn.getAttribute('aria-disabled') === 'true') return;
            const row = /** @type {HTMLElement | null} */ (btn.closest(itemSelector));
            const dir = btn.getAttribute(MOVE_ATTR);
            if (row && (dir === 'up' || dir === 'down')) move(row, dir);
        });

        container.addEventListener('keydown', (e) => {
            const direction = reorderKeyDirection(e);
            if (!direction) return;
            const row = /** @type {HTMLElement | null} */ (
                /** @type {Element | null} */ (e.target)?.closest?.(itemSelector) ?? null
            );
            if (!row || !container.contains(row)) return;
            e.preventDefault();
            move(row, direction);
        });

        container.addEventListener(
            'dragstart',
            (e) => {
                if (dragAllowed()) return;
                e.preventDefault();
                e.stopPropagation();
            },
            true,
        );

        syncBounds();
        return { move, syncBounds };
    },
};

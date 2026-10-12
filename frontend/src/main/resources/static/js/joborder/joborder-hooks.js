// @ts-check
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
 * @typedef {object} JoborderHooks
 * @property {((...args: any[]) => any) | undefined} [refreshOrderSection] re-renders order sections (orders-detail.js)
 * @property {((...args: any[]) => any) | undefined} [notifyOrderChanged] tells peers that order sections changed (orders-detail.js)
 * @property {(() => void) | undefined} [confirmRemoveMaterial] removes the material row awaiting confirmation (orders-detail-edit.js)
 * @property {((...args: any[]) => any) | undefined} [refreshOrdersQueue] re-renders the order queue (orders-index.js)
 * @property {boolean} ordersDragging whether a row of the queue is being dragged (orders-index-reorder.js)
 */

/**
 * The seams between the parts of the order pages. The detail parts and the list and its drag handle
 * each set what they offer when they run and the others read it when they need it, so the parts keep
 * their load order and none imports another for it.
 */
export const joborderHooks = /** @type {JoborderHooks} */ (
    /** @type {unknown} */ ({ ordersDragging: false })
);

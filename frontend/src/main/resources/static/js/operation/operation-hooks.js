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
 * @typedef {object} OperationHooks
 * @property {((...args: any[]) => any) | undefined} [refreshSection] re-renders operation sections (operation-detail.js)
 * @property {((...args: any[]) => any) | undefined} [notifyChanged] tells peers that sections changed (operation-detail.js)
 * @property {((...args: any[]) => any) | undefined} [reload] reloads the operation list (operations.js)
 */

/**
 * The seams between the operation pages: the detail page's section writer and the list's reload. Set
 * by the module that owns it and read when needed, so the modules keep their load order.
 */
export const operationHooks = /** @type {OperationHooks} */ (/** @type {unknown} */ ({}));

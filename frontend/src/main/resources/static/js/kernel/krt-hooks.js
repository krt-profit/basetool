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
 * @typedef {object} KrtHooks
 * @property {((...args: any[]) => void) | undefined} [filterTable] filters a table by a search term (catalogue pages)
 * @property {KrtMaterialDialogApi | undefined} [materialRelease] the offer dialog (materialboerse-release.js)
 * @property {KrtMaterialDialogApi | undefined} [materialRequest] the request dialog (materialgesuch-modal.js)
 */

/**
 * Collaborators that a domain module offers to the kernel or to another domain, which may not import
 * it: the table filter of the catalogue pages (read by common-handlers.js) and the two Materialbörse
 * dialogs (read by the Lager page). Set by the owner when it runs, read when needed.
 */
export const krtHooks = /** @type {KrtHooks} */ (/** @type {unknown} */ ({}));

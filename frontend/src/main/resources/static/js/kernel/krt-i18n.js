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
 * Returns a non-empty localized string as is; otherwise reports the key once per page view as an
 * `i18n_missing` client error and returns the key name. The classic error beacon owns the report-once
 * state and runs before any module, so this delegates to what it published.
 *
 * @param {unknown} value the localized string the page provided, if any
 * @param {string} key the string's identifier: `DICTIONARY.property` or `data-attribute`
 * @returns {string} the localized string, or the key name when it is missing
 */
export function krtI18nText(value, key) {
    return window.krtI18nText(value, key);
}

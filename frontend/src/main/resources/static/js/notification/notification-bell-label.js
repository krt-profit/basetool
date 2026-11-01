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

/** The largest unread count shown as a number; above it the badge reads "99+". */
const MAX_SHOWN_COUNT = 99;

/**
 * The unread count as the badge shows it.
 *
 * @param {number} count the unread count
 * @returns {string} the number, or "99+" above the cap
 */
export function badgeText(count) {
    return count > MAX_SHOWN_COUNT ? `${MAX_SHOWN_COUNT}+` : String(count);
}

/**
 * The bell button's accessible name: the plain label without unread notifications, otherwise the
 * unread pattern with the badge text in place of {@code {0}}.
 *
 * @param {string} openLabel the label without a count
 * @param {string} unreadPattern the label pattern holding the {0} placeholder
 * @param {number} count the unread count
 * @returns {string} the accessible name
 */
export function bellAriaLabel(openLabel, unreadPattern, count) {
    return count > 0 ? unreadPattern.replace('{0}', badgeText(count)) : openLabel;
}

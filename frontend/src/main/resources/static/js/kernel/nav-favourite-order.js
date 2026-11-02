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
 * Merges the order of one mode's favourites into the stored list of both modes: the mode's keys
 * take the new order in the places they held, every other key stays where it is.
 *
 * @param {string[]} stored the stored favourites of both modes
 * @param {string[]} moved the mode's favourites in their new order
 * @returns {string[]} the stored list in the new order
 */
export function mergeFavouriteOrder(stored, moved) {
    const remaining = [...moved];
    return stored.map((key) => (moved.includes(key) ? (remaining.shift() ?? key) : key));
}

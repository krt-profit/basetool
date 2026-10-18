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

const COMBINING_MARKS = /[̀-ͯ]/g;
const DIGRAPH_VOWELS = 'aou';

/**
 * Folds text for the tolerant menu search: lower case, diacritics dropped, `ß` as `ss`, and the
 * spelled-out umlauts `ae`, `oe`, `ue` collapsed like `ä`, `ö`, `ü` (REQ-UI-026).
 *
 * @param {string} text the text to fold
 * @returns {{ text: string, map: number[] }} the folded text and, for each of its characters, the
 *     index of the source character it came from
 */
export function krtSearchFoldWithMap(text) {
    let out = '';
    /** @type {number[]} */
    const map = [];
    for (let i = 0; i < text.length; i++) {
        const lower = text.charAt(i).toLowerCase();
        const base = lower === 'ß' ? 'ss' : lower.normalize('NFD').replace(COMBINING_MARKS, '');
        for (let k = 0; k < base.length; k++) {
            out += base.charAt(k);
            map.push(i);
        }
        if (
            base.length === 1 &&
            DIGRAPH_VOWELS.includes(base) &&
            text.charAt(i + 1).toLowerCase() === 'e'
        ) {
            i++;
        }
    }
    return { text: out, map };
}

/**
 * Folds text for the tolerant menu search.
 *
 * @param {string} text the text to fold
 * @returns {string} the folded text
 */
export function krtSearchFold(text) {
    return krtSearchFoldWithMap(text).text;
}

/**
 * Finds a folded query in a text and maps the hit back onto the text.
 *
 * @param {string} text the text to search
 * @param {string} foldedQuery the query, already folded
 * @returns {{ start: number, end: number } | null} the hit's range in `text`, end exclusive, or
 *     null when the text does not contain the query
 */
export function krtSearchFind(text, foldedQuery) {
    if (!foldedQuery) return null;
    const folded = krtSearchFoldWithMap(text);
    const at = folded.text.indexOf(foldedQuery);
    if (at < 0) return null;
    const after = at + foldedQuery.length;
    const end = after < folded.map.length ? folded.map[after] : text.length;
    return { start: folded.map[at], end: Math.max(end, folded.map[at] + 1) };
}

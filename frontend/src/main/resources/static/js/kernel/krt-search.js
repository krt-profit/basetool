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

/**
 * @typedef {object} KrtSearchPrefix
 * @property {string} kind the kind the prefix narrows to
 * @property {string} symbol its one-character symbol, empty when it has only words
 * @property {string[]} words its words without the colon, German first
 */

/**
 * The quick-access prefixes: symbol and words narrow to one kind, both languages' words are
 * accepted in either UI language (REQ-UI-033).
 *
 * @type {ReadonlyArray<KrtSearchPrefix>}
 */
export const KRT_SEARCH_PREFIXES = Object.freeze([
    { kind: 'action', symbol: '>', words: ['aktion', 'action'] },
    { kind: 'page', symbol: '/', words: ['seite', 'page'] },
    { kind: 'job_order', symbol: '#', words: ['auftrag', 'order'] },
    { kind: 'member', symbol: '@', words: ['mitglied', 'member'] },
    { kind: 'mission', symbol: '', words: ['mission'] },
    { kind: 'operation', symbol: '', words: ['operation'] },
    { kind: 'material', symbol: '', words: ['material'] },
    { kind: 'blueprint', symbol: '', words: ['blueprint'] },
    { kind: 'ship', symbol: '', words: ['schiff', 'ship'] },
    { kind: 'org_unit', symbol: '', words: ['einheit', 'unit'] },
]);

const PREFIX_WORD = /^([a-z]+):\s*/i;
const HELP_QUERY = '?';

/**
 * Splits a quick-access query into its kind prefix and the term.
 *
 * @param {string} raw the text as typed
 * @returns {{ kind: string | null, term: string, help: boolean }} the kind the prefix names, or
 *     null without one; the trimmed term after it; whether the query asks for the prefix help
 */
export function krtSearchParse(raw) {
    const text = raw.trim();
    if (text === HELP_QUERY) return { kind: null, term: '', help: true };
    const bySymbol = KRT_SEARCH_PREFIXES.find((p) => {
        return p.symbol !== '' && text.charAt(0) === p.symbol;
    });
    if (bySymbol) return { kind: bySymbol.kind, term: text.slice(1).trim(), help: false };
    const word = PREFIX_WORD.exec(text);
    if (word) {
        const lower = word[1].toLowerCase();
        const byWord = KRT_SEARCH_PREFIXES.find((p) => {
            return p.words.includes(lower);
        });
        if (byWord)
            return { kind: byWord.kind, term: text.slice(word[0].length).trim(), help: false };
    }
    return { kind: null, term: text, help: false };
}

/**
 * The page a server hit opens: its administration page in administration mode when it has one.
 *
 * @param {{ href: string, adminHref?: string | null }} hit the hit as the search answered it
 * @param {boolean} adminMode whether the navigation is in administration mode
 * @returns {string} the path to open
 */
export function krtSearchHitHref(hit, adminMode) {
    return adminMode && typeof hit.adminHref === 'string' && hit.adminHref.charAt(0) === '/'
        ? hit.adminHref
        : hit.href;
}

/**
 * How well an entry matches a folded query: a label that starts with it, a label that contains it,
 * then a breadcrumb or search term that contains it.
 *
 * @param {string} foldLabel the entry's folded label
 * @param {string} foldRest its folded breadcrumb and search terms
 * @param {string} q the folded query
 * @returns {number} 0, 1 or 2 from best to worst, -1 for no match
 */
export function krtSearchScore(foldLabel, foldRest, q) {
    if (foldLabel.startsWith(q)) return 0;
    if (foldLabel.includes(q)) return 1;
    return foldRest.includes(q) ? 2 : -1;
}

/** The score of a server hit whose label the folded query does not match. */
const SERVER_MATCH_SCORE = 3;

/**
 * Ranks pages and server hits into one list: by score, a page before a server hit of the same
 * score, then in the given order. Server hits always stay, since the server matched them.
 *
 * @template {{ foldLabel: string, foldRest: string, fromServer?: boolean }} T
 * @param {T[]} items the candidates
 * @param {string} q the folded query
 * @returns {T[]} the matching items, best first
 */
export function krtSearchRank(items, q) {
    return items
        .map((item, i) => {
            const s = krtSearchScore(item.foldLabel, item.foldRest, q);
            return { item, i, s: s < 0 && item.fromServer ? SERVER_MATCH_SCORE : s };
        })
        .filter((x) => {
            return x.s >= 0;
        })
        .sort((a, b) => {
            return (
                a.s - b.s || Number(!!a.item.fromServer) - Number(!!b.item.fromServer) || a.i - b.i
            );
        })
        .map((x) => {
            return x.item;
        });
}

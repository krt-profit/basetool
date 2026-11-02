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
 * The per-device „Häufig“ ranking of the quick access and the empty state built from it
 * (REQ-UI-033). Pure functions over a plain store; the browser storage lives in the caller.
 */

/** How long a visit counts: its weight falls linearly from 1 to 0 over this span (30 days). */
export const FREQUENT_WINDOW_MS = 30 * 24 * 60 * 60 * 1000;

/** The summed visit weight an entry needs before it counts as often used. */
export const FREQUENT_MIN_SCORE = 2;

const MAX_VISITS_PER_KEY = 30;
const MAX_KEYS = 200;

/**
 * The visits of each entry, keyed by entry key; each list holds the visit times in milliseconds,
 * oldest first.
 *
 * @typedef {Record<string, number[]>} KrtFrequentStore
 */

/**
 * @typedef {object} KrtEmptyStateSources
 * @property {string[]} favourites the favourite keys in the member's order
 * @property {string[]} frequent the often used keys, best first
 * @property {string[]} recent the recently visited keys, newest first
 */

/**
 * @typedef {object} KrtEmptyStateLimits
 * @property {number} frequent how many often used entries to show
 * @property {number} recent how many recent entries to show
 */

/**
 * The weight of one visit: 1 right now, falling to 0 once the window has passed.
 *
 * @param {number} at the visit time in milliseconds
 * @param {number} now the current time in milliseconds
 * @returns {number} the weight between 0 and 1
 */
function visitWeight(at, now) {
    return Math.max(0, Math.min(1, 1 - (now - at) / FREQUENT_WINDOW_MS));
}

/**
 * Reads a stored ranking, tolerating foreign and damaged content.
 *
 * @param {string | null} raw the stored text
 * @returns {KrtFrequentStore} the store; empty when the text is no valid store
 */
export function krtFrequentParse(raw) {
    /** @type {KrtFrequentStore} */
    const store = {};
    if (!raw) return store;
    try {
        const parsed = JSON.parse(raw);
        if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return store;
        for (const [key, visits] of Object.entries(parsed)) {
            if (!Array.isArray(visits)) continue;
            const times = visits.filter((at) => typeof at === 'number' && Number.isFinite(at));
            if (times.length) store[key] = times.sort((a, b) => a - b);
        }
    } catch (_e) {
        return {};
    }
    return store;
}

/**
 * The summed weight of an entry's visits.
 *
 * @param {number[]} visits the visit times in milliseconds
 * @param {number} now the current time in milliseconds
 * @returns {number} the score; 0 once every visit is older than the window
 */
export function krtFrequentScore(visits, now) {
    return visits.reduce((sum, at) => sum + visitWeight(at, now), 0);
}

/**
 * Counts one visit and drops what has decayed away: visits older than the window, entries
 * without a visit left, the oldest visits of a busy entry and the weakest entries beyond the cap.
 *
 * @param {KrtFrequentStore} store the stored ranking
 * @param {string} key the visited entry's key
 * @param {number} now the visit time in milliseconds
 * @returns {KrtFrequentStore} the new store; the argument is not changed
 */
export function krtFrequentRecord(store, key, now) {
    /** @type {KrtFrequentStore} */
    const next = {};
    for (const [stored, visits] of Object.entries({
        ...store,
        [key]: [...(store[key] || []), now],
    })) {
        const live = visits.filter((at) => visitWeight(at, now) > 0).slice(-MAX_VISITS_PER_KEY);
        if (live.length) next[stored] = live;
    }
    const keys = Object.keys(next);
    if (keys.length <= MAX_KEYS) return next;
    keys.sort((a, b) => krtFrequentScore(next[b], now) - krtFrequentScore(next[a], now));
    /** @type {KrtFrequentStore} */
    const kept = {};
    keys.slice(0, MAX_KEYS).forEach((stored) => {
        kept[stored] = next[stored];
    });
    return kept;
}

/**
 * The score of every entry that still has weight.
 *
 * @param {KrtFrequentStore} store the stored ranking
 * @param {number} now the current time in milliseconds
 * @returns {Map<string, number>} the scores by entry key
 */
export function krtFrequentScores(store, now) {
    /** @type {Map<string, number>} */
    const scores = new Map();
    for (const [key, visits] of Object.entries(store)) {
        const score = krtFrequentScore(visits, now);
        if (score > 0) scores.set(key, score);
    }
    return scores;
}

/**
 * The entries used often enough to be listed, best first.
 *
 * @param {KrtFrequentStore} store the stored ranking
 * @param {number} now the current time in milliseconds
 * @returns {string[]} the entry keys
 */
export function krtFrequentTop(store, now) {
    return Array.from(krtFrequentScores(store, now))
        .filter(([, score]) => score >= FREQUENT_MIN_SCORE)
        .sort(([, a], [, b]) => b - a)
        .map(([key]) => key);
}

/**
 * The empty state's three sections: favourites, then often used, then recent, each entry in the
 * highest section it qualifies for only.
 *
 * @param {KrtEmptyStateSources} sources the candidates of each section
 * @param {KrtEmptyStateLimits} limits how many entries the lower sections show
 * @returns {KrtEmptyStateSources} the keys to show in each section
 */
export function krtEmptyState(sources, limits) {
    const shown = new Set(sources.favourites);
    const favourites = Array.from(shown);
    const frequent = takeUnshown(sources.frequent, limits.frequent, shown);
    const recent = takeUnshown(sources.recent, limits.recent, shown);
    return { favourites, frequent, recent };
}

/**
 * The first keys not shown yet, which are then marked as shown.
 *
 * @param {string[]} keys the candidates in order
 * @param {number} limit how many to take at most
 * @param {Set<string>} shown the keys of the higher sections; extended by the keys taken
 * @returns {string[]} the keys taken
 */
function takeUnshown(keys, limit, shown) {
    /** @type {string[]} */
    const taken = [];
    for (const key of keys) {
        if (taken.length >= limit) break;
        if (shown.has(key)) continue;
        shown.add(key);
        taken.push(key);
    }
    return taken;
}

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
 * The quick access's search of the backend for entities (orders, missions, ...): debounced, one
 * request in flight, and its hits shown only while the query that asked is still the one typed
 * (REQ-UI-033).
 */

import { krtFetch } from '/js/kernel/krt-fetch.js';
import { CRUMB_SEPARATOR, makeItem } from '/js/kernel/krt-palette-items.js';

/** @import { PaletteItem } from '/js/kernel/krt-palette-items.js' */

const SEARCH_DEBOUNCE_MS = 250;
const SEARCH_MIN_LENGTH = 2;
const SEARCH_MIN_NUMBER_LENGTH = 1;
const NUMBER_TERM = /^[0-9]+$/;
const LOCAL_KINDS = ['action', 'page'];

/**
 * The identity of a query for the backend search: the same kind and term ask the same.
 *
 * @param {{ kind: string | null, term: string }} parsed the parsed query
 * @returns {string} the key
 */
function searchKeyOf(parsed) {
    return `${parsed.kind || ''}|${parsed.term}`;
}

/**
 * Turns one answer of the backend search into a row, or null for a malformed one.
 *
 * @param {any} e the answer's element
 * @returns {PaletteItem | null} the row
 */
function toHit(e) {
    if (!e || typeof e.href !== 'string' || e.href.charAt(0) !== '/') return null;
    if (typeof e.label !== 'string' || !e.label) return null;
    const date = typeof e.date === 'string' ? new Date(e.date) : null;
    const crumb = [
        typeof e.crumb === 'string' ? e.crumb : '',
        typeof e.status === 'string' ? e.status : '',
        date && !Number.isNaN(date.getTime())
            ? date.toLocaleDateString(document.documentElement.lang || undefined)
            : '',
    ]
        .filter(Boolean)
        .join(CRUMB_SEPARATOR);
    return makeItem({
        href: e.href,
        adminHref: typeof e.adminHref === 'string' ? e.adminHref : undefined,
        label: e.label,
        crumb,
        icon: typeof e.icon === 'string' && e.icon ? e.icon : 'list',
        keywords: '',
        fromServer: true,
    });
}

/**
 * The entity search of one palette.
 *
 * @param {string | undefined} url the search endpoint; without one the search never asks
 * @param {() => void} onSettled called when a request answered or failed while it was the current one
 * @returns {{
 *     hitsFor: (parsed: { kind: string | null, term: string }) => PaletteItem[],
 *     schedule: (parsed: { kind: string | null, term: string, help: boolean }) => void,
 *     cancel: () => void,
 *     pending: () => boolean,
 * }} the hits that answer a query, the debounced search for a query, the cancellation of the
 *     pending and running search, and whether a search is pending or running
 */
export function createEntitySearch(url, onSettled) {
    /** @type {PaletteItem[]} */
    let entityHits = [];
    let entityQuery = '';
    /** @type {AbortController | null} */
    let searchAbort = null;
    let searchTimer = 0;

    /** Drops the entity hits and stops the search still pending or in flight. */
    function cancel() {
        window.clearTimeout(searchTimer);
        searchTimer = 0;
        if (searchAbort) searchAbort.abort();
        searchAbort = null;
        entityHits = [];
        entityQuery = '';
    }

    /**
     * Asks the backend for the entities matching a query and keeps them when the query is still
     * the one typed.
     *
     * @param {{ kind: string | null, term: string }} parsed the parsed query
     */
    function run(parsed) {
        const key = searchKeyOf(parsed);
        const params = new URLSearchParams({ q: parsed.term });
        if (parsed.kind) params.append('kind', parsed.kind);
        const controller = new AbortController();
        searchAbort = controller;
        krtFetch
            .getJson(`${url}?${params.toString()}`, { signal: controller.signal })
            .then((list) => {
                if (searchAbort !== controller) return;
                entityHits = Array.isArray(list)
                    ? list.map(toHit).filter((h) => {
                          return h !== null;
                      })
                    : [];
                entityQuery = key;
            })
            .catch(() => {})
            .finally(() => {
                if (searchAbort !== controller) return;
                searchAbort = null;
                onSettled();
            });
    }

    /**
     * Debounces the backend search for a query; a query of a local kind, the help or a term shorter
     * than two characters, or one digit for an order number, asks nothing.
     *
     * @param {{ kind: string | null, term: string, help: boolean }} parsed the parsed query
     */
    function schedule(parsed) {
        if (searchKeyOf(parsed) === entityQuery) return;
        cancel();
        if (
            !url ||
            parsed.help ||
            (parsed.kind !== null && LOCAL_KINDS.includes(parsed.kind)) ||
            parsed.term.length <
                (NUMBER_TERM.test(parsed.term) ? SEARCH_MIN_NUMBER_LENGTH : SEARCH_MIN_LENGTH)
        ) {
            return;
        }
        searchTimer = window.setTimeout(() => {
            searchTimer = 0;
            run(parsed);
        }, SEARCH_DEBOUNCE_MS);
    }

    return {
        hitsFor: (parsed) => (entityQuery === searchKeyOf(parsed) ? entityHits : []),
        schedule,
        cancel,
        pending: () => searchTimer !== 0 || searchAbort !== null,
    };
}

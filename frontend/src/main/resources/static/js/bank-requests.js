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

(function () {
    'use strict';

    /** The segments of the status control, in display order. */
    const SEGMENTS = ['PENDING', 'CONFIRMED', 'REJECTED', 'ALL'];

    /** The single-status segments whose count the control shows. */
    const COUNTED = ['PENDING', 'CONFIRMED', 'REJECTED'];

    const RESULTS_ID = 'bank-request-queue-results';

    const form = /** @type {HTMLFormElement | null} */ (
        document.getElementById('bank-requests-filter-form')
    );
    if (!form) return;

    /**
     * The per-user storage key of the selected segment (REQ-UI-017).
     *
     * @returns {string} the key
     */
    function storageKey() {
        const main = document.querySelector('main[data-user-id]');
        const uid = main ? main.getAttribute('data-user-id') : 'unknown';
        return 'bank_request_status_filter_' + uid;
    }

    /**
     * The segment currently checked in the status control.
     *
     * @returns {string} the segment, `PENDING` when none is checked
     */
    function currentSegment() {
        const checked = /** @type {HTMLInputElement | null} */ (
            form && form.querySelector('input[name="status"]:checked')
        );
        return checked ? checked.value : 'PENDING';
    }

    /**
     * Checks the radio of one segment.
     *
     * @param {string} segment the segment to select
     */
    function selectSegment(segment) {
        if (!form) return;
        form.querySelectorAll('input[name="status"]').forEach(function (el) {
            const input = /** @type {HTMLInputElement} */ (el);
            input.checked = input.value === segment;
        });
    }

    /**
     * Maps a stored value onto a segment: a segment name as stored now, or the status list the
     * former independent checkboxes stored (one counted status keeps it, none means `PENDING`, any
     * other combination `ALL`).
     *
     * @param {unknown} saved the parsed stored value
     * @returns {string | null} the segment, or null when the value is unusable
     */
    function segmentOf(saved) {
        if (typeof saved === 'string') {
            return SEGMENTS.indexOf(saved) >= 0 ? saved : null;
        }
        if (Array.isArray(saved)) {
            const known = saved.filter(function (s) {
                return typeof s === 'string' && (COUNTED.indexOf(s) >= 0 || s === 'CANCELLED');
            });
            if (known.length === 0) return 'PENDING';
            if (known.length === 1 && COUNTED.indexOf(known[0]) >= 0) return known[0];
            return 'ALL';
        }
        return null;
    }

    /**
     * Reads the stored segment.
     *
     * @returns {string | null} the stored segment, or null when nothing usable is stored
     */
    function readSaved() {
        try {
            const raw = localStorage.getItem(storageKey());
            return raw === null ? null : segmentOf(JSON.parse(raw));
        } catch {
            return null;
        }
    }

    /**
     * Stores a segment.
     *
     * @param {string} segment the segment to store
     */
    function writeSaved(segment) {
        try {
            localStorage.setItem(storageKey(), JSON.stringify(segment));
        } catch {}
    }

    /**
     * Re-renders the queue for one segment in place.
     *
     * @param {string} segment the segment to show
     */
    function load(segment) {
        if (!window.krtFetch || typeof window.krtFetch.swap !== 'function') {
            window.location.assign('/bank/requests?status=' + encodeURIComponent(segment));
            return;
        }
        window.krtFetch.swap({
            url: '/bank/requests?status=' + encodeURIComponent(segment),
            container: '#' + RESULTS_ID,
            fragmentValue: 'requestQueue',
            history: true,
        });
    }

    /**
     * Copies the counts the queue fragment carries into the segment labels and the waiting
     * counter, which sit outside the swapped container.
     */
    function applyCounts() {
        const source = document.querySelector('#' + RESULTS_ID + ' [data-bank-request-counts]');
        if (!source || !form) return;
        COUNTED.forEach(function (segment) {
            const value = source.getAttribute('data-count-' + segment.toLowerCase());
            const input = form.querySelector('input[name="status"][value="' + segment + '"]');
            const label = input ? input.closest('label') : null;
            const badge = label ? label.querySelector('.seg-count') : null;
            if (badge && value !== null) badge.textContent = value;
        });
        const waiting = form.querySelector('[data-bank-waiting-count]');
        const template = waiting ? waiting.getAttribute('data-template') : null;
        const count = source.getAttribute('data-count-waiting');
        if (waiting && template && count !== null) {
            waiting.textContent = template.replace('{0}', count);
        }
    }

    form.addEventListener('submit', function (event) {
        event.preventDefault();
    });

    form.addEventListener('change', function (event) {
        const target = event.target;
        if (!(target instanceof HTMLInputElement) || target.name !== 'status') return;
        writeSaved(target.value);
        load(target.value);
    });

    document.addEventListener('krt:swapped', function (event) {
        const detail = /** @type {CustomEvent} */ (event).detail;
        const container = detail && detail.container instanceof Element ? detail.container : null;
        if (container && container.id === RESULTS_ID) applyCounts();
    });

    document.addEventListener('DOMContentLoaded', function () {
        if (/[?&]status=/.test(window.location.search)) {
            writeSaved(currentSegment());
            return;
        }
        const saved = readSaved();
        if (saved === null) {
            writeSaved(currentSegment());
            return;
        }
        if (saved !== currentSegment()) {
            writeSaved(saved);
            selectSegment(saved);
            load(saved);
        }
    });
})();

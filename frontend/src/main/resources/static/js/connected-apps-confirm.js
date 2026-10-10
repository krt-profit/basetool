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

// @ts-check

(function () {
    const BASE = '/connected-apps/confirm';

    const box = document.getElementById('cac-box');
    if (!box) {
        return;
    }
    const handoffId = box.dataset.handoffId || '';
    const i18n = readMessages();
    stripHandoff();

    function readMessages() {
        const holder = document.getElementById('cac-i18n');
        const data = holder ? holder.dataset : /** @type {DOMStringMap} */ ({});
        /** @param {string} key */
        const text = function (key) {
            const attribute = `data-${key.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`)}`;
            return window.krtI18nText(data[key], attribute);
        };
        return {
            error: text('error'),
            summary: text('summary'),
            done: text('done'),
            discarded: text('discarded'),
            refused: text('refused'),
            resources: {
                blueprints: text('resourceBlueprints'),
                stock: text('resourceStock'),
                ships: text('resourceShips'),
            },
        };
    }

    /** Removes the single-use id from the address bar, so a reload or a shared link cannot reuse it. */
    function stripHandoff() {
        const url = new URL(window.location.href);
        if (url.searchParams.has('handoff') && window.history && window.history.replaceState) {
            url.searchParams.delete('handoff');
            window.history.replaceState(null, '', url.pathname + url.search);
        }
    }

    /**
     * Shows one of the page's states and hides the others.
     * @param {string} id
     */
    function show(id) {
        ['cac-loading', 'cac-preview', 'cac-missing', 'cac-outcome'].forEach((other) => {
            const element = document.getElementById(other);
            if (element) {
                element.hidden = other !== id;
            }
        });
    }

    /**
     * Shows a closing message.
     * @param {string} message
     */
    function outcome(message) {
        const element = document.getElementById('cac-outcome');
        if (element) {
            element.textContent = message;
        }
        show('cac-outcome');
    }

    /**
     * Replaces the numbered placeholders of a message.
     * @param {string} template
     * @param {Array<string | number>} values
     */
    function format(template, values) {
        return template.replace(/\{(\d)\}/g, (match, index) => {
            const value = values[Number(index)];
            return value === undefined ? match : String(value);
        });
    }

    /**
     * Handles a refusal of the backend; truthy when it was handled.
     * @param {number} status
     */
    function refusal(status) {
        if (status === 404) {
            show('cac-missing');
            return true;
        }
        if (status === 403) {
            outcome(i18n.refused);
            return true;
        }
        return false;
    }

    if (!handoffId || !window.krtFetch) {
        show('cac-missing');
        return;
    }

    window.krtFetch.write({
        method: 'POST',
        url: `${BASE}/load`,
        payload: { handoffId },
        toast: false,
        errorMessage: i18n.error,
        onError: refusal,
        onSuccess(result) {
            const summary = document.getElementById('cac-summary');
            if (summary) {
                summary.textContent = format(i18n.summary, [
                    result.clientName,
                    i18n.resources[result.resource] || result.resource,
                    result.applied,
                    result.unchanged,
                    result.notApplied,
                ]);
            }
            show('cac-preview');
        },
    });

    const confirmBtn = document.getElementById('cac-confirm');
    if (confirmBtn) {
        confirmBtn.addEventListener('click', () => {
            window.krtFetch.write({
                method: 'POST',
                url: `${BASE}/apply`,
                payload: { handoffId },
                toast: false,
                errorMessage: i18n.error,
                submitter: confirmBtn,
                onError: refusal,
                onSuccess(result) {
                    outcome(format(i18n.done, [result.applied]));
                },
            });
        });
    }

    const discardBtn = document.getElementById('cac-discard');
    if (discardBtn) {
        discardBtn.addEventListener('click', () => {
            window.krtFetch.write({
                method: 'POST',
                url: `${BASE}/discard`,
                payload: { handoffId },
                toast: false,
                errorMessage: i18n.error,
                submitter: discardBtn,
                onSuccess() {
                    outcome(i18n.discarded);
                },
            });
        });
    }
})();

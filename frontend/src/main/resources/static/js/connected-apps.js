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
    const BASE = '/connected-apps';

    const host = document.getElementById('ca-host');
    if (!host) {
        return;
    }
    const i18n = readMessages();
    const undoI18n = readUndoMessages();
    const HOUR_MS = 3600000;
    /** @type {string | null} */
    let undoClientId = null;

    function readMessages() {
        const holder = document.getElementById('ca-i18n');
        const data = holder ? holder.dataset : /** @type {DOMStringMap} */ ({});
        /** @param {string} key */
        const text = function (key) {
            const attribute = `data-${key.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`)}`;
            return window.krtI18nText(data[key], attribute);
        };
        return {
            disconnected: text('disconnected'),
            error: text('error'),
            confirmOk: text('confirmOk'),
            confirmCancel: text('confirmCancel'),
            confirmClientTitle: text('confirmClientTitle'),
            confirmClientBody: text('confirmClientBody'),
            confirmInstallationTitle: text('confirmInstallationTitle'),
            confirmInstallationBody: text('confirmInstallationBody'),
        };
    }

    function readUndoMessages() {
        const holder = document.getElementById('ca-undo-i18n');
        const data = holder ? holder.dataset : /** @type {DOMStringMap} */ ({});
        /** @param {string} key */
        const text = function (key) {
            const attribute = `data-${key.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`)}`;
            return window.krtI18nText(data[key], attribute);
        };
        return {
            done: text('done'),
            skippedTitle: text('skippedTitle'),
            unnamed: text('unnamed'),
            reasons: {
                CHANGED_AFTERWARDS: text('reasonChangedAfterwards'),
                GONE: text('reasonGone'),
            },
            resources: {
                BLUEPRINT: text('resourceBlueprint'),
                STOCK: text('resourceStock'),
                SHIP: text('resourceShip'),
            },
        };
    }

    /**
     * Shows what an undo restored and what it left alone, as text only.
     * @param {{ restored: number, skipped: Array<{ resource: string, label: string | null, reason: string }> }} result
     */
    function showUndoResult(result) {
        const box = document.getElementById('ca-undo-result');
        if (!box) {
            return;
        }
        box.replaceChildren();
        const done = document.createElement('p');
        done.textContent = undoI18n.done.replace('{0}', String(result.restored));
        box.appendChild(done);
        if (result.skipped && result.skipped.length) {
            const title = document.createElement('h3');
            title.textContent = undoI18n.skippedTitle;
            box.appendChild(title);
            const list = document.createElement('ul');
            list.className = 'ca-undo-skipped';
            result.skipped.forEach((entry) => {
                const item = document.createElement('li');
                const resource = undoI18n.resources[entry.resource] || entry.resource;
                const reason = undoI18n.reasons[entry.reason] || entry.reason;
                item.textContent = `${resource}: ${entry.label || undoI18n.unnamed} \u2013 ${reason}`;
                list.appendChild(item);
            });
            box.appendChild(list);
        }
        box.hidden = false;
    }

    const undoForm = document.getElementById('ca-undo-form');
    if (undoForm instanceof HTMLFormElement) {
        undoForm.addEventListener('submit', (event) => {
            event.preventDefault();
            if (!undoClientId || !window.krtFetch) {
                return;
            }
            const span = /** @type {HTMLSelectElement | null} */ (
                document.getElementById('ca-undo-span')
            );
            const hours = span ? Number(span.value) : 24;
            const since = new Date(Date.now() - hours * HOUR_MS).toISOString();
            const submitter = undoForm.querySelector('button[type="submit"]');
            window.krtFetch.write({
                method: 'POST',
                url: `${BASE}/${encodeURIComponent(undoClientId)}/undo`,
                payload: { since },
                toast: false,
                errorMessage: i18n.error,
                submitter,
                onSuccess(result) {
                    window.krtModal.close('ca-undo-modal');
                    showUndoResult(result);
                    return refreshApps();
                },
            });
        });
    }

    /**
     * Reports one new installation as seen after the member acknowledged it, then re-renders the list.
     * @param {string} id
     * @param {Element} submitter
     */
    function markSeen(id, submitter) {
        if (!window.krtFetch) {
            return;
        }
        window.krtFetch.write({
            method: 'POST',
            url: `${BASE}/installations/${encodeURIComponent(id)}/seen`,
            toast: false,
            errorMessage: i18n.error,
            submitter,
            onSuccess() {
                return refreshApps();
            },
        });
    }

    /** Re-renders the list in place from the `apps` fragment. */
    function refreshApps() {
        return window.krtFetch.swap({
            url: BASE,
            container: '#ca-host',
            fragmentValue: 'apps',
            errorMessage: i18n.error,
        });
    }

    /**
     * Asks first, then sends one disconnect and re-renders the list.
     * @param {string} titleText
     * @param {string} bodyText
     * @param {string} url
     * @param {Element} submitter
     */
    function disconnect(titleText, bodyText, url, submitter) {
        if (!window.krtFetch) {
            return;
        }
        const send = function () {
            window.krtFetch.write({
                method: 'DELETE',
                url,
                successMessage: i18n.disconnected,
                errorMessage: i18n.error,
                submitter,
                onSuccess() {
                    return refreshApps();
                },
            });
        };
        if (typeof window.showKrtConfirm !== 'function') {
            send();
            return;
        }
        window
            .showKrtConfirm(titleText, bodyText, i18n.confirmOk, i18n.confirmCancel)
            .then((ok) => {
                if (ok) {
                    send();
                }
            });
    }

    host.addEventListener('click', (event) => {
        const target = event.target;
        if (!(target instanceof Element)) {
            return;
        }
        const undoBtn = target.closest('[data-ca-undo]');
        if (undoBtn) {
            const app = undoBtn.closest('[data-client-id]');
            undoClientId = app ? app.getAttribute('data-client-id') : null;
            if (undoClientId && window.krtModal) {
                window.krtModal.open('ca-undo-modal');
            }
            return;
        }
        const clientBtn = target.closest('[data-ca-disconnect-client]');
        if (clientBtn) {
            const app = clientBtn.closest('[data-client-id]');
            const clientId = app ? app.getAttribute('data-client-id') : null;
            if (clientId) {
                disconnect(
                    i18n.confirmClientTitle,
                    i18n.confirmClientBody,
                    `${BASE}/${encodeURIComponent(clientId)}`,
                    clientBtn,
                );
            }
            return;
        }
        const seenBtn = target.closest('[data-ca-mark-seen]');
        if (seenBtn) {
            const row = seenBtn.closest('[data-installation-id]');
            const id = row ? row.getAttribute('data-installation-id') : null;
            if (id) {
                markSeen(id, seenBtn);
            }
            return;
        }
        const installationBtn = target.closest('[data-ca-disconnect-installation]');
        if (installationBtn) {
            const row = installationBtn.closest('[data-installation-id]');
            const id = row ? row.getAttribute('data-installation-id') : null;
            if (id) {
                disconnect(
                    i18n.confirmInstallationTitle,
                    i18n.confirmInstallationBody,
                    `${BASE}/installations/${encodeURIComponent(id)}`,
                    installationBtn,
                );
            }
        }
    });
})();

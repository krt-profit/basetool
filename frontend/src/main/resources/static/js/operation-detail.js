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

/* global OPS_DETAIL_MSG, MSG_PAYOUT_PAID_ERROR, MSG_PAYOUT_PAID_FORBIDDEN, MSG_PAYOUT_PAID_UNSET_LOCKED, OPS_FINANCE_DETAIL_ERROR */

const OPERATION_SECTIONS = {
    overview: { container: '#op-overview-results', fragmentValue: 'overview' },
    missions: { container: '#op-missions-results', fragmentValue: 'missions' },
    payout: { container: '#op-payout-results', fragmentValue: 'payout' },
    finance: { container: '#op-finance-results', fragmentValue: 'finance' },
};

/**
 * The sections a peer change re-renders: the overview aggregates the KPI bar, the per-mission
 * results and the payout progress, so a change to any other section refreshes it too.
 *
 * @param {string[] | string} keys the changed section keys
 * @returns {string[]} the keys to refresh, with `overview` added when another section changed
 */
function withOverview(keys) {
    const list = Array.isArray(keys) ? keys.slice() : [keys];
    if (list.indexOf('overview') === -1 && list.length) {
        list.push('overview');
    }
    return list;
}

(function () {
    if (!window.krtFetch || typeof window.krtFetch.sectionWrite !== 'function') {
        return;
    }
    const operationSeam = window.krtFetch.sectionWrite({
        dict() {
            return { 'operation.section.refresh.error': OPS_DETAIL_MSG.sectionRefreshError };
        },
        keys: { refreshErrorKey: 'operation.section.refresh.error' },
        sections: OPERATION_SECTIONS,
        pageUrl() {
            return window.operationId ? `/operations/${window.operationId}` : null;
        },
        broadcast(keys) {
            if (
                window.operationId &&
                window.krtLiveSync &&
                typeof window.krtLiveSync.sendChanged === 'function'
            ) {
                window.krtLiveSync.sendChanged(`operation:${window.operationId}`, keys);
            }
        },
    });
    window.opRefreshSection = operationSeam.refresh;
    window.opNotifyChanged = operationSeam.notify;

    if (window.operationId && window.krtLiveSync && window.krtLiveSync.createReceiver) {
        window.krtLiveSync.createReceiver({
            topic: `operation:${window.operationId}`,
            sections: OPERATION_SECTIONS,
            refresh(keys) {
                if (window.opRefreshSection) {
                    window.opRefreshSection(withOverview(keys), { broadcast: false });
                }
            },
            pill: {
                label() {
                    return OPS_DETAIL_MSG.livesyncUpdates;
                },
            },
        });
    }

    /**
     * Writes a text into the element with the given id, when both exist.
     *
     * @param {string} id the element id
     * @param {string | null} text the new text
     */
    function setText(id, text) {
        const el = document.getElementById(id);
        if (el && text != null) {
            el.textContent = text;
        }
    }

    document.addEventListener('krt:swapped', (ev) => {
        const container = ev && ev.detail && ev.detail.container;
        if (!container || container.id !== 'op-overview-results') {
            return;
        }
        const meta = document.getElementById('operation-head-meta');
        if (!meta) {
            return;
        }
        setText('operation-title', meta.getAttribute('data-name'));
        const status = (meta.getAttribute('data-status') || '').trim();
        const badge = document.getElementById('operation-status-badge');
        if (badge && status) {
            badge.className = `status-badge status-${status}`;
            const statusLabel = meta.getAttribute('data-status-label');
            if (statusLabel != null) {
                badge.textContent = statusLabel;
            }
        }
        const missions = meta.getAttribute('data-kpi-missions');
        setText('op-kpi-missions', missions);
        setText('optab-missions-count', missions);
        setText('op-kpi-total', meta.getAttribute('data-kpi-total'));
        const total = document.getElementById('op-kpi-total');
        if (total) {
            total.classList.toggle(
                'is-neg',
                meta.getAttribute('data-kpi-total-negative') === 'true',
            );
        }
        setText('op-kpi-donated', meta.getAttribute('data-kpi-donated'));
        setText('op-kpi-participants', meta.getAttribute('data-kpi-participants'));
    });
})();

(function () {
    const tabs = Array.from(document.querySelectorAll('#operation-tabs > .tab[data-tab]'));
    const panes = Array.from(document.querySelectorAll('.tab-panes > .tab-pane'));
    if (!tabs.length) return;
    const STORAGE_KEY = `krt.operation.${window.operationId || 'new'}.tab`;

    /**
     * Shows one tab and its pane.
     *
     * @param {string | null} key the tab's `data-tab` key; an unknown key shows the first tab
     * @param {boolean} push whether to write the key into the `tab` query parameter
     */
    function show(key, push) {
        const tab = tabs.find((t) => t.getAttribute('data-tab') === key) || tabs[0];
        const shown = tab.getAttribute('data-tab');
        tabs.forEach((t) => {
            const on = t === tab;
            t.classList.toggle('active', on);
            t.setAttribute('aria-selected', on ? 'true' : 'false');
            t.setAttribute('tabindex', on ? '0' : '-1');
        });
        panes.forEach((p) => p.classList.toggle('on', p.id === `pane-op-${shown}`));
        try {
            localStorage.setItem(STORAGE_KEY, shown || '');
        } catch (_e) {}
        if (push && window.history && window.history.replaceState) {
            const url = new URL(window.location.href);
            url.searchParams.set('tab', shown || '');
            window.history.replaceState({ opTab: shown }, '', url.toString());
        }
    }

    tabs.forEach((tab) =>
        tab.addEventListener('click', () => show(tab.getAttribute('data-tab'), true)),
    );
    const tabNav = document.getElementById('operation-tabs');
    if (tabNav) {
        tabNav.addEventListener('keydown', (e) => {
            if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft') return;
            const i = tabs.indexOf(/** @type {HTMLElement} */ (document.activeElement));
            if (i < 0) return;
            e.preventDefault();
            const next =
                e.key === 'ArrowRight'
                    ? (i + 1) % tabs.length
                    : (i - 1 + tabs.length) % tabs.length;
            tabs[next].focus();
            show(tabs[next].getAttribute('data-tab'), true);
        });
    }

    document.addEventListener('click', (e) => {
        const target = /** @type {Element | null} */ (e.target);
        const goto = target && target.closest ? target.closest('[data-op-goto-tab]') : null;
        if (!goto) return;
        const key = goto.getAttribute('data-op-goto-tab');
        show(key, true);
        const tab = tabs.find((t) => t.getAttribute('data-tab') === key);
        if (tab) {
            tab.focus();
        }
    });

    const params = new URLSearchParams(window.location.search);
    let initial = params.get('tab');
    if (!initial || !tabs.some((t) => t.getAttribute('data-tab') === initial)) {
        try {
            initial = localStorage.getItem(STORAGE_KEY);
        } catch (_e) {
            initial = null;
        }
    }
    show(
        initial && tabs.some((t) => t.getAttribute('data-tab') === initial)
            ? initial
            : tabs[0].getAttribute('data-tab'),
        false,
    );
})();

/**
 * Points the delete form at the operation and opens the confirmation dialog.
 *
 * @param {string | null} id the operation id
 */
function openDeleteModal(id) {
    const deleteForm = /** @type {HTMLFormElement} */ (
        document.getElementById('delete-operation-form')
    );
    deleteForm.action = window.safeSameOriginUrl(`/operations/${id}/delete`, deleteForm.action);
    window.krtModal.open(document.getElementById('delete-operation-modal'));
}

document.addEventListener('DOMContentLoaded', () => {
    if (window.krtFetch) {
        window.krtFetch.bindSwap({
            container: '#op-missions-results',
            fragmentValue: 'missions',
            history: true,
        });
    }
});

if (window.krtEvents && typeof window.krtEvents.on === 'function') {
    window.krtEvents.on('click', 'operation-open-delete', (el) => {
        openDeleteModal(el.getAttribute('data-id'));
    });
}

(function () {
    const editor = document.getElementById('op-md-editor');
    if (!editor) return;
    const input = /** @type {HTMLTextAreaElement | null} */ (document.getElementById('op-desc'));
    const preview = document.getElementById('op-md-preview');
    const toolbar = document.getElementById('op-md-toolbar');
    const viewTabs = Array.prototype.slice.call(editor.querySelectorAll('[data-md-view]'));
    if (!input || !preview || !toolbar || !viewTabs.length) return;

    /**
     * Switches the description editor between the Markdown source and its rendered preview.
     *
     * @param {string | null} view `preview` for the preview, anything else for the editor
     */
    function showView(view) {
        const edit = view !== 'preview';
        if (!input || !preview || !toolbar) return;
        input.hidden = !edit;
        toolbar.hidden = !edit;
        preview.hidden = edit;
        viewTabs.forEach((t) => {
            const on = t.getAttribute('data-md-view') === (edit ? 'edit' : 'preview');
            t.classList.toggle('active', on);
            t.setAttribute('aria-selected', on ? 'true' : 'false');
        });
        if (!edit) {
            renderPreview();
        }
    }

    /** Renders the current Markdown source into the preview through the sanitising endpoint. */
    function renderPreview() {
        if (!input || !preview) return;
        preview.textContent = '';
        if (!window.krtFetch) return;
        window.krtFetch.write({
            method: 'POST',
            url: '/operations/markdown-preview',
            payload: { markdown: input.value },
            accept: 'text/html, application/problem+json',
            toast: false,
            onSuccess(html) {
                window.krtFetch.setTrustedHtml(preview, typeof html === 'string' ? html : '');
            },
            onError() {
                return true;
            },
            onNetworkError() {
                return true;
            },
        });
    }

    /**
     * Wraps the selection in a Markdown marker.
     *
     * @param {string} marker the marker, e.g. `**`
     */
    function wrapSelection(marker) {
        if (!input) return;
        const s = input.selectionStart,
            e = input.selectionEnd;
        const sel = input.value.slice(s, e) || '';
        input.setRangeText(marker + sel + marker, s, e, 'end');
        input.focus();
    }

    /**
     * Prefixes every selected line.
     *
     * @param {string} prefix the line prefix, e.g. `- `
     */
    function prefixLines(prefix) {
        if (!input) return;
        const s = input.selectionStart,
            e = input.selectionEnd;
        const lineStart = input.value.lastIndexOf('\n', s - 1) + 1;
        const block = input.value.slice(lineStart, e);
        const replaced = block
            .split('\n')
            .map((l) => prefix + l)
            .join('\n');
        input.setRangeText(replaced, lineStart, e, 'end');
        input.focus();
    }

    /** Inserts a Markdown link around the selection. */
    function insertLink() {
        if (!input) return;
        const s = input.selectionStart,
            e = input.selectionEnd;
        const sel =
            input.value.slice(s, e) ||
            window.krtI18nText(OPS_DETAIL_MSG.linkText, 'OPS_DETAIL_MSG.linkText');
        input.setRangeText(`[${sel}](https://)`, s, e, 'end');
        input.focus();
    }

    viewTabs.forEach((t) =>
        t.addEventListener('click', () => showView(t.getAttribute('data-md-view'))),
    );
    toolbar.addEventListener('click', (e) => {
        const target = /** @type {Element} */ (e.target);
        const btn = target.closest('button');
        if (!btn) return;
        if (btn.hasAttribute('data-md-wrap')) {
            wrapSelection(btn.getAttribute('data-md-wrap') || '');
        } else if (btn.hasAttribute('data-md-line')) {
            prefixLines(btn.getAttribute('data-md-line') || '');
        } else if (btn.hasAttribute('data-md-link')) {
            insertLink();
        }
    });

    const dialog = document.getElementById('edit-operation-modal');
    if (dialog) {
        dialog.addEventListener('close', () => {
            const form = /** @type {HTMLFormElement | null} */ (
                document.getElementById('operation-form')
            );
            if (form) {
                form.reset();
            }
            showView('edit');
            if (typeof window.resetUnsavedChanges === 'function') {
                window.resetUnsavedChanges();
            }
        });
    }
})();

/**
 * The conflict dialog strings of this page.
 *
 * @returns {object} the `conflict` option of `krtFetch.write`
 */
function opsDetailConflict() {
    return {
        title: OPS_DETAIL_MSG.conflictTitle,
        reloadDetailFallback: OPS_DETAIL_MSG.conflictDetail,
        reloadLabel: OPS_DETAIL_MSG.conflictReload,
        dismissLabel: OPS_DETAIL_MSG.conflictDismiss,
        reloadQuestion: OPS_DETAIL_MSG.conflictQuestion,
    };
}

/**
 * Makes the edit form's current values its defaults, so a later reset keeps the saved state.
 *
 * @param {HTMLFormElement} form the edit form
 */
function adoptOperationFormValues(form) {
    form.querySelectorAll('input, textarea').forEach((el) => {
        const field = /** @type {HTMLInputElement | HTMLTextAreaElement} */ (el);
        field.defaultValue = field.value;
    });
    form.querySelectorAll('select option').forEach((el) => {
        const option = /** @type {HTMLOptionElement} */ (el);
        option.defaultSelected = option.selected;
    });
}

(function () {
    if (!window.krtFetch) return;

    const form = /** @type {HTMLFormElement | null} */ (document.getElementById('operation-form'));
    if (form) {
        form.addEventListener('submit', (event) => {
            event.preventDefault();
            const versionInput = /** @type {HTMLInputElement | null} */ (
                form.querySelector('[name="version"]')
            );
            window.krtFetch.write({
                method: 'POST',
                url: form.getAttribute('action'),
                serialize: 'operation:core',
                submitter: document.querySelector('button[type="submit"][form="operation-form"]'),
                payload() {
                    return {
                        name: /** @type {HTMLInputElement} */ (form.querySelector('[name="name"]'))
                            .value,
                        description: /** @type {HTMLTextAreaElement} */ (
                            form.querySelector('[name="description"]')
                        ).value,
                        status: /** @type {HTMLSelectElement} */ (
                            form.querySelector('[name="status"]')
                        ).value,
                        version: versionInput ? Number(versionInput.value) : null,
                        owningOrgUnitId: null,
                    };
                },
                successMessage: OPS_DETAIL_MSG.updateSuccess,
                errorMessage: OPS_DETAIL_MSG.updateError,
                conflict: opsDetailConflict(),
                onSuccess(body) {
                    if (body && body.version != null && versionInput) {
                        versionInput.value = body.version;
                    }
                    adoptOperationFormValues(form);
                    window.krtModal.close('edit-operation-modal');
                    if (window.opRefreshSection) {
                        window.opRefreshSection('overview');
                    }
                },
            });
        });
    }

    const deleteForm = /** @type {HTMLFormElement | null} */ (
        document.getElementById('delete-operation-form')
    );
    if (deleteForm) {
        deleteForm.addEventListener('submit', (event) => {
            event.preventDefault();
            window.krtFetch.write({
                method: 'POST',
                url: deleteForm.action,
                submitter: deleteForm.querySelector('button[type="submit"]'),
                successMessage: OPS_DETAIL_MSG.deleteSuccess,
                errorMessage: OPS_DETAIL_MSG.deleteError,
                conflict: opsDetailConflict(),
                onSuccess() {
                    window.location.assign('/operations');
                },
            });
        });
    }
})();

/**
 * The page's paid-out write URL.
 *
 * @returns {string | null} the URL, or null without an operation id
 */
function payoutPaidUrl() {
    return window.operationId ? `/operations/${window.operationId}/payouts/paid-out` : null;
}

/**
 * Whether the viewer may take a paid-out mark back.
 *
 * @returns {boolean} true for officers and admins
 */
function canUnsetPaidOut() {
    const pane = document.getElementById('pane-op-payout');
    return pane != null && pane.getAttribute('data-can-unset-paid-out') === 'true';
}

/** Recounts the paid-out rows into the payout table's sum row. */
function refreshPayoutPaidCount() {
    const results = document.getElementById('op-payout-results');
    const counter = document.getElementById('op-payout-paid-count');
    if (!results || !counter) return;
    const boxes = results.querySelectorAll('.payout-paid-checkbox');
    if (!boxes.length) return;
    const paid = results.querySelectorAll('.payout-paid-checkbox:checked').length;
    counter.textContent = `${paid} / ${boxes.length}`;
}

/**
 * Applies a paid-out status response to its table row.
 *
 * @param {Element | null} row the participant's row
 * @param {{ paidOut?: boolean, paidOutByName?: string | null, paidOutAt?: string | null }} dto
 *     the refreshed status
 */
function refreshPayoutPaidStatusCell(row, dto) {
    if (!row) return;
    const checkbox = /** @type {HTMLInputElement | null} */ (
        row.querySelector('.payout-paid-checkbox')
    );
    if (!checkbox) return;
    checkbox.checked = !!dto.paidOut;
    if (checkbox.checked && !canUnsetPaidOut()) {
        checkbox.disabled = true;
        checkbox.title = MSG_PAYOUT_PAID_UNSET_LOCKED;
    } else {
        checkbox.disabled = false;
        checkbox.title = '';
    }
    let statusSpan = /** @type {HTMLElement | null} */ (row.querySelector('.payout-paid-status'));
    if (dto.paidOut && dto.paidOutByName) {
        if (!statusSpan) {
            statusSpan = document.createElement('span');
            statusSpan.className = 'payout-paid-status';
            if (checkbox.parentElement) {
                checkbox.parentElement.appendChild(statusSpan);
            }
        }
        statusSpan.textContent = dto.paidOutByName;
        if (dto.paidOutAt) {
            const date = new Date(dto.paidOutAt);
            if (!isNaN(date.getTime())) {
                statusSpan.title = date.toLocaleString();
            }
        }
    } else if (statusSpan) {
        statusSpan.remove();
    }
}

/**
 * Writes a paid-out toggle and updates the row, the sum row and the overview in place.
 *
 * @param {HTMLInputElement} checkbox the toggled checkbox
 */
async function handlePayoutPaidToggle(checkbox) {
    const url = payoutPaidUrl();
    if (!url) return;
    const participantKey = checkbox.getAttribute('data-participant-id');
    if (!participantKey) return;
    const desired = checkbox.checked;
    const previous = !desired;
    checkbox.disabled = true;
    try {
        const result = await window.krtFetch.write({
            method: 'POST',
            url,
            payload: { participantKey, paidOut: desired },
            toast: false,
            onError(status) {
                checkbox.checked = previous;
                if (window.showFrontendErrorToast) {
                    window.showFrontendErrorToast(
                        status === 401 || status === 403
                            ? MSG_PAYOUT_PAID_FORBIDDEN
                            : MSG_PAYOUT_PAID_ERROR,
                    );
                }
                return true;
            },
            onNetworkError() {
                checkbox.checked = previous;
                if (window.showFrontendErrorToast) {
                    window.showFrontendErrorToast(MSG_PAYOUT_PAID_ERROR);
                }
                return true;
            },
        });
        if (result && result.ok && result.body) {
            refreshPayoutPaidStatusCell(checkbox.closest('tr[data-participant-id]'), result.body);
            refreshPayoutPaidCount();
            if (window.opRefreshSection) {
                window.opRefreshSection('overview', { broadcast: false });
            }
            if (window.opNotifyChanged) {
                window.opNotifyChanged(['payout']);
            }
        }
    } finally {
        if (!(checkbox.checked && !canUnsetPaidOut())) {
            checkbox.disabled = false;
        }
    }
}

document.addEventListener('change', (ev) => {
    const target = /** @type {Element | null} */ (ev.target);
    const checkbox =
        target && target.closest
            ? /** @type {HTMLInputElement | null} */ (target.closest('.payout-paid-checkbox'))
            : null;
    if (checkbox) {
        handlePayoutPaidToggle(checkbox);
    }
});

(function () {
    const opId = window.operationId;
    if (!opId) return;

    /**
     * Loads one mission's finance breakdown the first time its row opens.
     *
     * @param {HTMLDetailsElement} details the opened row
     */
    function loadDetail(details) {
        if (!details.open) return;
        const body = /** @type {HTMLElement | null} */ (
            details.querySelector('.op-finance-detail-body')
        );
        if (!body || body.getAttribute('data-loaded') !== 'false') return;
        const missionId = details.getAttribute('data-op-finance-mission');
        if (!missionId) return;
        body.setAttribute('data-loaded', 'loading');
        const url = `/operations/${encodeURIComponent(opId)}/finance/${encodeURIComponent(missionId)}`;
        window.krtFetch
            .get(url)
            .then((r) => (r && r.ok ? r.text() : Promise.reject(r ? r.status : 0)))
            .then((html) => {
                window.krtFetch.setTrustedHtml(body, html);
                body.setAttribute('data-loaded', 'true');
            })
            .catch(() => {
                body.setAttribute('data-loaded', 'false');
                const msg =
                    typeof OPS_FINANCE_DETAIL_ERROR !== 'undefined' ? OPS_FINANCE_DETAIL_ERROR : '';
                const p = document.createElement('p');
                p.className = 'op-fin-note op-fin-error';
                p.textContent = msg;
                body.textContent = '';
                body.appendChild(p);
            });
    }
    document.addEventListener(
        'toggle',
        (ev) => {
            const details = /** @type {Element | null} */ (ev.target);
            if (details && details.matches && details.matches('details[data-op-finance-mission]')) {
                loadDetail(/** @type {HTMLDetailsElement} */ (details));
            }
        },
        true,
    );
})();

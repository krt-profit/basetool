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

/*
 * Operation-detail page module (/operations/{id}), extracted verbatim from the four former inline
 * scripts of operation-detail.html (ADR-0069, follow-up to #924).
 *
 * Covers: the WAI-ARIA tab switching (deeplink ?tab= + localStorage), the delete-confirmation modal
 * opener + missions-pager in-place swap, the Markdown description editor (Bearbeiten/Vorschau +
 * formatting toolbar, server-rendered preview), the in-place AJAX operation core save + delete (#576),
 * and the per-participant payout paid-out toggle. Writes go through window.krtFetch / a krtCsrf fetch
 * and update the DOM in place; the classic POST->redirect forms stay the no-JS fallback.
 *
 * The interpolated pieces are the only Thymeleaf expressions, so they stay inline in the page bootstrap:
 * window.operationId, the OPS_DETAIL_MSG toast/conflict strings, and the MSG_PAYOUT_PAID_* strings this
 * module reads.
 */

/* global OPS_DETAIL_MSG, MSG_PAYOUT_PAID_ERROR, MSG_PAYOUT_PAID_FORBIDDEN, MSG_PAYOUT_PAID_UNSET_LOCKED, OPS_FINANCE_DETAIL_ERROR */

// ---- Live multi-user sync (REQ-FE-010 / REQ-FE-015, ADR-0094) -------------------------------
// A peer's core save / paid-out toggle re-renders the affected operation section fragment in place
// for every other viewer, over the shared /ws/sync socket. Only opaque section keys cross the wire;
// each viewer re-pulls its own authorization-checked fragment. OPERATION_SECTIONS is the single
// source of truth shared by the write-side broadcast and the receive-side refresh (the three-mirror-
// points rule): its keys mirror the server-side LiveSyncTopicClass.OPERATION whitelist. 'overview'
// and 'payout' are broadcast from this operation page; 'missions' and 'finance' are cross-published
// from the mission detail page when a child mission's core/finance changes (#1241) — an operation
// viewer refreshes the embedded missions table / finance roll-up in place without a reload.
const OPERATION_SECTIONS = {
    overview: { container: '#op-overview-results', fragmentValue: 'overview' },
    missions: { container: '#op-missions-results', fragmentValue: 'missions' },
    payout: { container: '#op-payout-results', fragmentValue: 'payout' },
    finance: { container: '#op-finance-results', fragmentValue: 'finance' },
};

(function () {
    if (!window.krtFetch || typeof window.krtFetch.sectionWrite !== 'function') {
        return; // no-JS / no-foundation: the classic POST->redirect forms run.
    }
    const operationSeam = window.krtFetch.sectionWrite({
        dict: function () {
            return { 'operation.section.refresh.error': OPS_DETAIL_MSG.sectionRefreshError };
        },
        keys: { refreshErrorKey: 'operation.section.refresh.error' },
        sections: OPERATION_SECTIONS,
        pageUrl: function () {
            return window.operationId ? '/operations/' + window.operationId : null;
        },
        // Tell other users viewing this operation that these sections changed (REQ-FE-015).
        broadcast: function (keys) {
            if (
                window.operationId &&
                window.krtLiveSync &&
                typeof window.krtLiveSync.sendChanged === 'function'
            ) {
                window.krtLiveSync.sendChanged('operation:' + window.operationId, keys);
            }
        },
    });
    // opRefreshSection re-renders one or more sections in place (and broadcasts unless
    // {broadcast:false}); opNotifyChanged broadcasts only (for handlers that already patched their
    // own DOM surgically, e.g. the paid-out toggle keeps its per-row cell patch).
    window.opRefreshSection = operationSeam.refresh;
    window.opNotifyChanged = operationSeam.notify;

    // Inbound peer changes: subscribe to operation:{id} on /ws/sync and re-fetch the affected section
    // fragments locally with {broadcast:false} so an applied peer change never echoes back.
    if (window.operationId && window.krtLiveSync && window.krtLiveSync.createReceiver) {
        window.krtLiveSync.createReceiver({
            topic: 'operation:' + window.operationId,
            sections: OPERATION_SECTIONS,
            refresh: function (keys) {
                if (window.opRefreshSection) {
                    window.opRefreshSection(keys, { broadcast: false });
                }
            },
            pill: {
                label: function () {
                    return OPS_DETAIL_MSG.livesyncUpdates;
                },
            },
        });
    }

    // After an in-place OVERVIEW swap (local OR peer-driven) patch the sticky header parts that live
    // outside the fragment — the title (#operation-title) and the status pill — from the fresh values
    // #operation-head-meta exposes.
    document.addEventListener('krt:swapped', function (ev) {
        const container = ev && ev.detail && ev.detail.container;
        if (!container || container.id !== 'op-overview-results') {
            return;
        }
        const meta = document.getElementById('operation-head-meta');
        if (!meta) {
            return;
        }
        const name = meta.getAttribute('data-name');
        const title = document.getElementById('operation-title');
        if (title && name != null) {
            title.textContent = OPS_DETAIL_MSG.prefix + ' ' + name;
        }
        const status = (meta.getAttribute('data-status') || '').trim();
        const statusLabel = meta.getAttribute('data-status-label');
        const pill = document.querySelector('.mission-head-title .status-pill');
        if (pill && status) {
            pill.className = 'status-pill status-' + status;
            if (statusLabel != null) {
                pill.textContent = statusLabel;
            }
        }
    });
})();

// ---- Tab switching (deeplink ?tab= + localStorage fallback, WAI-ARIA tabs) -----------------
(function () {
    const tabs = Array.from(document.querySelectorAll('.tab-nav[role="tablist"] > .tab[data-tab]'));
    const panes = Array.from(document.querySelectorAll('.tab-panes > .tab-pane'));
    if (!tabs.length) return;
    const STORAGE_KEY = 'krt.operation.' + (window.operationId || 'new') + '.tab';

    function show(key, push) {
        const tab = tabs.find((t) => t.getAttribute('data-tab') === key) || tabs[0];
        key = tab.getAttribute('data-tab');
        tabs.forEach((t) => {
            const on = t === tab;
            t.classList.toggle('active', on);
            t.setAttribute('aria-selected', on ? 'true' : 'false');
            t.setAttribute('tabindex', on ? '0' : '-1');
        });
        panes.forEach((p) => p.classList.toggle('on', p.id === 'pane-op-' + key));
        try {
            localStorage.setItem(STORAGE_KEY, key);
        } catch (_e) {
            /* ignore */
        }
        if (push && window.history && window.history.replaceState) {
            const url = new URL(window.location.href);
            url.searchParams.set('tab', key);
            window.history.replaceState({ opTab: key }, '', url.toString());
        }
    }

    tabs.forEach((tab) =>
        tab.addEventListener('click', () => show(tab.getAttribute('data-tab'), true)),
    );
    const tabNav = document.querySelector('.mission-head-sticky .tab-nav');
    if (tabNav) {
        tabNav.addEventListener('keydown', (e) => {
            if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft') return;
            const i = tabs.indexOf(document.activeElement);
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

// ---- Delete modal ----
function openDeleteModal(id) {
    const deleteForm = document.getElementById('delete-operation-form');
    deleteForm.action = window.safeSameOriginUrl(
        '/operations/' + id + '/delete',
        deleteForm.action,
    );
    document.getElementById('delete-operation-modal').style.display = 'flex';
}

document.addEventListener('DOMContentLoaded', function () {
    // Embedded missions pager -> in-place swap (REQ-FE-002).
    if (window.krtFetch) {
        window.krtFetch.bindSwap({
            container: '#op-missions-results',
            fragmentValue: 'missions',
            history: true,
        });
    }
});

if (window.krtEvents && typeof window.krtEvents.on === 'function') {
    window.krtEvents.on('click', 'operation-open-delete', function (el) {
        openDeleteModal(el.getAttribute('data-id'));
    });
}

// ---- Markdown description editor: Bearbeiten / Vorschau + formatting toolbar --------------
(function () {
    const editor = document.getElementById('op-md-editor');
    if (!editor) return;
    const input = document.getElementById('op-desc');
    const preview = document.getElementById('op-md-preview');
    const toolbar = document.getElementById('op-md-toolbar');
    const viewTabs = Array.prototype.slice.call(editor.querySelectorAll('[data-md-view]'));
    // The editing chrome (toolbar + Bearbeiten/Vorschau tabs) only renders for editors; a
    // read-only viewer just sees the disabled textarea, so there is nothing to wire up.
    if (!input || !preview || !toolbar || !viewTabs.length) return;

    function showView(view) {
        const edit = view !== 'preview';
        input.style.display = edit ? '' : 'none';
        toolbar.style.display = edit ? '' : 'none';
        preview.style.display = edit ? 'none' : '';
        viewTabs.forEach((t) => {
            const on = t.getAttribute('data-md-view') === view;
            t.classList.toggle('on', on);
            t.classList.toggle('active', on);
        });
        if (!edit) {
            renderPreview();
        }
    }

    function renderPreview() {
        preview.textContent = '';
        const headers = window.krtCsrf
            ? window.krtCsrf.headers()
            : { 'Content-Type': 'application/json', 'X-Requested-With': 'XMLHttpRequest' };
        fetch('/operations/markdown-preview', {
            method: 'POST',
            headers: headers,
            body: JSON.stringify({ markdown: input.value }),
        })
            .then((r) => (r.ok ? r.text() : Promise.reject(r.status)))
            .then((html) => {
                preview.innerHTML = html;
            })
            .catch(() => {
                preview.textContent = '';
            });
    }

    // selection-aware wrap (bold/italic), line-prefix (heading/list) and link insert.
    function wrapSelection(marker) {
        const s = input.selectionStart,
            e = input.selectionEnd;
        const sel = input.value.slice(s, e) || '';
        input.setRangeText(marker + sel + marker, s, e, 'end');
        input.focus();
    }
    function prefixLines(prefix) {
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
    function insertLink() {
        const s = input.selectionStart,
            e = input.selectionEnd;
        const sel = input.value.slice(s, e) || 'Text';
        input.setRangeText('[' + sel + '](https://)', s, e, 'end');
        input.focus();
    }

    viewTabs.forEach((t) =>
        t.addEventListener('click', () => showView(t.getAttribute('data-md-view'))),
    );
    toolbar.addEventListener('click', function (e) {
        const btn = e.target.closest('button');
        if (!btn) return;
        if (btn.hasAttribute('data-md-wrap')) {
            wrapSelection(btn.getAttribute('data-md-wrap'));
        } else if (btn.hasAttribute('data-md-line')) {
            prefixLines(btn.getAttribute('data-md-line'));
        } else if (btn.hasAttribute('data-md-link')) {
            insertLink();
        }
    });
})();

// #576: save the operation core-edit form (#operation-form) and delete the operation in place
// (no full-page reload). The classic POST->redirect handlers stay the no-JS fallback.

function opsDetailConflict() {
    return {
        title: OPS_DETAIL_MSG.conflictTitle,
        reloadDetailFallback: OPS_DETAIL_MSG.conflictDetail,
        reloadLabel: OPS_DETAIL_MSG.conflictReload,
        dismissLabel: OPS_DETAIL_MSG.conflictDismiss,
        reloadQuestion: OPS_DETAIL_MSG.conflictQuestion,
    };
}

(function () {
    if (!window.krtFetch) return; // no-JS / no-foundation: the classic POST->redirect forms run.

    const form = document.getElementById('operation-form');
    if (form) {
        form.addEventListener('submit', function (event) {
            event.preventDefault();
            const versionInput = form.querySelector('[name="version"]');
            window.krtFetch.write({
                method: 'POST',
                url: form.getAttribute('action'),
                // #1117: serialize on 'operation:core' and read the payload (version included) in a
                // thunk at SEND time. A double-click / rapid re-save then queues instead of firing
                // two concurrent POSTs with the same version — the second re-reads the version the
                // first bumped back into the input (onSuccess below), so it no longer self-409s. The
                // explicit submitter also disables the button while the first save is in flight.
                serialize: 'operation:core',
                submitter: form.querySelector('button[type="submit"]'),
                payload: function () {
                    return {
                        name: form.querySelector('[name="name"]').value,
                        description: form.querySelector('[name="description"]').value,
                        status: form.querySelector('[name="status"]').value,
                        version: versionInput ? Number(versionInput.value) : null,
                        owningOrgUnitId: null,
                    };
                },
                successMessage: OPS_DETAIL_MSG.updateSuccess,
                errorMessage: OPS_DETAIL_MSG.updateError,
                conflict: opsDetailConflict(),
                onSuccess: function (body) {
                    if (body && body.version != null && versionInput) {
                        versionInput.value = body.version;
                    }
                    // Re-render the overview section in place (status pill, description, at-a-glance)
                    // and broadcast 'overview' to peers; the krt:swapped header patcher repaints the
                    // sticky title + status pill from #operation-head-meta. Replaces the former
                    // title-only patch so a core save reflects everywhere, locally and for peers
                    // (REQ-FE-015).
                    if (window.opRefreshSection) {
                        window.opRefreshSection('overview');
                    }
                },
            });
        });
    }

    const deleteForm = document.getElementById('delete-operation-form');
    if (deleteForm) {
        deleteForm.addEventListener('submit', function (event) {
            event.preventDefault();
            // Explicit submitter (bank.js pattern) so a double-click on Löschen cannot fire a second
            // DELETE that 404s and toasts "Fehler beim Löschen" over the succeeding navigation
            // (#1133). The synchronous resolveSubmitter guard in write() would auto-capture it too,
            // but threading it explicitly keeps the guard robust if the auto-capture ever misses.
            window.krtFetch.write({
                method: 'POST',
                url: deleteForm.action,
                submitter: deleteForm.querySelector('button[type="submit"]'),
                successMessage: OPS_DETAIL_MSG.deleteSuccess,
                errorMessage: OPS_DETAIL_MSG.deleteError,
                conflict: opsDetailConflict(),
                onSuccess: function () {
                    window.location.assign('/operations');
                },
            });
        });
    }
})();

// ---- Payout paid-out toggle (AJAX) ---------------------------------

function payoutPaidUrl() {
    return window.operationId ? '/operations/' + window.operationId + '/payouts/paid-out' : null;
}

function canUnsetPaidOut() {
    const pane = document.getElementById('pane-op-payout');
    return pane != null && pane.getAttribute('data-can-unset-paid-out') === 'true';
}

function refreshPayoutPaidStatusCell(row, dto) {
    if (!row) return;
    const checkbox = row.querySelector('.payout-paid-checkbox');
    if (!checkbox) return;
    checkbox.checked = !!dto.paidOut;
    if (checkbox.checked && !canUnsetPaidOut()) {
        checkbox.disabled = true;
        checkbox.title = MSG_PAYOUT_PAID_UNSET_LOCKED;
    } else {
        checkbox.disabled = false;
        checkbox.title = '';
    }
    let statusSpan = row.querySelector('.payout-paid-status');
    if (dto.paidOut && dto.paidOutByName) {
        if (!statusSpan) {
            statusSpan = document.createElement('span');
            statusSpan.className = 'payout-paid-status';
            checkbox.parentElement.appendChild(statusSpan);
        }
        statusSpan.textContent = dto.paidOutByName;
        if (dto.paidOutAt) {
            const date = new Date(dto.paidOutAt);
            if (!isNaN(date)) {
                statusSpan.title = date.toLocaleString();
            }
        }
    } else if (statusSpan) {
        statusSpan.remove();
    }
}

async function handlePayoutPaidToggle(checkbox) {
    const url = payoutPaidUrl();
    if (!url) return;
    const participantKey = checkbox.getAttribute('data-participant-id');
    if (!participantKey) return;
    const desired = checkbox.checked;
    const previous = !desired;
    // Disable the checkbox for the round-trip (it is not a submit button, so it cannot ride
    // krtFetch's double-submit guard — the guard also re-enables unconditionally, which would break
    // the "locked once paid" state below). Re-enabled in the finally with the conditional rule.
    checkbox.disabled = true;
    // #1119: route through krtFetch.write (REQ-FE-001) instead of a raw fetch + manual CSRF. This
    // gains the bare-403 refresh-and-retry the raw fetch lacked and the unified problem+json
    // handling, and pairs with the backend's idempotent 409->200 toggle (#1111). No version is
    // shipped — the paid-out flag is a documented idempotent boolean (last-writer-wins).
    try {
        const result = await window.krtFetch.write({
            method: 'POST',
            url: url,
            payload: { participantKey: participantKey, paidOut: desired },
            toast: false,
            onError: function (status) {
                checkbox.checked = previous;
                if (window.showFrontendErrorToast) {
                    window.showFrontendErrorToast(
                        status === 401 || status === 403
                            ? MSG_PAYOUT_PAID_FORBIDDEN
                            : MSG_PAYOUT_PAID_ERROR,
                    );
                }
                return true; // handled: skip krtFetch's default problem+json toast/reload-confirm
            },
            onNetworkError: function () {
                checkbox.checked = previous;
                if (window.showFrontendErrorToast) {
                    window.showFrontendErrorToast(MSG_PAYOUT_PAID_ERROR);
                }
                return true;
            },
        });
        if (result && result.ok && result.body) {
            refreshPayoutPaidStatusCell(checkbox.closest('tr[data-participant-id]'), result.body);
            // Actor keeps its surgical per-row patch; broadcast 'payout' so peers re-fetch the whole
            // payout fragment and see the new paid-out state (REQ-FE-015).
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

// Document-delegated so the checkboxes survive a peer-driven payout fragment swap (their listeners
// would die if bound directly at load, #571/#574). `change` bubbles, so plain delegation suffices.
document.addEventListener('change', function (ev) {
    const checkbox =
        ev.target && ev.target.closest ? ev.target.closest('.payout-paid-checkbox') : null;
    if (checkbox) {
        handlePayoutPaidToggle(checkbox);
    }
});

// ---- Lazy per-mission finance breakdown (#1121) ----
// Each finance-tab <details> starts collapsed with just its mission total; the entry/refinery
// breakdown loads on first expand via GET /operations/{id}/finance/{missionId} and is injected in
// place, so the full page render never materializes every finance entry across every child mission.
(function () {
    const opId = window.operationId;
    if (!opId) return;
    function loadDetail(details) {
        if (!details.open) return;
        const body = details.querySelector('.op-finance-detail-body');
        if (!body || body.getAttribute('data-loaded') !== 'false') return; // loaded or in flight
        const missionId = details.getAttribute('data-op-finance-mission');
        if (!missionId) return;
        body.setAttribute('data-loaded', 'loading');
        const url =
            '/operations/' + encodeURIComponent(opId) + '/finance/' + encodeURIComponent(missionId);
        fetch(url, {
            headers: { 'X-Requested-With': 'XMLHttpRequest' },
            credentials: 'same-origin',
        })
            .then((r) => (r.ok ? r.text() : Promise.reject(r.status)))
            .then((html) => {
                body.innerHTML = html;
                body.setAttribute('data-loaded', 'true');
            })
            .catch(() => {
                // Reset to 'false' so re-opening retries; show the error inline in the meantime.
                body.setAttribute('data-loaded', 'false');
                const msg =
                    typeof OPS_FINANCE_DETAIL_ERROR !== 'undefined' ? OPS_FINANCE_DETAIL_ERROR : '';
                const p = document.createElement('p');
                p.className = 'desc';
                p.style.paddingTop = '8px';
                p.style.color = 'var(--color-danger-text)';
                p.textContent = msg;
                body.textContent = '';
                body.appendChild(p);
            });
    }
    // Document-delegated on the CAPTURE phase: the `toggle` event does not bubble, so a plain
    // bubbling delegation would never fire — capture catches it at the document. Binding at the
    // document (not on each <details>) also means it survives any DOM replacement of the <details>
    // elements — a tab re-render, or the peer-driven finance fragment swap when a child mission's
    // finance changes and the mission page cross-publishes operation 'finance' (#1241, REQ-FE-015).
    document.addEventListener(
        'toggle',
        function (ev) {
            const details = ev.target;
            if (details && details.matches && details.matches('details[data-op-finance-mission]')) {
                loadDetail(details);
            }
        },
        true,
    );
})();

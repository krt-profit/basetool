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
            return window.operationId ? '/operations/' + window.operationId : null;
        },
        broadcast(keys) {
            if (
                window.operationId &&
                window.krtLiveSync &&
                typeof window.krtLiveSync.sendChanged === 'function'
            ) {
                window.krtLiveSync.sendChanged('operation:' + window.operationId, keys);
            }
        },
    });
    window.opRefreshSection = operationSeam.refresh;
    window.opNotifyChanged = operationSeam.notify;

    if (window.operationId && window.krtLiveSync && window.krtLiveSync.createReceiver) {
        window.krtLiveSync.createReceiver({
            topic: 'operation:' + window.operationId,
            sections: OPERATION_SECTIONS,
            refresh(keys) {
                if (window.opRefreshSection) {
                    window.opRefreshSection(keys, { broadcast: false });
                }
            },
            pill: {
                label() {
                    return OPS_DETAIL_MSG.livesyncUpdates;
                },
            },
        });
    }

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
        } catch (_e) {}
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

function openDeleteModal(id) {
    const deleteForm = document.getElementById('delete-operation-form');
    deleteForm.action = window.safeSameOriginUrl(
        '/operations/' + id + '/delete',
        deleteForm.action,
    );
    window.krtModal.open(document.getElementById('delete-operation-modal'));
}

document.addEventListener('DOMContentLoaded', function () {
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

(function () {
    const editor = document.getElementById('op-md-editor');
    if (!editor) return;
    const input = document.getElementById('op-desc');
    const preview = document.getElementById('op-md-preview');
    const toolbar = document.getElementById('op-md-toolbar');
    const viewTabs = Array.prototype.slice.call(editor.querySelectorAll('[data-md-view]'));
    if (!input || !preview || !toolbar || !viewTabs.length) return;

    function showView(view) {
        const edit = view !== 'preview';
        input.style.display = edit ? '' : 'none';
        toolbar.style.display = edit ? '' : 'none';
        preview.classList.toggle('krtm-display-none-5790', edit);
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
        const sel =
            input.value.slice(s, e) ||
            window.krtI18nText(OPS_DETAIL_MSG.linkText, 'OPS_DETAIL_MSG.linkText');
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
    if (!window.krtFetch) return;

    const form = document.getElementById('operation-form');
    if (form) {
        form.addEventListener('submit', function (event) {
            event.preventDefault();
            const versionInput = form.querySelector('[name="version"]');
            window.krtFetch.write({
                method: 'POST',
                url: form.getAttribute('action'),
                serialize: 'operation:core',
                submitter: form.querySelector('button[type="submit"]'),
                payload() {
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
                onSuccess(body) {
                    if (body && body.version != null && versionInput) {
                        versionInput.value = body.version;
                    }
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

document.addEventListener('change', function (ev) {
    const checkbox =
        ev.target && ev.target.closest ? ev.target.closest('.payout-paid-checkbox') : null;
    if (checkbox) {
        handlePayoutPaidToggle(checkbox);
    }
});

(function () {
    const opId = window.operationId;
    if (!opId) return;
    function loadDetail(details) {
        if (!details.open) return;
        const body = details.querySelector('.op-finance-detail-body');
        if (!body || body.getAttribute('data-loaded') !== 'false') return;
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
                window.krtFetch.setTrustedHtml(body, html);
                body.setAttribute('data-loaded', 'true');
            })
            .catch(() => {
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

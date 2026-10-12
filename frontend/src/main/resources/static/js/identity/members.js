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

/* global MSG_DELETE_TITLE, MSG_DELETE_CONFIRM, MSG_DELETE_LABEL, MSG_CANCEL_LABEL, MSG_DELETE_SUCCESS, MSG_DELETE_ERROR, MSG_SYNC_SUCCESS, MSG_SYNC_ERROR, MSG_CONSOLIDATE_SUCCESS, MSG_CONSOLIDATE_ERROR, MSG_CONSOLIDATE_NO_TARGET */

import { krtAutocomplete } from '/js/kernel/autocomplete.js';
import {
    showFrontendErrorToast,
    showFrontendSuccessToast,
    showKrtConfirm,
} from '/js/kernel/toast.js';
import { krtModal } from '/js/kernel/krt-modal.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtEvents } from '/js/kernel/event-delegation.js';
import { krtLiveSync } from '/js/kernel/krt-live-sync.js';

const MEMBERS_SECTIONS = {
    roster: { container: '#members-results', fragmentValue: 'results' },
};

/** Re-renders the roster fragment in place, or reloads when the transport is missing. */
function refreshMembersResults() {
    const container = document.getElementById('members-results');
    if (!container || !krtFetch) {
        location.reload();
        return;
    }
    krtFetch.swap({
        url: window.location.pathname + window.location.search,
        container,
    });
}

window.krtRefreshMembersResults = refreshMembersResults;

/** Wires the live roster filter and the name autocomplete of the search field. */
function bindFilter() {
    const searchInput = /** @type {HTMLInputElement | null} */ (
        document.getElementById('member-search')
    );
    const filterForm = /** @type {HTMLFormElement | null} */ (
        document.getElementById('members-filter-form')
    );
    const resultsContainer = document.getElementById('members-results');
    /** @type {number | undefined} */
    let membersFilterTimer;

    /** Swaps the roster for the current filter values, or submits the form without transport. */
    function applyMembersFilter() {
        if (!filterForm || !resultsContainer || !krtFetch) {
            if (filterForm) filterForm.submit();
            return;
        }
        const data = new FormData(filterForm);
        const params = new URLSearchParams();
        for (const [key, value] of data.entries()) {
            if (value !== '') params.append(key, String(value));
        }
        const query = params.toString();
        krtFetch.swap({
            url: `/members${query ? `?${query}` : ''}`,
            container: resultsContainer,
            history: true,
        });
    }

    if (filterForm) {
        filterForm.addEventListener('submit', (event) => {
            event.preventDefault();
            clearTimeout(membersFilterTimer);
            applyMembersFilter();
        });
    }

    if (searchInput) {
        const input = searchInput;
        input.addEventListener('input', () => {
            clearTimeout(membersFilterTimer);
            membersFilterTimer = setTimeout(applyMembersFilter, 300);
        });
        krtAutocomplete(
            input,
            async (/** @type {string} */ query) => {
                if (query.length < 2) return [];
                try {
                    const data = await krtFetch.getJson(
                        `/members/api/search?query=${encodeURIComponent(query)}`,
                    );
                    return [
                        ...new Set(
                            (data || [])
                                .map((/** @type {any} */ u) => u.effectiveName)
                                .filter(Boolean),
                        ),
                    ].slice(0, 10);
                } catch (_err) {
                    return [];
                }
            },
            {
                onSelect(/** @type {string} */ item) {
                    input.value = item;
                    clearTimeout(membersFilterTimer);
                    applyMembersFilter();
                },
            },
        );
    }
}

/**
 * Asks for confirmation and deletes one member.
 *
 * @param {string | null} userId the member's id
 */
async function deleteUser(userId) {
    const confirmed = showKrtConfirm
        ? await showKrtConfirm(
              MSG_DELETE_TITLE,
              MSG_DELETE_CONFIRM,
              MSG_DELETE_LABEL,
              MSG_CANCEL_LABEL,
          )
        : false;
    if (!confirmed) return;
    krtFetch.write({
        method: 'DELETE',
        url: `/members/${encodeURIComponent(userId || '')}`,
        successMessage: MSG_DELETE_SUCCESS,
        errorMessage: MSG_DELETE_ERROR,
        onSuccess() {
            refreshMembersResults();
        },
    });
}

/** Triggers the member synchronisation and refreshes the roster. */
function syncMembersNow() {
    krtFetch.write({
        method: 'POST',
        url: '/members/sync',
        successMessage: MSG_SYNC_SUCCESS,
        errorMessage: MSG_SYNC_ERROR,
        onSuccess() {
            refreshMembersResults();
        },
    });
}

/** @type {{ id: string | null, version: string | null } | null} */
let consolidateSource = null;

/**
 * Opens the consolidation dialog for the member the trigger names.
 *
 * @param {HTMLElement} el the trigger carrying `data-user-id` and `data-user-version`
 */
function openConsolidate(el) {
    const modal = document.getElementById('consolidate-modal');
    if (!modal || !krtModal) return;
    consolidateSource = {
        id: el.getAttribute('data-user-id'),
        version: el.getAttribute('data-user-version'),
    };
    const picker = /** @type {(HTMLInputElement & { krtComboboxReset?: () => void }) | null} */ (
        document.getElementById('consolidate-target')
    );
    if (picker) {
        picker.value = '';
        if (picker.krtComboboxReset) picker.krtComboboxReset();
    }
    krtModal.open(modal);
}

/** Forgets the source member and closes the consolidation dialog. */
function closeConsolidate() {
    consolidateSource = null;
    if (krtModal) krtModal.close('consolidate-modal');
}

/** Consolidates the source member into the picked target member. */
function confirmConsolidate() {
    if (!consolidateSource) return;
    const picker = /** @type {HTMLInputElement | null} */ (
        document.getElementById('consolidate-target')
    );
    const targetUserId = picker && picker.value ? picker.value : '';
    if (!targetUserId) {
        if (showFrontendErrorToast) {
            showFrontendErrorToast(MSG_CONSOLIDATE_NO_TARGET);
        }
        return;
    }
    const source = consolidateSource;
    krtFetch.write({
        method: 'POST',
        url: `/members/${encodeURIComponent(source.id || '')}/consolidate`,
        payload: {
            targetUserId,
            version:
                source.version == null || source.version === '' ? null : Number(source.version),
        },
        toast: false,
        errorMessage: MSG_CONSOLIDATE_ERROR,
        onSuccess() {
            if (showFrontendSuccessToast) {
                showFrontendSuccessToast(MSG_CONSOLIDATE_SUCCESS);
            }
            closeConsolidate();
            refreshMembersResults();
        },
    });
}

document.addEventListener('DOMContentLoaded', bindFilter);

document.addEventListener('click', (event) => {
    const target = event.target instanceof Element ? event.target : null;
    if (!target) return;
    if (target.closest('#consolidate-cancel')) {
        closeConsolidate();
    } else if (target.closest('#consolidate-confirm')) {
        confirmConsolidate();
    }
});

krtEvents.on('click', 'members-delete-user', (el) => {
    deleteUser(el.getAttribute('data-user-id'));
});
krtEvents.on('click', 'members-sync', () => {
    syncMembersNow();
});
krtEvents.on('click', 'members-consolidate', (el) => {
    openConsolidate(el);
});

(function () {
    if (!krtLiveSync || typeof krtLiveSync.createReceiver !== 'function') {
        return;
    }
    if (!document.getElementById('members-results')) {
        return;
    }

    krtLiveSync.createReceiver({
        topic: 'members',
        sections: MEMBERS_SECTIONS,
        coalesceMs: 1500,
        refresh() {
            if (typeof window.krtRefreshMembersResults === 'function') {
                window.krtRefreshMembersResults();
            }
        },
    });
})();

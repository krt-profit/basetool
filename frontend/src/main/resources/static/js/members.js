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

/* global MSG_DELETE_TITLE, MSG_DELETE_CONFIRM, MSG_DELETE_LABEL, MSG_CANCEL_LABEL, MSG_DELETE_SUCCESS, MSG_DELETE_ERROR, MSG_SYNC_SUCCESS, MSG_SYNC_ERROR, MSG_CONSOLIDATE_SUCCESS, MSG_CONSOLIDATE_ERROR, MSG_CONSOLIDATE_NO_TARGET, krtAutocomplete */

const MEMBERS_SECTIONS = {
    roster: { container: '#members-results', fragmentValue: 'results' },
};

(function () {
    'use strict';

    /** Re-renders the roster fragment in place, or reloads when the transport is missing. */
    function refreshMembersResults() {
        const container = document.getElementById('members-results');
        if (!container || !window.krtFetch) {
            location.reload();
            return;
        }
        window.krtFetch.swap({
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
            if (!filterForm || !resultsContainer || !window.krtFetch) {
                if (filterForm) filterForm.submit();
                return;
            }
            const data = new FormData(filterForm);
            const params = new URLSearchParams();
            for (const [key, value] of data.entries()) {
                if (value !== '') params.append(key, String(value));
            }
            const query = params.toString();
            window.krtFetch.swap({
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
                        const data = await window.krtFetch.getJson(
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
        const confirmed = window.showKrtConfirm
            ? await window.showKrtConfirm(
                  MSG_DELETE_TITLE,
                  MSG_DELETE_CONFIRM,
                  MSG_DELETE_LABEL,
                  MSG_CANCEL_LABEL,
              )
            : false;
        if (!confirmed) return;
        window.krtFetch.write({
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
        window.krtFetch.write({
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
        if (!modal || !window.krtModal) return;
        consolidateSource = {
            id: el.getAttribute('data-user-id'),
            version: el.getAttribute('data-user-version'),
        };
        const picker =
            /** @type {(HTMLInputElement & { krtComboboxReset?: () => void }) | null} */ (
                document.getElementById('consolidate-target')
            );
        if (picker) {
            picker.value = '';
            if (picker.krtComboboxReset) picker.krtComboboxReset();
        }
        window.krtModal.open(modal);
    }

    /** Forgets the source member and closes the consolidation dialog. */
    function closeConsolidate() {
        consolidateSource = null;
        if (window.krtModal) window.krtModal.close('consolidate-modal');
    }

    /** Consolidates the source member into the picked target member. */
    function confirmConsolidate() {
        if (!consolidateSource) return;
        const picker = /** @type {HTMLInputElement | null} */ (
            document.getElementById('consolidate-target')
        );
        const targetUserId = picker && picker.value ? picker.value : '';
        if (!targetUserId) {
            if (window.showFrontendErrorToast) {
                window.showFrontendErrorToast(MSG_CONSOLIDATE_NO_TARGET);
            }
            return;
        }
        const source = consolidateSource;
        window.krtFetch.write({
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
                if (window.showFrontendSuccessToast) {
                    window.showFrontendSuccessToast(MSG_CONSOLIDATE_SUCCESS);
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

    window.krtEvents.on('click', 'members-delete-user', (el) => {
        deleteUser(el.getAttribute('data-user-id'));
    });
    window.krtEvents.on('click', 'members-sync', () => {
        syncMembersNow();
    });
    window.krtEvents.on('click', 'members-consolidate', (el) => {
        openConsolidate(el);
    });
})();

(function () {
    'use strict';

    if (!window.krtLiveSync || typeof window.krtLiveSync.createReceiver !== 'function') {
        return;
    }
    if (!document.getElementById('members-results')) {
        return;
    }

    window.krtLiveSync.createReceiver({
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

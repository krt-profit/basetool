// @ts-check
/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */

(function () {
    'use strict';

    /** @type {Record<string, any>} */
    const i18n = /** @type {any} */ (window).leitungI18n || {};

    const SECTIONS_ID = 'leitung-sections';
    const NARROW = '(width <= 1024px)';

    const state = {
        /** @type {string} */
        unit: '',
        /** @type {string} */
        tab: 'members',
        /** @type {string} */
        query: '',
        /** @type {string | null} */
        focusSelector: null,
    };

    /**
     * The master-detail root rendered inside the swapped sections.
     *
     * @returns {HTMLElement | null} the root, or null when the caller manages no unit
     */
    function root() {
        return document.getElementById('leitung-md');
    }

    /**
     * Re-renders the unit tree and the detail panes in place for the current selection.
     *
     * @returns {Promise<boolean> | undefined} the swap, or nothing when it fell back to a reload
     */
    function reswap() {
        if (!window.krtFetch || !window.krtFetch.swap) {
            window.location.reload();
            return undefined;
        }
        const params = new URLSearchParams();
        if (state.unit) {
            params.set('unit', state.unit);
        }
        if (state.tab === 'groups') {
            params.set('tab', 'groups');
        }
        params.set('fragment', 'leitungSections');
        return window.krtFetch.swap({
            url: `/organisation/leitung?${params.toString()}`,
            container: `#${SECTIONS_ID}`,
            fragmentValue: 'leitungSections',
            history: false,
        });
    }

    /**
     * Sends one Leitung write through the shared pipeline and re-renders on success, and also on a
     * failure, so an immediately-saving control never shows a value the server refused.
     *
     * @param {{ url: string | (() => string), method: string, payload?: unknown | (() => unknown),
     *     success: string }} opts the request
     */
    function write(opts) {
        if (!window.krtFetch || !window.krtFetch.write) {
            window.location.reload();
            return;
        }
        window.krtFetch.write({
            url: opts.url,
            method: opts.method,
            payload: opts.payload,
            successMessage: opts.success,
            errorMessage: i18n.error,
            conflict: i18n.conflict,
            serialize: 'leitung',
            onSuccess: reswap,
            onError() {
                reswap();
                return false;
            },
        });
    }

    /**
     * Asks for confirmation, then runs the action; runs it directly when no dialog is available.
     *
     * @param {string | null} title the dialog title
     * @param {string} message the question
     * @param {() => void} run the confirmed action
     */
    function confirmThen(title, message, run) {
        if (typeof window.showKrtConfirm !== 'function') {
            run();
            return;
        }
        window.showKrtConfirm(title, message, i18n.confirm, i18n.cancel).then((ok) => {
            if (ok) {
                run();
            }
        });
    }

    /**
     * The tree rows that the search leaves visible, in document order.
     *
     * @returns {HTMLElement[]} the visible rows
     */
    function visibleRows() {
        const md = root();
        if (!md) {
            return [];
        }
        return /** @type {HTMLElement[]} */ (
            Array.prototype.slice.call(md.querySelectorAll('.leitung-tree__row'))
        ).filter((r) => {
            return !r.hidden;
        });
    }

    /** Writes the selected unit and tab into the address bar for the deep link. */
    function syncUrl() {
        const url = new URL(window.location.href);
        if (state.unit) {
            url.searchParams.set('unit', state.unit);
        }
        if (state.tab === 'groups') {
            url.searchParams.set('tab', 'groups');
        } else {
            url.searchParams.delete('tab');
        }
        history.replaceState(history.state, '', url);
    }

    /**
     * Shows one tab of a unit pane; a key the pane does not offer falls back to its members.
     *
     * @param {Element} pane the unit pane
     * @param {string} key the tab key
     * @returns {string} the key that is shown
     */
    function showTab(pane, key) {
        const tabs = pane.querySelectorAll('[data-lt-tab]');
        let shown = 'members';
        for (let i = 0; i < tabs.length; i++) {
            if (tabs[i].getAttribute('data-lt-tab') === key) {
                shown = key;
            }
        }
        for (let i = 0; i < tabs.length; i++) {
            const on = tabs[i].getAttribute('data-lt-tab') === shown;
            tabs[i].classList.toggle('active', on);
            tabs[i].setAttribute('aria-selected', on ? 'true' : 'false');
            /** @type {HTMLElement} */ (tabs[i]).tabIndex = on ? 0 : -1;
        }
        const panels = pane.querySelectorAll('[data-lt-panel]');
        for (let i = 0; i < panels.length; i++) {
            /** @type {HTMLElement} */ (panels[i]).hidden =
                panels[i].getAttribute('data-lt-panel') !== shown;
        }
        return shown;
    }

    /**
     * Shows the pane of one unit, marks its tree row and remembers the selection.
     *
     * @param {string} unitId the unit to show
     * @param {{ focus?: boolean, scroll?: boolean, keepTab?: boolean }} [opts] focus the row,
     *     scroll the pane into view, keep the current tab
     */
    function select(unitId, opts) {
        const md = root();
        if (!md) {
            return;
        }
        const options = opts || {};
        const pane = document.getElementById(`lt-pane-${unitId}`);
        if (!pane) {
            return;
        }
        if (!options.keepTab && state.unit !== unitId) {
            state.tab = 'members';
        }
        state.unit = unitId;
        const rows = md.querySelectorAll('.leitung-tree__row');
        for (let i = 0; i < rows.length; i++) {
            const row = /** @type {HTMLElement} */ (rows[i]);
            const on = row.getAttribute('data-unit-id') === unitId;
            row.classList.toggle('is-active', on);
            if (on) {
                row.setAttribute('aria-current', 'true');
                if (options.focus) {
                    row.focus();
                }
            } else {
                row.removeAttribute('aria-current');
            }
        }
        const panes = md.querySelectorAll('.leitung-pane');
        for (let i = 0; i < panes.length; i++) {
            /** @type {HTMLElement} */ (panes[i]).hidden = panes[i] !== pane;
        }
        state.tab = showTab(pane, state.tab);
        syncUrl();
        if (options.scroll && window.matchMedia(NARROW).matches) {
            pane.scrollIntoView({ block: 'start' });
        }
    }

    /** Hides the tree rows and groups the search does not match. */
    function applySearch() {
        const md = root();
        if (!md) {
            return;
        }
        const q = state.query.trim().toLowerCase();
        let any = false;
        const groups = md.querySelectorAll('.leitung-tree__group');
        for (let g = 0; g < groups.length; g++) {
            const rows = groups[g].querySelectorAll('.leitung-tree__row');
            let groupHit = false;
            for (let i = 0; i < rows.length; i++) {
                const row = /** @type {HTMLElement} */ (rows[i]);
                const hit = q === '' || (row.getAttribute('data-search') || '').indexOf(q) !== -1;
                row.hidden = !hit;
                groupHit ||= hit;
            }
            /** @type {HTMLElement} */ (groups[g]).hidden = !groupHit;
            any ||= groupHit;
        }
        const none = document.getElementById('leitung-tree-nomatch');
        if (none) {
            none.hidden = any;
        }
    }

    /**
     * Picks up the rendered state after the first load or a fragment swap: the selection, the
     * search and the control that had focus.
     */
    function init() {
        const md = root();
        if (!md) {
            return;
        }
        const rendered = md.getAttribute('data-selected-unit') || '';
        const unit =
            state.unit && document.getElementById(`lt-pane-${state.unit}`) ? state.unit : rendered;
        if (!state.unit) {
            state.tab = md.getAttribute('data-selected-tab') || 'members';
        }
        if (unit) {
            select(unit, { keepTab: true });
        }
        const search = /** @type {HTMLInputElement | null} */ (
            document.getElementById('leitung-tree-search')
        );
        if (search && search.value !== state.query) {
            search.value = state.query;
        }
        applySearch();
        if (state.focusSelector) {
            const target = /** @type {HTMLElement | null} */ (
                document.querySelector(state.focusSelector)
            );
            state.focusSelector = null;
            if (target) {
                target.focus();
            }
        }
    }

    /**
     * The roster row of one member in one unit as it is rendered now.
     *
     * @param {string} unitId the unit
     * @param {string} userId the member
     * @returns {Element | null} the row, or null when it is gone
     */
    function currentRow(unitId, userId) {
        return document.querySelector(
            `#lt-pane-${CSS.escape(unitId)} tr[data-user-id="${CSS.escape(userId)}"]`,
        );
    }

    /**
     * @param {string} rank the rank
     * @returns {boolean} whether the rank belongs to a Kommandogruppe
     */
    function needsGroup(rank) {
        return rank === 'KOMMANDOLEITER' || rank === 'STELLV_KOMMANDOLEITER' || rank === 'ENSIGN';
    }

    /**
     * Saves a member's Staffel rank and Kommandogruppe as soon as either select changes; the
     * version is read when the write runs, so queued changes do not collide.
     *
     * @param {Element} row the member row
     * @param {HTMLSelectElement} changed the select that changed
     */
    function saveRank(row, changed) {
        const unitId = row.getAttribute('data-unit-id') || '';
        const userId = row.getAttribute('data-user-id') || '';
        const rankSelect = /** @type {HTMLSelectElement | null} */ (
            row.querySelector('.leitung-rank-select')
        );
        const groupSelect = /** @type {HTMLSelectElement | null} */ (
            row.querySelector('.leitung-group-select')
        );
        if (!rankSelect) {
            return;
        }
        const rank = rankSelect.value;
        const groupId = groupSelect ? groupSelect.value : '';
        if (groupSelect) {
            groupSelect.disabled = !needsGroup(rank);
        }
        state.focusSelector = `#lt-pane-${CSS.escape(unitId)} tr[data-user-id="${CSS.escape(
            userId,
        )}"] .${changed === groupSelect ? 'leitung-group-select' : 'leitung-rank-select'}`;
        const base = `/organisation/leitung/squadrons/${unitId}/ranks/${userId}/ajax`;
        /** @returns {string} the version the page shows now */
        const version = function () {
            const fresh = currentRow(unitId, userId) || row;
            return fresh.getAttribute('data-version') || '0';
        };
        if (rank === 'MEMBER') {
            write({
                url() {
                    return `${base}?version=${encodeURIComponent(version())}`;
                },
                method: 'DELETE',
                success: i18n.deleted,
            });
            return;
        }
        write({
            url: base,
            method: 'PUT',
            payload() {
                /** @type {Record<string, unknown>} */
                const payload = { role: rank, version: Number(version()) };
                if (needsGroup(rank) && groupId) {
                    payload.kommandoGroupId = groupId;
                }
                return payload;
            },
            success: i18n.saved,
        });
    }

    /**
     * Clears a member's Staffel rank back to plain member after confirmation.
     *
     * @param {HTMLElement} item the menu item
     */
    function clearRank(item) {
        const unitId = item.getAttribute('data-unit-id') || '';
        const userId = item.getAttribute('data-user-id') || '';
        confirmThen(item.getAttribute('data-user-name'), i18n.confirmClearRank, () => {
            write({
                url() {
                    const row = currentRow(unitId, userId);
                    const version = row ? row.getAttribute('data-version') || '0' : '0';
                    return `/organisation/leitung/squadrons/${unitId}/ranks/${
                        userId
                    }/ajax?version=${encodeURIComponent(version)}`;
                },
                method: 'DELETE',
                success: i18n.deleted,
            });
        });
    }

    /**
     * Creates a Kommandogruppe from the add row of a Staffel.
     *
     * @param {HTMLElement} btn the add button
     */
    function createGroup(btn) {
        const unitId = btn.getAttribute('data-unit-id');
        const add = btn.closest('.leitung-group-add');
        const input = /** @type {HTMLInputElement | null} */ (
            add ? add.querySelector('.leitung-new-group-name') : null
        );
        const name = input ? input.value.trim() : '';
        if (!unitId || !name) {
            return;
        }
        write({
            url: `/organisation/leitung/squadrons/${unitId}/kommando-groups/ajax`,
            method: 'POST',
            payload: { name },
            success: i18n.saved,
        });
    }

    /**
     * Renames a Kommandogruppe when its name field changed to a non-empty value.
     *
     * @param {HTMLInputElement} input the name field
     */
    function renameGroup(input) {
        const row = input.closest('.leitung-group');
        if (!row) {
            return;
        }
        const name = input.value.trim();
        const original = input.getAttribute('data-original') || '';
        if (!name) {
            input.value = original;
            return;
        }
        if (name === original) {
            return;
        }
        write({
            url: `/organisation/leitung/squadrons/${row.getAttribute(
                'data-unit-id',
            )}/kommando-groups/${row.getAttribute('data-group-id')}/ajax`,
            method: 'PUT',
            payload: {
                name,
                sortIndex: Number(row.getAttribute('data-group-sort')),
                version: Number(row.getAttribute('data-group-version')),
            },
            success: i18n.saved,
        });
    }

    /**
     * Deletes a Kommandogruppe after confirmation.
     *
     * @param {HTMLElement} btn the delete button
     */
    function deleteGroup(btn) {
        const row = btn.closest('.leitung-group');
        if (!row) {
            return;
        }
        confirmThen(btn.getAttribute('data-group-name'), i18n.confirmDeleteGroup, () => {
            write({
                url: `/organisation/leitung/squadrons/${row.getAttribute(
                    'data-unit-id',
                )}/kommando-groups/${row.getAttribute('data-group-id')}/ajax`,
                method: 'DELETE',
                success: i18n.deleted,
            });
        });
    }

    /**
     * Removes an OL or Bereich member after confirmation.
     *
     * @param {HTMLElement} item the menu item
     * @param {string} segment the URL segment of the unit kind
     */
    function removeMember(item, segment) {
        confirmThen(item.getAttribute('data-user-name'), i18n.confirmRemove, () => {
            write({
                url: `/organisation/leitung/${segment}/${item.getAttribute(
                    'data-unit-id',
                )}/members/${item.getAttribute('data-user-id')}/ajax`,
                method: 'DELETE',
                success: i18n.deleted,
            });
        });
    }

    /**
     * Designates the member as Grand Admiral after confirmation.
     *
     * @param {HTMLElement} item the menu item
     */
    function setGrandAdmiral(item) {
        confirmThen(item.getAttribute('data-user-name'), i18n.confirmSetGrandAdmiral, () => {
            write({
                url: `/organisation/leitung/organisationsleitung/${item.getAttribute(
                    'data-unit-id',
                )}/grand-admiral/ajax`,
                method: 'PUT',
                payload: { userId: item.getAttribute('data-user-id') },
                success: i18n.saved,
            });
        });
    }

    /**
     * Vacates the Grand Admiral post after confirmation.
     *
     * @param {HTMLElement} item the menu item
     */
    function removeGrandAdmiral(item) {
        confirmThen(item.getAttribute('data-user-name'), i18n.confirmRemoveGrandAdmiral, () => {
            write({
                url: `/organisation/leitung/organisationsleitung/${item.getAttribute(
                    'data-unit-id',
                )}/grand-admiral/ajax`,
                method: 'DELETE',
                success: i18n.saved,
            });
        });
    }

    /**
     * Runs the action a control names.
     *
     * @param {string | null} action the `data-leitung-action` value
     * @param {HTMLElement} el the control
     */
    function handleAction(action, el) {
        if (action === 'create-group') {
            createGroup(el);
        } else if (action === 'delete-group') {
            deleteGroup(el);
        } else if (action === 'clear-rank') {
            clearRank(el);
        } else if (action === 'remove-bereich-role') {
            removeMember(el, 'bereiche');
        } else if (action === 'remove-ol-member') {
            removeMember(el, 'organisationsleitung');
        } else if (action === 'set-grand-admiral') {
            setGrandAdmiral(el);
        } else if (action === 'remove-grand-admiral') {
            removeGrandAdmiral(el);
        }
    }

    const modal = document.getElementById('leitung-modal');
    const modalUnit = document.getElementById('leitung-modal-unit');
    const modalRoleGroup = document.getElementById('leitung-modal-role-group');
    const modalRole = /** @type {HTMLSelectElement | null} */ (
        document.getElementById('leitung-modal-role')
    );
    /** @type {{ action: string, unitId: string } | null} */
    let modalContext = null;

    /**
     * @returns {HTMLSelectElement | null} the user picker of the dialog
     */
    function modalUser() {
        return /** @type {HTMLSelectElement | null} */ (
            document.getElementById('leitung-modal-user')
        );
    }

    /**
     * Appends one option to a select.
     *
     * @param {HTMLSelectElement} select the select
     * @param {string} value the option value
     * @param {string} label the option label
     */
    function addOption(select, value, label) {
        const opt = document.createElement('option');
        opt.value = value;
        opt.textContent = label;
        select.appendChild(opt);
    }

    /**
     * Opens the add-member dialog for the unit the trigger names.
     *
     * @param {HTMLElement} trigger the pane's primary action
     */
    function openModal(trigger) {
        if (!modal) {
            return;
        }
        const action = trigger.getAttribute('data-leitung-open') || '';
        modalContext = { action, unitId: trigger.getAttribute('data-unit-id') || '' };
        if (modalUnit) {
            modalUnit.textContent = trigger.getAttribute('data-unit-name') || '';
        }
        const user = modalUser();
        if (user) {
            if (user.krtCombobox) {
                user.krtCombobox.setValue('');
            } else {
                user.value = '';
            }
        }
        if (modalRoleGroup && modalRole) {
            modalRoleGroup.hidden = action !== 'add-bereich';
            modalRole.replaceChildren();
            if (action === 'add-bereich') {
                if (trigger.getAttribute('data-can-lead') === 'true') {
                    addOption(modalRole, 'LEITER', i18n.rankLeiter);
                }
                if (trigger.getAttribute('data-can-roster') === 'true') {
                    addOption(modalRole, 'KOORDINATOR', i18n.rankKoordinator);
                    addOption(modalRole, 'OPERATOR', i18n.rankOperator);
                }
            }
        }
        window.krtModal.open(modal);
        if (user) {
            const box = user.closest('.krt-combobox');
            const focusTarget = /** @type {HTMLElement | null} */ (
                box ? box.querySelector('.krt-combobox__input') : user
            );
            if (focusTarget) {
                focusTarget.focus();
            }
        }
    }

    /** Closes the add-member dialog. */
    function closeModal() {
        if (modal) {
            window.krtModal.close(modal);
        }
        modalContext = null;
    }

    /** Submits the add-member dialog to the endpoint of its unit kind. */
    function submitModal() {
        const context = modalContext;
        const user = modalUser();
        const userId = user ? user.value : '';
        if (!context || !userId) {
            return;
        }
        if (context.action === 'add-ol') {
            write({
                url: `/organisation/leitung/organisationsleitung/${context.unitId}/members/ajax`,
                method: 'POST',
                payload: { userId },
                success: i18n.saved,
            });
        } else if (context.action === 'add-bereich') {
            const role = modalRole ? modalRole.value : '';
            if (!role) {
                return;
            }
            write({
                url: `/organisation/leitung/bereiche/${context.unitId}/members/ajax`,
                method: 'POST',
                payload: { userId, role },
                success: i18n.saved,
            });
        } else if (context.action === 'add-sk') {
            if (!window.krtFetch) {
                return;
            }
            const params = new URLSearchParams();
            params.set('userId', userId);
            window.krtFetch.submitForm({
                url: `/organisation/special-commands/${context.unitId}/members`,
                method: 'POST',
                formData: params,
                successMessage: i18n.saved,
                errorMessage: i18n.error,
                conflict: i18n.conflict,
                serialize: 'sk-roster',
                onSuccess: reswap,
            });
        }
        closeModal();
    }

    document.addEventListener('click', (e) => {
        const target = e.target instanceof Element ? e.target : null;
        if (!target) {
            return;
        }
        const opener = /** @type {HTMLElement | null} */ (target.closest('[data-leitung-open]'));
        if (opener) {
            openModal(opener);
            return;
        }
        if (target.closest('[data-leitung-cancel], .leitung-modal-close')) {
            closeModal();
            return;
        }
        if (target.closest('#leitung-modal-submit')) {
            submitModal();
            return;
        }
        if (modal && target === modal) {
            closeModal();
            return;
        }
        if (!target.closest(`#${SECTIONS_ID}`)) {
            return;
        }
        const row = /** @type {HTMLElement | null} */ (target.closest('.leitung-tree__row'));
        if (row) {
            if (e.ctrlKey || e.metaKey || e.shiftKey || e.button !== 0) {
                return;
            }
            e.preventDefault();
            select(row.getAttribute('data-unit-id') || '', { scroll: true });
            return;
        }
        const tab = target.closest('[data-lt-tab]');
        if (tab) {
            const pane = tab.closest('.leitung-pane');
            if (pane) {
                state.tab = showTab(pane, tab.getAttribute('data-lt-tab') || 'members');
                syncUrl();
            }
            return;
        }
        const actionEl = /** @type {HTMLElement | null} */ (
            target.closest('[data-leitung-action]')
        );
        if (actionEl) {
            handleAction(actionEl.getAttribute('data-leitung-action'), actionEl);
        }
    });

    document.addEventListener('change', (e) => {
        const target = e.target instanceof Element ? e.target : null;
        if (!target || !target.closest(`#${SECTIONS_ID}`)) {
            return;
        }
        if (target.matches('.leitung-rank-select, .leitung-group-select')) {
            const row = target.closest('.leitung-rank-row');
            if (row) {
                saveRank(row, /** @type {HTMLSelectElement} */ (target));
            }
        } else if (target.matches('.leitung-group-name')) {
            renameGroup(/** @type {HTMLInputElement} */ (target));
        }
    });

    document.addEventListener('input', (e) => {
        const target = e.target instanceof HTMLInputElement ? e.target : null;
        if (target && target.id === 'leitung-tree-search') {
            state.query = target.value;
            applySearch();
        }
    });

    document.addEventListener('keydown', (e) => {
        const target = e.target instanceof HTMLElement ? e.target : null;
        if (!target || !target.closest(`#${SECTIONS_ID}`)) {
            return;
        }
        if (target.matches('.leitung-new-group-name') && e.key === 'Enter') {
            e.preventDefault();
            const add = target.closest('.leitung-group-add');
            const btn = /** @type {HTMLElement | null} */ (
                add ? add.querySelector('[data-leitung-action="create-group"]') : null
            );
            if (btn) {
                createGroup(btn);
            }
            return;
        }
        if (target.matches('.leitung-tree__row')) {
            if (
                e.key !== 'ArrowDown' &&
                e.key !== 'ArrowUp' &&
                e.key !== 'Home' &&
                e.key !== 'End'
            ) {
                return;
            }
            const rows = visibleRows();
            if (rows.length === 0) {
                return;
            }
            e.preventDefault();
            let idx = rows.indexOf(target);
            if (e.key === 'ArrowDown') {
                idx = Math.min(rows.length - 1, idx + 1);
            } else if (e.key === 'ArrowUp') {
                idx = Math.max(0, idx - 1);
            } else if (e.key === 'Home') {
                idx = 0;
            } else {
                idx = rows.length - 1;
            }
            select(rows[idx].getAttribute('data-unit-id') || '', { focus: true });
            return;
        }
        if (target.matches('[data-lt-tab]') && (e.key === 'ArrowRight' || e.key === 'ArrowLeft')) {
            const nav = target.closest('[role="tablist"]');
            const pane = target.closest('.leitung-pane');
            if (!nav || !pane) {
                return;
            }
            const tabs = /** @type {HTMLElement[]} */ (
                Array.prototype.slice.call(nav.querySelectorAll('[data-lt-tab]'))
            );
            const i = tabs.indexOf(target);
            const next = tabs[(i + (e.key === 'ArrowRight' ? 1 : tabs.length - 1)) % tabs.length];
            e.preventDefault();
            state.tab = showTab(pane, next.getAttribute('data-lt-tab') || 'members');
            syncUrl();
            next.focus();
        }
    });

    document.addEventListener('krt:swapped', (e) => {
        const container = e.detail ? e.detail.container : null;
        if (container && container.id === SECTIONS_ID) {
            init();
        }
    });

    init();
})();

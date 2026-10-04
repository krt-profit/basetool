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

    const i18n = window.materialboerseI18n || {};
    const gi18n = window.materialgesuchI18n || {};
    if (!window.krtFetch || !document.getElementById('mb-board')) {
        return;
    }

    const SERIALIZE_KEY = 'materialboerse';
    const REQUEST_SERIALIZE_KEY = 'materialgesuch';
    const MATERIALBOARD_TOPIC = 'materialboard';
    const FILTER_PREF_KEY = 'materialboerse_filters';
    const FILTER_PANEL_ID = 'mb-filter-panel';
    const SORT_KEYS = ['qual', 'menge', 'mat', 'neu'];
    const form = document.getElementById('mb-filter-form');
    let selectedId = readSelectedId();
    let selectedRequestId = readSelectedRequestId();
    let searchTimer = null;

    function fmt(template, value) {
        return String(template || '').replace('{0}', value);
    }

    function readSelectedId() {
        const active = document.querySelector('.mb-mrow.is-active[data-offer-id]');
        return active ? active.getAttribute('data-offer-id') : null;
    }

    function readSelectedRequestId() {
        const active = document.querySelector('.mb-mrow.is-active[data-request-id]');
        return active ? active.getAttribute('data-request-id') : null;
    }

    function val(selector) {
        const el = document.querySelector(selector);
        return el ? el.value.trim() : '';
    }

    /**
     * The board view the active tab shows.
     *
     * @returns {string} `requests` for the Gesuche, `offers` for the Angebote
     */
    function activeMode() {
        const tab = document.querySelector('.tab.active[data-mb-mode]');
        return tab && tab.getAttribute('data-mb-mode') === 'requests' ? 'requests' : 'offers';
    }

    /**
     * The scope the segment selects, in the stored and backend spelling.
     *
     * @returns {string} `mein` for the caller's own entries, `alle` for every entry
     */
    function activeTab() {
        const checked = form ? form.querySelector('input[name="scope"]:checked') : null;
        return checked && checked.value === 'mine' ? 'mein' : 'alle';
    }

    /**
     * Selects the scope segment that matches a stored scope.
     *
     * @param {string} tab `mein` or `alle`
     */
    function setScope(tab) {
        if (!form) {
            return;
        }
        const wanted = tab === 'mein' ? 'mine' : 'all';
        form.querySelectorAll('input[name="scope"]').forEach((radio) => {
            radio.checked = radio.value === wanted;
        });
    }

    function defaultBoardFilters() {
        return { minQuality: '', minAmount: '', sort: 'qual', excludeStolen: false };
    }

    function readFilterPref() {
        try {
            const raw = localStorage.getItem(FILTER_PREF_KEY);
            return raw === null ? null : JSON.parse(raw);
        } catch (_e) {
            return null;
        }
    }

    function writeFilterPref(value) {
        try {
            localStorage.setItem(FILTER_PREF_KEY, JSON.stringify(value));
        } catch (_e) {}
    }

    function mergeBoardFilters(target, saved) {
        if (!saved || typeof saved !== 'object') {
            return;
        }
        if (typeof saved.minQuality === 'string') {
            target.minQuality = saved.minQuality;
        }
        if (typeof saved.minAmount === 'string') {
            target.minAmount = saved.minAmount;
        }
        if (SORT_KEYS.indexOf(saved.sort) >= 0) {
            target.sort = saved.sort;
        }
        if (typeof saved.excludeStolen === 'boolean') {
            target.excludeStolen = saved.excludeStolen;
        }
    }

    function normalizeFilterPref(saved) {
        const state = {
            mode: 'offers',
            tab: 'alle',
            offers: defaultBoardFilters(),
            requests: defaultBoardFilters(),
        };
        if (!saved || typeof saved !== 'object') {
            return state;
        }
        if (saved.mode === 'requests') {
            state.mode = 'requests';
        }
        if (saved.tab === 'mein') {
            state.tab = 'mein';
        }
        mergeBoardFilters(state.offers, saved.offers);
        mergeBoardFilters(state.requests, saved.requests);
        return state;
    }

    const rawFilterPref = readFilterPref();
    const filterState = normalizeFilterPref(rawFilterPref);

    /**
     * The „ohne gestohlene" checkbox (REQ-INV-053).
     *
     * @returns {HTMLInputElement | null} the checkbox, or null when the toolbar is missing
     */
    function excludeStolenBox() {
        return /** @type {HTMLInputElement | null} */ (
            document.querySelector('[data-mb-exclude-stolen]')
        );
    }

    /**
     * Whether the „ohne gestohlene" filter applies; it is offered on the Angebote only.
     *
     * @returns {boolean} the filter state
     */
    function excludeStolenChecked() {
        const box = excludeStolenBox();
        return !!box && box.checked && !box.disabled;
    }

    /** Stores the active view, scope and the active view's toolbar values (REQ-UI-017). */
    function persistFilters() {
        const mode = activeMode();
        filterState.mode = mode;
        filterState.tab = activeTab();
        const current = {
            minQuality: val('[data-mb-minquality]'),
            minAmount: val('[data-mb-minamount]'),
            sort: val('[data-mb-sort]') || 'qual',
            excludeStolen: mode === 'requests' ? false : excludeStolenChecked(),
        };
        if (mode === 'requests') {
            filterState.requests = current;
        } else {
            filterState.offers = current;
        }
        writeFilterPref(filterState);
    }

    function setIfDifferent(selector, value) {
        const el = document.querySelector(selector);
        const next = value == null ? '' : String(value);
        if (!el || el.value.trim() === next) {
            return false;
        }
        el.value = next;
        return true;
    }

    /**
     * Writes a view's stored filters into the shared toolbar; the stolen filter is disabled and
     * hidden on the Gesuche.
     *
     * @param {{minQuality: string, minAmount: string, sort: string, excludeStolen: boolean}} saved
     *     the stored filters of the view
     * @param {string} mode the view the toolbar now serves
     * @returns {boolean} true when a control changed
     */
    function writeControls(saved, mode) {
        let changed = setIfDifferent('[data-mb-minquality]', saved.minQuality);
        changed = setIfDifferent('[data-mb-minamount]', saved.minAmount) || changed;
        changed = setIfDifferent('[data-mb-sort]', saved.sort) || changed;
        const requests = mode === 'requests';
        const box = excludeStolenBox();
        if (box) {
            const wanted = !requests && saved.excludeStolen === true;
            if (box.checked !== wanted) {
                box.checked = wanted;
                changed = true;
            }
            box.disabled = requests;
        }
        document.querySelectorAll('[data-mb-offers-only]').forEach((el) => {
            /** @type {HTMLElement} */ (el).hidden = requests;
        });
        return changed;
    }

    /** Re-renders the filter chips and the filter badge after a programmatic change. */
    function refreshFilterUi() {
        if (window.krtFilterChips && form) {
            window.krtFilterChips.refresh(form);
        }
        if (window.krtFilterPanel) {
            window.krtFilterPanel.refresh(FILTER_PANEL_ID);
        }
    }

    function params() {
        const p = new URLSearchParams();
        const requests = activeMode() === 'requests';
        p.set('view', requests ? 'requests' : 'offers');
        p.set('scope', activeTab() === 'mein' ? 'mine' : 'all');
        const qv = val('#mb-search');
        if (qv) {
            p.set('q', qv);
        }
        const minQ = val('[data-mb-minquality]');
        if (minQ) {
            p.set('minQuality', minQ);
        }
        const minA = val('[data-mb-minamount]');
        if (minA) {
            p.set('minAmount', minA);
        }
        const sort = val('[data-mb-sort]');
        if (sort) {
            p.set('sort', sort);
        }
        if (!requests && excludeStolenChecked()) {
            p.set('excludeStolen', 'true');
        }
        const selected = requests ? selectedRequestId : selectedId;
        if (selected) {
            p.set('selected', selected);
        }
        return p;
    }

    function swapList() {
        const p = params();
        p.set('fragment', 'list');
        return window.krtFetch.swap({
            url: `/materialboerse?${p.toString()}`,
            container: activeMode() === 'requests' ? '#mg-listwrap' : '#mb-listwrap',
            fragmentValue: 'list',
            history: false,
        });
    }

    function swapBoard() {
        const p = params();
        p.set('fragment', 'board');
        return window.krtFetch.swap({
            url: `/materialboerse?${p.toString()}`,
            container: '#mb-board',
            fragmentValue: 'board',
            history: false,
        });
    }

    function swapDetail(id) {
        return window.krtFetch.swap({
            url: `/materialboerse?fragment=detail&selected=${encodeURIComponent(id)}`,
            container: '#mb-detail',
            fragmentValue: 'detail',
            history: false,
        });
    }

    function swapRequestDetail(id) {
        return window.krtFetch.swap({
            url: `/materialboerse?view=requests&fragment=detail&selected=${encodeURIComponent(id)}`,
            container: '#mg-detail',
            fragmentValue: 'detail',
            history: false,
        });
    }

    /** Puts the active view and scope into the address bar, so the view can be linked. */
    function syncUrl() {
        const p = new URLSearchParams();
        p.set('view', activeMode());
        p.set('scope', activeTab() === 'mein' ? 'mine' : 'all');
        try {
            window.history.replaceState(
                window.history.state,
                '',
                `${window.location.pathname}?${p.toString()}`,
            );
        } catch (_e) {}
    }

    function applyAgo(root) {
        (root || document).querySelectorAll('[data-mb-ago]').forEach((el) => {
            const ts = el.getAttribute('data-ts');
            if (!ts) {
                return;
            }
            const then = Date.parse(ts);
            if (isNaN(then)) {
                return;
            }
            const hours = Math.floor((Date.now() - then) / 3600000);
            let text;
            if (hours < 1) {
                text = window.krtI18nText(i18n.agoNow, 'materialboerseI18n.agoNow');
            } else if (hours < 24) {
                text = fmt(window.krtI18nText(i18n.agoHours, 'materialboerseI18n.agoHours'), hours);
            } else {
                const days = Math.round(hours / 24);
                text =
                    days === 1
                        ? window.krtI18nText(i18n.agoDayOne, 'materialboerseI18n.agoDayOne')
                        : fmt(window.krtI18nText(i18n.agoDays, 'materialboerseI18n.agoDays'), days);
            }
            el.textContent = text;
        });
    }

    /**
     * Marks a view's tab active and shows that view's create menu.
     *
     * @param {string} mode `offers` or `requests`
     */
    function setActiveMode(mode) {
        document.querySelectorAll('.tab[data-mb-mode]').forEach((btn) => {
            const on = btn.getAttribute('data-mb-mode') === mode;
            btn.classList.toggle('active', on);
            btn.setAttribute('aria-selected', on ? 'true' : 'false');
        });
        if (window.krtOverflowMenu) {
            window.krtOverflowMenu.closeAll();
        }
        document.querySelectorAll('[data-mb-cta-group]').forEach((group) => {
            /** @type {HTMLElement} */ (group).hidden =
                group.getAttribute('data-mb-cta-group') !== mode;
        });
    }

    function setText(selector, value) {
        const el = document.querySelector(selector);
        if (el && value != null) {
            el.textContent = value;
        }
    }

    /** Copies the counts the last swapped list carries into the tabs and the scope segment. */
    function updateCounts() {
        const counts = /** @type {HTMLElement | null} */ (
            document.querySelector('[data-mb-counts]')
        );
        if (!counts) {
            return;
        }
        const data = counts.dataset;
        setText('[data-mb-mode="offers"] .tab-count', data.offersAll);
        setText('[data-mb-mode="requests"] .tab-count', data.requestsAll);
        const requests = activeMode() === 'requests';
        setText(
            '[data-testid="segment-scope-all"] .seg-count',
            requests ? data.requestsAll : data.offersAll,
        );
        setText(
            '[data-testid="segment-scope-mine"] .seg-count',
            requests ? data.requestsMine : data.offersMine,
        );
    }

    function markActiveRow(id) {
        document.querySelectorAll('.mb-mrow[data-offer-id]').forEach((row) => {
            const on = row.getAttribute('data-offer-id') === id;
            row.classList.toggle('is-active', on);
            row.setAttribute('aria-pressed', on ? 'true' : 'false');
        });
    }

    function markActiveRequestRow(id) {
        document.querySelectorAll('.mb-mrow[data-request-id]').forEach((row) => {
            const on = row.getAttribute('data-request-id') === id;
            row.classList.toggle('is-active', on);
            row.setAttribute('aria-pressed', on ? 'true' : 'false');
        });
    }

    function clearSelection() {
        if (activeMode() === 'requests') {
            selectedRequestId = null;
        } else {
            selectedId = null;
        }
    }

    function debouncedList() {
        if (searchTimer) {
            clearTimeout(searchTimer);
        }
        searchTimer = setTimeout(() => {
            clearSelection();
            swapList();
        }, 250);
    }

    function anyModalOpen() {
        return ['mb-modal', 'mg-modal'].some((id) => {
            const modal = document.getElementById(id);
            return (
                modal &&
                window.getComputedStyle(modal).display !== '' &&
                window.getComputedStyle(modal).display !== 'none'
            );
        });
    }

    /**
     * Switches the board between Angebote and Gesuche, keeping each view's own filters.
     *
     * @param {string} toMode `offers` or `requests`
     */
    function switchMode(toMode) {
        if (toMode === activeMode()) {
            return;
        }
        persistFilters();
        setActiveMode(toMode);
        writeControls(toMode === 'requests' ? filterState.requests : filterState.offers, toMode);
        updateCounts();
        refreshFilterUi();
        clearSelection();
        persistFilters();
        syncUrl();
        swapBoard();
    }

    function resetFilters() {
        const search = /** @type {HTMLInputElement | null} */ (
            document.getElementById('mb-search')
        );
        if (search) {
            search.value = '';
        }
        setIfDifferent('[data-mb-minquality]', '');
        setIfDifferent('[data-mb-minamount]', '');
        const box = excludeStolenBox();
        if (box) {
            box.checked = false;
        }
        setScope('alle');
        updateCounts();
        refreshFilterUi();
        persistFilters();
        syncUrl();
        clearSelection();
        swapList();
    }

    function toggleInterest(button) {
        const id = button.getAttribute('data-offer-id');
        const interested = button.getAttribute('data-interested') === 'true';
        window.krtFetch.write({
            method: interested ? 'DELETE' : 'POST',
            url: `/materialboerse/offers/${id}/interest/ajax`,
            successMessage: interested ? i18n.interestRemoved : i18n.interestAdded,
            errorMessage: i18n.error,
            serialize: SERIALIZE_KEY,
            onSuccess() {
                notifyPeersBoard();
                return swapList();
            },
        });
    }

    function deactivateOffer(id) {
        window
            .showKrtConfirm(
                i18n.deactivateConfirmTitle,
                i18n.deactivateConfirmBody,
                i18n.confirmYes,
                i18n.confirmNo,
            )
            .then((ok) => {
                if (!ok) {
                    return;
                }
                window.krtFetch.write({
                    method: 'POST',
                    url: `/materialboerse/offers/${id}/deactivate/ajax`,
                    successMessage: i18n.deactivated,
                    errorMessage: i18n.error,
                    serialize: SERIALIZE_KEY,
                    onSuccess() {
                        notifyPeersBoard();
                        selectedId = null;
                        return swapBoard();
                    },
                });
            });
    }

    function onReleased(body) {
        if (body && body.id) {
            selectedId = body.id;
        }
        return swapBoard();
    }

    function toggleFulfillment(button) {
        const id = button.getAttribute('data-request-id');
        const interested = button.getAttribute('data-interested') === 'true';
        window.krtFetch.write({
            method: interested ? 'DELETE' : 'POST',
            url: `/materialboerse/requests/${id}/interest/ajax`,
            successMessage: interested ? gi18n.interestRemoved : gi18n.interestAdded,
            errorMessage: gi18n.error,
            serialize: REQUEST_SERIALIZE_KEY,
            onSuccess() {
                notifyPeersRequests();
                return swapList();
            },
        });
    }

    function deactivateRequest(id) {
        window
            .showKrtConfirm(
                gi18n.deactivateConfirmTitle,
                gi18n.deactivateConfirmBody,
                gi18n.confirmYes,
                gi18n.confirmNo,
            )
            .then((ok) => {
                if (!ok) {
                    return;
                }
                window.krtFetch.write({
                    method: 'POST',
                    url: `/materialboerse/requests/${id}/deactivate/ajax`,
                    successMessage: gi18n.deactivated,
                    errorMessage: gi18n.error,
                    serialize: REQUEST_SERIALIZE_KEY,
                    onSuccess() {
                        notifyPeersRequests();
                        selectedRequestId = null;
                        return swapBoard();
                    },
                });
            });
    }

    function onRequestCreated(body) {
        if (body && body.id) {
            selectedRequestId = body.id;
        }
        return swapBoard();
    }

    function notifyPeersBoard() {
        if (window.krtLiveSync) {
            window.krtLiveSync.sendChanged(MATERIALBOARD_TOPIC, ['board']);
        }
    }

    function notifyPeersRequests() {
        if (window.krtLiveSync) {
            window.krtLiveSync.sendChanged(MATERIALBOARD_TOPIC, ['requests']);
        }
    }

    function handleAction(el) {
        if (el.hasAttribute('data-mb-interest')) {
            toggleInterest(el);
        } else if (el.hasAttribute('data-mb-deactivate')) {
            deactivateOffer(el.getAttribute('data-offer-id'));
        } else if (el.hasAttribute('data-mb-edit') && window.krtMaterialRelease) {
            window.krtMaterialRelease.open(
                'edit',
                {
                    offerId: el.getAttribute('data-offer-id'),
                    version: el.getAttribute('data-version'),
                    kind: el.getAttribute('data-kind'),
                    material: el.getAttribute('data-material'),
                    quality: el.getAttribute('data-quality'),
                    amount: el.getAttribute('data-amount'),
                    available: el.getAttribute('data-available'),
                    quantityType: el.getAttribute('data-quantity-type'),
                    itemName: el.getAttribute('data-item-name'),
                    itemQuantity: el.getAttribute('data-item-quantity'),
                    remark: el.getAttribute('data-remark'),
                },
                swapBoard,
            );
        } else if (el.hasAttribute('data-mb-open-release') && window.krtMaterialRelease) {
            window.krtMaterialRelease.open('new', {}, onReleased);
        } else if (el.hasAttribute('data-mb-open-item') && window.krtMaterialRelease) {
            window.krtMaterialRelease.open('item', {}, onReleased);
        }
    }

    function handleRequestAction(el) {
        if (el.hasAttribute('data-mg-interest')) {
            toggleFulfillment(el);
        } else if (el.hasAttribute('data-mg-deactivate')) {
            deactivateRequest(el.getAttribute('data-request-id'));
        } else if (el.hasAttribute('data-mg-edit') && window.krtMaterialRequest) {
            window.krtMaterialRequest.open(
                'edit',
                {
                    requestId: el.getAttribute('data-request-id'),
                    version: el.getAttribute('data-version'),
                    kind: el.getAttribute('data-kind'),
                    subject: el.getAttribute('data-subject'),
                    minQuality: el.getAttribute('data-min-quality'),
                    amount: el.getAttribute('data-amount'),
                    quantityType: el.getAttribute('data-quantity-type'),
                    remark: el.getAttribute('data-remark'),
                },
                swapBoard,
            );
        } else if (el.hasAttribute('data-mg-open-request') && window.krtMaterialRequest) {
            window.krtMaterialRequest.open('new', { kind: 'MATERIAL' }, onRequestCreated);
        } else if (el.hasAttribute('data-mg-open-item-request') && window.krtMaterialRequest) {
            window.krtMaterialRequest.open('new', { kind: 'ITEM' }, onRequestCreated);
        }
    }

    document.addEventListener('click', (e) => {
        let el;
        if ((el = e.target.closest('[data-mb-mode]'))) {
            switchMode(el.getAttribute('data-mb-mode') === 'requests' ? 'requests' : 'offers');
            return;
        }
        if (e.target.closest('[data-mb-reset]') || e.target.closest('[data-mg-reset]')) {
            resetFilters();
            return;
        }
        if (e.target.closest('[data-mb-deselect]') || e.target.closest('[data-mg-deselect]')) {
            const md = document.querySelector('.mb-md');
            if (md) {
                md.classList.remove('has-sel');
            }
            return;
        }
        if (
            (el = e.target.closest('[data-mb-interest]')) ||
            (el = e.target.closest('[data-mb-deactivate]')) ||
            (el = e.target.closest('[data-mb-edit]')) ||
            (el = e.target.closest('[data-mb-open-release]')) ||
            (el = e.target.closest('[data-mb-open-item]'))
        ) {
            handleAction(el);
            return;
        }
        if (
            (el = e.target.closest('[data-mg-interest]')) ||
            (el = e.target.closest('[data-mg-deactivate]')) ||
            (el = e.target.closest('[data-mg-edit]')) ||
            (el = e.target.closest('[data-mg-open-request]')) ||
            (el = e.target.closest('[data-mg-open-item-request]'))
        ) {
            handleRequestAction(el);
            return;
        }
        if ((el = e.target.closest('[data-mb-select]'))) {
            selectedId = el.getAttribute('data-offer-id');
            markActiveRow(selectedId);
            const mdSel = document.querySelector('.mb-md');
            if (mdSel) {
                mdSel.classList.add('has-sel');
            }
            swapDetail(selectedId);
            return;
        }
        if ((el = e.target.closest('[data-mg-select]'))) {
            selectedRequestId = el.getAttribute('data-request-id');
            markActiveRequestRow(selectedRequestId);
            const mdSel = document.querySelector('.mb-md');
            if (mdSel) {
                mdSel.classList.add('has-sel');
            }
            swapRequestDetail(selectedRequestId);
        }
    });

    document.addEventListener('input', (e) => {
        if (e.target.matches('#mb-search, [data-mb-minquality], [data-mb-minamount]')) {
            persistFilters();
            debouncedList();
        }
    });

    document.addEventListener('change', (e) => {
        if (e.target.matches('[data-mb-sort], [data-mb-exclude-stolen]')) {
            persistFilters();
            swapList();
        } else if (e.target.matches('#mb-filter-form input[name="scope"]')) {
            clearSelection();
            updateCounts();
            persistFilters();
            syncUrl();
            swapList();
        }
    });

    if (form) {
        form.addEventListener('submit', (e) => {
            e.preventDefault();
            if (searchTimer) {
                clearTimeout(searchTimer);
            }
            persistFilters();
            clearSelection();
            swapList();
        });
    }

    let peerTimer = null;
    function onPeerChanged(sections) {
        const secs = Array.isArray(sections) ? sections : [];
        if (peerTimer) {
            clearTimeout(peerTimer);
        }
        peerTimer = setTimeout(() => {
            if (anyModalOpen()) {
                return;
            }
            const key = activeMode() === 'requests' ? 'requests' : 'board';
            if (secs.length === 0 || secs.indexOf(key) >= 0) {
                swapList();
            }
        }, 400);
    }
    if (window.krtLiveSync) {
        window.krtLiveSync.subscribe(MATERIALBOARD_TOPIC, { onChanged: onPeerChanged });
    }

    document.addEventListener('krt:swapped', () => {
        applyAgo(document);
        selectedId = readSelectedId();
        selectedRequestId = readSelectedRequestId();
        updateCounts();
    });

    /**
     * Restores the stored view, scope and filters (REQ-UI-017). Filter parameters in the URL win
     * and are stored instead; a `view` / `scope` (or `mode` / `tab`) parameter fixes only the view
     * or the scope, and the view's stored filters still apply.
     */
    function restoreFilters() {
        const search = window.location.search;
        if (/[?&](q|minQuality|minAmount|sort|selected|excludeStolen)=/.test(search)) {
            persistFilters();
            return;
        }
        if (rawFilterPref === null) {
            return;
        }
        const targetMode = /[?&](view|mode)=/.test(search) ? activeMode() : filterState.mode;
        const targetTab = /[?&](scope|tab)=/.test(search) ? activeTab() : filterState.tab;
        const modeChanged = targetMode !== activeMode();
        if (modeChanged) {
            setActiveMode(targetMode);
        }
        let differs = writeControls(
            targetMode === 'requests' ? filterState.requests : filterState.offers,
            targetMode,
        );
        if (targetTab !== activeTab()) {
            setScope(targetTab);
            differs = true;
        }
        updateCounts();
        refreshFilterUi();
        persistFilters();
        if (modeChanged) {
            clearSelection();
            swapBoard();
            return;
        }
        if (differs) {
            clearSelection();
            swapList();
        }
    }

    applyAgo(document);
    restoreFilters();
})();

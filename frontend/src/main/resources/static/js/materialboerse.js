/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Materialbörse board interactions (Flotte & Logistik). The board carries two modes — Angebote
 * (offers) and Gesuche (requests) — sharing one four-tab bar. Tabs / filters / sort and row
 * selection re-render server fragments through window.krtFetch.swap; interest / deactivate writes
 * go through window.krtFetch.write; the offer release/edit dialog is window.krtMaterialRelease
 * (materialboerse-release.js) and the request create/edit dialog is window.krtMaterialRequest
 * (materialgesuch-modal.js). Board changes peer-sync over the shared multiplexed window.krtLiveSync
 * `materialboard` room — offers on the `board` section key, requests on `requests`. No hand-rolled
 * fetch, no full-page reload, no native dialogs. REQ-MARKET-*, REQ-FE-001..015.
 */
(function () {
    'use strict';

    let i18n = window.materialboerseI18n || {};
    let gi18n = window.materialgesuchI18n || {};
    if (!window.krtFetch || !document.getElementById('mb-board')) {
        return;
    }

    let SERIALIZE_KEY = 'materialboerse';
    let REQUEST_SERIALIZE_KEY = 'materialgesuch';
    // REQ-FE-015 (ADR-0094): the global live-sync room the board publishes to / subscribes from,
    // multiplexed over the shared /ws/sync socket. Offers broadcast the `board` section key,
    // requests the `requests` key; the receiver refreshes only the visible board.
    let MATERIALBOARD_TOPIC = 'materialboard';
    let selectedId = readSelectedId();
    let selectedRequestId = readSelectedRequestId();
    let searchTimer = null;
    let requestSearchTimer = null;

    // -------- helpers --------------------------------------------------------

    function fmt(template, value) {
        return String(template || '').replace('{0}', value);
    }

    function readSelectedId() {
        let active = document.querySelector('.mb-mrow.is-active[data-offer-id]');
        return active ? active.getAttribute('data-offer-id') : null;
    }

    function readSelectedRequestId() {
        let active = document.querySelector('.mb-mrow.is-active[data-request-id]');
        return active ? active.getAttribute('data-request-id') : null;
    }

    function val(selector) {
        let el = document.querySelector(selector);
        return el ? el.value.trim() : '';
    }

    function activeTabEl() {
        return document.querySelector('.tab.active[data-mb-tab]');
    }

    function activeTab() {
        let tab = activeTabEl();
        return tab ? tab.getAttribute('data-mb-tab') : 'alle';
    }

    function activeMode() {
        let tab = activeTabEl();
        return tab ? tab.getAttribute('data-mb-mode') || 'offers' : 'offers';
    }

    // -------- per-browser filter persistence (REQ-UI-017) --------------------

    // One JSON object under a single key: the active (mode, tab) pair plus each board's
    // minQuality / minAmount / sort. The search inputs ([data-mb-search] / [data-mg-search]) are
    // deliberately NOT persisted. Absent key = no saved preference = the server-rendered
    // defaults. Guarded so privacy modes that deny storage degrade to the defaults instead of
    // breaking the board.
    let FILTER_PREF_KEY = 'materialboerse_filters';
    let SORT_KEYS = ['qual', 'menge', 'mat', 'neu'];

    function defaultBoardFilters() {
        return { minQuality: '', minAmount: '', sort: 'qual' };
    }

    function readFilterPref() {
        try {
            let raw = localStorage.getItem(FILTER_PREF_KEY);
            return raw === null ? null : JSON.parse(raw);
        } catch (_e) {
            return null; // corrupt value / storage unavailable: fall back to the defaults
        }
    }

    function writeFilterPref(value) {
        try {
            localStorage.setItem(FILTER_PREF_KEY, JSON.stringify(value));
        } catch (_e) {
            /* storage unavailable */
        }
    }

    // Copies one board's saved filter values onto its defaults, dropping anything stale or
    // malformed (an unknown sort key falls back to the default).
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
    }

    function normalizeFilterPref(saved) {
        let state = {
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

    let rawFilterPref = readFilterPref();
    let filterState = normalizeFilterPref(rawFilterPref);

    // Snapshots the current widget state into localStorage, immediately on every tab / filter /
    // sort / reset interaction (not debounced with the re-fetch). Only the visible board's
    // toolbar exists in the DOM, so its values are merged into the kept state keyed by which
    // widgets are present — the hidden board's stored values survive untouched.
    function persistFilters() {
        if (activeTabEl()) {
            filterState.mode = activeMode();
            filterState.tab = activeTab();
        }
        if (document.querySelector('[data-mb-minquality]')) {
            filterState.offers.minQuality = val('[data-mb-minquality]');
            filterState.offers.minAmount = val('[data-mb-minamount]');
            filterState.offers.sort = val('[data-mb-sort]') || 'qual';
        }
        if (document.querySelector('[data-mg-minquality]')) {
            filterState.requests.minQuality = val('[data-mg-minquality]');
            filterState.requests.minAmount = val('[data-mg-minamount]');
            filterState.requests.sort = val('[data-mg-sort]') || 'qual';
        }
        writeFilterPref(filterState);
    }

    // Reads a filter input's value, falling back to the persisted state (REQ-UI-017) when the
    // widget is not in the DOM — only the visible board's toolbar is rendered, so a cross-mode
    // board swap would otherwise silently drop the target board's persisted filters. The swap
    // URL then carries them and the server echoes them into the swapped-in toolbar.
    function filterVal(selector, savedValue) {
        let el = document.querySelector(selector);
        if (el) {
            return el.value.trim();
        }
        return savedValue == null ? '' : String(savedValue);
    }

    // -------- offer swaps ----------------------------------------------------

    function params() {
        let p = new URLSearchParams();
        p.set('tab', activeTab());
        let qv = val('[data-mb-search]');
        if (qv) {
            p.set('q', qv);
        }
        let minQ = filterVal('[data-mb-minquality]', filterState.offers.minQuality);
        if (minQ) {
            p.set('minQuality', minQ);
        }
        let minA = filterVal('[data-mb-minamount]', filterState.offers.minAmount);
        if (minA) {
            p.set('minAmount', minA);
        }
        let sort = filterVal('[data-mb-sort]', filterState.offers.sort);
        if (sort) {
            p.set('sort', sort);
        }
        if (selectedId) {
            p.set('selected', selectedId);
        }
        return p;
    }

    function swapList() {
        let p = params();
        p.set('fragment', 'list');
        return window.krtFetch.swap({
            url: '/materialboerse?' + p.toString(),
            container: '#mb-listwrap',
            fragmentValue: 'list',
            history: false,
        });
    }

    function swapBoard() {
        let p = params();
        p.set('fragment', 'board');
        return window.krtFetch.swap({
            url: '/materialboerse?' + p.toString(),
            container: '#mb-board',
            fragmentValue: 'board',
            history: false,
        });
    }

    function swapDetail(id) {
        return window.krtFetch.swap({
            url: '/materialboerse?fragment=detail&selected=' + encodeURIComponent(id),
            container: '#mb-detail',
            fragmentValue: 'detail',
            history: false,
        });
    }

    // -------- request (Gesuche) swaps ---------------------------------------

    function requestParams() {
        let p = new URLSearchParams();
        p.set('mode', 'requests');
        p.set('tab', activeTab());
        let qv = val('[data-mg-search]');
        if (qv) {
            p.set('q', qv);
        }
        let minQ = filterVal('[data-mg-minquality]', filterState.requests.minQuality);
        if (minQ) {
            p.set('minQuality', minQ);
        }
        let minA = filterVal('[data-mg-minamount]', filterState.requests.minAmount);
        if (minA) {
            p.set('minAmount', minA);
        }
        let sort = filterVal('[data-mg-sort]', filterState.requests.sort);
        if (sort) {
            p.set('sort', sort);
        }
        if (selectedRequestId) {
            p.set('selected', selectedRequestId);
        }
        return p;
    }

    function swapRequestList() {
        let p = requestParams();
        p.set('fragment', 'list');
        return window.krtFetch.swap({
            url: '/materialboerse?' + p.toString(),
            container: '#mg-listwrap',
            fragmentValue: 'list',
            history: false,
        });
    }

    function swapRequestBoard() {
        let p = requestParams();
        p.set('fragment', 'board');
        return window.krtFetch.swap({
            url: '/materialboerse?' + p.toString(),
            container: '#mb-board',
            fragmentValue: 'board',
            history: false,
        });
    }

    function swapRequestDetail(id) {
        return window.krtFetch.swap({
            url: '/materialboerse?mode=requests&fragment=detail&selected=' + encodeURIComponent(id),
            container: '#mg-detail',
            fragmentValue: 'detail',
            history: false,
        });
    }

    // -------- relative time --------------------------------------------------

    function applyAgo(root) {
        (root || document).querySelectorAll('[data-mb-ago]').forEach(function (el) {
            let ts = el.getAttribute('data-ts');
            if (!ts) {
                return;
            }
            let then = Date.parse(ts);
            if (isNaN(then)) {
                return;
            }
            let hours = Math.floor((Date.now() - then) / 3600000);
            let text;
            if (hours < 1) {
                text = i18n.agoNow || 'gerade eben';
            } else if (hours < 24) {
                text = fmt(i18n.agoHours || 'vor {0} Std', hours);
            } else {
                let days = Math.round(hours / 24);
                text =
                    days === 1
                        ? i18n.agoDayOne || 'vor 1 Tag'
                        : fmt(i18n.agoDays || 'vor {0} Tagen', days);
            }
            el.textContent = text;
        });
    }

    // -------- tabs / CTA / selection -----------------------------------------

    function setActiveTabEl(el) {
        document.querySelectorAll('.tab[data-mb-tab]').forEach(function (btn) {
            let on = btn === el;
            btn.classList.toggle('active', on);
            btn.setAttribute('aria-selected', on ? 'true' : 'false');
        });
    }

    function tabEl(mode, tab) {
        return document.querySelector(
            '.tab[data-mb-mode="' + mode + '"][data-mb-tab="' + tab + '"]',
        );
    }

    function toggleCtaGroups(mode) {
        document.querySelectorAll('[data-mb-cta-group]').forEach(function (group) {
            group.hidden = group.getAttribute('data-mb-cta-group') !== mode;
        });
    }

    function markActiveRow(id) {
        document.querySelectorAll('.mb-mrow[data-offer-id]').forEach(function (row) {
            let on = row.getAttribute('data-offer-id') === id;
            row.classList.toggle('is-active', on);
            row.setAttribute('aria-pressed', on ? 'true' : 'false');
        });
    }

    function markActiveRequestRow(id) {
        document.querySelectorAll('.mb-mrow[data-request-id]').forEach(function (row) {
            let on = row.getAttribute('data-request-id') === id;
            row.classList.toggle('is-active', on);
            row.setAttribute('aria-pressed', on ? 'true' : 'false');
        });
    }

    function debouncedList() {
        if (searchTimer) {
            clearTimeout(searchTimer);
        }
        searchTimer = setTimeout(function () {
            selectedId = null;
            swapList();
        }, 250);
    }

    function debouncedRequestList() {
        if (requestSearchTimer) {
            clearTimeout(requestSearchTimer);
        }
        requestSearchTimer = setTimeout(function () {
            selectedRequestId = null;
            swapRequestList();
        }, 250);
    }

    function setInputVal(selector, value) {
        let el = document.querySelector(selector);
        if (el) {
            el.value = value;
        }
    }

    function anyModalOpen() {
        return ['mb-modal', 'mg-modal'].some(function (id) {
            let modal = document.getElementById(id);
            return (
                modal &&
                window.getComputedStyle(modal).display !== '' &&
                window.getComputedStyle(modal).display !== 'none'
            );
        });
    }

    // -------- offer writes ---------------------------------------------------

    function toggleInterest(button) {
        let id = button.getAttribute('data-offer-id');
        let interested = button.getAttribute('data-interested') === 'true';
        window.krtFetch.write({
            method: interested ? 'DELETE' : 'POST',
            url: '/materialboerse/offers/' + id + '/interest/ajax',
            successMessage: interested ? i18n.interestRemoved : i18n.interestAdded,
            errorMessage: i18n.error,
            serialize: SERIALIZE_KEY,
            onSuccess: function () {
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
            .then(function (ok) {
                if (!ok) {
                    return;
                }
                window.krtFetch.write({
                    method: 'POST',
                    url: '/materialboerse/offers/' + id + '/deactivate/ajax',
                    successMessage: i18n.deactivated,
                    errorMessage: i18n.error,
                    serialize: SERIALIZE_KEY,
                    onSuccess: function () {
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

    // -------- request (Gesuche) writes --------------------------------------

    function toggleFulfillment(button) {
        let id = button.getAttribute('data-request-id');
        let interested = button.getAttribute('data-interested') === 'true';
        window.krtFetch.write({
            method: interested ? 'DELETE' : 'POST',
            url: '/materialboerse/requests/' + id + '/interest/ajax',
            successMessage: interested ? gi18n.interestRemoved : gi18n.interestAdded,
            errorMessage: gi18n.error,
            serialize: REQUEST_SERIALIZE_KEY,
            onSuccess: function () {
                notifyPeersRequests();
                return swapRequestList();
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
            .then(function (ok) {
                if (!ok) {
                    return;
                }
                window.krtFetch.write({
                    method: 'POST',
                    url: '/materialboerse/requests/' + id + '/deactivate/ajax',
                    successMessage: gi18n.deactivated,
                    errorMessage: gi18n.error,
                    serialize: REQUEST_SERIALIZE_KEY,
                    onSuccess: function () {
                        notifyPeersRequests();
                        selectedRequestId = null;
                        return swapRequestBoard();
                    },
                });
            });
    }

    function onRequestCreated(body) {
        if (body && body.id) {
            selectedRequestId = body.id;
        }
        return swapRequestBoard();
    }

    // Broadcast literal section keys (not a variable) so the LiveSyncSectionMapParityTest literal
    // scan can prove every broadcast key is whitelisted.
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

    // -------- action dispatch ------------------------------------------------

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
                swapRequestBoard,
            );
        } else if (el.hasAttribute('data-mg-open-request') && window.krtMaterialRequest) {
            window.krtMaterialRequest.open('new', { kind: 'MATERIAL' }, onRequestCreated);
        } else if (el.hasAttribute('data-mg-open-item-request') && window.krtMaterialRequest) {
            window.krtMaterialRequest.open('new', { kind: 'ITEM' }, onRequestCreated);
        }
    }

    // -------- delegated events -----------------------------------------------

    document.addEventListener('click', function (e) {
        let el;
        if ((el = e.target.closest('[data-mb-tab]'))) {
            let toMode = el.getAttribute('data-mb-mode') || 'offers';
            let fromMode = activeMode();
            setActiveTabEl(el);
            toggleCtaGroups(toMode);
            persistFilters(); // REQ-UI-017: keep the (mode, tab) selection across visits
            if (toMode !== fromMode) {
                if (toMode === 'requests') {
                    selectedRequestId = null;
                    swapRequestBoard();
                } else {
                    selectedId = null;
                    swapBoard();
                }
            } else if (toMode === 'requests') {
                selectedRequestId = null;
                swapRequestList();
            } else {
                selectedId = null;
                swapList();
            }
            return;
        }
        if (e.target.closest('[data-mb-reset]')) {
            setInputVal('[data-mb-search]', '');
            setInputVal('[data-mb-minquality]', '');
            setInputVal('[data-mb-minamount]', '');
            setActiveTabEl(tabEl('offers', 'alle'));
            persistFilters(); // REQ-UI-017: a reset persists the cleared state
            selectedId = null;
            swapList();
            return;
        }
        if (e.target.closest('[data-mg-reset]')) {
            setInputVal('[data-mg-search]', '');
            setInputVal('[data-mg-minquality]', '');
            setInputVal('[data-mg-minamount]', '');
            setActiveTabEl(tabEl('requests', 'alle'));
            persistFilters(); // REQ-UI-017: a reset persists the cleared state
            selectedRequestId = null;
            swapRequestList();
            return;
        }
        if (e.target.closest('[data-mb-deselect]') || e.target.closest('[data-mg-deselect]')) {
            let md = document.querySelector('.mb-md');
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
            let mdSel = document.querySelector('.mb-md');
            if (mdSel) {
                mdSel.classList.add('has-sel');
            }
            swapDetail(selectedId);
            return;
        }
        if ((el = e.target.closest('[data-mg-select]'))) {
            selectedRequestId = el.getAttribute('data-request-id');
            markActiveRequestRow(selectedRequestId);
            let mdSel = document.querySelector('.mb-md');
            if (mdSel) {
                mdSel.classList.add('has-sel');
            }
            swapRequestDetail(selectedRequestId);
        }
    });

    document.addEventListener('input', function (e) {
        if (e.target.matches('[data-mb-search], [data-mb-minquality], [data-mb-minamount]')) {
            persistFilters(); // REQ-UI-017: persist immediately, not with the debounced fetch
            debouncedList();
        } else if (
            e.target.matches('[data-mg-search], [data-mg-minquality], [data-mg-minamount]')
        ) {
            persistFilters(); // REQ-UI-017: persist immediately, not with the debounced fetch
            debouncedRequestList();
        }
    });

    document.addEventListener('change', function (e) {
        if (e.target.matches('[data-mb-sort]')) {
            persistFilters();
            swapList();
        } else if (e.target.matches('[data-mg-sort]')) {
            persistFilters();
            swapRequestList();
        }
    });

    // REQ-MARKET-010 / REQ-FE-015: a peer released / deactivated / registered interest / posted a
    // request — re-pull only the currently-visible board's list. Debounced; skipped while either
    // modal is open so an in-progress dialog is not disrupted. The receiver branches on the changed
    // section keys (offers on `board`, requests on `requests`), so an offer change never re-pulls
    // the request list and vice-versa.
    let peerTimer = null;
    function onPeerChanged(sections) {
        let secs = Array.isArray(sections) ? sections : [];
        if (peerTimer) {
            clearTimeout(peerTimer);
        }
        peerTimer = setTimeout(function () {
            if (anyModalOpen()) {
                return;
            }
            if (activeMode() === 'requests') {
                if (secs.length === 0 || secs.indexOf('requests') >= 0) {
                    swapRequestList();
                }
            } else if (secs.length === 0 || secs.indexOf('board') >= 0) {
                swapList();
            }
        }, 400);
    }
    if (window.krtLiveSync) {
        window.krtLiveSync.subscribe(MATERIALBOARD_TOPIC, { onChanged: onPeerChanged });
    }

    document.addEventListener('krt:swapped', function () {
        applyAgo(document);
        selectedId = readSelectedId();
        selectedRequestId = readSelectedRequestId();
    });

    // Writes a saved value into a rendered toolbar widget; reports whether it actually changed.
    function setIfDifferent(selector, value) {
        let el = document.querySelector(selector);
        if (!el || el.value.trim() === value) {
            return false;
        }
        el.value = value;
        return true;
    }

    // Writes one board's saved values into its rendered toolbar; reports whether anything changed.
    function applySavedBoardFilters(prefix, saved) {
        let changed = setIfDifferent('[data-' + prefix + '-minquality]', saved.minQuality);
        changed = setIfDifferent('[data-' + prefix + '-minamount]', saved.minAmount) || changed;
        changed = setIfDifferent('[data-' + prefix + '-sort]', saved.sort) || changed;
        return changed;
    }

    // Applies the persisted (mode, tab) + filter selection at init (REQ-UI-017). A URL carrying
    // explicit board query params (deep link — the swaps themselves run history:false) is
    // authoritative: the rendered state is adopted and re-persisted; only a bare URL restores
    // from storage. When the restored state differs from the server-rendered defaults, the
    // existing tab-activation / swap path is driven exactly once.
    function restoreFilters() {
        if (/[?&](mode|tab|q|minQuality|minAmount|sort|selected)=/.test(window.location.search)) {
            persistFilters();
            return;
        }
        if (rawFilterPref === null) {
            return; // no saved preference: keep the server-rendered defaults
        }
        let target = tabEl(filterState.mode, filterState.tab);
        if (!target) {
            return;
        }
        if (filterState.mode !== activeMode()) {
            // Cross-mode restore: activate the saved tab and swap the whole board once. The swap
            // URL picks the saved filters up through the filterVal fallback and the server echoes
            // them into the swapped-in toolbar.
            setActiveTabEl(target);
            toggleCtaGroups(filterState.mode);
            if (filterState.mode === 'requests') {
                selectedRequestId = null;
                swapRequestBoard();
            } else {
                selectedId = null;
                swapBoard();
            }
            return;
        }
        // Same mode: write the saved values into the rendered toolbar, then re-render the list
        // once — only if anything differs from what the server rendered.
        let differs =
            filterState.mode === 'requests'
                ? applySavedBoardFilters('mg', filterState.requests)
                : applySavedBoardFilters('mb', filterState.offers);
        if (filterState.tab !== activeTab()) {
            setActiveTabEl(target);
            differs = true;
        }
        if (!differs) {
            return;
        }
        if (filterState.mode === 'requests') {
            selectedRequestId = null;
            swapRequestList();
        } else {
            selectedId = null;
            swapList();
        }
    }

    applyAgo(document);
    restoreFilters();
})();

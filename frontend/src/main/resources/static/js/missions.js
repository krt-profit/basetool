// ---- Live multi-user sync — the mission list (REQ-FE-010 / REQ-FE-015, ADR-0094, #1235) --------
// When anyone creates, core-edits (name / status / planned start) or deletes a mission, every other
// viewer's list re-fetches its OWN filter/page in place over the shared /ws/sync `missions` room.
// Only the opaque `list` key crosses the wire; each viewer re-pulls its own org-unit-scoped,
// peer-redacted fragment, so a viewer outside the actor's scope simply re-renders the same rows.
// MISSIONS_SECTIONS mirrors the server LiveSyncTopicClass.MISSIONS_LIST whitelist (the REQ-FE-010
// three-mirror-points rule, build-enforced by LiveSyncSectionMapParityTest).
//
// The BROADCAST side is server-side (MissionWriteController.liveSyncLocalBus): create, update and
// delete all redirect, so a client publish issued just before that navigation would race the socket
// teardown. This module is therefore receive-only.
const MISSIONS_SECTIONS = {
    list: { container: '#missions-results', fragmentValue: 'results' },
};

(function () {
    'use strict';

    const form = document.getElementById('missions-filter-form');
    const resultsContainer = document.getElementById('missions-results');
    const loadingIndicator = document.getElementById('missions-loading-indicator');
    const resetBtn = document.getElementById('missions-filter-reset');

    // krtFetch (fragments/head.html) owns the fragment swap + the in-results pagination
    // interception, so the whole list — filter, sort and paginate — stays in place.
    if (!resultsContainer || !window.krtFetch) return;

    let debounceTimer = null;

    function buildQueryString() {
        if (!form) return '';
        const data = new FormData(form);
        const params = new URLSearchParams();
        for (const [key, value] of data.entries()) {
            if (value !== '') params.append(key, value);
        }
        return params.toString();
    }

    // pushHistory=false is the peer-driven path: a peer's change must not push a history entry, or
    // a busy room would bury the user's own navigation under a stack of identical list URLs.
    function loadResults(pushHistory) {
        const query = buildQueryString();
        window.krtFetch.swap({
            url: '/missions' + (query ? '?' + query : ''),
            container: resultsContainer,
            indicator: loadingIndicator,
            history: pushHistory !== false,
        });
    }

    if (window.krtLiveSync && typeof window.krtLiveSync.createReceiver === 'function') {
        window.krtLiveSync.createReceiver({
            topic: 'missions',
            sections: MISSIONS_SECTIONS,
            // Global room: the longer coalesce window (#1125) flattens the re-fetch herd when many
            // viewers receive the same signal at once.
            coalesceMs: 1500,
            refresh: function () {
                loadResults(false);
            },
        });
    }

    // The filter form is absent for anonymous visitors; live sync above still applies.
    if (!form) return;

    function onFilterChange() {
        clearTimeout(debounceTimer);
        // Wrapped rather than passed by reference so loadResults never receives a stray timer
        // argument as its pushHistory flag — a user-driven filter change does push history.
        debounceTimer = setTimeout(function () {
            loadResults(true);
        }, 300);
    }

    // Per-browser persistence of the "show past" toggle (REQ-UI-017): one JSON object under a
    // single localStorage key. The search text and the start/end range inputs are deliberately
    // NOT persisted. Absent key = no saved preference = the server-rendered default (unchecked).
    // Guarded so privacy modes that deny storage degrade to the default instead of breaking.
    const FILTER_PREF_KEY = 'missions_filter';

    function showPastInput() {
        return form.querySelector('input[name="showPast"]');
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
        } catch (_e) {
            /* storage unavailable */
        }
    }

    function persistFilters() {
        const input = showPastInput();
        if (input) {
            writeFilterPref({ showPast: input.checked });
        }
    }

    // Restores the persisted toggle at init, driving the existing swap exactly once when the
    // restored state differs from what the server rendered. An explicit showPast query param
    // (deep link / back-nav — the swaps run history:true) wins over the stored state and is
    // re-persisted; only a bare URL restores from storage. The checkbox is absent for anonymous
    // visitors, in which case this is a no-op.
    function restoreFilters() {
        const input = showPastInput();
        if (!input) return;
        if (/[?&]showPast=/.test(window.location.search)) {
            persistFilters();
            return;
        }
        const saved = readFilterPref();
        if (!saved || typeof saved.showPast !== 'boolean' || saved.showPast === input.checked) {
            return;
        }
        input.checked = saved.showPast;
        loadResults();
    }

    form.querySelectorAll('input, select').forEach(function (el) {
        el.addEventListener('input', onFilterChange);
        el.addEventListener('change', onFilterChange);
    });

    const showPastToggle = showPastInput();
    if (showPastToggle) {
        // Persist immediately on every toggle (the debounced re-fetch stays onFilterChange's job).
        showPastToggle.addEventListener('change', persistFilters);
    }

    if (resetBtn) {
        resetBtn.addEventListener('click', function () {
            form.querySelectorAll(
                'input[type="text"], input[type="hidden"], input[type="date"], input[type="time"]',
            ).forEach(function (el) {
                el.value = '';
            });
            form.querySelectorAll('input[type="checkbox"]').forEach(function (el) {
                el.checked = false;
            });
            persistFilters(); // REQ-UI-017: a reset persists the cleared state
            loadResults();
        });
    }

    restoreFilters();
})();

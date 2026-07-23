(function () {
    'use strict';

    const form = document.getElementById('missions-filter-form');
    const resultsContainer = document.getElementById('missions-results');
    const loadingIndicator = document.getElementById('missions-loading-indicator');
    const resetBtn = document.getElementById('missions-filter-reset');

    // krtFetch (fragments/head.html) owns the fragment swap + the in-results pagination
    // interception, so the whole list — filter, sort and paginate — stays in place.
    if (!form || !resultsContainer || !window.krtFetch) return;

    let debounceTimer = null;

    function buildQueryString() {
        const data = new FormData(form);
        const params = new URLSearchParams();
        for (const [key, value] of data.entries()) {
            if (value !== '') params.append(key, value);
        }
        return params.toString();
    }

    function loadResults() {
        const query = buildQueryString();
        window.krtFetch.swap({
            url: '/missions' + (query ? '?' + query : ''),
            container: resultsContainer,
            indicator: loadingIndicator,
            history: true,
        });
    }

    function onFilterChange() {
        clearTimeout(debounceTimer);
        debounceTimer = setTimeout(loadResults, 300);
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

(function () {
    'use strict';

    const form = document.getElementById('operations-filter-form');
    const resultsContainer = document.getElementById('operations-results');
    const loadingIndicator = document.getElementById('operations-loading-indicator');
    const resetBtn = document.getElementById('operations-filter-reset');

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
            url: `/operations${query ? `?${query}` : ''}`,
            container: resultsContainer,
            indicator: loadingIndicator,
            history: true,
        });
    }

    window.krtOperationsReload = loadResults;

    function onFilterChange() {
        clearTimeout(debounceTimer);
        debounceTimer = setTimeout(loadResults, 300);
    }

    const FILTER_PREF_KEY = 'operations_filter';
    const PERIODS = ['UPCOMING', 'PAST', 'ALL'];

    function periodInputs() {
        return form.querySelectorAll('input[name="period"]');
    }

    function currentPeriod() {
        const checked = form.querySelector('input[name="period"]:checked');
        return checked ? checked.value : 'UPCOMING';
    }

    function selectPeriod(value) {
        periodInputs().forEach((el) => {
            el.checked = el.value === value;
        });
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

    function persistFilters() {
        writeFilterPref({ period: currentPeriod() });
    }

    function storedPeriod(saved) {
        if (!saved) return null;
        if (typeof saved.period === 'string' && PERIODS.indexOf(saved.period) >= 0) {
            return saved.period;
        }
        if (typeof saved.showPast === 'boolean') return saved.showPast ? 'ALL' : 'UPCOMING';
        return null;
    }

    function restoreFilters() {
        if (/[?&](period|showPast)=/.test(window.location.search)) {
            persistFilters();
            return;
        }
        const saved = storedPeriod(readFilterPref());
        if (!saved || saved === currentPeriod()) return;
        selectPeriod(saved);
        loadResults();
    }

    form.querySelectorAll('input, select').forEach((el) => {
        el.addEventListener('input', onFilterChange);
        el.addEventListener('change', onFilterChange);
    });

    periodInputs().forEach((el) => {
        el.addEventListener('change', persistFilters);
    });

    if (resetBtn) {
        resetBtn.addEventListener('click', () => {
            form.querySelectorAll(
                'input[type="search"], input[type="text"], input[type="hidden"], input[type="date"], input[type="time"]',
            ).forEach((el) => {
                el.value = '';
            });
            selectPeriod('UPCOMING');
            persistFilters();
            if (window.krtFilterChips) window.krtFilterChips.refresh(form);
            loadResults();
        });
    }

    restoreFilters();
})();

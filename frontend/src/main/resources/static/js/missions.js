const MISSIONS_SECTIONS = {
    list: { container: '#missions-results', fragmentValue: 'results' },
};

(function () {
    'use strict';

    const form = document.getElementById('missions-filter-form');
    const resultsContainer = document.getElementById('missions-results');
    const loadingIndicator = document.getElementById('missions-loading-indicator');
    const resetBtn = document.getElementById('missions-filter-reset');

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
            coalesceMs: 1500,
            refresh() {
                loadResults(false);
            },
        });
    }

    if (!form) return;

    function onFilterChange() {
        clearTimeout(debounceTimer);
        debounceTimer = setTimeout(function () {
            loadResults(true);
        }, 300);
    }

    const FILTER_PREF_KEY = 'missions_filter';
    const PERIODS = ['UPCOMING', 'PAST', 'ALL'];

    function periodInputs() {
        return form.querySelectorAll('input[name="period"]');
    }

    function currentPeriod() {
        const checked = form.querySelector('input[name="period"]:checked');
        return checked ? checked.value : 'UPCOMING';
    }

    function selectPeriod(value) {
        periodInputs().forEach(function (el) {
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

    form.querySelectorAll('input, select').forEach(function (el) {
        el.addEventListener('input', onFilterChange);
        el.addEventListener('change', onFilterChange);
    });

    periodInputs().forEach(function (el) {
        el.addEventListener('change', persistFilters);
    });

    if (resetBtn) {
        resetBtn.addEventListener('click', function () {
            form.querySelectorAll(
                'input[type="search"], input[type="text"], input[type="hidden"], input[type="date"], input[type="time"]',
            ).forEach(function (el) {
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

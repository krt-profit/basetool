import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtLiveSync } from '/js/kernel/krt-live-sync.js';
import { krtFilterChips } from '/js/kernel/krt-filter-chips.js';

const MISSIONS_SECTIONS = {
    list: { container: '#missions-results', fragmentValue: 'results' },
};

function initMissions() {
    const form = /** @type {HTMLFormElement | null} */ (
        document.getElementById('missions-filter-form')
    );
    const resultsContainer = document.getElementById('missions-results');
    const loadingIndicator = document.getElementById('missions-loading-indicator');
    const resetBtn = document.getElementById('missions-filter-reset');

    if (!resultsContainer) return;

    /** @type {number | null} */
    let debounceTimer = null;

    function buildQueryString() {
        if (!form) return '';
        const data = new FormData(form);
        const params = new URLSearchParams();
        for (const [key, value] of data.entries()) {
            if (value !== '') params.append(key, String(value));
        }
        return params.toString();
    }

    /**
     * Re-pulls the result list with the current filters.
     *
     * @param {boolean} [pushHistory] false to replace the history entry instead of adding one
     */
    function loadResults(pushHistory) {
        const query = buildQueryString();
        krtFetch.swap({
            url: `/missions${query ? `?${query}` : ''}`,
            container: resultsContainer,
            indicator: loadingIndicator,
            history: pushHistory !== false,
        });
    }

    krtLiveSync.createReceiver({
        topic: 'missions',
        sections: MISSIONS_SECTIONS,
        coalesceMs: 1500,
        refresh() {
            loadResults(false);
        },
    });

    if (!form) return;

    function onFilterChange() {
        clearTimeout(debounceTimer ?? undefined);
        debounceTimer = setTimeout(() => {
            loadResults(true);
        }, 300);
    }

    const FILTER_PREF_KEY = 'missions_filter';
    const PERIODS = ['UPCOMING', 'PAST', 'ALL'];

    const periodForm = form;

    function periodInputs() {
        return /** @type {NodeListOf<HTMLInputElement>} */ (
            periodForm.querySelectorAll('input[name="period"]')
        );
    }

    function currentPeriod() {
        const checked = /** @type {HTMLInputElement | null} */ (
            periodForm.querySelector('input[name="period"]:checked')
        );
        return checked ? checked.value : 'UPCOMING';
    }

    /**
     * Checks the period radio with the given value and unchecks the others.
     *
     * @param {string} value the period value
     */
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

    /**
     * Stores the filter preference; a storage failure is ignored.
     *
     * @param {unknown} value the JSON-serialisable preference
     */
    function writeFilterPref(value) {
        try {
            localStorage.setItem(FILTER_PREF_KEY, JSON.stringify(value));
        } catch (_e) {}
    }

    function persistFilters() {
        writeFilterPref({ period: currentPeriod() });
    }

    /**
     * Reads the period out of a stored preference, including the legacy `showPast` flag.
     *
     * @param {{ period?: unknown, showPast?: unknown } | null} saved the stored preference
     * @returns {string | null} the period, or null when the preference holds none
     */
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
            /** @type {NodeListOf<HTMLInputElement>} */ (
                form.querySelectorAll(
                    'input[type="search"], input[type="text"], input[type="hidden"], input[type="date"], input[type="time"]',
                )
            ).forEach((el) => {
                el.value = '';
            });
            selectPeriod('UPCOMING');
            persistFilters();
            krtFilterChips.refresh(form);
            loadResults();
        });
    }

    restoreFilters();
}

initMissions();

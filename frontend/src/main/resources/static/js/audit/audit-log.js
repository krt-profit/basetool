import { krtModal } from '/js/kernel/krt-modal.js';
import { krtCsrf, krtFetch } from '/js/kernel/krt-fetch.js';
import { krtFilterChips } from '/js/kernel/krt-filter-chips.js';

const form = /** @type {HTMLFormElement | null} */ (document.getElementById('audit-filter-form'));
const resultsContainer = document.getElementById('audit-results');
const resetLink = document.getElementById('audit-filter-reset');

if (form && resultsContainer) {
    /** @type {number | null} */
    let debounceTimer = null;

    const buildUrl = function () {
        const data = new FormData(form);
        const params = new URLSearchParams();
        for (const [key, value] of data.entries()) {
            if (value !== '') params.append(key, /** @type {string} */ (value));
        }
        const query = params.toString();
        return `/admin/audit-log${query ? `?${query}` : ''}`;
    };

    /**
     * Swaps the results container for the given list URL, or for the current filter's URL.
     *
     * @param {string | null} [url] the list URL; the filter form's URL when omitted or empty
     */
    const loadResults = function (url) {
        krtFetch.swap({
            url: url || buildUrl(),
            container: resultsContainer,
            history: true,
        });
    };

    const onFilterChange = function () {
        clearTimeout(debounceTimer ?? undefined);
        debounceTimer = setTimeout(() => {
            loadResults();
        }, 300);
    };

    form.addEventListener('submit', (event) => {
        event.preventDefault();
        clearTimeout(debounceTimer ?? undefined);
        loadResults();
    });

    form.querySelectorAll('input, select').forEach((el) => {
        el.addEventListener('input', onFilterChange);
        el.addEventListener('change', onFilterChange);
    });

    if (resetLink) {
        resetLink.addEventListener('click', (event) => {
            event.preventDefault();
            form.querySelectorAll('input').forEach((el) => {
                if (el.name !== 'domain') {
                    el.value = '';
                }
            });
            form.querySelectorAll('select').forEach((el) => {
                el.selectedIndex = 0;
            });
            krtFilterChips.refresh(form);
            loadResults(resetLink.getAttribute('href'));
        });
    }
}

const reloadAuditResults = function () {
    if (!resultsContainer) {
        return;
    }
    let url = '/admin/audit-log';
    if (form) {
        const data = new FormData(form);
        const params = new URLSearchParams();
        for (const [key, value] of data.entries()) {
            if (value !== '') params.append(key, /** @type {string} */ (value));
        }
        const query = params.toString();
        if (query) url += `?${query}`;
    }
    krtFetch.swap({ url, container: resultsContainer, history: true });
};

const EVENT_FILTER_PREF_KEY = 'admin_audit_filter';
const eventSelect = /** @type {HTMLSelectElement | null} */ (
    document.getElementById('audit-event')
);
const domainField = /** @type {HTMLInputElement | null} */ (
    form ? form.querySelector('input[name="domain"]') : null
);
const activeDomain = domainField ? domainField.value : '';

const readEventFilterPrefs = function () {
    try {
        const raw = localStorage.getItem(EVENT_FILTER_PREF_KEY);
        const parsed = raw === null ? null : JSON.parse(raw);
        return parsed && typeof parsed === 'object' ? parsed : {};
    } catch (_e) {
        return {};
    }
};

const persistEventType = function () {
    try {
        if (!eventSelect) {
            return;
        }
        const prefs = readEventFilterPrefs();
        if (eventSelect.value) {
            prefs[activeDomain] = eventSelect.value;
        } else {
            delete prefs[activeDomain];
        }
        localStorage.setItem(EVENT_FILTER_PREF_KEY, JSON.stringify(prefs));
    } catch (_e) {}
};

if (eventSelect && activeDomain && form && resultsContainer) {
    eventSelect.addEventListener('change', persistEventType);
    if (resetLink) {
        resetLink.addEventListener('click', persistEventType);
    }

    if (/[?&]eventType=/.test(window.location.search)) {
        persistEventType();
    } else {
        const saved = readEventFilterPrefs()[activeDomain];
        const known =
            typeof saved === 'string' &&
            Array.prototype.some.call(eventSelect.options, (option) => {
                return option.value === saved;
            });
        if (known && saved !== eventSelect.value) {
            eventSelect.value = saved;
            reloadAuditResults();
        }
    }
}

/**
 * Builds the request headers, with the CSRF header when one is available.
 *
 * @returns {Record<string, string>} the header map
 */
const csrfHeaders = function () {
    /** @type {Record<string, string>} */
    const base = { Accept: 'application/json' };
    try {
        return krtCsrf.headers(base);
    } catch {}
    return base;
};

/**
 * Downloads a report through `krtFetch` and offers it as a file, or calls `onError` when the
 * request fails.
 *
 * @param {string} url the report URL
 * @param {string} filename the file name offered to the user
 * @param {() => void} onError runs when the download failed
 */
const downloadBlob = async function (url, filename, onError) {
    const headers = csrfHeaders();
    delete headers['Content-Type'];
    const tz =
        window.Intl && Intl.DateTimeFormat ? Intl.DateTimeFormat().resolvedOptions().timeZone : '';
    if (tz) {
        headers['X-User-Time-Zone'] = tz;
    }
    let response;
    try {
        response = await krtFetch.get(url, { headers });
    } catch {
        response = null;
    }
    if (!response || !response.ok) {
        onError();
        return;
    }
    const blob = await response.blob();
    const objectUrl = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = objectUrl;
    a.download = filename;
    krtModal.layerRoot().appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(objectUrl);
};

/**
 * Empties every field-error slot of the download form.
 *
 * @param {HTMLFormElement} frm the download form
 */
const clearErrors = function (frm) {
    frm.querySelectorAll('.bank-field-error').forEach((el) => {
        el.textContent = '';
    });
};

/**
 * Writes a message into the form's error slot for `field`, falling back to the global slot.
 *
 * @param {HTMLFormElement} frm the download form
 * @param {string} field the field name, or `_global`
 * @param {string} message the already-localized message
 */
const showError = function (frm, field, message) {
    const slot =
        frm.querySelector(`.bank-field-error[data-error-for="${field}"]`) ||
        frm.querySelector('.bank-field-error[data-error-for="_global"]');
    if (slot) {
        slot.textContent = message;
    }
};

document.addEventListener('submit', (event) => {
    const frm = /** @type {HTMLFormElement | null} */ (
        /** @type {Element} */ (event.target).closest('form.audit-download-form')
    );
    if (!frm) {
        return;
    }
    event.preventDefault();
    clearErrors(frm);
    const from = /** @type {HTMLInputElement | null} */ (frm.querySelector('input[name="from"]'));
    const to = /** @type {HTMLInputElement | null} */ (frm.querySelector('input[name="to"]'));
    const required = frm.getAttribute('data-period-required') || '';
    const downloadError = frm.getAttribute('data-download-error') || required;
    let valid = true;
    if (!from || !from.value) {
        showError(frm, 'from', required);
        valid = false;
    }
    if (!to || !to.value) {
        showError(frm, 'to', required);
        valid = false;
    }
    if (!valid) {
        return;
    }
    const json = event.submitter && event.submitter.getAttribute('data-format') === 'json';
    const endpoint = frm.getAttribute('data-endpoint');
    const fromValue = /** @type {HTMLInputElement} */ (from).value;
    const toValue = /** @type {HTMLInputElement} */ (to).value;
    const url = `${json ? `${endpoint}.json` : endpoint}?from=${encodeURIComponent(
        fromValue,
    )}&to=${encodeURIComponent(toValue)}`;
    const baseName = frm.getAttribute('data-filename') || 'audit.pdf';
    const filename = json ? baseName.replace(/\.pdf$/, '.json') : baseName;
    downloadBlob(url, filename, () => {
        showError(frm, '_global', downloadError);
    });
});

document.addEventListener('submit', (event) => {
    const frm = /** @type {HTMLFormElement | null} */ (
        /** @type {Element} */ (event.target).closest('form.audit-purge-form')
    );
    if (!frm) {
        return;
    }
    event.preventDefault();
    clearErrors(frm);
    const successSlot = frm.querySelector('[data-success-slot]');
    if (successSlot) {
        successSlot.textContent = '';
    }
    const before = /** @type {HTMLInputElement | null} */ (
        frm.querySelector('input[name="before"]')
    );
    const required = frm.getAttribute('data-period-required') || '';
    const deleteError = frm.getAttribute('data-delete-error') || required;
    if (!before || !before.value) {
        showError(frm, 'before', required);
        return;
    }

    const endpoint = frm.getAttribute('data-endpoint');
    const url = `${endpoint}?before=${encodeURIComponent(before.value)}`;
    const successTemplate = frm.getAttribute('data-success-template') || '';
    krtFetch.write({
        url,
        method: 'DELETE',
        submitter: event.submitter,
        toast: false,
        errorMessage: deleteError,
        onSuccess(body) {
            const count = body && typeof body.deletedCount === 'number' ? body.deletedCount : 0;
            if (successSlot) {
                successSlot.textContent = successTemplate
                    ? successTemplate.replace('{0}', String(count))
                    : String(count);
            }
            reloadAuditResults();
        },
    });
});

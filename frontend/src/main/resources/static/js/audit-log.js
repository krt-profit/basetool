(function () {
    'use strict';

    const form = document.getElementById('audit-filter-form');
    const resultsContainer = document.getElementById('audit-results');
    const resetLink = document.getElementById('audit-filter-reset');

    if (form && resultsContainer && window.krtFetch) {
        let debounceTimer = null;

        const buildUrl = function () {
            const data = new FormData(form);
            const params = new URLSearchParams();
            for (const [key, value] of data.entries()) {
                if (value !== '') params.append(key, value);
            }
            const query = params.toString();
            return '/admin/audit-log' + (query ? '?' + query : '');
        };

        const loadResults = function (url) {
            window.krtFetch.swap({
                url: url || buildUrl(),
                container: resultsContainer,
                history: true,
            });
        };

        const onFilterChange = function () {
            clearTimeout(debounceTimer);
            debounceTimer = setTimeout(function () {
                loadResults();
            }, 300);
        };

        form.addEventListener('submit', function (event) {
            event.preventDefault();
            clearTimeout(debounceTimer);
            loadResults();
        });

        form.querySelectorAll('input, select').forEach(function (el) {
            el.addEventListener('input', onFilterChange);
            el.addEventListener('change', onFilterChange);
        });

        if (resetLink) {
            resetLink.addEventListener('click', function (event) {
                event.preventDefault();
                form.querySelectorAll('input').forEach(function (el) {
                    if (el.name !== 'domain') {
                        el.value = '';
                    }
                });
                form.querySelectorAll('select').forEach(function (el) {
                    el.selectedIndex = 0;
                });
                loadResults(resetLink.getAttribute('href'));
            });
        }
    }

    const reloadAuditResults = function () {
        if (!resultsContainer || !window.krtFetch) {
            return;
        }
        let url = '/admin/audit-log';
        if (form) {
            const data = new FormData(form);
            const params = new URLSearchParams();
            for (const [key, value] of data.entries()) {
                if (value !== '') params.append(key, value);
            }
            const query = params.toString();
            if (query) url += '?' + query;
        }
        window.krtFetch.swap({ url, container: resultsContainer, history: true });
    };

    const EVENT_FILTER_PREF_KEY = 'admin_audit_filter';
    const eventSelect = document.getElementById('audit-event');
    const domainField = form ? form.querySelector('input[name="domain"]') : null;
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
            const prefs = readEventFilterPrefs();
            if (eventSelect.value) {
                prefs[activeDomain] = eventSelect.value;
            } else {
                delete prefs[activeDomain];
            }
            localStorage.setItem(EVENT_FILTER_PREF_KEY, JSON.stringify(prefs));
        } catch (_e) {}
    };

    if (eventSelect && activeDomain && form && resultsContainer && window.krtFetch) {
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
                Array.prototype.some.call(eventSelect.options, function (option) {
                    return option.value === saved;
                });
            if (known && saved !== eventSelect.value) {
                eventSelect.value = saved;
                reloadAuditResults();
            }
        }
    }

    const csrfHeaders = function () {
        const base = { Accept: 'application/json' };
        try {
            if (window.krtCsrf && typeof window.krtCsrf.headers === 'function') {
                return window.krtCsrf.headers(base);
            }
        } catch {}
        return base;
    };

    const downloadBlob = async function (url, filename, onError) {
        const headers = csrfHeaders();
        delete headers['Content-Type'];
        const tz =
            window.Intl && Intl.DateTimeFormat
                ? Intl.DateTimeFormat().resolvedOptions().timeZone
                : '';
        if (tz) {
            headers['X-User-Time-Zone'] = tz;
        }
        let response;
        try {
            response = await fetch(url, { method: 'GET', headers });
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
        window.krtModal.layerRoot().appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(objectUrl);
    };

    const clearErrors = function (frm) {
        frm.querySelectorAll('.bank-field-error').forEach(function (el) {
            el.textContent = '';
        });
    };

    const showError = function (frm, field, message) {
        const slot =
            frm.querySelector('.bank-field-error[data-error-for="' + field + '"]') ||
            frm.querySelector('.bank-field-error[data-error-for="_global"]');
        if (slot) {
            slot.textContent = message;
        }
    };

    document.addEventListener('submit', function (event) {
        const frm = event.target.closest('form.audit-download-form');
        if (!frm) {
            return;
        }
        event.preventDefault();
        clearErrors(frm);
        const from = frm.querySelector('input[name="from"]');
        const to = frm.querySelector('input[name="to"]');
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
        const url =
            (json ? endpoint + '.json' : endpoint) +
            '?from=' +
            encodeURIComponent(from.value) +
            '&to=' +
            encodeURIComponent(to.value);
        const baseName = frm.getAttribute('data-filename') || 'audit.pdf';
        const filename = json ? baseName.replace(/\.pdf$/, '.json') : baseName;
        downloadBlob(url, filename, function () {
            showError(frm, '_global', downloadError);
        });
    });

    document.addEventListener('submit', function (event) {
        const frm = event.target.closest('form.audit-purge-form');
        if (!frm) {
            return;
        }
        event.preventDefault();
        clearErrors(frm);
        const successSlot = frm.querySelector('[data-success-slot]');
        if (successSlot) {
            successSlot.textContent = '';
        }
        const before = frm.querySelector('input[name="before"]');
        const required = frm.getAttribute('data-period-required') || '';
        const deleteError = frm.getAttribute('data-delete-error') || required;
        if (!before || !before.value) {
            showError(frm, 'before', required);
            return;
        }
        if (!window.krtFetch || typeof window.krtFetch.write !== 'function') {
            showError(frm, '_global', deleteError);
            return;
        }
        const endpoint = frm.getAttribute('data-endpoint');
        const url = endpoint + '?before=' + encodeURIComponent(before.value);
        const successTemplate = frm.getAttribute('data-success-template') || '';
        window.krtFetch.write({
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
})();

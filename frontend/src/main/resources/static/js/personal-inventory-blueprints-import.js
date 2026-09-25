// @ts-check
(function () {
    'use strict';

    /** @type {HTMLInputElement | null} */
    let fileInput = null;
    /** @type {HTMLElement | null} */
    let modal = null;
    /** @type {HTMLElement | null} */
    let bodyEl = null;
    /** @type {HTMLElement | null} */
    let summaryEl = null;
    /** @type {HTMLButtonElement | null} */
    let applyBtn = null;
    const rowDebounce = new WeakMap();

    function $(id) {
        return document.getElementById(id);
    }

    function i18n() {
        return window.krtBlueprintsImportI18n || {};
    }

    function endpoints() {
        return window.krtBlueprintsEndpoints || {};
    }

    function init() {
        fileInput = /** @type {HTMLInputElement | null} */ ($('krt-bp-import-file'));
        modal = $('krt-bp-import-modal');
        bodyEl = $('krt-bp-import-body');
        summaryEl = $('krt-bp-import-summary');
        applyBtn = /** @type {HTMLButtonElement | null} */ ($('krt-bp-import-apply'));
        if (fileInput) fileInput.addEventListener('change', onFileChosen);
        document.addEventListener('keydown', function (e) {
            if (e.key === 'Escape') closeModal();
        });
        loadHandoff();
    }

    /**
     * Opens the preview modal for a krtFetch outcome whose body is the parsed import preview, or runs
     * onFailure when it is not; an already reported non-2xx outcome is left alone.
     *
     * @param {KrtWriteResult} result the krtFetch outcome
     * @param {() => void} onFailure shows the caller's error toast
     */
    function showPreviewResult(result, onFailure) {
        if (result.ok && result.body && typeof result.body === 'object') {
            renderPreview(result.body);
            openModal();
        } else if (result.ok) {
            onFailure();
        }
    }

    function loadHandoff() {
        const params = new URLSearchParams(window.location.search);
        const id = params.get('handoff');
        if (!id) return;
        params.delete('handoff');
        const cleaned =
            window.location.pathname + (params.toString() ? '?' + params.toString() : '');
        if (window.history && window.history.replaceState) {
            window.history.replaceState(null, '', cleaned);
        }
        if (!window.krtFetch) return;
        const url =
            (endpoints().importStaged || '/personal-inventory/blueprints/import/staged') +
            '?handoff=' +
            encodeURIComponent(id);
        window.krtFetch
            .write({
                method: 'POST',
                url,
                toast: false,
                onError() {
                    handoffNotFound();
                    return true;
                },
                onNetworkError() {
                    handoffNotFound();
                    return true;
                },
            })
            .then(function (result) {
                showPreviewResult(result, handoffNotFound);
            });
    }

    function handoffNotFound() {
        if (window.showFrontendErrorToast) {
            window.showFrontendErrorToast(
                window.krtI18nText(
                    i18n().handoffNotFound || i18n().error,
                    'krtBlueprintsImportI18n.handoffNotFound',
                ),
            );
        }
    }

    function pickFile() {
        if (fileInput) fileInput.click();
    }

    function onFileChosen() {
        if (!fileInput || !fileInput.files || fileInput.files.length === 0) return;
        const file = fileInput.files[0];
        uploadPreview(file);
        fileInput.value = '';
    }

    function uploadPreview(file) {
        if (!window.krtFetch) return;
        const fd = new FormData();
        fd.append('file', file);
        window.krtFetch
            .submitForm({
                url: endpoints().importPreview || '/personal-inventory/blueprints/import/preview',
                method: 'POST',
                formData: fd,
                toast: false,
                onError() {
                    toastError();
                    return true;
                },
                onNetworkError() {
                    toastError();
                    return true;
                },
            })
            .then(function (result) {
                showPreviewResult(result, toastError);
            });
    }

    function renderPreview(preview) {
        if (!bodyEl) return;
        if (summaryEl) {
            summaryEl.textContent =
                window.krtI18nText(i18n().summary, 'krtBlueprintsImportI18n.summary') +
                ': ' +
                (preview.total || 0) +
                ' · ' +
                window.krtI18nText(i18n().groupMatched, 'krtBlueprintsImportI18n.groupMatched') +
                ': ' +
                ((preview.matched || 0) + (preview.matchedByAlias || 0)) +
                ' · ' +
                window.krtI18nText(
                    i18n().groupSuggested,
                    'krtBlueprintsImportI18n.groupSuggested',
                ) +
                ': ' +
                (preview.suggested || 0) +
                ' · ' +
                window.krtI18nText(
                    i18n().groupUnmatched,
                    'krtBlueprintsImportI18n.groupUnmatched',
                ) +
                ': ' +
                (preview.unmatched || 0) +
                ' · ' +
                window.krtI18nText(i18n().groupOwned, 'krtBlueprintsImportI18n.groupOwned') +
                ': ' +
                (preview.alreadyOwned || 0);
        }
        const entries = preview.entries || [];
        const matched = entries.filter(function (e) {
            return e.status === 'MATCHED' || e.status === 'MATCHED_BY_ALIAS';
        });
        const suggested = entries.filter(function (e) {
            return e.status === 'SUGGESTED';
        });
        const unmatched = entries.filter(function (e) {
            return e.status === 'UNMATCHED';
        });
        const owned = entries.filter(function (e) {
            return e.status === 'ALREADY_OWNED';
        });

        let html = '';

        function appendGroup(title, groupEntries, kind) {
            if (!groupEntries || groupEntries.length === 0) return;
            html +=
                '<section class="krt-bp-imp-group">' +
                '<h3 class="krt-bp-imp-group-title">' +
                escapeHtml(title || '') +
                ' (' +
                escapeHtml(groupEntries.length) +
                ')</h3>' +
                '<div class="krt-bp-imp-rows">';
            groupEntries.forEach(function (e) {
                appendRow(e, kind);
            });
            html += '</div>' + '</section>';
        }

        function appendRow(entry, kind) {
            const isOwned = kind === 'owned';
            const suggestions = entry.suggestions || [];
            let resolved = entry.productKey || '';
            if (!resolved && kind === 'suggested' && suggestions.length > 0) {
                resolved = suggestions[0].productKey || '';
            }
            const acquired = entry.suggestedAcquiredAt || '';
            let includeState = '';
            if (isOwned) {
                includeState = ' disabled';
            } else if (kind === 'matched' || (kind === 'suggested' && resolved)) {
                includeState = ' checked';
            }
            html +=
                '<div class="krt-bp-imp-row" data-external="' +
                escapeAttr(entry.externalName) +
                '"' +
                ' data-key="' +
                escapeAttr(resolved) +
                '" data-acquired="' +
                escapeAttr(acquired) +
                '">' +
                '<label class="krt-bp-imp-include-cell">' +
                '<input type="checkbox" class="krt-bp-imp-include"' +
                includeState +
                '>' +
                '</label>' +
                '<span class="krt-bp-imp-external">' +
                escapeHtml(entry.externalName || '') +
                '</span>' +
                '<span class="krt-bp-imp-resolution">';
            if (kind === 'matched') {
                html +=
                    '<span class="krt-bp-imp-product">' +
                    escapeHtml(entry.productName || '') +
                    '</span>';
            } else if (isOwned) {
                html +=
                    '<span class="krt-bp-imp-owned">' +
                    escapeHtml(
                        window.krtI18nText(i18n().ownedLabel, 'krtBlueprintsImportI18n.ownedLabel'),
                    ) +
                    '</span>';
            } else {
                appendSearchControl(entry);
            }
            if (acquired) {
                html +=
                    '<span class="krt-bp-imp-date">' +
                    escapeHtml(String(acquired).substring(0, 10)) +
                    '</span>';
            }
            html += '</span>';
            if (!isOwned) {
                html +=
                    '<input type="text" class="krt-bp-imp-note" maxlength="2000"' +
                    ' placeholder="' +
                    escapeAttr(i18n().notePlaceholder || '') +
                    '">';
            }
            html += '</div>';
        }

        function appendSearchControl(entry) {
            const suggestions = entry.suggestions || [];
            html +=
                '<span class="krt-bp-imp-search-wrap">' +
                '<input type="text" class="krt-bp-imp-search" autocomplete="off"' +
                ' value="' +
                escapeAttr(suggestions.length > 0 ? suggestions[0].productName : '') +
                '" placeholder="' +
                escapeAttr(
                    window.krtI18nText(
                        i18n().searchPlaceholder,
                        'krtBlueprintsImportI18n.searchPlaceholder',
                    ),
                ) +
                '">' +
                '<div class="krt-bp-imp-results krt-pi-typeahead-results" hidden></div>';
            if (suggestions.length > 0) {
                html += '<span class="krt-bp-imp-suggestions">';
                suggestions.forEach(function (s) {
                    html +=
                        '<button type="button" class="krt-bp-imp-suggestion" data-key="' +
                        escapeAttr(s.productKey) +
                        '"' +
                        ' data-name="' +
                        escapeAttr(s.productName) +
                        '">' +
                        escapeHtml(s.productName) +
                        '</button>';
                });
                html += '</span>';
            }
            html += '</span>';
        }

        appendGroup(i18n().groupMatched, matched, 'matched');
        appendGroup(i18n().groupSuggested, suggested, 'suggested');
        appendGroup(i18n().groupUnmatched, unmatched, 'unmatched');
        appendGroup(i18n().groupOwned, owned, 'owned');
        if (!html)
            html =
                '<p class="krt-bp-staging-empty">' +
                escapeHtml(window.krtI18nText(i18n().nothing, 'krtBlueprintsImportI18n.nothing')) +
                '</p>';
        bodyEl.innerHTML = html;
        bindRowSearch();
    }

    function bindRowSearch() {
        if (!bodyEl) return;
        bodyEl.querySelectorAll('.krt-bp-imp-suggestion').forEach(function (chip) {
            chip.addEventListener('click', function () {
                const row = chip.closest('.krt-bp-imp-row');
                setRowProduct(row, chip.getAttribute('data-key'), chip.getAttribute('data-name'));
            });
        });
        bodyEl.querySelectorAll('.krt-bp-imp-search').forEach(function (input) {
            const row = input.closest('.krt-bp-imp-row');
            const results = row
                ? /** @type {HTMLElement | null} */ (row.querySelector('.krt-bp-imp-results'))
                : null;
            input.addEventListener('input', function () {
                if (rowDebounce.has(input)) clearTimeout(rowDebounce.get(input));
                rowDebounce.set(
                    input,
                    setTimeout(function () {
                        rowSearch(input, results, row);
                    }, 250),
                );
            });
            input.addEventListener('blur', function () {
                setTimeout(function () {
                    if (results) results.hidden = true;
                }, 200);
            });
        });
    }

    function rowSearch(input, results, row) {
        if (!results) return;
        const q = input.value || '';
        const url =
            (endpoints().search || '/personal-inventory/blueprints/search') +
            '?q=' +
            encodeURIComponent(q) +
            '&limit=10';
        fetch(url, { credentials: 'same-origin', headers: { Accept: 'application/json' } })
            .then(function (resp) {
                return resp.ok ? resp.json() : [];
            })
            .then(function (items) {
                renderRowResults(results, row, items);
            })
            .catch(function () {
                renderRowResults(results, row, []);
            });
    }

    function renderRowResults(results, row, items) {
        if (!results) return;
        if (!items || items.length === 0) {
            results.innerHTML =
                '<div class="krt-pi-typeahead-empty">' +
                escapeHtml(
                    window.krtI18nText(i18n().noResults, 'krtBlueprintsImportI18n.noResults'),
                ) +
                '</div>';
            results.hidden = false;
            return;
        }
        let html = '';
        items.forEach(function (it) {
            html +=
                '<button type="button" class="krt-pi-typeahead-item krt-bp-imp-hit"' +
                ' data-key="' +
                escapeAttr(it.productKey) +
                '" data-name="' +
                escapeAttr(it.name) +
                '">' +
                '<span class="krt-pi-typeahead-name">' +
                escapeHtml(it.name || '') +
                '</span>' +
                '</button>';
        });
        results.innerHTML = html;
        results.hidden = false;
        results.querySelectorAll('.krt-bp-imp-hit').forEach(function (btn) {
            btn.addEventListener('click', function () {
                setRowProduct(row, btn.getAttribute('data-key'), btn.getAttribute('data-name'));
                results.hidden = true;
            });
        });
    }

    function setRowProduct(row, key, name) {
        if (!row) return;
        row.setAttribute('data-key', key || '');
        const input = row.querySelector('.krt-bp-imp-search');
        if (input) input.value = name || '';
        const include = row.querySelector('.krt-bp-imp-include');
        if (include && key) include.checked = true;
    }

    function selectAll(value) {
        if (!bodyEl) return;
        /** @type {NodeListOf<HTMLInputElement>} */ (
            bodyEl.querySelectorAll('.krt-bp-imp-include')
        ).forEach(function (cb) {
            if (!cb.disabled) {
                const row = cb.closest('.krt-bp-imp-row');
                if (!value || (row && row.getAttribute('data-key'))) cb.checked = value;
            }
        });
    }

    function apply() {
        if (!bodyEl) return;
        const resolutions = [];
        bodyEl.querySelectorAll('.krt-bp-imp-row').forEach(function (row) {
            const include = /** @type {HTMLInputElement | null} */ (
                row.querySelector('.krt-bp-imp-include')
            );
            const key = row.getAttribute('data-key');
            if (!include || !include.checked || !key) return;
            const noteEl = /** @type {HTMLInputElement | null} */ (
                row.querySelector('.krt-bp-imp-note')
            );
            const acquired = row.getAttribute('data-acquired');
            resolutions.push({
                externalName: row.getAttribute('data-external'),
                productKey: key,
                acquiredAt: acquired ? acquired : null,
                note: noteEl && noteEl.value ? noteEl.value : null,
            });
        });
        if (resolutions.length === 0) {
            closeModal();
            return;
        }
        if (applyBtn) applyBtn.disabled = true;
        if (!window.krtFetch) {
            applyError();
            return;
        }
        window.krtFetch
            .write({
                method: 'POST',
                url: endpoints().importApply || '/personal-inventory/blueprints/import/apply',
                payload: resolutions,
                toast: false,
                errorMessage: i18n().error,
                onSuccess(result) {
                    const res = result || {};
                    const msg =
                        window.krtI18nText(i18n().applied, 'krtBlueprintsImportI18n.applied') +
                        ' ' +
                        window.krtI18nText(
                            i18n().addedLabel,
                            'krtBlueprintsImportI18n.addedLabel',
                        ) +
                        ': ' +
                        (res.added || 0) +
                        ', ' +
                        window.krtI18nText(
                            i18n().updatedLabel,
                            'krtBlueprintsImportI18n.updatedLabel',
                        ) +
                        ': ' +
                        (res.acquiredAtUpdated || 0) +
                        ', ' +
                        window.krtI18nText(
                            i18n().aliasesLabel,
                            'krtBlueprintsImportI18n.aliasesLabel',
                        ) +
                        ': ' +
                        (res.aliasesLearned || 0) +
                        ', ' +
                        window.krtI18nText(
                            i18n().skippedLabel,
                            'krtBlueprintsImportI18n.skippedLabel',
                        ) +
                        ': ' +
                        (res.skipped || 0);
                    if (window.showFrontendSuccessToast) {
                        window.showFrontendSuccessToast(msg);
                    }
                    closeModal();
                    window.krtFetch.swap({
                        url: window.location.pathname + window.location.search,
                        container: '#krt-bp-list',
                        fragmentValue: 'list',
                        history: false,
                    });
                },
            })
            .then(function () {
                if (applyBtn) {
                    applyBtn.disabled = false;
                }
            });
    }

    function applyError() {
        if (applyBtn) applyBtn.disabled = false;
        toastError();
    }

    function toastError() {
        if (window.showFrontendErrorToast)
            window.showFrontendErrorToast(
                window.krtI18nText(i18n().error, 'krtBlueprintsImportI18n.error'),
            );
    }

    function openModal() {
        if (modal) window.krtModal.open(modal);
    }

    function closeModal() {
        if (modal) window.krtModal.close(modal);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }

    if (window.krtEvents && typeof window.krtEvents.on === 'function') {
        window.krtEvents.on('click', 'bp-import-pick', pickFile);
        window.krtEvents.on('click', 'bp-import-close', closeModal);
        window.krtEvents.on('click', 'bp-import-apply', apply);
        window.krtEvents.on('click', 'bp-import-selectall', function () {
            selectAll(true);
        });
        window.krtEvents.on('click', 'bp-import-selectnone', function () {
            selectAll(false);
        });
    }
})();

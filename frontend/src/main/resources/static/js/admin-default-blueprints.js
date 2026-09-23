// @ts-check

/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Admin default-blueprints page (REQ-INV-017): blueprint-product type-ahead, staging of the
 * picked products into the add form, and the remove-confirm modal. Both mutations are in place
 * (REQ-FE-001): the add and the remove go through krtFetch.write to the controller's
 * X-Requested-With-routed JSON twins, and on success the list is re-rendered from the server's
 * `rows` fragment with krtFetch.swap — no reload, and no client-side opinion about what the set
 * now contains. The set of keys the type-ahead marks "Bereits Standard" is read back from the
 * swapped rows, so it cannot drift from the list on screen.
 *
 * The row actions are bound by DELEGATION on the document, not per button: every swap replaces
 * the rows wholesale, so a per-button listener would survive exactly one mutation. Where krtFetch
 * did not load, the forms keep their native POST -> redirect fallback.
 */
(function () {
    'use strict';

    const cfg = window.krtDefaultBlueprints || {};
    /** @type {KrtI18nDict} */
    const i18n = cfg.i18n || {};
    /** Product keys already in the default set, re-read from the list after every swap. */
    let defaultKeys = new Set();
    /** @type {Set<string>} */
    const staged = new Set();

    /** @type {HTMLInputElement | null} */
    const searchInput = /** @type {HTMLInputElement | null} */ (
        document.getElementById('krt-dbp-search-input')
    );
    const resultsEl = document.getElementById('krt-dbp-search-results');
    const stagingList = document.getElementById('krt-dbp-staging-list');
    /** @type {HTMLFormElement | null} */
    const addForm = /** @type {HTMLFormElement | null} */ (
        document.getElementById('krt-dbp-add-form')
    );
    /** @type {HTMLButtonElement | null} */
    const addSubmit = /** @type {HTMLButtonElement | null} */ (
        document.getElementById('krt-dbp-add-submit')
    );
    const listHost = document.getElementById('krt-dbp-list-host');
    const deleteModal = document.getElementById('krt-dbp-delete-modal');
    /** @type {HTMLButtonElement | null} */
    const deleteConfirm = /** @type {HTMLButtonElement | null} */ (
        document.getElementById('krt-dbp-delete-confirm')
    );
    const deleteMessage = document.getElementById('krt-dbp-delete-message');
    /**
     * The per-row form the open modal is about (set on open). Its action is server-rendered, so no
     * URL ever flows from the DOM into a form action.
     * @type {HTMLFormElement | null}
     */
    let pendingForm = null;

    /**
     * @param {(value: string) => void} fn
     * @param {number} wait
     * @returns {(value: string) => void}
     */
    function debounce(fn, wait) {
        /** @type {number | undefined} */
        let timer;
        return function (value) {
            window.clearTimeout(timer);
            timer = window.setTimeout(function () {
                fn(value);
            }, wait);
        };
    }

    /**
     * @param {unknown} value
     * @returns {string}
     */
    function str(value) {
        return value == null ? '' : String(value);
    }

    /** @param {string | undefined} message */
    function successToast(message) {
        if (message && typeof window.showFrontendSuccessToast === 'function') {
            window.showFrontendSuccessToast(message);
        }
    }

    /** @param {string | undefined} message */
    function errorToast(message) {
        if (message && typeof window.showFrontendErrorToast === 'function') {
            window.showFrontendErrorToast(message);
        }
    }

    /* ------------------------------------------------------------- the list */

    /** Re-reads the default set from the rendered rows, the only copy the page keeps. */
    function syncDefaultKeys() {
        const keys = new Set();
        if (listHost) {
            listHost.querySelectorAll('tr[data-product-key]').forEach(function (row) {
                const key = row.getAttribute('data-product-key');
                if (key) {
                    keys.add(key);
                }
            });
        }
        defaultKeys = keys;
    }

    /**
     * Re-renders the list in place from the server and re-reads the default set from it.
     * @returns {Promise<void>}
     */
    function refreshList() {
        if (!listHost || !cfg.listUrl) {
            return Promise.resolve();
        }
        return window.krtFetch
            .swap({
                url: cfg.listUrl,
                container: listHost,
                fragmentValue: 'rows',
                errorMessage: i18n.loadError,
            })
            .then(syncDefaultKeys);
    }

    /* ------------------------------------------------------------- type-ahead */

    function hideResults() {
        if (resultsEl) {
            resultsEl.hidden = true;
            resultsEl.replaceChildren();
        }
    }

    /**
     * Builds nodes with the DOM API (textContent) rather than innerHTML, so untrusted product
     * names from the search response can never be reinterpreted as HTML (CodeQL
     * js/xss-through-dom).
     * @param {string | undefined} text
     */
    function renderMessage(text) {
        if (!resultsEl) {
            return;
        }
        const empty = document.createElement('div');
        empty.className = 'krt-pi-typeahead-empty';
        empty.textContent = str(text);
        resultsEl.replaceChildren(empty);
        resultsEl.hidden = false;
    }

    /** @param {ApiDto<'BlueprintProductDto'>[] | null | undefined} items */
    function renderResults(items) {
        if (!resultsEl) {
            return;
        }
        if (!items || items.length === 0) {
            renderMessage(i18n.noResults || 'Keine Treffer');
            return;
        }
        const host = resultsEl;
        host.replaceChildren();
        items.forEach(function (item) {
            const key = str(item.productKey);
            const isDefault = defaultKeys.has(key);
            const button = document.createElement('button');
            button.type = 'button';
            button.className = 'krt-pi-typeahead-item';
            button.disabled = isDefault || staged.has(key);
            button.setAttribute('data-key', key);
            button.setAttribute('data-name', str(item.name));

            const label = document.createElement('span');
            label.textContent = str(item.name);
            if (item.variantCount != null && item.variantCount > 1) {
                const variants = document.createElement('span');
                variants.className = 'krt-pi-typeahead-variants';
                variants.textContent =
                    ' (' + item.variantCount + ' ' + (i18n.variants || 'Varianten') + ')';
                label.appendChild(variants);
            }
            button.appendChild(label);

            const tag = document.createElement('span');
            tag.className = 'krt-pi-typeahead-tag';
            if (isDefault) {
                tag.textContent = ' · ' + (i18n.alreadyDefault || 'Bereits Standard');
            }
            button.appendChild(tag);

            host.appendChild(button);
        });
        host.hidden = false;
    }

    /** @param {string} q */
    function runSearch(q) {
        if (!cfg.searchUrl) {
            return;
        }
        if (!q || q.trim().length === 0) {
            hideResults();
            return;
        }
        renderMessage(i18n.searching || 'Suche...');
        const url = cfg.searchUrl + '?q=' + encodeURIComponent(q.trim()) + '&limit=25';
        window
            .fetch(url, { headers: { Accept: 'application/json' }, credentials: 'same-origin' })
            .then(function (resp) {
                return resp.ok ? resp.json() : [];
            })
            .then(renderResults)
            .catch(function () {
                renderMessage(i18n.noResults || 'Keine Treffer');
            });
    }

    /* ------------------------------------------------------------- staging */

    function refreshStagingEmptyState() {
        /** @type {HTMLElement | null} */
        const empty = stagingList ? stagingList.querySelector('.krt-bp-staging-empty') : null;
        if (empty) {
            empty.style.display = staged.size === 0 ? '' : 'none';
        }
        if (addSubmit) {
            addSubmit.disabled = staged.size === 0;
        }
    }

    /** @param {string} key */
    function unstageProduct(key) {
        staged.delete(key);
        if (stagingList) {
            stagingList.querySelectorAll('.krt-bp-chip').forEach(function (chip) {
                if (chip.getAttribute('data-key') === key) {
                    chip.remove();
                }
            });
        }
    }

    /**
     * @param {string | null} key
     * @param {string | null} name
     */
    function stageProduct(key, name) {
        if (!key || staged.has(key) || defaultKeys.has(key) || !stagingList) {
            return;
        }
        const stagedKey = key;
        staged.add(stagedKey);
        const chip = document.createElement('span');
        chip.className = 'krt-bp-chip';
        chip.setAttribute('data-key', stagedKey);

        const label = document.createElement('span');
        label.className = 'krt-bp-chip-label';
        label.textContent = str(name || stagedKey);
        chip.appendChild(label);

        const removeBtn = document.createElement('button');
        removeBtn.type = 'button';
        removeBtn.className = 'krt-bp-chip-remove';
        removeBtn.setAttribute('aria-label', i18n.chipRemove || '');
        removeBtn.textContent = '×';
        removeBtn.addEventListener('click', function () {
            unstageProduct(stagedKey);
            refreshStagingEmptyState();
        });
        chip.appendChild(removeBtn);

        // Carried for the native-submit fallback; the in-place add sends the staged set as JSON.
        const hidden = document.createElement('input');
        hidden.type = 'hidden';
        hidden.name = 'productKeys';
        hidden.value = stagedKey;
        chip.appendChild(hidden);

        stagingList.appendChild(chip);
        refreshStagingEmptyState();
    }

    /* ------------------------------------------------------------- add */

    /**
     * Applies the add's per-key outcome: every key that did not fail leaves the staging (added, or
     * already a default), the failed ones stay staged for a retry, and the list is re-rendered.
     * @param {{ added?: number, skipped?: number, failedKeys?: string[] } | null} result
     * @returns {Promise<void>}
     */
    function applyAddResult(result) {
        const failed = new Set((result && result.failedKeys) || []);
        Array.from(staged).forEach(function (key) {
            if (!failed.has(key)) {
                unstageProduct(key);
            }
        });
        refreshStagingEmptyState();
        if (failed.size > 0) {
            errorToast(i18n.addError);
        } else if (result && result.added && result.added > 0) {
            successToast(i18n.added);
        } else {
            successToast(i18n.noneAdded);
        }
        return refreshList();
    }

    if (addForm) {
        const form = addForm;
        form.addEventListener('submit', function (event) {
            if (!window.krtFetch) {
                return;
            }
            event.preventDefault();
            if (staged.size === 0) {
                return;
            }
            window.krtFetch
                .write({
                    method: 'POST',
                    url: form.getAttribute('action') || '',
                    payload: { productKeys: Array.from(staged) },
                    toast: false,
                    errorMessage: i18n.addError,
                    onSuccess: applyAddResult,
                })
                .finally(function () {
                    // krtFetch re-enables the submit button when the write settles; the staging
                    // decides whether it stays enabled.
                    refreshStagingEmptyState();
                });
        });
    }

    /* ------------------------------------------------------------- remove modal */

    /**
     * @param {string | null} formId
     * @param {string | null} name
     */
    function openDeleteModal(formId, name) {
        if (!deleteModal) {
            return;
        }
        pendingForm = formId
            ? /** @type {HTMLFormElement | null} */ (document.getElementById(formId))
            : null;
        if (deleteMessage) {
            const base = i18n.removeBody || 'Wirklich entfernen?';
            deleteMessage.textContent = name ? base + ' (' + name + ')' : base;
        }
        deleteModal.style.display = 'flex';
    }

    function closeDeleteModal() {
        if (deleteModal) {
            deleteModal.style.display = 'none';
        }
    }

    if (deleteConfirm) {
        const confirmBtn = deleteConfirm;
        confirmBtn.addEventListener('click', function () {
            const form = pendingForm;
            if (!form) {
                return;
            }
            if (!window.krtFetch) {
                form.requestSubmit();
                return;
            }
            window.krtFetch.write({
                method: 'POST',
                url: form.getAttribute('action') || '',
                submitter: confirmBtn,
                successMessage: i18n.removed,
                errorMessage: i18n.removeError,
                onSuccess() {
                    pendingForm = null;
                    closeDeleteModal();
                    return refreshList();
                },
            });
        });
    }

    /* ------------------------------------------------------------- wiring */

    if (searchInput) {
        const input = searchInput;
        const debouncedSearch = debounce(runSearch, 200);
        input.addEventListener('input', function () {
            debouncedSearch(input.value);
        });
    }

    if (resultsEl) {
        resultsEl.addEventListener('click', function (e) {
            const target = /** @type {Element | null} */ (e.target);
            /** @type {HTMLButtonElement | null} */
            const item = target ? target.closest('.krt-pi-typeahead-item') : null;
            if (!item || item.disabled) {
                return;
            }
            stageProduct(item.getAttribute('data-key'), item.getAttribute('data-name'));
            hideResults();
            if (searchInput) {
                searchInput.value = '';
                searchInput.focus();
            }
        });
    }

    document.addEventListener('click', function (e) {
        const target = /** @type {Element | null} */ (e.target);
        if (!target || typeof target.closest !== 'function') {
            return;
        }
        const removeBtn = target.closest('[data-trigger="dbp-open-delete"]');
        if (removeBtn) {
            openDeleteModal(
                removeBtn.getAttribute('data-form'),
                removeBtn.getAttribute('data-name'),
            );
            return;
        }
        if (target.closest('[data-trigger="dbp-close-delete"]')) {
            closeDeleteModal();
            return;
        }
        if (target === deleteModal) {
            closeDeleteModal();
        }
    });

    document.addEventListener('keydown', function (e) {
        if (e.key === 'Escape') {
            closeDeleteModal();
            hideResults();
        }
    });

    document.addEventListener('click', function (e) {
        const target = /** @type {Element | null} */ (e.target);
        if (
            resultsEl &&
            !resultsEl.hidden &&
            target &&
            typeof target.closest === 'function' &&
            !target.closest('.krt-bp-search')
        ) {
            hideResults();
        }
    });

    syncDefaultKeys();
    refreshStagingEmptyState();
})();

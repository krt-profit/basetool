(function () {
    'use strict';

    const SEARCH_LIMIT = 2000;

    let modal = null;
    let deleteModal = null;
    let form = null;
    let deleteForm = null;
    let titleEl = null;
    let searchInput = null;
    let resultsEl = null;
    let hiddenUexId = null;
    let hiddenLocationType = null;
    let debounceTimer = null;

    function $(id) {
        return document.getElementById(id);
    }

    function init() {
        modal = $('krt-pi-modal');
        deleteModal = $('krt-pi-delete-modal');
        form = $('krt-pi-form');
        deleteForm = $('krt-pi-delete-form');
        titleEl = $('krt-pi-modal-title');
        searchInput = $('krt-pi-location-search');
        resultsEl = $('krt-pi-location-results');
        hiddenUexId = $('krt-pi-location-uex-id');
        hiddenLocationType = $('krt-pi-location-type');

        if (searchInput) {
            searchInput.addEventListener('input', onSearchInput);
            searchInput.addEventListener('focus', onSearchInput);
            document.addEventListener('click', function (e) {
                if (!resultsEl) return;
                if (e.target !== searchInput && !resultsEl.contains(e.target)) {
                    resultsEl.hidden = true;
                }
            });
        }
        document.addEventListener('keydown', function (e) {
            if (e.key === 'Escape') {
                closeModal();
                closeDelete();
            }
        });
        wireFilterSwap();
        wireWriteSubmits();
        wireAdminMemberPersistence();
    }

    const ADMIN_USER_PREF_KEY = 'admin_personal_inventory_user';

    function wireAdminMemberPersistence() {
        if (!document.querySelector('form.krt-pi-userform [name="userSub"]')) {
            return;
        }
        function persistMember(value) {
            try {
                if (value) {
                    localStorage.setItem(ADMIN_USER_PREF_KEY, JSON.stringify({ userSub: value }));
                } else {
                    localStorage.removeItem(ADMIN_USER_PREF_KEY);
                }
            } catch (_e) {}
        }
        document.addEventListener('change', function (e) {
            const sel = e.target;
            if (sel.matches && sel.matches('form.krt-pi-userform [name="userSub"]')) {
                persistMember(sel.value);
            }
        });
        const params = new URLSearchParams(window.location.search);
        if (params.has('userSub')) {
            persistMember(params.get('userSub'));
            return;
        }
        let saved;
        try {
            const raw = localStorage.getItem(ADMIN_USER_PREF_KEY);
            const parsed = raw === null ? null : JSON.parse(raw);
            saved = parsed && typeof parsed.userSub === 'string' ? parsed.userSub : null;
        } catch (_e) {
            saved = null;
        }
        if (saved) {
            window.location.replace(
                window.location.pathname + '?userSub=' + encodeURIComponent(saved),
            );
        }
    }

    function wireWriteSubmits() {
        if (!window.krtFetch) {
            return;
        }
        document.addEventListener('submit', function (e) {
            if (e.target === form) {
                e.preventDefault();
                submitItemForm();
            } else if (e.target === deleteForm) {
                e.preventDefault();
                submitDeleteForm();
            }
        });
    }

    function conflictObj() {
        const i18n = window.krtPersonalInventoryI18n || {};
        return {
            title: i18n.conflictTitle,
            reloadDetailFallback: i18n.conflictDetail,
            reloadLabel: i18n.conflictReload,
            dismissLabel: i18n.conflictDismiss,
            reloadQuestion: i18n.conflictQuestion,
        };
    }

    function reswapResults() {
        window.krtFetch.swap({
            url: window.location.pathname + window.location.search,
            container: '#pi-results',
            history: false,
        });
    }

    function fieldValue(name) {
        if (!form) {
            return '';
        }
        const el = form.querySelector('[name="' + name + '"]');
        return el ? el.value : '';
    }

    function toIntOrNull(raw) {
        if (raw == null || String(raw).trim() === '') {
            return null;
        }
        const n = parseInt(raw, 10);
        return isNaN(n) ? null : n;
    }

    function submitItemForm() {
        if (!form) {
            return;
        }
        const i18n = window.krtPersonalInventoryI18n || {};
        const action = form.getAttribute('action') || form.action;
        const isUpdate = /\/update$/.test(action);
        const uexId = hiddenUexId ? hiddenUexId.value : '';
        if (!uexId) {
            if (window.showFrontendErrorToast) {
                window.showFrontendErrorToast(
                    window.krtI18nText(
                        i18n.locationRequired || i18n.errorCreate,
                        'krtPersonalInventoryI18n.locationRequired',
                    ),
                );
            }
            return;
        }
        const payload = {
            name: fieldValue('name'),
            note: fieldValue('note'),
            quantity: toIntOrNull(fieldValue('quantity')),
            locationUexId: toIntOrNull(uexId),
            locationType: hiddenLocationType ? hiddenLocationType.value : null,
        };
        if (isUpdate) {
            payload.version = toIntOrNull(fieldValue('version'));
        }
        const submitBtn = form.querySelector('button[type="submit"]');
        if (submitBtn) {
            submitBtn.disabled = true;
        }
        window.krtFetch
            .write({
                method: 'POST',
                url: window.safeSameOriginUrl(action, action),
                payload,
                successMessage: isUpdate ? i18n.updated : i18n.created,
                errorMessage: isUpdate ? i18n.errorUpdate : i18n.errorCreate,
                conflict: conflictObj(),
                onSuccess() {
                    closeModal();
                    reswapResults();
                },
            })
            .then(function () {
                if (submitBtn) {
                    submitBtn.disabled = false;
                }
            });
    }

    function submitDeleteForm() {
        if (!deleteForm) {
            return;
        }
        const i18n = window.krtPersonalInventoryI18n || {};
        const action = deleteForm.getAttribute('action') || deleteForm.action;
        const btn = deleteForm.querySelector('button[type="submit"]');
        if (btn) {
            btn.disabled = true;
        }
        window.krtFetch
            .write({
                method: 'POST',
                url: window.safeSameOriginUrl(action, action),
                successMessage: i18n.deleted,
                errorMessage: i18n.errorDelete,
                conflict: conflictObj(),
                onSuccess() {
                    closeDelete();
                    reswapResults();
                },
            })
            .then(function () {
                if (btn) {
                    btn.disabled = false;
                }
            });
    }

    function wireFilterSwap() {
        if (!window.krtFetch || !document.getElementById('pi-results')) {
            return;
        }
        let timer = null;
        function swapFromForm(formEl) {
            if (!formEl) {
                return;
            }
            const params = new URLSearchParams(new FormData(formEl)).toString();
            const url = formEl.getAttribute('action') + (params ? '?' + params : '');
            window.krtFetch.swap({ url, container: '#pi-results', history: true });
        }
        document.addEventListener('submit', function (e) {
            if (!e.target.classList || !e.target.classList.contains('krt-pi-filter')) {
                return;
            }
            e.preventDefault();
            if (timer) {
                clearTimeout(timer);
                timer = null;
            }
            swapFromForm(e.target);
        });
        document.addEventListener('input', function (e) {
            const t = e.target;
            if (!t.matches || !t.matches('form.krt-pi-filter input[type="search"]')) {
                return;
            }
            const formEl = t.form;
            if (timer) {
                clearTimeout(timer);
            }
            timer = setTimeout(function () {
                timer = null;
                swapFromForm(formEl);
            }, 300);
        });
        document.addEventListener('change', function (e) {
            const sel = e.target;
            if (!sel.matches || !sel.matches('form.krt-pi-userform [name="userSub"]')) {
                return;
            }
            swapFromForm(sel.form);
        });
        document.addEventListener('krt:swapped', function (e) {
            const c = e.detail && e.detail.container;
            if (c && c.id === 'pi-results') {
                syncCounts(c);
            }
        });
    }

    function syncCounts(root) {
        const meta = (root || document).querySelector('#pi-total-meta');
        if (!meta) {
            return;
        }
        const total = meta.getAttribute('data-total') || '0';
        const tabCount = document.querySelector('.tab-nav .tab.active .tab-count');
        if (tabCount) {
            tabCount.textContent = total;
        }
        const subtitle = document.querySelector('.krt-personal-inventory-header .krt-subtitle');
        const i18n = window.krtPersonalInventoryI18n || {};
        if (subtitle && i18n.factsCount) {
            subtitle.textContent = total + ' ' + i18n.factsCount;
        }
    }

    function openCreate(btn) {
        if (!modal || !form) return;
        const i18n = window.krtPersonalInventoryI18n || {};
        if (titleEl && i18n.createTitle) titleEl.textContent = i18n.createTitle;
        form.action = window.safeSameOriginUrl(btn.getAttribute('data-action'), form.action);
        clearForm();
        window.krtModal.open(modal);
    }

    function openEdit(btn) {
        if (!modal || !form) return;
        const i18n = window.krtPersonalInventoryI18n || {};
        if (titleEl && i18n.editTitle) titleEl.textContent = i18n.editTitle;
        form.action = window.safeSameOriginUrl(btn.getAttribute('data-action'), form.action);
        setField('id', btn.getAttribute('data-id'));
        setField('version', btn.getAttribute('data-version'));
        setField('name', btn.getAttribute('data-name'));
        setField('note', btn.getAttribute('data-note'));
        setField('quantity', btn.getAttribute('data-quantity'));
        if (hiddenUexId) hiddenUexId.value = btn.getAttribute('data-location-uex-id') || '';
        if (hiddenLocationType)
            hiddenLocationType.value = btn.getAttribute('data-location-type') || '';
        if (searchInput) searchInput.value = btn.getAttribute('data-location-name') || '';
        window.krtModal.open(modal);
    }

    function closeModal() {
        if (modal) window.krtModal.close(modal);
    }

    function openDelete(btn) {
        if (!deleteModal || !deleteForm) return;
        deleteForm.action = window.safeSameOriginUrl(
            btn.getAttribute('data-action'),
            deleteForm.action,
        );
        const msgEl = $('krt-pi-delete-message');
        const name = btn.getAttribute('data-name');
        if (msgEl && name) {
            msgEl.textContent =
                window.krtPersonalInventoryI18n && window.krtPersonalInventoryI18n.confirmBody
                    ? window.krtPersonalInventoryI18n.confirmBody + ' (' + name + ')'
                    : msgEl.textContent;
        }
        window.krtModal.open(deleteModal);
    }

    function closeDelete() {
        if (deleteModal) window.krtModal.close(deleteModal);
    }

    function setField(name, value) {
        if (!form) return;
        const el = form.querySelector('[name="' + name + '"]');
        if (el) el.value = value == null ? '' : value;
    }

    function clearForm() {
        if (!form) return;
        ['id', 'version', 'name', 'note', 'quantity'].forEach(function (n) {
            setField(n, '');
        });
        if (hiddenUexId) hiddenUexId.value = '';
        if (hiddenLocationType) hiddenLocationType.value = '';
        if (searchInput) searchInput.value = '';
    }

    function onSearchInput() {
        if (debounceTimer) clearTimeout(debounceTimer);
        debounceTimer = setTimeout(runSearch, 250);
    }

    function runSearch() {
        if (!searchInput || !resultsEl) return;
        const q = searchInput.value || '';
        const endpoints = window.krtPersonalInventoryEndpoints || {};
        const url =
            (endpoints.uexSearch || '/personal-inventory/uex-search') +
            '?q=' +
            encodeURIComponent(q) +
            '&limit=' +
            SEARCH_LIMIT;
        resultsEl.hidden = false;
        resultsEl.innerHTML =
            '<div class="krt-pi-typeahead-loading">' +
            escapeHtml(
                window.krtI18nText(
                    (window.krtPersonalInventoryI18n || {}).searching,
                    'krtPersonalInventoryI18n.searching',
                ),
            ) +
            '</div>';
        fetch(url, { credentials: 'same-origin', headers: { Accept: 'application/json' } })
            .then(function (resp) {
                return resp.ok ? resp.json() : [];
            })
            .then(renderResults)
            .catch(function () {
                renderResults([]);
            });
    }

    function renderResults(items) {
        if (!resultsEl) return;
        if (!items || items.length === 0) {
            resultsEl.innerHTML =
                '<div class="krt-pi-typeahead-empty">' +
                escapeHtml(
                    window.krtI18nText(
                        (window.krtPersonalInventoryI18n || {}).noResults,
                        'krtPersonalInventoryI18n.noResults',
                    ),
                ) +
                '</div>';
            return;
        }
        let html = '';
        items.forEach(function (it) {
            const typeClass = it.type === 'CITY' ? 'krt-pi-loc-city' : 'krt-pi-loc-station';
            html +=
                '<button type="button" class="krt-pi-typeahead-item" ' +
                'data-uex-id="' +
                escapeAttr(it.uexId) +
                '" ' +
                'data-type="' +
                escapeAttr(it.type) +
                '" ' +
                'data-name="' +
                escapeAttr(it.name) +
                '">' +
                '<span class="krt-pi-location-marker ' +
                escapeAttr(typeClass) +
                '"></span>' +
                '<span class="krt-pi-typeahead-name">' +
                escapeHtml(it.name || '') +
                '</span>' +
                '<span class="krt-pi-typeahead-meta">' +
                escapeHtml(it.parentName || '');
            if (it.starSystemName) {
                html += ' / ' + escapeHtml(it.starSystemName);
            }
            html += '</span></button>';
        });
        if (items.length >= SEARCH_LIMIT) {
            html +=
                '<div class="krt-pi-typeahead-more">' +
                escapeHtml(
                    window.krtI18nText(
                        (window.krtPersonalInventoryI18n || {}).moreResults,
                        'krtPersonalInventoryI18n.moreResults',
                    ),
                ) +
                '</div>';
        }
        resultsEl.innerHTML = html;
        resultsEl.querySelectorAll('.krt-pi-typeahead-item').forEach(function (btn) {
            btn.addEventListener('click', function () {
                if (hiddenUexId) hiddenUexId.value = btn.getAttribute('data-uex-id') || '';
                if (hiddenLocationType)
                    hiddenLocationType.value = btn.getAttribute('data-type') || '';
                if (searchInput) searchInput.value = btn.getAttribute('data-name') || '';
                resultsEl.hidden = true;
            });
        });
    }

    /**
     * Restricts the quantity input to a positive integer by stripping non-digit characters and
     * clamping it to at least 1; no upper bound is enforced.
     */
    function sanitizeQuantity(input) {
        if (!input) return;
        const raw = input.value || '';
        let digitsOnly = raw.replace(/[^0-9]/g, '');
        if (digitsOnly === '') {
            if (raw !== '') input.value = '';
            return;
        }
        digitsOnly = digitsOnly.replace(/^0+/, '');
        let n = parseInt(digitsOnly, 10);
        if (isNaN(n) || n < 1) n = 1;
        input.value = String(n);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }

    if (window.krtEvents && typeof window.krtEvents.on === 'function') {
        window.krtEvents.on('click', 'pi-open-create', openCreate);
        window.krtEvents.on('click', 'pi-open-edit', openEdit);
        window.krtEvents.on('click', 'pi-open-delete', openDelete);
        window.krtEvents.on('click', 'pi-close-modal', closeModal);
        window.krtEvents.on('click', 'pi-close-delete', closeDelete);
        window.krtEvents.on('input', 'pi-sanitize-quantity', sanitizeQuantity);
    }
})();

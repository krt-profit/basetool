/*
 * Personal Inventory frontend logic.
 *
 * Responsibilities:
 *  - Open/close create/edit modal, prefill fields from row dataset.
 *  - Open/close KRT-styled delete confirmation modal (no native confirm()).
 *  - Provide a debounced typeahead against /personal-inventory/uex-search,
 *    rendering combined CITY + SPACE_STATION results and writing the chosen
 *    UEX id and type into the hidden form fields.
 *
 * Wiring: this module uses the global `krtEvents.on` event-delegation helper
 * (see static/js/event-delegation.js) to bind logical actions declared in the
 * template as `data-trigger="pi-…"` attributes — no inline `onclick="…"` is
 * required, which is what lets the CSP forbid `script-src-attr 'unsafe-inline'`.
 *
 * The module purposely avoids any inline alert()/confirm()/prompt() calls
 * (KRT corporate design rule) and reads its translatable strings from
 * window.krtPersonalInventoryI18n which the Thymeleaf template populates.
 */
(function () {
    'use strict';

    // Result cap requested from /personal-inventory/uex-search (mirrors the backend clamp). When a
    // response fills the cap — realistically only the empty-query "browse everything" mode — the
    // list is (potentially) truncated and renderResults appends a refine-your-search hint instead
    // of pretending the catalog ends there (REQ-FE-016 no-silent-truncation rule, ADR-0100).
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
        // ESC closes any open modal.
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

    // ------------------------------------------------ admin member persistence

    // Admin page only (REQ-UI-017): the selected member is kept per browser so reopening
    // /admin/personal-inventory returns to the last-inspected member. An explicit ?userSub= in the
    // address bar (kept in sync by the history:true member swap below) wins and is re-persisted; a
    // BARE load with a saved member does a one-time location.replace to ?userSub=<saved> — loop-
    // safe because the target URL carries the param — so the SERVER seeds the remote-users
    // combobox with the member's display label (which a client-side restore could not
    // reconstruct). The q text filter is deliberately NOT persisted. Guarded on the member picker
    // form, which only the admin page renders, so the user page is untouched.
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
                    // Cleared selection = back to the server default (the bare picker).
                    localStorage.removeItem(ADMIN_USER_PREF_KEY);
                }
            } catch (_e) {
                /* storage unavailable */
            }
        }
        // Persist every member change right away; the in-place swap of #pi-results is wired
        // separately (wireFilterSwap above) and both listeners see the same change event.
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

    // --------------------------------------------------------- in-place writes

    // The create/edit modal form and the delete form submit through krtFetch (REQ-FE-001/002)
    // instead of a classic POST→redirect: on success the modal closes and the #pi-results list is
    // re-rendered in place via the existing fragment swap, preserving the active filter. The header
    // count resyncs from the swapped #pi-total-meta marker (see syncCounts). The classic POST
    // handlers stay the no-JS fallback (the AJAX twins are gated on X-Requested-With, which krtFetch
    // always sends). Listeners are delegated on document; the forms live outside #pi-results, so a
    // filter swap never detaches them.
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

    // Re-render only the #pi-results list for the active filter (mirrors the address-bar query the
    // filter swap keeps in sync), without touching the URL again.
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
                window.showFrontendErrorToast(i18n.locationRequired || i18n.errorCreate || 'Error');
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
                payload: payload,
                successMessage: isUpdate ? i18n.updated : i18n.created,
                errorMessage: isUpdate ? i18n.errorUpdate : i18n.errorCreate,
                conflict: conflictObj(),
                onSuccess: function () {
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
                onSuccess: function () {
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

    // ----------------------------------------------------------- filter swap

    // The search filter (and, on the admin variant, the member <select>) re-render only the
    // #pi-results block in place (REQ-FE-002) instead of reloading the page. Listeners are
    // DELEGATED on document so they survive the filter form / table being re-rendered inside the
    // swapped fragment — the admin page nests the filter form INSIDE the swap target, so a direct
    // binding would be lost after the first swap. The row edit/delete buttons are krtEvents-
    // delegated and the modals live outside the container, so swapped-in rows stay live with no
    // re-init. After each swap the header counts are resynced from the hidden total the fragment
    // carries (user page only). Without krtFetch (JS disabled) the GET forms reload as before.
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
            window.krtFetch.swap({ url: url, container: '#pi-results', history: true });
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
        // Admin variant: selecting a member swaps in their inventory in place, replacing the
        // legacy data-trigger="submit-form" full reload (removed from that <select>).
        document.addEventListener('change', function (e) {
            const sel = e.target;
            // Match the member control by name (not tag): the global searchable-combobox enhancer
            // replaces the <select> with a hidden <input name="userSub">, which is what dispatches
            // the change once enhanced; the plain <select> still matches before enhancement / no-JS.
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

    // Mirrors the freshly filtered total (carried by the #pi-total-meta marker inside the swapped
    // fragment) into the header subtitle ("<n> Eintraege") and the active tab's count badge, so the
    // counts never drift from the visible list after a filter swap.
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
        let i18n = window.krtPersonalInventoryI18n || {};
        if (titleEl && i18n.createTitle) titleEl.textContent = i18n.createTitle;
        form.action = window.safeSameOriginUrl(btn.getAttribute('data-action'), form.action);
        clearForm();
        modal.style.display = 'flex';
    }

    function openEdit(btn) {
        if (!modal || !form) return;
        let i18n = window.krtPersonalInventoryI18n || {};
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
        modal.style.display = 'flex';
    }

    function closeModal() {
        if (modal) modal.style.display = 'none';
    }

    function openDelete(btn) {
        if (!deleteModal || !deleteForm) return;
        deleteForm.action = window.safeSameOriginUrl(
            btn.getAttribute('data-action'),
            deleteForm.action,
        );
        let msgEl = $('krt-pi-delete-message');
        let name = btn.getAttribute('data-name');
        if (msgEl && name) {
            msgEl.textContent =
                window.krtPersonalInventoryI18n && window.krtPersonalInventoryI18n.confirmBody
                    ? window.krtPersonalInventoryI18n.confirmBody + ' (' + name + ')'
                    : msgEl.textContent;
        }
        deleteModal.style.display = 'flex';
    }

    function closeDelete() {
        if (deleteModal) deleteModal.style.display = 'none';
    }

    function setField(name, value) {
        if (!form) return;
        let el = form.querySelector('[name="' + name + '"]');
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
        let q = searchInput.value || '';
        let endpoints = window.krtPersonalInventoryEndpoints || {};
        let url =
            (endpoints.uexSearch || '/personal-inventory/uex-search') +
            '?q=' +
            encodeURIComponent(q) +
            '&limit=' +
            SEARCH_LIMIT;
        resultsEl.hidden = false;
        resultsEl.innerHTML =
            '<div class="krt-pi-typeahead-loading">' +
            escapeHtml((window.krtPersonalInventoryI18n || {}).searching || 'Suche...') +
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
                escapeHtml((window.krtPersonalInventoryI18n || {}).noResults || 'Keine Treffer') +
                '</div>';
            return;
        }
        let html = '';
        items.forEach(function (it) {
            let typeClass = it.type === 'CITY' ? 'krt-pi-loc-city' : 'krt-pi-loc-station';
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
                typeClass +
                '"></span>' +
                '<span class="krt-pi-typeahead-name">' +
                escapeHtml(it.name || '') +
                '</span>' +
                '<span class="krt-pi-typeahead-meta">' +
                escapeHtml(it.parentName || '') +
                (it.starSystemName ? ' / ' + escapeHtml(it.starSystemName) : '') +
                '</span>' +
                '</button>';
        });
        if (items.length >= SEARCH_LIMIT) {
            // The response filled the requested cap, so more locations likely exist beyond it —
            // say so instead of silently ending the list (REQ-FE-016). Typing any query narrows
            // the result far below the cap, so every location stays reachable.
            html +=
                '<div class="krt-pi-typeahead-more">' +
                escapeHtml(
                    (window.krtPersonalInventoryI18n || {}).moreResults ||
                        'Weitere Treffer vorhanden - Suche verfeinern',
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
     * Sanitizes the quantity input to ensure only positive integers (>= 1).
     * Strips any non-digit characters (e.g. '-', '.', 'e') that some browsers
     * still allow in <input type="number"> and clamps the lower bound to 1.
     * The upper bound is intentionally not enforced here; backend @Min(1)
     * remains the authoritative validator.
     */
    function sanitizeQuantity(input) {
        if (!input) return;
        let raw = input.value || '';
        let digitsOnly = raw.replace(/[^0-9]/g, '');
        if (digitsOnly === '') {
            if (raw !== '') input.value = '';
            return;
        }
        // Strip leading zeros (but keep a single zero if user is mid-typing).
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

    // Delegated event bindings via the global event-delegation helper. The
    // matching `data-trigger="pi-…"` attributes are set on the buttons / inputs
    // in personal-inventory.html. Using delegated listeners (rather than
    // querySelectorAll + addEventListener per node) keeps the wiring working for
    // table rows that are re-rendered by Thymeleaf on form errors without
    // requiring a re-bind. The legacy `window.krtPersonalInventory.x(this)`
    // global is intentionally removed — every template entry point now goes
    // through `data-trigger` so the CSP can later drop
    // `script-src-attr 'unsafe-inline'`.
    if (window.krtEvents && typeof window.krtEvents.on === 'function') {
        window.krtEvents.on('click', 'pi-open-create', openCreate);
        window.krtEvents.on('click', 'pi-open-edit', openEdit);
        window.krtEvents.on('click', 'pi-open-delete', openDelete);
        window.krtEvents.on('click', 'pi-close-modal', closeModal);
        window.krtEvents.on('click', 'pi-close-delete', closeDelete);
        window.krtEvents.on('input', 'pi-sanitize-quantity', sanitizeQuantity);
    }
})();

/*
 * Personal Inventory — Blueprints sub-page logic (#327, Phase 5).
 *
 * Responsibilities:
 *  - Debounced type-ahead against /personal-inventory/blueprints/search, rendering
 *    products with an "already owned" marker (owned hits are not stageable).
 *  - Multi-select staging: tick several hits into a chip area, keep searching,
 *    then "Add selected" -> POST the staged keys to the batch proxy, toast the
 *    added/skipped counts, and reload to show the new owned rows.
 *  - Open/close KRT-styled edit-note and remove modals (no native confirm()/alert()).
 *
 * Wiring: uses the global `krtEvents.on` delegation helper and `data-trigger="bp-…"`
 * attributes (CSP-nonce-safe, no inline onclick). Translatable strings come from
 * window.krtBlueprintsI18n; endpoints from window.krtBlueprintsEndpoints (the
 * per-row URLs carry an `ID_PLACEHOLDER` placeholder the module substitutes).
 */
(function () {
    'use strict';

    let searchInput = null;
    let resultsEl = null;
    let stagingListEl = null;
    let addSelectedBtn = null;
    let editModal = null;
    let editForm = null;
    let deleteModal = null;
    let deleteForm = null;
    let deleteAllModal = null;
    let deleteAllForm = null;
    let deleteAllBtn = null;
    let debounceTimer = null;

    // productKey -> display name of staged (not-yet-added) blueprints.
    const staged = new Map();

    function $(id) {
        return document.getElementById(id);
    }

    function i18n() {
        return window.krtBlueprintsI18n || {};
    }

    function endpoints() {
        return window.krtBlueprintsEndpoints || {};
    }

    // Escape HTML meta-characters before any value is written via innerHTML. Implemented as a
    // self-contained replace chain (not a delegate to window.escapeHtml) so it is an unconditional,
    // statically-recognizable HTML-escape barrier on every path (CodeQL js/xss-through-dom).
    function esc(v) {
        return String(v == null ? '' : v)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function init() {
        searchInput = $('krt-bp-search-input');
        resultsEl = $('krt-bp-search-results');
        stagingListEl = $('krt-bp-staging-list');
        addSelectedBtn = $('krt-bp-add-selected');
        editModal = $('krt-bp-edit-modal');
        editForm = $('krt-bp-edit-form');
        deleteModal = $('krt-bp-delete-modal');
        deleteForm = $('krt-bp-delete-form');
        deleteAllModal = $('krt-bp-delete-all-modal');
        deleteAllForm = $('krt-bp-delete-all-form');
        deleteAllBtn = $('krt-bp-delete-all-open');

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
                closeEdit();
                closeDelete();
                closeDeleteAll();
            }
        });
        if (addSelectedBtn) addSelectedBtn.addEventListener('click', addSelected);
        // The edit/remove modals live outside the swapped #krt-bp-list fragment, so their forms
        // persist and a direct submit binding survives every list re-render.
        if (editForm) editForm.addEventListener('submit', submitEditNote);
        if (deleteForm) deleteForm.addEventListener('submit', submitDeleteBp);
        if (deleteAllForm) deleteAllForm.addEventListener('submit', submitDeleteAll);
        // After a list swap (batch add / import / remove), resync the header counts from the fresh
        // rows (recipe.js separately re-inits the master/detail wiring on the same event).
        document.addEventListener('krt:swapped', function (e) {
            const c = e.detail && e.detail.container;
            if (c && c.id === 'krt-bp-list') {
                recountAndSync();
            }
        });
        renderStaging();
        wireAdminSwap();
        wireAdminMemberPersistence();
    }

    /* ------------------------------------------------ admin member persistence */

    // Admin page only (REQ-UI-017): the selected member is kept per browser so reopening
    // /admin/personal-blueprints returns to the last-inspected member. The picker is a deliberate
    // full-GET reload (data-trigger="submit-form", see wireAdminSwap above), so the URL always
    // carries ?userSub= once a member is selected: an explicit param wins and is re-persisted,
    // and a BARE load with a saved member does a one-time location.replace to ?userSub=<saved> —
    // loop-safe because the target URL carries the param — so the SERVER seeds the remote-users
    // combobox with the member's display label (which a client-side restore could not
    // reconstruct). Guarded on the member picker form, which only the admin page renders, so the
    // user blueprints page is untouched. NOTE: this requires the module to load on the BARE admin
    // page too — admin/personal-blueprints.html loads it outside the selected-user block.
    const ADMIN_USER_PREF_KEY = 'admin_personal_blueprints_user';

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
        // Persist the pick right away; the data-trigger="submit-form" navigation runs in the same
        // synchronous change dispatch and the storage write lands before the page unloads.
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

    /**
     * Admin-page only: the owned-blueprint search filter re-renders just the #bp-results table in
     * place (REQ-FE-002) instead of reloading the page. Guarded on #bp-results so it is a no-op on
     * the user page (which has no such container) and without krtFetch (no-JS GET fallback). The
     * submit is delegated on document so it survives the table being re-rendered inside the swap.
     * The member <select> is intentionally NOT swapped here — switching the member changes the
     * per-user endpoints embedded in the page's inline script, which a fragment swap cannot refresh,
     * so it stays a full reload by design.
     */
    function wireAdminSwap() {
        if (!window.krtFetch || !document.getElementById('bp-results')) return;
        document.addEventListener('submit', function (e) {
            if (!e.target.classList || !e.target.classList.contains('krt-pi-filter')) return;
            e.preventDefault();
            const params = new URLSearchParams(new FormData(e.target)).toString();
            const url = e.target.getAttribute('action') + (params ? '?' + params : '');
            window.krtFetch.swap({ url: url, container: '#bp-results', history: true });
        });
    }

    /* ----------------------------------------------------------------- search */

    function onSearchInput() {
        if (debounceTimer) clearTimeout(debounceTimer);
        debounceTimer = setTimeout(runSearch, 250);
    }

    function runSearch() {
        if (!searchInput || !resultsEl) return;
        const q = searchInput.value || '';
        const url =
            (endpoints().search || '/personal-inventory/blueprints/search') +
            '?q=' +
            encodeURIComponent(q) +
            '&limit=25';
        resultsEl.hidden = false;
        resultsEl.innerHTML =
            '<div class="krt-pi-typeahead-loading">' +
            esc(i18n().searching || 'Suche...') +
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
                esc(i18n().noResults || 'Keine Treffer') +
                '</div>';
            return;
        }
        let html = '';
        items.forEach(function (it) {
            const isStaged = staged.has(it.productKey);
            const blocked = it.ownedByCurrentUser;
            const cls =
                'krt-pi-typeahead-item krt-bp-result' +
                (blocked ? ' krt-bp-result-owned' : '') +
                (isStaged ? ' krt-bp-result-staged' : '');
            const variants =
                it.variantCount && it.variantCount > 1
                    ? it.variantCount + ' ' + esc(i18n().variants || 'Varianten')
                    : esc(it.manufacturerName || '');
            html +=
                '<button type="button" class="' +
                cls +
                '"' +
                ' data-key="' +
                esc(it.productKey) +
                '"' +
                ' data-name="' +
                esc(it.name) +
                '"' +
                (blocked ? ' disabled' : '') +
                '>' +
                '<span class="krt-pi-typeahead-name">' +
                esc(it.name || '') +
                '</span>' +
                '<span class="krt-pi-typeahead-meta">' +
                (blocked ? esc(i18n().owned || 'Bereits vorhanden') : variants) +
                '</span>' +
                '</button>';
        });
        resultsEl.innerHTML = html;
        resultsEl.querySelectorAll('.krt-bp-result').forEach(function (btn) {
            if (btn.disabled) return;
            btn.addEventListener('click', function () {
                toggleStaged(btn.getAttribute('data-key'), btn.getAttribute('data-name'));
                btn.classList.toggle(
                    'krt-bp-result-staged',
                    staged.has(btn.getAttribute('data-key')),
                );
            });
        });
    }

    /* ---------------------------------------------------------------- staging */

    function toggleStaged(key, name) {
        if (!key) return;
        if (staged.has(key)) {
            staged.delete(key);
        } else {
            staged.set(key, name || key);
        }
        renderStaging();
    }

    function renderStaging() {
        if (!stagingListEl) return;
        if (staged.size === 0) {
            stagingListEl.innerHTML =
                '<span class="krt-bp-staging-empty">' +
                esc(stagingListEl.getAttribute('data-empty-text') || i18n().emptyStaging || '') +
                '</span>';
        } else {
            let html = '';
            // DS staging chip: the canonical squared .chip (chip--primary), not the bespoke
            // .krt-bp-chip orange-border box. The inline remove × keeps its i18n accessible name.
            const removeLabel = esc(i18n().chipRemove || 'Entfernen');
            staged.forEach(function (name, key) {
                html +=
                    '<span class="chip chip--primary" data-key="' +
                    esc(key) +
                    '">' +
                    '<span class="krt-bp-chip-name">' +
                    esc(name) +
                    '</span>' +
                    '<button type="button" class="krt-bp-chip-remove" data-key="' +
                    esc(key) +
                    '"' +
                    ' aria-label="' +
                    removeLabel +
                    '">&times;</button>' +
                    '</span>';
            });
            stagingListEl.innerHTML = html;
            stagingListEl.querySelectorAll('.krt-bp-chip-remove').forEach(function (btn) {
                btn.addEventListener('click', function () {
                    toggleStaged(btn.getAttribute('data-key'), null);
                });
            });
        }
        if (addSelectedBtn) addSelectedBtn.disabled = staged.size === 0;
    }

    // krtFetch conflict strings (REQ-FE-003) for the write paths below.
    function conflictObj() {
        const i = i18n();
        return {
            title: i.conflictTitle,
            reloadDetailFallback: i.conflictDetail,
            reloadLabel: i.conflictReload,
            dismissLabel: i.conflictDismiss,
            reloadQuestion: i.conflictQuestion,
        };
    }

    // Re-render the #krt-bp-list collection card in place (REQ-FE-005) for the active server filter,
    // without touching the URL again. recipe.js re-inits the master/detail wiring on krt:swapped.
    function reswapList() {
        if (!window.krtFetch) {
            return;
        }
        window.krtFetch.swap({
            url: window.location.pathname + window.location.search,
            container: '#krt-bp-list',
            fragmentValue: 'list',
            history: false,
        });
    }

    // Recompute the header subtitle + active tab-count from the rendered master rows so the
    // "<n> Blueprints · <m> mit Notiz" facts never drift after an in-place note edit or a list swap.
    function recountAndSync() {
        const rows = document.querySelectorAll('#krt-bp-master-rows .master-row');
        let withNote = 0;
        rows.forEach(function (r) {
            const n = r.getAttribute('data-note');
            if (n && n !== 'null' && n.trim() !== '') {
                withNote++;
            }
        });
        const total = rows.length;
        const tabCount = document.querySelector('.tab-nav .tab.active .tab-count');
        if (tabCount) {
            tabCount.textContent = String(total);
        }
        const subtitle = document.querySelector('.krt-personal-inventory-header .krt-subtitle');
        const i = i18n();
        if (subtitle && i.factsCount && i.factsWithNote) {
            subtitle.textContent =
                total + ' ' + i.factsCount + ' · ' + withNote + ' ' + i.factsWithNote;
        }
        // Keep the "delete all" control in step with the collection: hide it once nothing removable
        // remains (a set of only non-removable defaults, or an empty set) so it never points at an
        // empty clear. The button lives outside the swapped fragment, so JS owns its visibility.
        if (deleteAllBtn) {
            let removable = 0;
            rows.forEach(function (r) {
                if (r.getAttribute('data-removable') === 'true') {
                    removable++;
                }
            });
            deleteAllBtn.hidden = removable === 0;
        }
    }

    // Multi-select batch add -> krtFetch (REQ-FE-002), then re-render the list in place instead of
    // the former AJAX-then-reload. The hand-rolled CSRF reader was dropped for krtCsrf (via krtFetch).
    function addSelected() {
        if (staged.size === 0 || !window.krtFetch) {
            return;
        }
        const keys = Array.from(staged.keys());
        if (addSelectedBtn) {
            addSelectedBtn.disabled = true;
        }
        window.krtFetch
            .write({
                method: 'POST',
                url: endpoints().addSelected || '/personal-inventory/blueprints/add-selected',
                payload: keys,
                toast: false,
                errorMessage: i18n().errorToast,
                conflict: conflictObj(),
                onSuccess: function (result) {
                    const res = result || {};
                    const msg =
                        (i18n().addedLabel || 'added') +
                        ': ' +
                        (res.added || 0) +
                        ', ' +
                        (i18n().skippedLabel || 'skipped') +
                        ': ' +
                        ((res.skippedAlreadyOwned || 0) + (res.skippedUnresolved || 0));
                    if (window.showFrontendSuccessToast) {
                        window.showFrontendSuccessToast(msg);
                    }
                    staged.clear();
                    renderStaging();
                    reswapList();
                },
            })
            .then(function () {
                if (addSelectedBtn) {
                    addSelectedBtn.disabled = staged.size === 0;
                }
            });
    }

    /* ----------------------------------------------------------------- modals */

    function resolveUrl(template, id) {
        const raw = (template || '').replace('ID_PLACEHOLDER', encodeURIComponent(id));
        return window.safeSameOriginUrl ? window.safeSameOriginUrl(raw, raw) : raw;
    }

    function openEdit(btn) {
        if (!editModal || !editForm) return;
        editForm.action = resolveUrl(endpoints().updateNote, btn.getAttribute('data-id'));
        setValue('krt-bp-edit-version', btn.getAttribute('data-version'));
        setValue('krt-bp-edit-acquired', normalizeAcquired(btn.getAttribute('data-acquired-at')));
        setValue('krt-bp-edit-note', btn.getAttribute('data-note'));
        const productEl = $('krt-bp-edit-product');
        if (productEl) productEl.textContent = btn.getAttribute('data-name') || '';
        editModal.style.display = 'flex';
    }

    function closeEdit() {
        if (editModal) editModal.style.display = 'none';
    }

    function openDelete(btn) {
        if (!deleteModal || !deleteForm) return;
        deleteForm.action = resolveUrl(endpoints().remove, btn.getAttribute('data-id'));
        const msgEl = $('krt-bp-delete-message');
        const name = btn.getAttribute('data-name');
        if (msgEl && name) {
            msgEl.textContent = (i18n().removeBody || msgEl.textContent) + ' (' + name + ')';
        }
        deleteModal.style.display = 'flex';
    }

    function closeDelete() {
        if (deleteModal) deleteModal.style.display = 'none';
    }

    // Delete-all confirm modal (REQ-INV-023): no per-row context, so the danger frame's server text
    // (which names that defaults are kept) is shown as-is.
    function openDeleteAll() {
        if (deleteAllModal) deleteAllModal.style.display = 'flex';
    }

    function closeDeleteAll() {
        if (deleteAllModal) deleteAllModal.style.display = 'none';
    }

    function setValue(id, value) {
        const el = $(id);
        if (el) el.value = value == null || value === 'null' ? '' : value;
    }

    // Thymeleaf renders a null Instant attribute as the literal string "null";
    // treat that (and blank) as "no timestamp" so the hidden field stays empty.
    function normalizeAcquired(value) {
        return !value || value === 'null' ? '' : value;
    }

    /* ------------------------------------------------------------- in-place writes */

    // Note edit -> krtFetch (REQ-FE-001/002): the twin returns the fresh blueprint, so the master
    // row's note + version and the detail pane are patched in place (the selection and the loaded
    // recipe survive). The classic POST→redirect stays the no-JS fallback.
    function submitEditNote(e) {
        e.preventDefault();
        if (!editForm || !window.krtFetch) {
            return;
        }
        const noteEl = $('krt-bp-edit-note');
        const versionEl = $('krt-bp-edit-version');
        const acquiredEl = $('krt-bp-edit-acquired');
        const payload = {
            note: noteEl ? noteEl.value : '',
            acquiredAt: acquiredEl && acquiredEl.value ? acquiredEl.value : null,
            version: versionEl && versionEl.value ? Number(versionEl.value) : null,
        };
        const submitBtn = editForm.querySelector('button[type="submit"]');
        if (submitBtn) {
            submitBtn.disabled = true;
        }
        window.krtFetch
            .write({
                method: 'POST',
                url: window.safeSameOriginUrl(editForm.getAttribute('action'), editForm.action),
                payload: payload,
                successMessage: i18n().noteUpdated,
                errorMessage: i18n().editError,
                conflict: conflictObj(),
                onSuccess: function (dto) {
                    closeEdit();
                    patchBlueprintRow(dto);
                },
            })
            .then(function () {
                if (submitBtn) {
                    submitBtn.disabled = false;
                }
            });
    }

    // Patch the edited blueprint's master row (note + version + note-marker badge) and, if it is the
    // active selection, the detail pane — then resync the header counts. Mirrors the trap note in
    // #578 (data-version sync + recompute per-blueprint badges in the success handler).
    function patchBlueprintRow(dto) {
        if (!dto || !dto.id) {
            return;
        }
        const newNote = dto.note == null ? '' : dto.note;
        let row = null;
        document.querySelectorAll('#krt-bp-master-rows .master-row').forEach(function (r) {
            if (r.getAttribute('data-id') === dto.id) {
                row = r;
            }
        });
        if (row) {
            row.setAttribute('data-note', newNote);
            if (dto.version != null) {
                row.setAttribute('data-version', String(dto.version));
            }
            let marker = row.querySelector('.master-row-note');
            if (newNote.trim() !== '') {
                if (!marker) {
                    // The note pencil lives in the trailing .krt-bp-row-aside cluster (alongside the
                    // craft badge); find-or-create it and insert the marker before the badge.
                    let aside = row.querySelector('.krt-bp-row-aside');
                    if (!aside) {
                        aside = document.createElement('span');
                        aside.className = 'krt-bp-row-aside';
                        row.appendChild(aside);
                    }
                    marker = document.createElement('span');
                    marker.className = 'master-row-note';
                    marker.setAttribute('aria-hidden', 'true');
                    marker.title = i18n().noteTitle || '';
                    // Static sprite reference (no user data) — this constant is not an HTML sink.
                    marker.innerHTML = '<svg class="krt-icon"><use href="#krt-icon-edit"/></svg>';
                    aside.insertBefore(marker, aside.firstChild);
                }
            } else if (marker) {
                marker.remove();
            }
            if (row.classList.contains('is-active')) {
                const editBtn = $('krt-bp-detail-edit');
                if (editBtn) {
                    editBtn.setAttribute('data-note', newNote);
                    if (dto.version != null) {
                        editBtn.setAttribute('data-version', String(dto.version));
                    }
                }
                const noteText = $('krt-bp-detail-note');
                const noteSection = $('krt-bp-detail-note-section');
                if (noteText) {
                    noteText.textContent = newNote;
                }
                if (noteSection) {
                    noteSection.hidden = newNote.trim() === '';
                }
            }
        }
        recountAndSync();
    }

    // Remove -> krtFetch (REQ-FE-001): on success re-render the list in place (resyncing counts and
    // the empty state). The classic POST→redirect stays the no-JS fallback.
    function submitDeleteBp(e) {
        e.preventDefault();
        if (!deleteForm || !window.krtFetch) {
            return;
        }
        const submitBtn = deleteForm.querySelector('button[type="submit"]');
        if (submitBtn) {
            submitBtn.disabled = true;
        }
        window.krtFetch
            .write({
                method: 'POST',
                url: window.safeSameOriginUrl(deleteForm.getAttribute('action'), deleteForm.action),
                successMessage: i18n().removed,
                errorMessage: i18n().removeError,
                conflict: conflictObj(),
                onSuccess: function () {
                    closeDelete();
                    reswapList();
                },
            })
            .then(function () {
                if (submitBtn) {
                    submitBtn.disabled = false;
                }
            });
    }

    // Delete-all -> krtFetch.submitForm (REQ-FE-001/002, S10 #916): the FormData twin of write() owns
    // the CSRF header + retry-on-403; on success we toast the removed count and re-render the list in
    // place (reswapList resyncs the counts, the empty state, and — via recountAndSync — the
    // delete-all button's own visibility). The form keeps its th:action/method=post so a script-less
    // browser gets the native POST->redirect fallback.
    function submitDeleteAll(e) {
        e.preventDefault();
        if (!deleteAllForm) {
            return;
        }
        if (!window.krtFetch) {
            deleteAllForm.submit();
            return;
        }
        const submitBtn = deleteAllForm.querySelector('button[type="submit"]');
        if (submitBtn) {
            submitBtn.disabled = true;
        }
        window.krtFetch
            .submitForm({
                form: deleteAllForm,
                toast: false,
                errorMessage: i18n().removeAllError,
                conflict: conflictObj(),
                onSuccess: function (body) {
                    const count = body && body.deleted != null ? body.deleted : 0;
                    if (window.showFrontendSuccessToast) {
                        const tpl = i18n().removedAllCount || '{0}';
                        window.showFrontendSuccessToast(String(tpl).replace('{0}', count));
                    }
                    closeDeleteAll();
                    reswapList();
                },
            })
            .then(function () {
                if (submitBtn) {
                    submitBtn.disabled = false;
                }
            });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }

    if (window.krtEvents && typeof window.krtEvents.on === 'function') {
        window.krtEvents.on('click', 'bp-open-edit', openEdit);
        window.krtEvents.on('click', 'bp-close-edit', closeEdit);
        window.krtEvents.on('click', 'bp-open-delete', openDelete);
        window.krtEvents.on('click', 'bp-close-delete', closeDelete);
        window.krtEvents.on('click', 'bp-open-delete-all', openDeleteAll);
        window.krtEvents.on('click', 'bp-close-delete-all', closeDeleteAll);
    }
})();

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
        wireLiveSync();

        if (searchInput) {
            searchInput.addEventListener('input', onSearchInput);
            searchInput.addEventListener('focus', onSearchInput);
            document.addEventListener('click', (e) => {
                if (!resultsEl) return;
                if (e.target !== searchInput && !resultsEl.contains(e.target)) {
                    resultsEl.hidden = true;
                }
            });
        }
        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') {
                closeEdit();
                closeDelete();
                closeDeleteAll();
            }
        });
        if (addSelectedBtn) addSelectedBtn.addEventListener('click', addSelected);
        if (editForm) editForm.addEventListener('submit', submitEditNote);
        if (deleteForm) deleteForm.addEventListener('submit', submitDeleteBp);
        if (deleteAllForm) deleteAllForm.addEventListener('submit', submitDeleteAll);
        document.addEventListener('krt:swapped', (e) => {
            const c = e.detail && e.detail.container;
            if (c && c.id === 'krt-bp-list') {
                recountAndSync();
            }
        });
        renderStaging();
        wireAdminSwap();
        wireAdminMemberPersistence();
    }

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
                    localStorage.removeItem(ADMIN_USER_PREF_KEY);
                }
            } catch (_e) {}
        }
        document.addEventListener('change', (e) => {
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
                `${window.location.pathname}?userSub=${encodeURIComponent(saved)}`,
            );
        }
    }

    /**
     * Makes the admin page's owned-blueprint search filter re-render only the #bp-results table in
     * place while typing (REQ-FE-002); a no-op without #bp-results or krtFetch. The member select
     * still reloads.
     */
    function wireAdminSwap() {
        if (!window.krtFetch || !document.getElementById('bp-results')) return;
        let filterTimer = null;
        function swapFilter(form) {
            const params = new URLSearchParams(new FormData(form)).toString();
            const url = form.getAttribute('action') + (params ? `?${params}` : '');
            window.krtFetch.swap({ url, container: '#bp-results', history: true });
        }
        document.addEventListener('submit', (e) => {
            if (!e.target.classList || !e.target.classList.contains('krt-pi-filter')) return;
            e.preventDefault();
            if (filterTimer) clearTimeout(filterTimer);
            swapFilter(e.target);
        });
        document.addEventListener('input', (e) => {
            const input = e.target;
            const form = input && input.form;
            if (!form || !form.classList.contains('krt-pi-filter') || input.name !== 'q') return;
            if (filterTimer) clearTimeout(filterTimer);
            filterTimer = setTimeout(() => {
                swapFilter(form);
            }, 300);
        });
    }

    function onSearchInput() {
        if (debounceTimer) clearTimeout(debounceTimer);
        debounceTimer = setTimeout(runSearch, 250);
    }

    function runSearch() {
        if (!searchInput || !resultsEl) return;
        const q = searchInput.value || '';
        const url = `${
            endpoints().search || '/personal-inventory/blueprints/search'
        }?q=${encodeURIComponent(q)}&limit=25`;
        resultsEl.hidden = false;
        resultsEl.innerHTML = `<div class="krt-pi-typeahead-loading">${escapeHtml(
            window.krtI18nText(i18n().searching, 'krtBlueprintsI18n.searching'),
        )}</div>`;
        window.krtFetch
            .getJson(url)
            .then(renderResults)
            .catch(() => {
                renderResults([]);
            });
    }

    function renderResults(items) {
        if (!resultsEl) return;
        if (!items || items.length === 0) {
            resultsEl.innerHTML = `<div class="krt-pi-typeahead-empty">${escapeHtml(
                window.krtI18nText(i18n().noResults, 'krtBlueprintsI18n.noResults'),
            )}</div>`;
            return;
        }
        let html = '';
        items.forEach((it) => {
            const isStaged = staged.has(it.productKey);
            const blocked = it.ownedByCurrentUser;
            const cls = `krt-pi-typeahead-item krt-bp-result${
                blocked ? ' krt-bp-result-owned' : ''
            }${isStaged ? ' krt-bp-result-staged' : ''}`;
            let meta = escapeHtml(it.manufacturerName || '');
            if (blocked) {
                meta = escapeHtml(window.krtI18nText(i18n().owned, 'krtBlueprintsI18n.owned'));
            } else if (it.variantCount && it.variantCount > 1) {
                meta = escapeHtml(
                    `${it.variantCount} ${window.krtI18nText(
                        i18n().variants,
                        'krtBlueprintsI18n.variants',
                    )}`,
                );
            }
            let disabledAttr = '';
            if (blocked) {
                disabledAttr = ' disabled';
            }
            html +=
                `<button type="button" class="${escapeAttr(cls)}"` +
                ` data-key="${escapeAttr(it.productKey)}"` +
                ` data-name="${escapeAttr(it.name)}"${disabledAttr}>` +
                `<span class="krt-pi-typeahead-name">${escapeHtml(it.name || '')}</span>` +
                `<span class="krt-pi-typeahead-meta">${meta}</span>` +
                `</button>`;
        });
        resultsEl.innerHTML = html;
        resultsEl.querySelectorAll('.krt-bp-result').forEach((btn) => {
            if (btn.disabled) return;
            btn.addEventListener('click', () => {
                toggleStaged(btn.getAttribute('data-key'), btn.getAttribute('data-name'));
                btn.classList.toggle(
                    'krt-bp-result-staged',
                    staged.has(btn.getAttribute('data-key')),
                );
            });
        });
    }

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
        let html = '';
        if (staged.size === 0) {
            stagingListEl.innerHTML = `<span class="krt-bp-staging-empty">${escapeHtml(
                stagingListEl.getAttribute('data-empty-text') || i18n().emptyStaging || '',
            )}</span>`;
        } else {
            const removeLabel = window.krtI18nText(
                i18n().chipRemove,
                'krtBlueprintsI18n.chipRemove',
            );
            staged.forEach((name, key) => {
                html +=
                    `<span class="chip chip--primary" data-key="${escapeAttr(key)}">` +
                    `<span class="krt-bp-chip-name">${escapeHtml(name)}</span>` +
                    `<button type="button" class="krt-bp-chip-remove" data-key="${escapeAttr(
                        key,
                    )}"` +
                    ` aria-label="${escapeAttr(removeLabel)}">&times;</button>` +
                    `</span>`;
            });
            stagingListEl.innerHTML = html;
            stagingListEl.querySelectorAll('.krt-bp-chip-remove').forEach((btn) => {
                btn.addEventListener('click', () => {
                    toggleStaged(btn.getAttribute('data-key'), null);
                });
            });
        }
        if (addSelectedBtn) addSelectedBtn.disabled = staged.size === 0;
    }

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

    function reswapList() {
        if (!window.krtFetch) {
            return;
        }
        if (!document.getElementById('krt-bp-list') && document.getElementById('bp-results')) {
            window.krtFetch.swap({
                url: window.location.pathname + window.location.search,
                container: '#bp-results',
                history: false,
            });
            return;
        }
        window.krtFetch.swap({
            url: window.location.pathname + window.location.search,
            container: '#krt-bp-list',
            fragmentValue: 'list',
            history: false,
        });
    }

    const BLUEPRINT_SECTIONS = { list: { container: '#krt-bp-list', fragmentValue: 'list' } };

    function blueprintTopic() {
        const list = document.getElementById('krt-bp-list');
        return list ? list.dataset.liveSyncTopic || '' : '';
    }

    function afterListWrite() {
        reswapList();
        const topic = blueprintTopic();
        if (topic && window.krtLiveSync && typeof window.krtLiveSync.sendChanged === 'function') {
            window.krtLiveSync.sendChanged(topic, Object.keys(BLUEPRINT_SECTIONS));
        }
    }

    function wireLiveSync() {
        const topic = blueprintTopic();
        if (
            !topic ||
            !window.krtLiveSync ||
            typeof window.krtLiveSync.createReceiver !== 'function'
        ) {
            return;
        }
        window.krtLiveSync.createReceiver({
            topic,
            sections: BLUEPRINT_SECTIONS,
            coalesceMs: 1500,
            refresh() {
                reswapList();
            },
        });
    }

    function recountAndSync() {
        const rows = document.querySelectorAll('#krt-bp-master-rows .master-row');
        const total = rows.length;
        const tabCount = document.querySelector('.tab-nav .tab.active .tab-count');
        if (tabCount) {
            tabCount.textContent = String(total);
        }
        const allCount = document.querySelector('[data-testid="segment-bpScope-all"] .seg-count');
        if (allCount) {
            allCount.textContent = String(total);
        }
        const toolbar = $('krt-bp-craft-toolbar');
        if (toolbar) {
            const q = $('krt-bp-q');
            toolbar.classList.toggle('is-empty', total === 0 && !(q && q.value));
        }
        if (deleteAllBtn) {
            let removable = 0;
            rows.forEach((r) => {
                if (r.getAttribute('data-removable') === 'true') {
                    removable++;
                }
            });
            deleteAllBtn.hidden = removable === 0;
        }
    }

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
                onSuccess(result) {
                    const res = result || {};
                    const msg = `${window.krtI18nText(
                        i18n().addedLabel,
                        'krtBlueprintsI18n.addedLabel',
                    )}: ${res.added || 0}, ${window.krtI18nText(
                        i18n().skippedLabel,
                        'krtBlueprintsI18n.skippedLabel',
                    )}: ${(res.skippedAlreadyOwned || 0) + (res.skippedUnresolved || 0)}`;
                    if (window.showFrontendSuccessToast) {
                        window.showFrontendSuccessToast(msg);
                    }
                    staged.clear();
                    renderStaging();
                    const addModal = $('krt-bp-add-modal');
                    if (addModal) {
                        window.krtModal.close(addModal);
                    }
                    afterListWrite();
                },
            })
            .then(() => {
                if (addSelectedBtn) {
                    addSelectedBtn.disabled = staged.size === 0;
                }
            });
    }

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
        window.krtModal.open(editModal);
    }

    function closeEdit() {
        if (editModal) window.krtModal.close(editModal);
    }

    function openDelete(btn) {
        if (!deleteModal || !deleteForm) return;
        deleteForm.action = resolveUrl(endpoints().remove, btn.getAttribute('data-id'));
        const msgEl = $('krt-bp-delete-message');
        const name = btn.getAttribute('data-name');
        if (msgEl && name) {
            msgEl.textContent = `${i18n().removeBody || msgEl.textContent} (${name})`;
        }
        window.krtModal.open(deleteModal);
    }

    function closeDelete() {
        if (deleteModal) window.krtModal.close(deleteModal);
    }

    function openDeleteAll() {
        if (deleteAllModal) window.krtModal.open(deleteAllModal);
    }

    function closeDeleteAll() {
        if (deleteAllModal) window.krtModal.close(deleteAllModal);
    }

    function setValue(id, value) {
        const el = $(id);
        if (el) el.value = value == null || value === 'null' ? '' : value;
    }

    function normalizeAcquired(value) {
        return !value || value === 'null' ? '' : value;
    }

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
                payload,
                successMessage: i18n().noteUpdated,
                errorMessage: i18n().editError,
                conflict: conflictObj(),
                onSuccess(dto) {
                    closeEdit();
                    patchBlueprintRow(dto);
                },
            })
            .then(() => {
                if (submitBtn) {
                    submitBtn.disabled = false;
                }
            });
    }

    function patchBlueprintRow(dto) {
        if (!dto || !dto.id) {
            return;
        }
        if (!document.getElementById('krt-bp-list') && document.getElementById('bp-results')) {
            reswapList();
            return;
        }
        const newNote = dto.note == null ? '' : dto.note;
        let row = null;
        document.querySelectorAll('#krt-bp-master-rows .master-row').forEach((r) => {
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
                onSuccess() {
                    closeDelete();
                    afterListWrite();
                },
            })
            .then(() => {
                if (submitBtn) {
                    submitBtn.disabled = false;
                }
            });
    }

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
                onSuccess(body) {
                    const count = body && body.deleted != null ? body.deleted : 0;
                    if (window.showFrontendSuccessToast) {
                        const tpl = i18n().removedAllCount || '{0}';
                        window.showFrontendSuccessToast(String(tpl).replace('{0}', count));
                    }
                    closeDeleteAll();
                    afterListWrite();
                },
            })
            .then(() => {
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

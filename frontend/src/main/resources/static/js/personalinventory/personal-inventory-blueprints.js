import { showFrontendSuccessToast } from '/js/kernel/toast.js';
import { safeSameOriginUrl } from '/js/kernel/safe-url.js';
import { krtModal } from '/js/kernel/krt-modal.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtEvents } from '/js/kernel/event-delegation.js';
import { krtPageSections } from '/js/kernel/krt-page-sections.js';
import { krtHtml } from '/js/kernel/krt-html.js';
import { krtI18nText } from '/js/kernel/krt-i18n.js';

/** @type {HTMLInputElement | null} */
let searchInput = null;
/** @type {HTMLElement | null} */
let resultsEl = null;
/** @type {HTMLElement | null} */
let stagingListEl = null;
/** @type {HTMLButtonElement | null} */
let addSelectedBtn = null;
/** @type {HTMLElement | null} */
let editModal = null;
/** @type {HTMLFormElement | null} */
let editForm = null;
/** @type {HTMLElement | null} */
let deleteModal = null;
/** @type {HTMLFormElement | null} */
let deleteForm = null;
/** @type {HTMLElement | null} */
let deleteAllModal = null;
/** @type {HTMLFormElement | null} */
let deleteAllForm = null;
/** @type {HTMLButtonElement | null} */
let deleteAllBtn = null;
/** @type {number | null} */
let debounceTimer = null;

/** @type {Map<string, string>} */
const staged = new Map();

/**
 * Looks an element up by id.
 *
 * @param {string} id the element id
 * @returns {HTMLElement | null} the element, or null when absent
 */
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
    searchInput = /** @type {HTMLInputElement | null} */ ($('krt-bp-search-input'));
    resultsEl = $('krt-bp-search-results');
    stagingListEl = $('krt-bp-staging-list');
    addSelectedBtn = /** @type {HTMLButtonElement | null} */ ($('krt-bp-add-selected'));
    editModal = $('krt-bp-edit-modal');
    editForm = /** @type {HTMLFormElement | null} */ ($('krt-bp-edit-form'));
    deleteModal = $('krt-bp-delete-modal');
    deleteForm = /** @type {HTMLFormElement | null} */ ($('krt-bp-delete-form'));
    deleteAllModal = $('krt-bp-delete-all-modal');
    deleteAllForm = /** @type {HTMLFormElement | null} */ ($('krt-bp-delete-all-form'));
    deleteAllBtn = /** @type {HTMLButtonElement | null} */ ($('krt-bp-delete-all-open'));

    if (searchInput) {
        searchInput.addEventListener('input', onSearchInput);
        searchInput.addEventListener('focus', onSearchInput);
        document.addEventListener('click', (e) => {
            if (!resultsEl) return;
            if (
                e.target !== searchInput &&
                !resultsEl.contains(/** @type {Node | null} */ (e.target))
            ) {
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
    /**
     * Stores the picked member, or clears the stored one.
     *
     * @param {string | null} value the member's sub, empty to clear
     */
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
        const sel = /** @type {HTMLSelectElement} */ (e.target);
        if (sel.matches && sel.matches('form.krt-pi-userform [name="userSub"]')) {
            persistMember(sel.value);
        }
    });
    const params = new URLSearchParams(window.location.search);
    if (params.has('userSub')) {
        persistMember(params.get('userSub'));
        return;
    }
    /** @type {string | null} */
    let saved;
    try {
        const raw = localStorage.getItem(ADMIN_USER_PREF_KEY);
        const parsed = raw === null ? null : JSON.parse(raw);
        saved = parsed && typeof parsed.userSub === 'string' ? parsed.userSub : null;
    } catch (_e) {
        saved = null;
    }
    if (saved) {
        window.location.replace(`${window.location.pathname}?userSub=${encodeURIComponent(saved)}`);
    }
}

/**
 * Makes the admin page's owned-blueprint search filter re-render only the #bp-results table in
 * place while typing (REQ-FE-002); a no-op without #bp-results or krtFetch. The member select
 * still reloads.
 */
function wireAdminSwap() {
    if (!document.getElementById('bp-results')) return;
    /** @type {number | null} */
    let filterTimer = null;
    /**
     * Re-swaps the results from a filter form's current values.
     *
     * @param {HTMLFormElement} form the filter form
     */
    function swapFilter(form) {
        const params = new URLSearchParams(
            /** @type {Record<string, string>} */ (/** @type {unknown} */ (new FormData(form))),
        ).toString();
        const url = form.getAttribute('action') + (params ? `?${params}` : '');
        krtFetch.swap({ url, container: '#bp-results', history: true });
    }
    document.addEventListener('submit', (e) => {
        const target = /** @type {HTMLFormElement} */ (e.target);
        if (!target.classList || !target.classList.contains('krt-pi-filter')) return;
        e.preventDefault();
        if (filterTimer) clearTimeout(filterTimer);
        swapFilter(target);
    });
    document.addEventListener('input', (e) => {
        const input = /** @type {HTMLInputElement | null} */ (e.target);
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
    krtHtml.set(
        resultsEl,
        krtHtml`<div class="krt-pi-typeahead-loading">${krtI18nText(
            i18n().searching,
            'krtBlueprintsI18n.searching',
        )}</div>`,
    );
    krtFetch
        .getJson(url)
        .then(renderResults)
        .catch(() => {
            renderResults([]);
        });
}

/**
 * Renders the blueprint type-ahead results.
 *
 * @param {ApiDto<'BlueprintProductDto'>[] | null} items the matching products
 */
function renderResults(items) {
    if (!resultsEl) return;
    if (!items || items.length === 0) {
        krtHtml.set(
            resultsEl,
            krtHtml`<div class="krt-pi-typeahead-empty">${krtI18nText(
                i18n().noResults,
                'krtBlueprintsI18n.noResults',
            )}</div>`,
        );
        return;
    }
    const html = items.map((it) => {
        const isStaged = staged.has(/** @type {string} */ (it.productKey));
        const blocked = it.ownedByCurrentUser;
        const cls = `krt-pi-typeahead-item krt-bp-result${
            blocked ? ' krt-bp-result-owned' : ''
        }${isStaged ? ' krt-bp-result-staged' : ''}`;
        let meta = it.manufacturerName || '';
        if (blocked) {
            meta = krtI18nText(i18n().owned, 'krtBlueprintsI18n.owned');
        } else if (it.variantCount && it.variantCount > 1) {
            meta = `${it.variantCount} ${krtI18nText(
                i18n().variants,
                'krtBlueprintsI18n.variants',
            )}`;
        }
        const disabledAttr = blocked ? krtHtml` disabled` : '';
        return krtHtml`<button type="button" class="${cls}" data-key="${it.productKey}" data-name="${
            it.name
        }"${disabledAttr}><span class="krt-pi-typeahead-name">${
            it.name || ''
        }</span><span class="krt-pi-typeahead-meta">${meta}</span></button>`;
    });
    krtHtml.set(resultsEl, html);
    /** @type {NodeListOf<HTMLButtonElement>} */ (
        resultsEl.querySelectorAll('.krt-bp-result')
    ).forEach((btn) => {
        if (btn.disabled) return;
        btn.addEventListener('click', () => {
            toggleStaged(btn.getAttribute('data-key'), btn.getAttribute('data-name'));
            btn.classList.toggle(
                'krt-bp-result-staged',
                staged.has(/** @type {string} */ (btn.getAttribute('data-key'))),
            );
        });
    });
}

/**
 * Adds a blueprint to the staging list, or removes it when already staged.
 *
 * @param {string | null} key the product key
 * @param {string | null} name the display name
 */
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
        krtHtml.set(
            stagingListEl,
            krtHtml`<span class="krt-bp-staging-empty">${
                stagingListEl.getAttribute('data-empty-text') || i18n().emptyStaging || ''
            }</span>`,
        );
    } else {
        const removeLabel = krtI18nText(i18n().chipRemove, 'krtBlueprintsI18n.chipRemove');
        /** @type {KrtHtml[]} */
        const html = [];
        staged.forEach((name, key) => {
            html.push(
                krtHtml`<span class="chip chip--primary" data-key="${key}"><span class="krt-bp-chip-name">${
                    name
                }</span><button type="button" class="krt-bp-chip-remove" data-key="${
                    key
                }" aria-label="${removeLabel}">&times;</button></span>`,
            );
        });
        krtHtml.set(stagingListEl, html);
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

function blueprintTopic() {
    const list = document.getElementById('krt-bp-list');
    return list ? list.dataset.liveSyncTopic || '' : '';
}

function blueprintPageUrl() {
    return window.location.pathname + window.location.search;
}

const memberBlueprints = krtPageSections.create({
    topicClass: 'BLUEPRINTS_OWN',
    sections: { list: { container: '#krt-bp-list', fragmentValue: 'list' } },
    pageUrl: blueprintPageUrl,
    topic() {
        return blueprintTopic() || null;
    },
    receiver: blueprintTopic() ? { coalesceMs: 1500 } : undefined,
});

const adminBlueprints = krtPageSections.create({
    sections: { list: { container: '#bp-results' } },
    pageUrl: blueprintPageUrl,
});

/** @returns {KrtPageSections} the seam of the blueprint list the page renders */
function blueprintSeam() {
    return document.getElementById('krt-bp-list') ? memberBlueprints : adminBlueprints;
}

function afterListWrite() {
    blueprintSeam().refresh('list');
}
function recountAndSync() {
    const rows = document.querySelectorAll('#krt-bp-master-rows .master-row');
    const total = rows.length;
    const tabCount = document.querySelector('.tab-nav .tab.active .tab-count');
    if (tabCount) {
        tabCount.textContent = String(total);
    }
    const allCount = document.querySelector(
        '[data-testid="segment-bpScope-all"] .krt-segmented__count',
    );
    if (allCount) {
        allCount.textContent = String(total);
    }
    const toolbar = $('krt-bp-craft-toolbar');
    if (toolbar) {
        const q = /** @type {HTMLInputElement | null} */ ($('krt-bp-q'));
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
    if (staged.size === 0) {
        return;
    }
    const keys = Array.from(staged.keys());
    if (addSelectedBtn) {
        addSelectedBtn.disabled = true;
    }
    krtFetch
        .write({
            method: 'POST',
            url: endpoints().addSelected || '/personal-inventory/blueprints/add-selected',
            payload: keys,
            toast: false,
            errorMessage: i18n().errorToast,
            conflict: conflictObj(),
            onSuccess(result) {
                const res = result || {};
                const msg = `${krtI18nText(
                    i18n().addedLabel,
                    'krtBlueprintsI18n.addedLabel',
                )}: ${res.added || 0}, ${krtI18nText(
                    i18n().skippedLabel,
                    'krtBlueprintsI18n.skippedLabel',
                )}: ${(res.skippedAlreadyOwned || 0) + (res.skippedUnresolved || 0)}`;
                showFrontendSuccessToast(msg);
                staged.clear();
                renderStaging();
                const addModal = $('krt-bp-add-modal');
                if (addModal) {
                    krtModal.close(addModal);
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

/**
 * Fills an endpoint template with an id and keeps it same-origin.
 *
 * @param {string | undefined} template the endpoint with an `ID_PLACEHOLDER`
 * @param {string | null} id the blueprint id
 * @returns {string} the URL
 */
function resolveUrl(template, id) {
    const raw = (template || '').replace('ID_PLACEHOLDER', encodeURIComponent(String(id)));
    return safeSameOriginUrl(raw, raw);
}

/**
 * Opens the edit dialog prefilled from the row's button.
 *
 * @param {HTMLElement} btn the activated button, carrying the blueprint's values
 */
function openEdit(btn) {
    if (!editModal || !editForm) return;
    editForm.action = resolveUrl(endpoints().updateNote, btn.getAttribute('data-id'));
    setValue('krt-bp-edit-version', btn.getAttribute('data-version'));
    setValue('krt-bp-edit-acquired', normalizeAcquired(btn.getAttribute('data-acquired-at')));
    setValue('krt-bp-edit-note', btn.getAttribute('data-note'));
    const productEl = $('krt-bp-edit-product');
    if (productEl) productEl.textContent = btn.getAttribute('data-name') || '';
    krtModal.open(editModal);
}

function closeEdit() {
    if (editModal) krtModal.close(editModal);
}

/**
 * Opens the delete confirmation for one blueprint.
 *
 * @param {HTMLElement} btn the activated button, carrying the blueprint's id and name
 */
function openDelete(btn) {
    if (!deleteModal || !deleteForm) return;
    deleteForm.action = resolveUrl(endpoints().remove, btn.getAttribute('data-id'));
    const msgEl = $('krt-bp-delete-message');
    const name = btn.getAttribute('data-name');
    if (msgEl && name) {
        msgEl.textContent = `${i18n().removeBody || msgEl.textContent} (${name})`;
    }
    krtModal.open(deleteModal);
}

function closeDelete() {
    if (deleteModal) krtModal.close(deleteModal);
}

function openDeleteAll() {
    if (deleteAllModal) krtModal.open(deleteAllModal);
}

function closeDeleteAll() {
    if (deleteAllModal) krtModal.close(deleteAllModal);
}

/**
 * Sets a form field by id; null and the string `null` clear it.
 *
 * @param {string} id the field's element id
 * @param {string | null} value the value
 */
function setValue(id, value) {
    const el = /** @type {HTMLInputElement | HTMLTextAreaElement | null} */ ($(id));
    if (el) el.value = value == null || value === 'null' ? '' : value;
}

/**
 * Maps the acquired-at attribute onto a field value.
 *
 * @param {string | null} value the raw attribute
 * @returns {string} the value, empty for none
 */
function normalizeAcquired(value) {
    return !value || value === 'null' ? '' : value;
}

/**
 * Saves the edited note and acquisition date.
 *
 * @param {Event} e the form's submit event
 */
function submitEditNote(e) {
    e.preventDefault();
    if (!editForm) {
        return;
    }
    const noteEl = /** @type {HTMLInputElement | HTMLTextAreaElement | null} */ (
        $('krt-bp-edit-note')
    );
    const versionEl = /** @type {HTMLInputElement | null} */ ($('krt-bp-edit-version'));
    const acquiredEl = /** @type {HTMLInputElement | null} */ ($('krt-bp-edit-acquired'));
    const payload = {
        note: noteEl ? noteEl.value : '',
        acquiredAt: acquiredEl && acquiredEl.value ? acquiredEl.value : null,
        version: versionEl && versionEl.value ? Number(versionEl.value) : null,
    };
    const submitBtn = /** @type {HTMLButtonElement | null} */ (
        editForm.querySelector('button[type="submit"]')
    );
    if (submitBtn) {
        submitBtn.disabled = true;
    }
    krtFetch
        .write({
            method: 'POST',
            url: safeSameOriginUrl(editForm.getAttribute('action'), editForm.action),
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

/**
 * Patches the list row and detail pane of an updated blueprint in place.
 *
 * @param {ApiDto<'PersonalBlueprintResponse'> | null} dto the updated blueprint
 */
function patchBlueprintRow(dto) {
    if (!dto || !dto.id) {
        return;
    }
    if (!document.getElementById('krt-bp-list') && document.getElementById('bp-results')) {
        adminBlueprints.refresh('list');
        return;
    }
    const newNote = dto.note == null ? '' : dto.note;
    let row = /** @type {Element | null} */ (null);
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
        let marker = /** @type {HTMLElement | null} */ (row.querySelector('.master-row-note'));
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
                krtHtml.set(
                    marker,
                    krtHtml`<svg class="krt-icon"><use href="#krt-icon-edit"/></svg>`,
                );
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

/**
 * Deletes the blueprint of the open confirmation.
 *
 * @param {Event} e the form's submit event
 */
function submitDeleteBp(e) {
    e.preventDefault();
    if (!deleteForm) {
        return;
    }
    const submitBtn = /** @type {HTMLButtonElement | null} */ (
        deleteForm.querySelector('button[type="submit"]')
    );
    if (submitBtn) {
        submitBtn.disabled = true;
    }
    krtFetch
        .write({
            method: 'POST',
            url: safeSameOriginUrl(deleteForm.getAttribute('action'), deleteForm.action),
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

/**
 * Deletes every removable blueprint of the viewed user.
 *
 * @param {Event} e the form's submit event
 */
function submitDeleteAll(e) {
    e.preventDefault();
    if (!deleteAllForm) {
        return;
    }

    const submitBtn = /** @type {HTMLButtonElement | null} */ (
        deleteAllForm.querySelector('button[type="submit"]')
    );
    if (submitBtn) {
        submitBtn.disabled = true;
    }
    krtFetch
        .submitForm({
            form: deleteAllForm,
            toast: false,
            errorMessage: i18n().removeAllError,
            conflict: conflictObj(),
            onSuccess(body) {
                const count = body && body.deleted != null ? body.deleted : 0;
                {
                    const tpl = i18n().removedAllCount || '{0}';
                    showFrontendSuccessToast(String(tpl).replace('{0}', count));
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

krtEvents.on('click', 'bp-open-edit', openEdit);
krtEvents.on('click', 'bp-close-edit', closeEdit);
krtEvents.on('click', 'bp-open-delete', openDelete);
krtEvents.on('click', 'bp-close-delete', closeDelete);
krtEvents.on('click', 'bp-open-delete-all', openDeleteAll);
krtEvents.on('click', 'bp-close-delete-all', closeDeleteAll);

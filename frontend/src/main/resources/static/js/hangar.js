/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

/*
 * Hangar page module (/hangar), extracted verbatim from the former inline script of hangar.html
 * (ADR-0069, follow-up to #924).
 *
 * Drives the whole hangar screen from a single DOMContentLoaded handler: the ship create/edit modal,
 * per-row delete + delete-all, the Fleetview multipart import (bespoke fetch, since a multipart body
 * cannot go through krtFetch.write), the bulk home-location set, the ship-type-by-manufacturer filter,
 * and the debounced server-side search + pagination. Every write goes through window.krtFetch and
 * re-swaps the #hangar-results fragment in place (REQ-FE-001/002/005, REQ-HANGAR-002); the classic
 * POST->redirect forms stay the no-JS fallback.
 *
 * The localized toast + conflict strings are the block's only Thymeleaf interpolation, so they stay
 * inline in the page bootstrap as the hangarI18n / hangarConflict globals this module reads. escapeHtml
 * is the shared global from escape-html.js (loaded deferred via fragments/head.html), so it is defined
 * by the time this DOMContentLoaded handler runs.
 */

/* global hangarI18n, hangarConflict */

document.addEventListener('DOMContentLoaded', function () {
    const modal = document.getElementById('ship-modal');
    const closeBtn = document.querySelector('.close-modal');
    const form = document.getElementById('ship-form');
    const modalTitle = document.getElementById('modal-title');

    // Re-render the ship table in place after a write (add/edit/delete/import/home-location),
    // carrying the active page/size/search from the address bar so the write does not bounce the
    // user back to page 0 or drop the filter (REQ-HANGAR-002). The pagination/search swaps keep
    // the address bar in sync (history:true), so window.location.search is the live state.
    function reswapHangar() {
        if (window.krtFetch) {
            window.krtFetch.swap({
                url: '/hangar' + window.location.search,
                container: '#hangar-results',
                history: false,
            });
        }
    }

    function openModal() {
        modal.style.display = 'flex';
    }

    function closeModal() {
        if (typeof window.resetUnsavedChanges === 'function') {
            window.resetUnsavedChanges();
        }
        modal.style.display = 'none';
    }

    if (closeBtn) closeBtn.addEventListener('click', closeModal);

    const deleteModal = document.getElementById('delete-confirm-modal');
    const deleteForm = document.getElementById('delete-confirm-form');
    const closeDeleteBtns = document.querySelectorAll('.close-delete-modal');

    window.addEventListener('click', (e) => {
        if (e.target === modal) closeModal();
        if (deleteModal && e.target === deleteModal) deleteModal.style.display = 'none';
    });

    const modalDeleteBtn = document.getElementById('modal-delete-btn');

    function openAddModal(btn) {
        modalTitle.textContent = modal.getAttribute('data-title-add');
        form.action = window.safeSameOriginUrl(btn.getAttribute('data-action'), form.action);
        document.getElementById('ship-name').value = '';
        document.getElementById('ship-type').value = '';
        document.getElementById('ship-insurance').value = '';
        document.getElementById('ship-location').value = '';
        document.getElementById('ship-fitted').checked = false;
        document.getElementById('ship-version').value = '';
        modalDeleteBtn.style.display = 'none';
        openModal();
    }

    function openEditModal(btn) {
        modalTitle.textContent = modal.getAttribute('data-title-edit');
        form.action = window.safeSameOriginUrl(btn.getAttribute('data-action'), form.action);
        document.getElementById('ship-name').value = btn.getAttribute('data-name');
        document.getElementById('ship-type').value = btn.getAttribute('data-type');
        document.getElementById('ship-insurance').value = btn.getAttribute('data-ins');
        document.getElementById('ship-location').value = btn.getAttribute('data-loc') || '';
        document.getElementById('ship-fitted').checked = btn.getAttribute('data-fitted') === 'true';
        document.getElementById('ship-version').value = btn.getAttribute('data-version');
        // The edit action is /hangar/{id}/update; the delete twin lives at /hangar/{id}/delete.
        const action = btn.getAttribute('data-action');
        modalDeleteBtn.setAttribute('data-action', action.replace('/update', '/delete'));
        modalDeleteBtn.style.display = 'block';
        openModal();
    }

    // data-trigger delegation (krtEvents): the add + per-row edit buttons live INSIDE the
    // #hangar-results fragment, so a delegated binding is what keeps them working after each
    // in-place re-swap (a direct addEventListener would be lost on the re-rendered nodes).
    if (window.krtEvents && typeof window.krtEvents.on === 'function') {
        window.krtEvents.on('click', 'hangar-add-ship', openAddModal);
        window.krtEvents.on('click', 'hangar-edit-ship', openEditModal);
    }

    modalDeleteBtn.addEventListener('click', () => {
        deleteForm.action = window.safeSameOriginUrl(
            modalDeleteBtn.getAttribute('data-action'),
            deleteForm.action,
        );
        deleteModal.style.display = 'flex';
    });

    if (deleteModal) {
        closeDeleteBtns.forEach((btn) => {
            btn.addEventListener('click', () => {
                deleteModal.style.display = 'none';
            });
        });
    }

    // Delete confirm submit -> krtFetch (REQ-FE-001): remove the ship in place; the classic
    // POST→redirect stays the no-JS fallback (the twin is gated on X-Requested-With).
    if (deleteForm) {
        deleteForm.addEventListener('submit', function (e) {
            e.preventDefault();
            const submitBtn = deleteForm.querySelector('button[type="submit"]');
            if (submitBtn) submitBtn.disabled = true;
            window.krtFetch
                .write({
                    method: 'POST',
                    url: window.safeSameOriginUrl(
                        deleteForm.getAttribute('action'),
                        deleteForm.action,
                    ),
                    successMessage: hangarI18n.deleteSuccess,
                    errorMessage: hangarI18n.deleteError,
                    conflict: hangarConflict,
                    onSuccess: function () {
                        if (deleteModal) deleteModal.style.display = 'none';
                        closeModal();
                        reswapHangar();
                    },
                })
                .then(function () {
                    if (submitBtn) submitBtn.disabled = false;
                });
        });
    }

    // Open modal automatically if there are validation errors
    const showModal = document.body.getAttribute('data-show-modal') === 'true';
    if (showModal) {
        openModal();
    }

    // ---- Fleetview Import ----
    (function () {
        const importBtn = document.getElementById('fleetview-import-btn');
        const fileInput = document.getElementById('fleetview-file');
        const statusEl = document.getElementById('fleetview-status');
        const resultModal = document.getElementById('import-result-modal');
        const closeResultBtn = document.getElementById('import-result-close');

        function showStatus(msg, color) {
            statusEl.textContent = msg;
            statusEl.style.color = color || 'var(--color-primary)';
            statusEl.style.display = 'block';
        }

        function hideStatus() {
            statusEl.style.display = 'none';
        }

        function openResultModal(data) {
            document.getElementById('import-res-imported').textContent = data.importedCount;
            document.getElementById('import-res-skipped').textContent = data.skippedCount;
            document.getElementById('import-res-duplicates').textContent = data.duplicateCount;

            const skipSection = document.getElementById('import-skip-section');
            const skipList = document.getElementById('import-skip-list');
            if (data.skippedShips && data.skippedShips.length > 0) {
                skipList.innerHTML = data.skippedShips
                    .map((n) => '<p>' + escapeHtml(n) + '</p>')
                    .join('');
                skipSection.style.display = 'block';
            } else {
                skipSection.style.display = 'none';
            }

            const dupSection = document.getElementById('import-dup-section');
            const dupList = document.getElementById('import-dup-list');
            if (data.duplicateShips && data.duplicateShips.length > 0) {
                dupList.innerHTML = data.duplicateShips
                    .map((n) => '<p>' + escapeHtml(n) + '</p>')
                    .join('');
                dupSection.style.display = 'block';
            } else {
                dupSection.style.display = 'none';
            }

            resultModal.style.display = 'flex';
        }

        if (closeResultBtn) {
            closeResultBtn.addEventListener('click', function () {
                resultModal.style.display = 'none';
                // In-place: re-render the ship table instead of reloading the page (REQ-FE-001).
                if (document.getElementById('import-res-imported').textContent !== '0') {
                    reswapHangar();
                }
            });
        }

        window.addEventListener('click', function (e) {
            if (e.target === resultModal) {
                resultModal.style.display = 'none';
            }
        });

        if (importBtn) {
            importBtn.addEventListener('click', function () {
                const file = fileInput.files[0];
                if (!file) {
                    showStatus(importBtn.getAttribute('data-error-nofile'), 'var(--color-danger)');
                    return;
                }
                const formData = new FormData();
                formData.append('file', file);

                importBtn.disabled = true;
                showStatus(importBtn.getAttribute('data-uploading'));

                // CSRF via the shared krtCsrf seam (REQ-FE-002), replacing the hand-rolled
                // meta-tag read. A multipart upload cannot go through krtFetch.write (JSON-only),
                // so this keeps a bespoke fetch; the browser must set its own multipart boundary,
                // so the JSON Content-Type krtCsrf adds by default is removed.
                const reqHeaders = window.krtCsrf
                    ? window.krtCsrf.headers({ Accept: 'application/json' })
                    : { Accept: 'application/json' };
                delete reqHeaders['Content-Type'];

                fetch('/hangar/import/ships', {
                    method: 'POST',
                    body: formData,
                    headers: reqHeaders,
                })
                    .then(function (resp) {
                        if (!resp.ok) {
                            return resp
                                .json()
                                .catch(function () {
                                    return null;
                                })
                                .then(function (body) {
                                    throw new Error(
                                        body && body.detail ? body.detail : resp.status,
                                    );
                                });
                        }
                        return resp.json();
                    })
                    .then(function (data) {
                        hideStatus();
                        fileInput.value = '';
                        openResultModal(data);
                    })
                    .catch(function (err) {
                        showStatus(
                            importBtn.getAttribute('data-error-failed') +
                                (err.message ? ' (' + err.message + ')' : ''),
                            'var(--color-danger)',
                        );
                    })
                    .finally(function () {
                        importBtn.disabled = false;
                    });
            });
        }
    })();

    // ---- Delete All Ships ----
    (function () {
        const deleteAllBtn = document.getElementById('delete-all-ships-btn');
        const deleteAllModal = document.getElementById('delete-all-confirm-modal');
        const deleteAllConfirmBtn = document.getElementById('delete-all-confirm-btn');
        const deleteAllCancelBtn = document.getElementById('delete-all-cancel-btn');

        if (!deleteAllBtn || !deleteAllModal) return;

        deleteAllBtn.addEventListener('click', function () {
            deleteAllModal.style.display = 'flex';
        });

        deleteAllCancelBtn.addEventListener('click', function () {
            deleteAllModal.style.display = 'none';
        });

        window.addEventListener('click', function (e) {
            if (e.target === deleteAllModal) {
                deleteAllModal.style.display = 'none';
            }
        });

        deleteAllConfirmBtn.addEventListener('click', function () {
            deleteAllConfirmBtn.disabled = true;
            deleteAllCancelBtn.disabled = true;

            // krtFetch.write (REQ-FE-002): CSRF + retry-on-403 + problem handling; on success the
            // ship table re-renders in place instead of reloading the page (REQ-FE-001).
            window.krtFetch
                .write({
                    method: 'DELETE',
                    url: '/hangar/ships/all',
                    toast: false,
                    errorMessage: deleteAllBtn.getAttribute('data-error-failed'),
                    conflict: hangarConflict,
                    onSuccess: function () {
                        deleteAllModal.style.display = 'none';
                        reswapHangar();
                        if (window.showFrontendSuccessToast) {
                            window.showFrontendSuccessToast(
                                deleteAllBtn.getAttribute('data-success'),
                            );
                        }
                    },
                })
                .then(function () {
                    deleteAllConfirmBtn.disabled = false;
                    deleteAllCancelBtn.disabled = false;
                });
        });
    })();

    // ---- Set Home Location ----
    (function () {
        const homeModal = document.getElementById('home-location-modal');
        if (!homeModal) return;

        function closeHome() {
            homeModal.style.display = 'none';
        }

        // Re-render the modal body with the LIVE ship count. The count baked in at page load
        // goes stale once an in-place ship add/delete re-swaps #hangar-results; the trigger
        // button lives inside that fragment, so its data-ship-count is always current.
        function refreshHomeBody() {
            const body = document.getElementById('home-location-body');
            const trigger = document.querySelector('[data-trigger="hangar-open-home"]');
            if (body && trigger && hangarI18n.homeBodyTemplate) {
                const count = trigger.getAttribute('data-ship-count') || '';
                body.textContent = hangarI18n.homeBodyTemplate.replace('{0}', count);
            }
        }

        // The open button lives in the swapped #hangar-results fragment -> delegate via krtEvents.
        if (window.krtEvents && typeof window.krtEvents.on === 'function') {
            window.krtEvents.on('click', 'hangar-open-home', function () {
                refreshHomeBody();
                homeModal.style.display = 'flex';
            });
        }

        homeModal.querySelectorAll('.close-home-cancel, .close-modal-home').forEach(function (el) {
            el.addEventListener('click', closeHome);
        });
        window.addEventListener('click', function (e) {
            if (e.target === homeModal) {
                closeHome();
            }
        });

        const homeForm = document.getElementById('home-location-form');
        if (homeForm) {
            homeForm.addEventListener('submit', function (e) {
                e.preventDefault();
                const submitBtn = homeForm.querySelector('button[type="submit"]');
                const select = homeForm.querySelector('select[name="locationId"]');
                const locationId = select ? select.value : '';
                if (!locationId) {
                    return;
                }
                if (submitBtn) submitBtn.disabled = true;
                window.krtFetch
                    .write({
                        method: 'POST',
                        url: window.safeSameOriginUrl(
                            homeForm.getAttribute('action'),
                            homeForm.action,
                        ),
                        payload: { locationId: locationId },
                        successMessage: hangarI18n.homeSuccess,
                        errorMessage: hangarI18n.homeError,
                        conflict: hangarConflict,
                        onSuccess: function () {
                            closeHome();
                            reswapHangar();
                        },
                    })
                    .then(function () {
                        if (submitBtn) submitBtn.disabled = false;
                    });
            });
        }
    })();

    // Ship create/edit submit -> krtFetch (REQ-FE-001/002). The classic POST→redirect stays the
    // no-JS fallback (the AJAX twin is gated on X-Requested-With, which krtFetch always sends).
    // HTML5 `required` on the ship-type + insurance selects blocks an empty submit before this
    // handler runs; on success the modal closes and #hangar-results re-renders in place.
    form.addEventListener('submit', function (e) {
        e.preventDefault();
        const action = form.getAttribute('action') || form.action;
        const isUpdate = /\/update$/.test(action);
        const versionRaw = document.getElementById('ship-version').value;
        const ownerSel = form.querySelector('[name="owningOrgUnitId"]');
        const payload = {
            name: document.getElementById('ship-name').value || null,
            shipTypeId: document.getElementById('ship-type').value || null,
            insurance: document.getElementById('ship-insurance').value || null,
            locationId: document.getElementById('ship-location').value || null,
            fitted: document.getElementById('ship-fitted').checked,
            version: isUpdate && versionRaw ? Number(versionRaw) : null,
            owningOrgUnitId: isUpdate ? null : ownerSel && ownerSel.value ? ownerSel.value : null,
        };
        const submitBtn = form.querySelector('button[type="submit"]');
        if (submitBtn) submitBtn.disabled = true;
        window.krtFetch
            .write({
                method: 'POST',
                url: window.safeSameOriginUrl(action, action),
                payload: payload,
                successMessage: isUpdate ? hangarI18n.updateSuccess : hangarI18n.addSuccess,
                errorMessage: isUpdate ? hangarI18n.updateError : hangarI18n.addError,
                conflict: hangarConflict,
                onSuccess: function () {
                    closeModal();
                    reswapHangar();
                },
            })
            .then(function () {
                if (submitBtn) submitBtn.disabled = false;
            });
    });

    // Filter ship types by manufacturer
    const manufacturerSelect = document.getElementById('ship-manufacturer');
    const shipTypeSelect = document.getElementById('ship-type');
    const shipTypeAllOptions = Array.from(shipTypeSelect.options);

    manufacturerSelect.addEventListener('change', function () {
        const selectedManufacturer = this.value;

        // Keep the default option (index 0)
        const defaultOption = shipTypeAllOptions[0];

        // Clear all options
        shipTypeSelect.innerHTML = '';
        shipTypeSelect.appendChild(defaultOption);

        // Add matching options
        shipTypeAllOptions.slice(1).forEach((option) => {
            if (
                !selectedManufacturer ||
                option.getAttribute('data-manufacturer-id') === selectedManufacturer
            ) {
                shipTypeSelect.appendChild(option);
            }
        });
    });

    // ---- Server-side search + pagination (REQ-HANGAR-002) ----
    // The filter now spans the user's whole fleet, not just the rows currently in the DOM: a
    // debounced keystroke re-swaps #hangar-results via GET /hangar?search=...&size=... so the
    // backend re-orders + re-pages the matching ships. The form/input live INSIDE the swapped
    // fragment, so all handlers are delegated on document (the elements are replaced on every
    // swap) and focus is restored to the search box after the swap. The GET form is the no-JS
    // fallback. bindSwap wires the in-fragment .page-btn pagination links for the very first
    // page click (later swaps re-bind the container themselves).
    let hangarFilterTimer = null;

    function applyHangarFilter(url) {
        const resultsContainer = document.getElementById('hangar-results');
        const form = document.getElementById('hangar-filter-form');
        if (!resultsContainer || !window.krtFetch) {
            if (url) {
                window.location.assign(url);
            } else if (form) {
                form.submit();
            }
            return Promise.resolve(false);
        }
        let target = url;
        if (!target) {
            const params = new URLSearchParams();
            if (form) {
                const data = new FormData(form);
                for (const [key, value] of data.entries()) {
                    if (value !== '') {
                        params.append(key, value);
                    }
                }
            }
            const query = params.toString();
            target = '/hangar' + (query ? '?' + query : '');
        }
        return window.krtFetch
            .swap({ url: target, container: resultsContainer, history: true })
            .then(function (ok) {
                // The swap replaced the input; restore focus + caret so typing is uninterrupted.
                const input = document.getElementById('hangar-ship-filter');
                if (input) {
                    input.focus();
                    const v = input.value;
                    try {
                        input.setSelectionRange(v.length, v.length);
                    } catch (_ignored) {
                        /* setSelectionRange is unsupported on some input states; ignore */
                    }
                }
                return ok;
            });
    }

    document.addEventListener('input', function (e) {
        if (!e.target || e.target.id !== 'hangar-ship-filter') {
            return;
        }
        clearTimeout(hangarFilterTimer);
        hangarFilterTimer = setTimeout(function () {
            applyHangarFilter();
        }, 300);
    });

    document.addEventListener('submit', function (e) {
        if (!e.target || e.target.id !== 'hangar-filter-form') {
            return;
        }
        e.preventDefault();
        clearTimeout(hangarFilterTimer);
        applyHangarFilter();
    });

    document.addEventListener('click', function (e) {
        const clear = e.target.closest ? e.target.closest('#hangar-filter-clear') : null;
        if (!clear) {
            return;
        }
        e.preventDefault();
        const input = document.getElementById('hangar-ship-filter');
        if (input) {
            input.value = '';
        }
        applyHangarFilter(clear.getAttribute('href'));
    });

    // Initial-load pagination interception: the .page-btn links inside #hangar-results re-swap
    // in place instead of navigating (later swaps re-bind the container automatically).
    if (window.krtFetch && typeof window.krtFetch.bindSwap === 'function') {
        window.krtFetch.bindSwap({ container: '#hangar-results', history: true });
    }
});

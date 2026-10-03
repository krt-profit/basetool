// @ts-check
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

/* global QT_MSG, QT_CONFLICT */

(function () {
    'use strict';

    const PAGE_URL = '/admin/quality-tiers';

    const modal = document.getElementById('qt-modal');
    const form = /** @type {HTMLFormElement | null} */ (document.getElementById('qt-form'));
    const title = document.getElementById('qt-modal-title');
    const idInput = /** @type {HTMLInputElement | null} */ (document.getElementById('qt-id'));
    const versionInput = /** @type {HTMLInputElement | null} */ (
        document.getElementById('qt-version')
    );
    const codeInput = /** @type {HTMLInputElement | null} */ (document.getElementById('qt-code'));
    const floorInput = /** @type {HTMLInputElement | null} */ (
        document.getElementById('qt-min-quality')
    );
    const labelDeInput = /** @type {HTMLInputElement | null} */ (
        document.getElementById('qt-label-de')
    );
    const labelEnInput = /** @type {HTMLInputElement | null} */ (
        document.getElementById('qt-label-en')
    );
    const sortInput = /** @type {HTMLInputElement | null} */ (
        document.getElementById('qt-sort-order')
    );
    const activeInput = /** @type {HTMLInputElement | null} */ (
        document.getElementById('qt-active')
    );
    const baseHint = document.getElementById('qt-base-hint');
    const submitBtn = /** @type {HTMLButtonElement | null} */ (
        document.getElementById('qt-submit')
    );
    const addBtn = document.getElementById('qt-add-btn');

    const deleteModal = document.getElementById('qt-delete-modal');
    const deleteCode = document.getElementById('qt-delete-code');
    const deleteConfirm = /** @type {HTMLButtonElement | null} */ (
        document.getElementById('qt-delete-confirm')
    );
    /** @type {string | null} */
    let pendingDeleteId = null;

    if (
        !modal ||
        !form ||
        !idInput ||
        !versionInput ||
        !codeInput ||
        !floorInput ||
        !labelDeInput ||
        !labelEnInput ||
        !sortInput ||
        !activeInput
    ) {
        return;
    }

    /** @param {string} message */
    function errorToast(message) {
        if (message && typeof window.showFrontendErrorToast === 'function') {
            window.showFrontendErrorToast(message);
        }
    }

    /**
     * Locks the active toggle and the floor for the base tier, whose floor stays 0 and which stays
     * active.
     * @param {boolean} isBase
     */
    function applyBaseLock(isBase) {
        if (!activeInput || !floorInput) {
            return;
        }
        activeInput.disabled = isBase;
        floorInput.readOnly = isBase;
        if (baseHint) {
            baseHint.hidden = !isBase;
        }
    }

    function openCreate() {
        if (!form || !idInput || !versionInput || !activeInput) {
            return;
        }
        form.reset();
        if (title) {
            title.textContent = QT_MSG.createTitle;
        }
        idInput.value = '';
        versionInput.value = '';
        activeInput.checked = true;
        applyBaseLock(false);
        window.krtModal.open(modal);
    }

    /** @param {HTMLElement} btn */
    function openEdit(btn) {
        if (
            !form ||
            !idInput ||
            !versionInput ||
            !codeInput ||
            !floorInput ||
            !labelDeInput ||
            !labelEnInput ||
            !sortInput ||
            !activeInput
        ) {
            return;
        }
        form.reset();
        if (title) {
            title.textContent = QT_MSG.editTitle;
        }
        idInput.value = btn.dataset.id || '';
        versionInput.value = btn.dataset.tierVersion || '';
        codeInput.value = btn.dataset.code || '';
        floorInput.value = btn.dataset.minQuality || '0';
        labelDeInput.value = btn.dataset.labelDe || '';
        labelEnInput.value = btn.dataset.labelEn || '';
        sortInput.value = btn.dataset.sortOrder || '0';
        activeInput.checked = btn.dataset.active === 'true';
        applyBaseLock(btn.dataset.base === 'true');
        window.krtModal.open(modal);
    }

    /** @param {HTMLElement} btn */
    function openDelete(btn) {
        pendingDeleteId = btn.dataset.id || null;
        if (deleteCode) {
            deleteCode.textContent = btn.dataset.code || '';
        }
        window.krtModal.open(deleteModal);
    }

    /** @returns {Promise<boolean>} */
    function refreshTable() {
        return window.krtFetch.swap({ url: PAGE_URL, container: '#qt-results', history: false });
    }

    /**
     * Maps a relayed backend refusal to its own toast; an optimistic-lock conflict falls through to
     * the shared conflict dialog.
     * @param {boolean} deleting
     * @returns {(status: number, body: any) => boolean}
     */
    function refusalHandler(deleting) {
        return function (status, body) {
            if (status !== 409) {
                return false;
            }
            const code = body && typeof body.code === 'string' ? body.code : '';
            if (code === 'ENTITY_IN_USE') {
                errorToast(QT_MSG.inUse);
                return true;
            }
            if (code === 'DUPLICATE_ENTITY') {
                errorToast(QT_MSG.conflict);
                return true;
            }
            if (code === 'BUSINESS_CONFLICT') {
                errorToast(deleting ? QT_MSG.inUse : QT_MSG.floorLocked);
                return true;
            }
            return false;
        };
    }

    /** @returns {Record<string, unknown>} */
    function payload() {
        const version = versionInput ? versionInput.value : '';
        return {
            code: codeInput ? codeInput.value.trim().toUpperCase() : '',
            minQuality: floorInput ? Number(floorInput.value) : 0,
            labelDe: labelDeInput ? labelDeInput.value.trim() : '',
            labelEn: labelEnInput ? labelEnInput.value.trim() : '',
            sortOrder: sortInput ? Number(sortInput.value) : 0,
            active: activeInput ? activeInput.disabled || activeInput.checked : true,
            version: version === '' ? null : Number(version),
        };
    }

    form.addEventListener('submit', function (event) {
        event.preventDefault();
        if (!window.krtFetch || !idInput) {
            return;
        }
        const id = idInput.value;
        window.krtFetch.write({
            method: 'POST',
            url: id ? PAGE_URL + '/' + encodeURIComponent(id) : PAGE_URL,
            payload: payload(),
            submitter: submitBtn,
            successMessage: id ? QT_MSG.updated : QT_MSG.created,
            errorMessage: QT_MSG.error,
            conflict: QT_CONFLICT,
            onError: refusalHandler(false),
            onSuccess() {
                window.krtModal.close(modal);
                return refreshTable();
            },
        });
    });

    if (deleteConfirm) {
        deleteConfirm.addEventListener('click', function () {
            if (!window.krtFetch || !pendingDeleteId) {
                return;
            }
            const id = pendingDeleteId;
            window.krtFetch.write({
                method: 'POST',
                url: PAGE_URL + '/' + encodeURIComponent(id) + '/delete',
                submitter: deleteConfirm,
                successMessage: QT_MSG.deleted,
                errorMessage: QT_MSG.deleteError,
                conflict: QT_CONFLICT,
                onError: refusalHandler(true),
                onSuccess() {
                    pendingDeleteId = null;
                    window.krtModal.close(deleteModal);
                    return refreshTable();
                },
            });
        });
    }

    if (addBtn) {
        addBtn.addEventListener('click', openCreate);
    }

    document.addEventListener('click', function (event) {
        const target = event.target instanceof Element ? event.target : null;
        if (!target) {
            return;
        }
        const editBtn = /** @type {HTMLElement | null} */ (target.closest('[data-qt-edit]'));
        if (editBtn) {
            openEdit(editBtn);
            return;
        }
        const deleteBtn = /** @type {HTMLElement | null} */ (target.closest('[data-qt-delete]'));
        if (deleteBtn) {
            openDelete(deleteBtn);
        }
    });
})();

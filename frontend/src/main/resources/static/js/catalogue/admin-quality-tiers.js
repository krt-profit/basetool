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

import { showFrontendErrorToast } from '/js/kernel/toast.js';
import { krtModal } from '/js/kernel/krt-modal.js';
import { krtPageSections } from '/js/kernel/krt-page-sections.js';

function initAdminQualityTiers() {
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
        form ? form.querySelector('input[name="active"][value="true"]') : null
    );
    const inactiveInput = /** @type {HTMLInputElement | null} */ (
        form ? form.querySelector('input[name="active"][value="false"]') : null
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
        !activeInput ||
        !inactiveInput
    ) {
        return;
    }

    /** @param {string} message */
    function errorToast(message) {
        if (message) {
            showFrontendErrorToast(message);
        }
    }

    /**
     * Checks the active or the inactive option of the status segment.
     * @param {boolean} active
     */
    function setActive(active) {
        if (!activeInput || !inactiveInput) {
            return;
        }
        activeInput.checked = active;
        inactiveInput.checked = !active;
    }

    /**
     * Locks the status segment and the floor for the base tier, whose floor stays 0 and which stays
     * active.
     * @param {boolean} isBase
     */
    function applyBaseLock(isBase) {
        if (!activeInput || !inactiveInput || !floorInput) {
            return;
        }
        activeInput.disabled = isBase;
        inactiveInput.disabled = isBase;
        floorInput.readOnly = isBase;
        if (baseHint) {
            baseHint.hidden = !isBase;
        }
    }

    function openCreate() {
        if (!form || !idInput || !versionInput) {
            return;
        }
        form.reset();
        if (title) {
            title.textContent = QT_MSG.createTitle;
        }
        idInput.value = '';
        versionInput.value = '';
        setActive(true);
        applyBaseLock(false);
        krtModal.open(modal);
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
            !sortInput
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
        setActive(btn.dataset.active === 'true');
        applyBaseLock(btn.dataset.base === 'true');
        krtModal.open(modal);
    }

    /** @param {HTMLElement} btn */
    function openDelete(btn) {
        pendingDeleteId = btn.dataset.id || null;
        if (deleteCode) {
            deleteCode.textContent = btn.dataset.code || '';
        }
        krtModal.open(deleteModal);
    }

    const tiers = krtPageSections.create({
        dict() {
            return {
                'conflict.title': QT_CONFLICT.title,
                'conflict.detail': QT_CONFLICT.reloadDetailFallback,
                'conflict.reload': QT_CONFLICT.reloadLabel,
                'conflict.dismiss': QT_CONFLICT.dismissLabel,
                'conflict.question': QT_CONFLICT.reloadQuestion,
            };
        },
        keys: {
            conflictTitleKey: 'conflict.title',
            reloadDetailKey: 'conflict.detail',
            reloadLabelKey: 'conflict.reload',
            dismissLabelKey: 'conflict.dismiss',
            reloadQuestionKey: 'conflict.question',
        },
        sections: {
            tiers: { container: '#qt-results' },
        },
        pageUrl() {
            return PAGE_URL;
        },
    });

    /**
     * Maps a relayed backend refusal to its own toast; an optimistic-lock conflict falls through to
     * the shared conflict dialog.
     * @param {boolean} deleting
     * @returns {(body: any) => boolean}
     */
    function refusalHandler(deleting) {
        return function (body) {
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

    form.addEventListener('submit', (event) => {
        event.preventDefault();
        if (!idInput) {
            return;
        }
        const id = idInput.value;
        tiers.write({
            method: 'POST',
            url: id ? `${PAGE_URL}/${encodeURIComponent(id)}` : PAGE_URL,
            payload: payload(),
            submitter: submitBtn,
            successMessage: id ? QT_MSG.updated : QT_MSG.created,
            errorMessage: QT_MSG.error,
            onConflict: refusalHandler(false),
            refresh: ['tiers'],
            onSuccess() {
                krtModal.close(modal);
            },
        });
    });

    if (deleteConfirm) {
        deleteConfirm.addEventListener('click', () => {
            if (!pendingDeleteId) {
                return;
            }
            const id = pendingDeleteId;
            tiers.write({
                method: 'POST',
                url: `${PAGE_URL}/${encodeURIComponent(id)}/delete`,
                submitter: deleteConfirm,
                successMessage: QT_MSG.deleted,
                errorMessage: QT_MSG.deleteError,
                onConflict: refusalHandler(true),
                refresh: ['tiers'],
                onSuccess() {
                    pendingDeleteId = null;
                    krtModal.close(deleteModal);
                },
            });
        });
    }

    if (addBtn) {
        addBtn.addEventListener('click', openCreate);
    }

    document.addEventListener('click', (event) => {
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
}

initAdminQualityTiers();

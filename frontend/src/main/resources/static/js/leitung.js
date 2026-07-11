/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */

/*
 * Delegated Leitung page (epic #800, REQ-ROLE-004). Drives the appointment surfaces:
 *  - a shared add-member modal for Bereich / OL (user picker + optional role);
 *  - in-roster controls for squadron ranks + Kommandogruppen, SK-lead toggles and member removals.
 * Every write goes through krtFetch.write (CSRF + retry-on-403 + RFC-7807 toast + OPTIMISTIC_LOCK
 * reload-confirm); on success the whole leitungSections fragment is re-swapped so derived state never
 * desyncs. Per-unit authorisation is enforced by the backend; the page only renders manageable units.
 */
(function () {
    'use strict';

    const i18n = window.leitungI18n || {};

    function reswap() {
        if (!window.krtFetch || !window.krtFetch.swap) {
            window.location.reload();
            return;
        }
        // Return the swap promise so a serialized write awaits the section refresh before the next
        // Leitung write starts (see the serialize key in write()).
        return window.krtFetch.swap({
            url: '/organisation/leitung?fragment=leitungSections',
            container: '#leitung-sections',
            fragmentValue: 'leitungSections',
            history: false,
        });
    }

    function write(opts) {
        if (!window.krtFetch || !window.krtFetch.write) {
            window.location.reload();
            return;
        }
        window.krtFetch.write({
            url: opts.url,
            method: opts.method,
            payload: opts.payload,
            successMessage: opts.success,
            errorMessage: i18n.error,
            conflict: i18n.conflict,
            // Serialize all Leitung writes so back-to-back rank / group / SK-lead actions run in
            // order and never overlap; each success reswaps the whole section (refreshing every
            // row's version) before the next write fires.
            serialize: 'leitung',
            onSuccess: reswap,
        });
    }

    // ----------------------------------------------------- shared add-member modal --

    const modal = document.getElementById('leitung-modal');
    const modalUnit = document.getElementById('leitung-modal-unit');
    const modalRoleGroup = document.getElementById('leitung-modal-role-group');

    // The user picker is upgraded into a searchable combobox by the global krt-searchable-select.js
    // auto-initialiser (the <select> carries data-krt-combobox). Enhancement moves the original id
    // onto the combobox's hidden input, so always resolve the control LIVE — a reference captured
    // before enhancement would point at the detached original <select> and read a stale value.
    function getModalUser() {
        return document.getElementById('leitung-modal-user');
    }
    const modalRole = document.getElementById('leitung-modal-role');
    let modalContext = null;
    let lastTrigger = null;

    function addOption(select, value, label) {
        const opt = document.createElement('option');
        opt.value = value;
        opt.textContent = label;
        select.appendChild(opt);
    }

    function openModal(trigger) {
        const action = trigger.getAttribute('data-leitung-open');
        modalContext = { action: action, unitId: trigger.getAttribute('data-unit-id') };
        if (modalUnit) {
            modalUnit.textContent = trigger.getAttribute('data-unit-name') || '';
        }
        const modalUser = getModalUser();
        if (modalUser) {
            if (modalUser.krtCombobox) {
                modalUser.krtCombobox.setValue('');
            } else {
                modalUser.value = '';
            }
        }
        if (action === 'add-bereich') {
            modalRoleGroup.style.display = '';
            modalRole.innerHTML = '';
            if (trigger.getAttribute('data-can-lead') === 'true') {
                addOption(modalRole, 'LEITER', i18n.rankLeiter);
            }
            if (trigger.getAttribute('data-can-roster') === 'true') {
                addOption(modalRole, 'KOORDINATOR', i18n.rankKoordinator);
                addOption(modalRole, 'OPERATOR', i18n.rankOperator);
            }
        } else {
            modalRoleGroup.style.display = 'none';
        }
        lastTrigger = trigger;
        modal.style.display = 'flex';
        // Reuse the modalUser resolved above (same open call, the control is not replaced in between).
        if (modalUser) {
            // Focus the visible combobox textbox (the resolved element is the hidden input).
            const box = modalUser.closest ? modalUser.closest('.krt-combobox') : null;
            const focusTarget = box ? box.querySelector('.krt-combobox__input') : modalUser;
            if (focusTarget) {
                focusTarget.focus();
            }
        }
    }

    function closeModal() {
        if (!modal) {
            return;
        }
        modal.style.display = 'none';
        modalContext = null;
        if (lastTrigger && typeof lastTrigger.focus === 'function') {
            lastTrigger.focus();
        }
    }

    function submitModal() {
        if (!modalContext) {
            return;
        }
        const modalUser = getModalUser();
        const userId = modalUser ? modalUser.value : '';
        if (!userId) {
            return;
        }
        if (modalContext.action === 'add-ol') {
            write({
                url:
                    '/organisation/leitung/organisationsleitung/' +
                    modalContext.unitId +
                    '/members/ajax',
                method: 'POST',
                payload: { userId: userId },
                success: i18n.saved,
            });
        } else if (modalContext.action === 'add-bereich') {
            const role = modalRole ? modalRole.value : '';
            if (!role) {
                return;
            }
            write({
                url: '/organisation/leitung/bereiche/' + modalContext.unitId + '/members/ajax',
                method: 'POST',
                payload: { userId: userId, role: role },
                success: i18n.saved,
            });
        }
        closeModal();
    }

    if (modal) {
        document.addEventListener('click', function (e) {
            const opener = e.target.closest('[data-leitung-open]');
            if (opener) {
                openModal(opener);
                return;
            }
            if (e.target.closest('[data-leitung-cancel]')) {
                closeModal();
                return;
            }
            if (e.target.closest('#leitung-modal-submit')) {
                submitModal();
                return;
            }
            if (e.target === modal) {
                closeModal();
            }
        });
        document.addEventListener('keydown', function (e) {
            if (e.key === 'Escape' && window.getComputedStyle(modal).display === 'flex') {
                closeModal();
            }
        });
    }

    // ------------------------------------------------ delegated in-fragment actions --

    const sections = document.getElementById('leitung-sections');

    function needsGroup(rank) {
        return rank === 'KOMMANDOLEITER' || rank === 'STELLV_KOMMANDOLEITER' || rank === 'ENSIGN';
    }

    function toggleGroupSelect(rankSelect) {
        const row = rankSelect.closest('.leitung-rank-row');
        if (!row) {
            return;
        }
        const groupSelect = row.querySelector('.leitung-group-select');
        if (groupSelect) {
            groupSelect.disabled = !needsGroup(rankSelect.value);
        }
    }

    function saveRank(btn) {
        const unitId = btn.getAttribute('data-unit-id');
        const row = btn.closest('.leitung-rank-row');
        if (!row) {
            return;
        }
        const userId = row.getAttribute('data-user-id');
        const version = row.getAttribute('data-version');
        const rank = row.querySelector('.leitung-rank-select').value;
        if (rank === 'MEMBER') {
            write({
                url:
                    '/organisation/leitung/squadrons/' +
                    unitId +
                    '/ranks/' +
                    userId +
                    '/ajax?version=' +
                    encodeURIComponent(version),
                method: 'DELETE',
                success: i18n.deleted,
            });
            return;
        }
        const payload = { role: rank, version: Number(version) };
        const groupSelect = row.querySelector('.leitung-group-select');
        if (needsGroup(rank) && groupSelect && groupSelect.value) {
            payload.kommandoGroupId = groupSelect.value;
        }
        write({
            url: '/organisation/leitung/squadrons/' + unitId + '/ranks/' + userId + '/ajax',
            method: 'PUT',
            payload: payload,
            success: i18n.saved,
        });
    }

    function createGroup(btn) {
        const unitId = btn.getAttribute('data-unit-id');
        const card = btn.closest('.leitung-group-add');
        const input = card ? card.querySelector('.leitung-new-group-name') : null;
        const name = input ? input.value.trim() : '';
        if (!name) {
            return;
        }
        write({
            url: '/organisation/leitung/squadrons/' + unitId + '/kommando-groups/ajax',
            method: 'POST',
            payload: { name: name },
            success: i18n.saved,
        });
    }

    function renameGroup(btn) {
        const card = btn.closest('.leitung-group');
        if (!card) {
            return;
        }
        const input = card.querySelector('.leitung-group-name');
        const name = input ? input.value.trim() : '';
        if (!name) {
            return;
        }
        write({
            url:
                '/organisation/leitung/kommando-groups/' +
                card.getAttribute('data-group-id') +
                '/ajax',
            method: 'PUT',
            payload: {
                name: name,
                sortIndex: Number(card.getAttribute('data-group-sort')),
                version: Number(card.getAttribute('data-group-version')),
            },
            success: i18n.saved,
        });
    }

    function deleteGroup(btn) {
        const card = btn.closest('.leitung-group');
        if (!card) {
            return;
        }
        write({
            url:
                '/organisation/leitung/kommando-groups/' +
                card.getAttribute('data-group-id') +
                '/ajax',
            method: 'DELETE',
            success: i18n.deleted,
        });
    }

    function toggleSkLead(btn) {
        const unitId = btn.getAttribute('data-unit-id');
        const row = btn.closest('tr');
        if (!row) {
            return;
        }
        write({
            url:
                '/organisation/leitung/special-commands/' +
                unitId +
                '/members/' +
                row.getAttribute('data-user-id') +
                '/lead/ajax',
            method: 'PATCH',
            payload: {
                isLead: btn.getAttribute('data-lead') !== 'true',
                version: Number(row.getAttribute('data-version')),
            },
            success: i18n.saved,
        });
    }

    function removeMember(btn, segment) {
        write({
            url:
                '/organisation/leitung/' +
                segment +
                '/' +
                btn.getAttribute('data-unit-id') +
                '/members/' +
                btn.getAttribute('data-user-id') +
                '/ajax',
            method: 'DELETE',
            success: i18n.deleted,
        });
    }

    function handleAction(action, btn) {
        if (action === 'save-rank') {
            saveRank(btn);
        } else if (action === 'create-group') {
            createGroup(btn);
        } else if (action === 'rename-group') {
            renameGroup(btn);
        } else if (action === 'delete-group') {
            deleteGroup(btn);
        } else if (action === 'toggle-sk-lead') {
            toggleSkLead(btn);
        } else if (action === 'remove-bereich-role') {
            removeMember(btn, 'bereiche');
        } else if (action === 'remove-ol-member') {
            removeMember(btn, 'organisationsleitung');
        }
    }

    if (sections) {
        sections.addEventListener('click', function (e) {
            const btn = e.target.closest('[data-leitung-action]');
            if (btn) {
                handleAction(btn.getAttribute('data-leitung-action'), btn);
            }
        });
        sections.addEventListener('change', function (e) {
            const sel = e.target.closest('.leitung-rank-select');
            if (sel) {
                toggleGroupSelect(sel);
            }
        });
        Array.prototype.forEach.call(
            sections.querySelectorAll('.leitung-rank-select'),
            toggleGroupSelect,
        );
    }
})();

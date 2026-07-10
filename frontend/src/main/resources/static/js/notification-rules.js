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
 * Admin editor for the data-driven notification rules (epic #622, REQ-NOTIF-007).
 *
 * Manages the dynamic selector editor (add/remove rows, show only the fields a selector kind
 * needs), builds the rule JSON, and creates/updates/deletes via window.krtFetch (CSRF + 403-retry
 * handled centrally). On a successful mutation the page reloads — the rule editor is an admin,
 * low-traffic surface where a reload is the simplest correct refresh (CLAUDE.md no-reload
 * fallback). Destructive delete confirms through the non-native window.showKrtConfirm dialog.
 */
(function () {
    const form = document.getElementById('rule-form');
    if (!form) {
        return;
    }
    const container = document.getElementById('selectors-container');
    const template = document.getElementById('selector-row-template');
    const i18n = readMessages();

    function readMessages() {
        const holder = document.getElementById('rule-i18n');
        const data = holder ? holder.dataset : {};
        return {
            confirmDeleteTitle: data.confirmDeleteTitle || 'Delete rule',
            confirmDeleteBody: data.confirmDeleteBody || 'Delete this rule?',
            confirmOk: data.confirmOk || 'Delete',
            confirmCancel: data.confirmCancel || 'Cancel',
            saved: data.saved || 'Saved',
            deleted: data.deleted || 'Deleted',
            error: data.error || 'Action failed',
        };
    }

    // ---- selector rows ------------------------------------------------------

    function toggleRow(row) {
        const kind = row.querySelector('[data-selector-kind]').value;
        setFieldVisible(row, 'orgRelativeRole', kind === 'ORG_RELATIVE_ROLE');
        setFieldVisible(row, 'contextRole', kind === 'ORG_RELATIVE_ROLE');
        setFieldVisible(row, 'roleCode', kind === 'ROLE');
        setFieldVisible(row, 'userSub', kind === 'SPECIFIC_USER');
    }

    function setFieldVisible(row, field, visible) {
        const group = row.querySelector('[data-field="' + field + '"]');
        if (group) {
            group.hidden = !visible;
        }
    }

    function addSelectorRow(selector) {
        const fragment = template.content.cloneNode(true);
        const row = fragment.querySelector('[data-selector-row]');
        if (selector) {
            setValue(row, '[data-selector-kind]', selector.kind);
            setValue(row, '[data-selector-orgRelativeRole]', selector.orgRelativeRole);
            setValue(row, '[data-selector-contextRole]', selector.contextRole);
            setValue(row, '[data-selector-roleCode]', selector.roleCode);
        }
        container.appendChild(row);
        const appended = container.lastElementChild;
        toggleRow(appended);
        // Upgrade the freshly cloned selector row's SPECIFIC_USER picker into a searchable combobox.
        // The picker now searches the roster server-side (remote-users, #1193) instead of preloading
        // every user, so an existing rule's chosen user is seeded here in edit mode: resolve its
        // display name via /users/{id}, inject one selected <option>, THEN enhance (the enhancer
        // seeds its committed value/label from that option). New rows have no user and enhance at
        // once.
        enhanceSelectorRow(appended, selector);
        return appended;
    }

    // Enhances a selector row's SPECIFIC_USER combobox. When the row carries a preselected user
    // (edit mode), seeds one <option> (value + resolved display name) before enhancing so the box
    // shows the name, not a blank field; a failed name lookup falls back to the raw id so the value
    // is never lost. All other rows (and non-user selectors) enhance immediately.
    function enhanceSelectorRow(row, selector) {
        const enhance = function () {
            if (window.krtEnhanceComboboxes) {
                window.krtEnhanceComboboxes(row);
            }
        };
        if (!selector || selector.kind !== 'SPECIFIC_USER' || !selector.userSub) {
            enhance();
            return;
        }
        const select = row.querySelector('[data-selector-userSub]');
        fetch('/users/' + encodeURIComponent(selector.userSub), {
            headers: { Accept: 'application/json' },
        })
            .then(function (response) {
                return response.ok ? response.json() : null;
            })
            .catch(function () {
                return null;
            })
            .then(function (user) {
                if (select) {
                    const option = document.createElement('option');
                    option.value = selector.userSub;
                    option.textContent =
                        (user && (user.effectiveName || user.displayName || user.username)) ||
                        selector.userSub;
                    option.selected = true;
                    select.appendChild(option);
                }
                enhance();
            });
    }

    function setValue(row, selector, value) {
        const el = row.querySelector(selector);
        if (el && value != null) {
            el.value = value;
        }
    }

    function readValue(row, selector) {
        const el = row.querySelector(selector);
        const value = el && el.value != null ? el.value.trim() : '';
        return value === '' ? null : value;
    }

    function collectSelectors() {
        const rows = container.querySelectorAll('[data-selector-row]');
        return Array.prototype.map.call(rows, function (row) {
            const kind = row.querySelector('[data-selector-kind]').value;
            const selector = { kind: kind };
            if (kind === 'ORG_RELATIVE_ROLE') {
                selector.orgRelativeRole = readValue(row, '[data-selector-orgRelativeRole]');
                selector.contextRole = readValue(row, '[data-selector-contextRole]');
            } else if (kind === 'ROLE') {
                selector.roleCode = readValue(row, '[data-selector-roleCode]');
            } else if (kind === 'SPECIFIC_USER') {
                selector.userSub = readValue(row, '[data-selector-userSub]');
            }
            return selector;
        });
    }

    function buildPayload() {
        const version = document.getElementById('rule-version').value;
        const description = document.getElementById('rule-description').value.trim();
        return {
            eventType: document.getElementById('rule-eventType').value,
            notificationType: document.getElementById('rule-notificationType').value,
            description: description === '' ? null : description,
            enabled: document.getElementById('rule-enabled').checked,
            excludeActor: document.getElementById('rule-excludeActor').checked,
            version: version === '' ? null : Number(version),
            selectors: collectSelectors(),
        };
    }

    // ---- form mode ----------------------------------------------------------

    function resetForm() {
        form.setAttribute('data-rule-id', '');
        document.getElementById('rule-version').value = '';
        document.getElementById('rule-eventType').value = 'JOB_ORDER_CREATED';
        document.getElementById('rule-notificationType').value = 'JOB_ORDER_CREATED';
        document.getElementById('rule-description').value = '';
        document.getElementById('rule-enabled').checked = true;
        document.getElementById('rule-excludeActor').checked = true;
        container.innerHTML = '';
        addSelectorRow(null);
    }

    function prefillForm(rule) {
        form.setAttribute('data-rule-id', rule.id);
        document.getElementById('rule-version').value = rule.version != null ? rule.version : '';
        document.getElementById('rule-eventType').value = rule.eventType;
        document.getElementById('rule-notificationType').value = rule.notificationType;
        document.getElementById('rule-description').value = rule.description || '';
        document.getElementById('rule-enabled').checked = !!rule.enabled;
        document.getElementById('rule-excludeActor').checked = !!rule.excludeActor;
        container.innerHTML = '';
        const selectors = Array.isArray(rule.selectors) ? rule.selectors : [];
        if (selectors.length === 0) {
            addSelectorRow(null);
        } else {
            selectors.forEach(addSelectorRow);
        }
        form.scrollIntoView({ behavior: 'smooth' });
    }

    function editRule(id) {
        fetch('/admin/notification-rules/' + encodeURIComponent(id), {
            headers: { Accept: 'application/json', 'X-Requested-With': 'XMLHttpRequest' },
        })
            .then(function (res) {
                return res.ok ? res.json() : null;
            })
            .then(function (rule) {
                if (rule) {
                    prefillForm(rule);
                }
            })
            .catch(function () {
                if (typeof window.showFrontendErrorToast === 'function') {
                    window.showFrontendErrorToast(i18n.error);
                }
            });
    }

    // ---- submit / delete ----------------------------------------------------

    function onSubmit(event) {
        event.preventDefault();
        if (!window.krtFetch) {
            return;
        }
        const id = form.getAttribute('data-rule-id');
        const submitter = form.querySelector('button[type="submit"]');
        window.krtFetch.write({
            method: id ? 'PUT' : 'POST',
            url: '/admin/notification-rules' + (id ? '/' + encodeURIComponent(id) : ''),
            payload: buildPayload(),
            successMessage: i18n.saved,
            errorMessage: i18n.error,
            submitter: submitter,
            onSuccess: function () {
                window.location.reload();
            },
        });
    }

    function deleteRule(id, submitter) {
        if (!window.krtFetch) {
            return;
        }
        confirmThen(function () {
            window.krtFetch.write({
                method: 'DELETE',
                url: '/admin/notification-rules/' + encodeURIComponent(id),
                successMessage: i18n.deleted,
                errorMessage: i18n.error,
                submitter: submitter,
                onSuccess: function () {
                    window.location.reload();
                },
            });
        });
    }

    function confirmThen(action) {
        if (typeof window.showKrtConfirm === 'function') {
            window
                .showKrtConfirm(
                    i18n.confirmDeleteTitle,
                    i18n.confirmDeleteBody,
                    i18n.confirmOk,
                    i18n.confirmCancel,
                )
                .then(function (ok) {
                    if (ok) {
                        action();
                    }
                });
        } else {
            action();
        }
    }

    // ---- wiring -------------------------------------------------------------

    form.addEventListener('submit', onSubmit);
    document.getElementById('add-selector').addEventListener('click', function () {
        addSelectorRow(null);
    });
    document.getElementById('rule-cancel').addEventListener('click', resetForm);

    container.addEventListener('change', function (event) {
        const kindSelect = event.target.closest('[data-selector-kind]');
        if (kindSelect) {
            toggleRow(kindSelect.closest('[data-selector-row]'));
        }
    });
    container.addEventListener('click', function (event) {
        const removeBtn = event.target.closest('[data-selector-remove]');
        if (removeBtn) {
            const row = removeBtn.closest('[data-selector-row]');
            if (row) {
                row.remove();
            }
        }
    });

    document.addEventListener('click', function (event) {
        const editBtn = event.target.closest('[data-rule-edit]');
        if (editBtn) {
            const tr = editBtn.closest('[data-rule-id]');
            if (tr) {
                editRule(tr.getAttribute('data-rule-id'));
            }
            return;
        }
        const deleteBtn = event.target.closest('[data-rule-delete]');
        if (deleteBtn) {
            const tr = deleteBtn.closest('[data-rule-id]');
            if (tr) {
                deleteRule(tr.getAttribute('data-rule-id'), deleteBtn);
            }
        }
    });

    resetForm();
})();

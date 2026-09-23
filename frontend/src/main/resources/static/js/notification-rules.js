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

// @ts-check

/*
 * Admin editor for the data-driven notification rules (epic #622, REQ-NOTIF-007).
 *
 * Manages the dynamic selector editor (add/remove rows, show only the fields a selector kind
 * needs), builds the rule JSON, and creates/updates/deletes via window.krtFetch (CSRF + 403-retry
 * handled centrally). A successful mutation re-fetches the rules table in place — the `rules`
 * fragment of /admin/notification-rules swapped into #rules-host — and puts the form back into
 * create mode; the page never reloads (REQ-FE-001). The edit / delete buttons are delegated on the
 * document, so the swapped-in rows work without re-binding. There is no peer broadcast: no admin
 * catalogue page takes part in the live multi-user sync, and this one follows them. Destructive
 * delete confirms through the non-native window.showKrtConfirm dialog.
 */
(function () {
    const form = document.getElementById('rule-form');
    const container = document.getElementById('selectors-container');
    const template = document.getElementById('selector-row-template');
    const version = document.getElementById('rule-version');
    const eventType = document.getElementById('rule-eventType');
    const notificationType = document.getElementById('rule-notificationType');
    const description = document.getElementById('rule-description');
    const enabled = document.getElementById('rule-enabled');
    const excludeActor = document.getElementById('rule-excludeActor');
    if (
        !(form instanceof HTMLFormElement) ||
        !container ||
        !(template instanceof HTMLTemplateElement) ||
        !(version instanceof HTMLInputElement) ||
        !(eventType instanceof HTMLSelectElement) ||
        !(notificationType instanceof HTMLSelectElement) ||
        !(description instanceof HTMLInputElement) ||
        !(enabled instanceof HTMLInputElement) ||
        !(excludeActor instanceof HTMLInputElement)
    ) {
        return;
    }
    wire({
        form,
        container,
        template,
        version,
        eventType,
        notificationType,
        description,
        enabled,
        excludeActor,
    });

    /**
     * The page's form controls, resolved and type-checked once.
     * @typedef {object} RuleFormElements
     * @property {HTMLFormElement} form
     * @property {HTMLElement} container the selector-row list
     * @property {HTMLTemplateElement} template the selector-row template
     * @property {HTMLInputElement} version the optimistic-lock version of the rule being edited
     * @property {HTMLSelectElement} eventType
     * @property {HTMLSelectElement} notificationType
     * @property {HTMLInputElement} description
     * @property {HTMLInputElement} enabled
     * @property {HTMLInputElement} excludeActor
     */

    /**
     * Installs the editor on the resolved controls.
     * @param {RuleFormElements} el
     */
    function wire(el) {
        const i18n = readMessages();

        /**
         * The selector kinds that take the account or the recipient from the event and have no
         * further field: the row shows the fromEvent hint and sends only its kind.
         */
        const EVENT_DERIVED_KINDS = ['ACCOUNT_GRANT', 'EVENT_RECIPIENT', 'ACCOUNT_RESPONSIBLE'];

        /** The kind a new, empty selector row starts with. */
        const DEFAULT_KIND = 'ORG_RELATIVE_ROLE';

        function readMessages() {
            const holder = document.getElementById('rule-i18n');
            const data = holder ? holder.dataset : /** @type {DOMStringMap} */ ({});
            return {
                confirmDeleteTitle: window.krtI18nText(
                    data.confirmDeleteTitle,
                    'data-confirm-delete-title',
                ),
                confirmDeleteBody: window.krtI18nText(
                    data.confirmDeleteBody,
                    'data-confirm-delete-body',
                ),
                confirmOk: window.krtI18nText(data.confirmOk, 'data-confirm-ok'),
                confirmCancel: window.krtI18nText(data.confirmCancel, 'data-confirm-cancel'),
                saved: window.krtI18nText(data.saved, 'data-saved'),
                deleted: window.krtI18nText(data.deleted, 'data-deleted'),
                error: window.krtI18nText(data.error, 'data-error'),
                unknownValue: data.unknownValue || '{0}',
            };
        }

        // ---- selects ------------------------------------------------------------

        /**
         * Selects `value` in a `<select>`. A value with no matching option — a backend enum value
         * newer than this page — is not dropped: an option carrying the raw code is added (marked
         * data-unknown-option, labelled with the `selector.unknown` text) so the value is shown as
         * unknown and round-trips unchanged on save instead of silently becoming '' or the first
         * option.
         * @param {HTMLSelectElement | null} select
         * @param {string | null | undefined} value
         */
        function selectValue(select, value) {
            if (!select || value == null || value === '') {
                return;
            }
            const code = value;
            const known = Array.from(select.options).some(function (option) {
                return option.value === code;
            });
            if (!known) {
                const option = document.createElement('option');
                option.value = code;
                option.textContent = unknownLabel(code);
                option.setAttribute('data-unknown-option', '');
                select.appendChild(option);
            }
            select.value = code;
        }

        /**
         * The label of an option added for an unknown code: the `selector.unknown` text with its
         * `{0}` placeholder replaced by the code, or followed by the code when it has none.
         * @param {string} code
         * @returns {string}
         */
        function unknownLabel(code) {
            const text = i18n.unknownValue;
            // A replacer function, so a `$` in the code is never read as a replacement pattern.
            return text.includes('{0}')
                ? text.replace('{0}', function () {
                      return code;
                  })
                : text + ' ' + code;
        }

        /**
         * Removes the options selectValue added for unknown values, so they do not linger into the
         * next rule or into create mode.
         * @param {HTMLSelectElement} select
         */
        function dropUnknownOptions(select) {
            select.querySelectorAll('[data-unknown-option]').forEach(function (option) {
                option.remove();
            });
        }

        /**
         * @param {ParentNode} row
         * @param {string} name the data-selector-* attribute suffix
         * @returns {HTMLSelectElement | null}
         */
        function rowSelect(row, name) {
            return /** @type {HTMLSelectElement | null} */ (
                row.querySelector('[data-selector-' + name + ']')
            );
        }

        // ---- selector rows ------------------------------------------------------

        /** @param {Element} row */
        function toggleRow(row) {
            const kindSelect = rowSelect(row, 'kind');
            const kind = kindSelect ? kindSelect.value : '';
            setFieldVisible(row, 'orgRelativeRole', kind === 'ORG_RELATIVE_ROLE');
            setFieldVisible(row, 'contextRole', kind === 'ORG_RELATIVE_ROLE');
            setFieldVisible(row, 'roleCode', kind === 'ROLE');
            setFieldVisible(row, 'userId', kind === 'SPECIFIC_USER');
            setFieldVisible(row, 'fromEvent', EVENT_DERIVED_KINDS.includes(kind));
        }

        /**
         * @param {Element} row
         * @param {string} field
         * @param {boolean} visible
         */
        function setFieldVisible(row, field, visible) {
            const group = /** @type {HTMLElement | null} */ (
                row.querySelector('[data-field="' + field + '"]')
            );
            if (group) {
                group.hidden = !visible;
            }
        }

        /**
         * Appends one selector row, prefilled from `selector` in edit mode.
         * @param {ApiDto<'NotificationRuleSelectorDto'> | null} selector
         */
        function addSelectorRow(selector) {
            const fragment = /** @type {DocumentFragment} */ (el.template.content.cloneNode(true));
            const row = fragment.querySelector('[data-selector-row]');
            if (!row) {
                return;
            }
            if (selector) {
                selectValue(rowSelect(row, 'kind'), selector.kind);
                selectValue(rowSelect(row, 'orgRelativeRole'), selector.orgRelativeRole);
                selectValue(rowSelect(row, 'contextRole'), selector.contextRole);
                selectValue(rowSelect(row, 'roleCode'), selector.roleCode);
            } else {
                selectValue(rowSelect(row, 'kind'), DEFAULT_KIND);
            }
            el.container.appendChild(row);
            toggleRow(row);
            // Upgrade the freshly cloned selector row's SPECIFIC_USER picker into a searchable
            // combobox. The picker searches the roster server-side (remote-users, #1193) instead of
            // preloading every user, so an existing rule's chosen user is seeded here in edit mode:
            // resolve its display name via /users/{id}, inject one selected <option>, THEN enhance
            // (the enhancer seeds its committed value/label from that option). New rows have no
            // user and enhance at once.
            enhanceSelectorRow(row, selector);
        }

        /**
         * Enhances a selector row's SPECIFIC_USER combobox. When the row carries a preselected
         * user (edit mode), seeds one <option> (value + resolved display name) before enhancing so
         * the box shows the name, not a blank field; a failed name lookup falls back to the raw id
         * so the value is never lost. All other rows (and non-user selectors) enhance immediately.
         * @param {Element} row
         * @param {ApiDto<'NotificationRuleSelectorDto'> | null} selector
         */
        function enhanceSelectorRow(row, selector) {
            const enhance = function () {
                if (window.krtEnhanceComboboxes) {
                    window.krtEnhanceComboboxes(row);
                }
            };
            const userId = selector && selector.kind === 'SPECIFIC_USER' ? selector.userId : null;
            if (!userId) {
                enhance();
                return;
            }
            const select = rowSelect(row, 'userId');
            fetch('/users/' + encodeURIComponent(userId), {
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
                        option.value = userId;
                        option.textContent =
                            (user && (user.effectiveName || user.displayName || user.username)) ||
                            userId;
                        option.selected = true;
                        select.appendChild(option);
                    }
                    enhance();
                });
        }

        /**
         * @param {Element} row
         * @param {string} name the data-selector-* attribute suffix
         * @returns {string | null}
         */
        function readValue(row, name) {
            const select = rowSelect(row, name);
            const value = select ? select.value.trim() : '';
            return value === '' ? null : value;
        }

        function collectSelectors() {
            const rows = el.container.querySelectorAll('[data-selector-row]');
            return Array.from(rows).map(function (row) {
                const kind = readValue(row, 'kind');
                /** @type {Record<string, string | null>} */
                const selector = { kind };
                // The event-derived kinds send only their kind: they read no selector field.
                if (kind === 'ORG_RELATIVE_ROLE') {
                    selector.orgRelativeRole = readValue(row, 'orgRelativeRole');
                    selector.contextRole = readValue(row, 'contextRole');
                } else if (kind === 'ROLE') {
                    selector.roleCode = readValue(row, 'roleCode');
                } else if (kind === 'SPECIFIC_USER') {
                    selector.userId = readValue(row, 'userId');
                }
                return selector;
            });
        }

        function buildPayload() {
            const versionValue = el.version.value;
            const descriptionValue = el.description.value.trim();
            return {
                eventType: el.eventType.value,
                notificationType: el.notificationType.value,
                description: descriptionValue === '' ? null : descriptionValue,
                enabled: el.enabled.checked,
                excludeActor: el.excludeActor.checked,
                version: versionValue === '' ? null : Number(versionValue),
                selectors: collectSelectors(),
            };
        }

        // ---- form mode ----------------------------------------------------------

        /** Puts the form back into create mode with one empty selector row. */
        function resetForm() {
            el.form.setAttribute('data-rule-id', '');
            el.version.value = '';
            dropUnknownOptions(el.eventType);
            dropUnknownOptions(el.notificationType);
            el.eventType.selectedIndex = 0;
            el.notificationType.selectedIndex = 0;
            el.description.value = '';
            el.enabled.checked = true;
            el.excludeActor.checked = true;
            el.container.innerHTML = '';
            addSelectorRow(null);
        }

        /** @param {ApiDto<'NotificationRuleDto'>} rule */
        function prefillForm(rule) {
            el.form.setAttribute('data-rule-id', rule.id || '');
            el.version.value = rule.version != null ? String(rule.version) : '';
            dropUnknownOptions(el.eventType);
            dropUnknownOptions(el.notificationType);
            selectValue(el.eventType, rule.eventType);
            selectValue(el.notificationType, rule.notificationType);
            el.description.value = rule.description || '';
            el.enabled.checked = !!rule.enabled;
            el.excludeActor.checked = !!rule.excludeActor;
            el.container.innerHTML = '';
            const selectors = Array.isArray(rule.selectors) ? rule.selectors : [];
            if (selectors.length === 0) {
                addSelectorRow(null);
            } else {
                selectors.forEach(function (selector) {
                    addSelectorRow(selector);
                });
            }
            el.form.scrollIntoView({ behavior: 'smooth' });
        }

        /** @param {string} id */
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

        // ---- live refresh -------------------------------------------------------

        /** Re-renders the rules table in place from the `rules` fragment. */
        function refreshRules() {
            return window.krtFetch.swap({
                url: '/admin/notification-rules',
                container: '#rules-host',
                fragmentValue: 'rules',
                errorMessage: i18n.error,
            });
        }

        // ---- submit / delete ----------------------------------------------------

        /** @param {SubmitEvent} event */
        function onSubmit(event) {
            event.preventDefault();
            if (!window.krtFetch) {
                return;
            }
            const id = el.form.getAttribute('data-rule-id');
            const submitter = el.form.querySelector('button[type="submit"]');
            window.krtFetch.write({
                method: id ? 'PUT' : 'POST',
                url: '/admin/notification-rules' + (id ? '/' + encodeURIComponent(id) : ''),
                payload: buildPayload(),
                successMessage: i18n.saved,
                errorMessage: i18n.error,
                submitter,
                onSuccess() {
                    resetForm();
                    return refreshRules();
                },
            });
        }

        /**
         * @param {string} id
         * @param {Element} submitter
         */
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
                    submitter,
                    onSuccess() {
                        // Only the rule being edited is taken out of the form: an admin half-way
                        // through editing another rule keeps their input.
                        if (el.form.getAttribute('data-rule-id') === id) {
                            resetForm();
                        }
                        return refreshRules();
                    },
                });
            });
        }

        /** @param {() => void} action */
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

        el.form.addEventListener('submit', onSubmit);
        const addSelector = document.getElementById('add-selector');
        if (addSelector) {
            addSelector.addEventListener('click', function () {
                addSelectorRow(null);
            });
        }
        const cancel = document.getElementById('rule-cancel');
        if (cancel) {
            cancel.addEventListener('click', resetForm);
        }

        el.container.addEventListener('change', function (event) {
            const target = event.target;
            const kindSelect =
                target instanceof Element ? target.closest('[data-selector-kind]') : null;
            const row = kindSelect ? kindSelect.closest('[data-selector-row]') : null;
            if (row) {
                toggleRow(row);
            }
        });
        el.container.addEventListener('click', function (event) {
            const target = event.target;
            const removeBtn =
                target instanceof Element ? target.closest('[data-selector-remove]') : null;
            const row = removeBtn ? removeBtn.closest('[data-selector-row]') : null;
            if (row) {
                row.remove();
            }
        });

        // Delegated on the document, not bound to the rows: the rows are replaced by every
        // refreshRules() swap, and these handlers must keep working on the swapped-in ones.
        document.addEventListener('click', function (event) {
            const target = event.target;
            if (!(target instanceof Element)) {
                return;
            }
            const editBtn = target.closest('[data-rule-edit]');
            if (editBtn) {
                const tr = editBtn.closest('[data-rule-id]');
                const id = tr ? tr.getAttribute('data-rule-id') : null;
                if (id) {
                    editRule(id);
                }
                return;
            }
            const deleteBtn = target.closest('[data-rule-delete]');
            if (deleteBtn) {
                const tr = deleteBtn.closest('[data-rule-id]');
                const id = tr ? tr.getAttribute('data-rule-id') : null;
                if (id) {
                    deleteRule(id, deleteBtn);
                }
            }
        });

        resetForm();
    }
})();

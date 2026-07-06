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
 * Admin material-aliases page module (/admin/material-aliases), extracted verbatim from the former
 * inline script of admin/material-aliases.html (ADR-0069, follow-up to #924).
 *
 * On DOMContentLoaded wires the in-place alias CRUD: one delegated submit listener handles the
 * create, update and delete forms via krtFetch.write (KRT conflict dialog), maintaining the list
 * rows and the "no aliases yet" placeholder in place and echoing the new @Version back into the
 * edit form. Native form submit stays the no-JS fallback.
 *
 * The ALIAS_MSG toast strings and the ALIAS_CONFLICT dialog labels are defined by the inline
 * Thymeleaf bootstrap block of admin/material-aliases.html, which executes immediately before this
 * classic script.
 */

/* global ALIAS_MSG, ALIAS_CONFLICT */

document.addEventListener('DOMContentLoaded', function () {
    const table = document.getElementById('aliasesTable');
    const tbody = table ? table.querySelector('tbody') : null;

    // Reads a form field's trimmed value, mapping an empty string to null so optional fields
    // (and especially the UUID-typed externalUuid) deserialize cleanly server-side.
    function fieldOrNull(form, name) {
        const el = form.elements[name];
        const value = el && el.value != null ? el.value.trim() : '';
        return value === '' ? null : value;
    }

    function aliasPayload(form) {
        const payload = {
            materialId: fieldOrNull(form, 'materialId'),
            sourceSystem: fieldOrNull(form, 'sourceSystem'),
            externalName: fieldOrNull(form, 'externalName'),
            externalKey: fieldOrNull(form, 'externalKey'),
            externalUuid: fieldOrNull(form, 'externalUuid'),
            externalCode: fieldOrNull(form, 'externalCode'),
            note: fieldOrNull(form, 'note'),
        };
        const versionEl = form.elements['version'];
        if (versionEl && versionEl.value !== '') {
            payload.version = Number(versionEl.value);
        }
        return payload;
    }

    // Builds a list row from the created alias. The trash SVG is constant markup (no user data);
    // all alias values are written via textContent. The new delete form is handled by the
    // delegated submit listener below, so no per-row binding is needed.
    // Rebuilds the "no aliases yet" placeholder row removed by the first create, so deleting the
    // last alias restores it instead of leaving a header over an empty body.
    function buildEmptyAliasRow() {
        const tr = document.createElement('tr');
        tr.setAttribute('data-alias-empty', '');
        const td = document.createElement('td');
        td.colSpan = 7;
        td.textContent = ALIAS_MSG.empty;
        tr.appendChild(td);
        return tr;
    }

    function buildAliasRow(alias) {
        const tr = document.createElement('tr');
        tr.setAttribute('data-alias-id', alias.id);
        [
            'sourceSystem',
            'externalName',
            'materialName',
            'externalKey',
            'externalCode',
            'createdBy',
        ].forEach(function (key) {
            const td = document.createElement('td');
            td.textContent = alias[key] != null ? alias[key] : '';
            tr.appendChild(td);
        });
        const actionTd = document.createElement('td');
        const wrap = document.createElement('div');
        wrap.className = 'flex-gap-xs';
        const form = document.createElement('form');
        form.method = 'post';
        form.action = '/admin/material-aliases/' + encodeURIComponent(alias.id) + '/delete';
        form.className = 'm-0';
        form.setAttribute('data-alias-delete', '');
        const btn = document.createElement('button');
        btn.type = 'submit';
        btn.className = 'btn btn-quiet-danger btn-icon';
        btn.title = ALIAS_MSG.deleteTitle;
        btn.setAttribute('aria-label', ALIAS_MSG.deleteTitle);
        btn.innerHTML =
            '<svg class="krt-icon" aria-hidden="true"><use href="#krt-icon-trash"/></svg>';
        form.appendChild(btn);
        wrap.appendChild(form);
        actionTd.appendChild(wrap);
        tr.appendChild(actionTd);
        return tr;
    }

    // Re-renders the first five data cells of an existing list row after an edit.
    function patchAliasRow(alias) {
        const row = tbody ? tbody.querySelector('tr[data-alias-id="' + alias.id + '"]') : null;
        if (!row) {
            return;
        }
        const cells = row.querySelectorAll('td');
        const values = [
            alias.sourceSystem,
            alias.externalName,
            alias.materialName,
            alias.externalKey,
            alias.externalCode,
        ];
        values.forEach(function (value, i) {
            if (cells[i]) {
                cells[i].textContent = value != null ? value : '';
            }
        });
    }

    document.addEventListener('submit', function (event) {
        const createForm = event.target.closest('form[data-alias-create]');
        const updateForm = event.target.closest('form[data-alias-update]');
        const deleteForm = event.target.closest('form[data-alias-delete]');
        const form = createForm || updateForm || deleteForm;
        if (!form) {
            return;
        }
        event.preventDefault();
        if (!window.krtFetch) {
            form.submit();
            return;
        }
        const submitBtn = form.querySelector('button[type="submit"]');
        if (submitBtn) {
            submitBtn.disabled = true;
        }

        const opts = {
            method: 'POST',
            url: form.getAttribute('action'),
            conflict: ALIAS_CONFLICT,
        };
        if (deleteForm) {
            opts.successMessage = ALIAS_MSG.deleteSuccess;
            opts.errorMessage = ALIAS_MSG.deleteError;
            opts.onSuccess = function () {
                const row = form.closest('tr');
                const tb = row ? row.parentElement : null;
                if (row) {
                    row.remove();
                }
                // Restore the placeholder when the last alias is gone (the old full reload
                // re-rendered this server-side empty-state row).
                if (tb && !tb.querySelector('tr:not([data-alias-empty])')) {
                    tb.appendChild(buildEmptyAliasRow());
                }
            };
        } else {
            opts.payload = aliasPayload(form);
            opts.successMessage = ALIAS_MSG.saveSuccess;
            opts.errorMessage = ALIAS_MSG.saveError;
            if (createForm) {
                opts.onSuccess = function (created) {
                    if (!created || created.id == null || !tbody) {
                        return;
                    }
                    const emptyRow = tbody.querySelector('[data-alias-empty]');
                    if (emptyRow) {
                        emptyRow.remove();
                    }
                    tbody.appendChild(buildAliasRow(created));
                    form.reset();
                };
            } else {
                opts.onSuccess = function (updated) {
                    if (!updated) {
                        return;
                    }
                    const versionInput = form.elements['version'];
                    if (versionInput && updated.version != null) {
                        versionInput.value = updated.version;
                    }
                    patchAliasRow(updated);
                };
            }
        }
        window.krtFetch.write(opts).finally(function () {
            if (submitBtn) {
                submitBtn.disabled = false;
            }
        });
    });
});

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

/* global ALIAS_MSG, ALIAS_CONFLICT */

document.addEventListener('DOMContentLoaded', function () {
    const table = document.getElementById('aliasesTable');
    const tbody = table ? table.querySelector('tbody') : null;

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

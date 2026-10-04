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

document.addEventListener('DOMContentLoaded', () => {
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

    /**
     * Shows the table while it holds a row and the empty state otherwise, and copies the row
     * count into the page-head count chip.
     */
    function syncAliasEmpty() {
        if (!tbody) {
            return;
        }
        const count = tbody.querySelectorAll('tr[data-alias-id]').length;
        const tableWrap = document.querySelector('[data-alias-table]');
        const emptyState = document.querySelector('[data-alias-empty]');
        if (tableWrap instanceof HTMLElement) {
            tableWrap.hidden = count === 0;
        }
        if (emptyState instanceof HTMLElement) {
            emptyState.hidden = count > 0;
        }
        const chip = document.getElementById('aliases-count');
        if (chip) {
            chip.textContent = String(count);
        }
    }

    function sourceLabel(code) {
        const labels = {
            SCWIKI: ALIAS_MSG.sourceScwiki,
            UEX: ALIAS_MSG.sourceUex,
            REFINERY_SCREEN: ALIAS_MSG.sourceRefineryScreen,
        };
        return code != null && labels[code] ? labels[code] : code != null ? code : '';
    }

    function textCell(field, value, label) {
        const td = document.createElement('td');
        if (field) {
            td.setAttribute('data-alias-field', field);
        }
        if (label) {
            td.setAttribute('data-label', label);
        }
        td.textContent = value != null ? value : '';
        return td;
    }

    function buildAliasRow(alias) {
        const tr = document.createElement('tr');
        tr.setAttribute('data-alias-id', alias.id);
        tr.setAttribute('data-testid', 'alias-row');

        const nameTd = document.createElement('td');
        const title = document.createElement('span');
        title.className = 'cell-title';
        title.setAttribute('data-alias-field', 'externalName');
        title.textContent = alias.externalName != null ? alias.externalName : '';
        nameTd.appendChild(title);
        tr.appendChild(nameTd);

        const sourceTd = document.createElement('td');
        sourceTd.setAttribute('data-label', ALIAS_MSG.sourceSystemLabel);
        const chip = document.createElement('span');
        chip.className = 'chip chip--muted';
        chip.setAttribute('data-alias-field', 'sourceSystem');
        chip.setAttribute('data-source-system', alias.sourceSystem || '');
        chip.textContent = sourceLabel(alias.sourceSystem);
        sourceTd.appendChild(chip);
        tr.appendChild(sourceTd);

        tr.appendChild(textCell('materialName', alias.materialName, ALIAS_MSG.materialLabel));
        tr.appendChild(textCell('externalKey', alias.externalKey, ALIAS_MSG.externalKeyLabel));
        tr.appendChild(textCell('externalCode', alias.externalCode, ALIAS_MSG.externalCodeLabel));
        tr.appendChild(textCell(null, alias.createdBy, ALIAS_MSG.createdByLabel));

        const actionTd = document.createElement('td');
        actionTd.className = 'cell-actions';
        const form = document.createElement('form');
        form.method = 'post';
        form.action = `/admin/material-aliases/${encodeURIComponent(alias.id)}/delete`;
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
        actionTd.appendChild(form);
        tr.appendChild(actionTd);
        return tr;
    }

    function patchAliasRow(alias) {
        const row = tbody ? tbody.querySelector(`tr[data-alias-id="${alias.id}"]`) : null;
        if (!row) {
            return;
        }
        ['externalName', 'materialName', 'externalKey', 'externalCode'].forEach((key) => {
            const cell = row.querySelector(`[data-alias-field="${key}"]`);
            if (cell) {
                cell.textContent = alias[key] != null ? alias[key] : '';
            }
        });
        const source = row.querySelector('[data-alias-field="sourceSystem"]');
        if (source) {
            source.setAttribute('data-source-system', alias.sourceSystem || '');
            source.textContent = sourceLabel(alias.sourceSystem);
        }
    }

    document.addEventListener('submit', (event) => {
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
                if (row) {
                    row.remove();
                }
                syncAliasEmpty();
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
                    tbody.appendChild(buildAliasRow(created));
                    syncAliasEmpty();
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
        window.krtFetch.write(opts).finally(() => {
            if (submitBtn) {
                submitBtn.disabled = false;
            }
        });
    });
});

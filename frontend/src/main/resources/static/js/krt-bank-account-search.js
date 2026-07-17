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
 * Server-side bank-account search source for the searchable combobox (REQ-FE-017, ADR-0104). The
 * account analogue of krt-user-search.js: registers the `remote-bank-accounts` remoteSource the
 * global combobox auto-initialiser (krt-searchable-select.js) wires up by marker value, so an
 * account picker (transfer destination, grant target/filter, direct-booking source account) fetches
 * only the matching ACTIVE accounts on demand from /api/proxy/bank/accounts/search instead of
 * shipping every account as an <option> — the scaling switch past the former 500-account cap
 * (ADR-0085).
 *
 *   - `data-krt-combobox="remote-bank-accounts"` -> caller-scoped ACTIVE account search
 *
 * The picker label is `<accountNo> — <name>`. Because combobox enhancement replaces the <select>
 * with a value-only hidden input (per-option metadata does not survive), the source also records
 * each fetched account's justification mandate in `window.krtBankAccountMeta` (id -> bool), keyed by
 * account type (CARTEL / CARTEL_BANK / SPECIAL mandate a Begruendung, REQ-BANK-045). The unified
 * movement modal's source-account picker (bank.js) reads that map to keep the Begruendung `required`
 * flag correct after conversion. A fetch failure degrades to an empty list so the picker shows "no
 * matches" rather than throwing. Loaded from head.html BEFORE krt-searchable-select.js so the
 * registry exists when the enhancer runs.
 */
(function () {
    'use strict';

    // Account types whose withdrawals/transfers mandate a Begruendung (REQ-BANK-045). Mirrors the
    // server-rendered data-requires-justification flag the native <option> carried pre-conversion.
    const JUSTIFICATION_TYPES = ['CARTEL', 'CARTEL_BANK', 'SPECIAL'];

    // Metadata the movement modal needs after the <select> (and its per-option data) is gone: maps a
    // committed account id to whether that account mandates a withdrawal/transfer justification.
    window.krtBankAccountMeta = window.krtBankAccountMeta || {};

    // Maps a backend account row to the combobox option shape and records its justification mandate.
    // The label folds the account number and name so typing either narrows the list; the id is the
    // submitted value.
    function toOption(row) {
        const requiresJustification = JUSTIFICATION_TYPES.indexOf(row.type) !== -1;
        window.krtBankAccountMeta[row.id] = requiresJustification;
        const accountNo = row.accountNo || '';
        const name = row.name || '';
        const label = accountNo && name ? accountNo + ' — ' + name : accountNo || name || row.id;
        return { value: row.id, label: label };
    }

    // Fetches the matching accounts for `query` from the account-search proxy and maps them to
    // combobox options. Returns [] on any non-OK response or network error.
    function fetchAccounts(query) {
        return fetch('/api/proxy/bank/accounts/search?query=' + encodeURIComponent(query || ''), {
            headers: { Accept: 'application/json' },
        })
            .then(function (response) {
                return response.ok ? response.json() : [];
            })
            .then(function (list) {
                return (list || []).map(toOption);
            })
            .catch(function () {
                return [];
            });
    }

    // Registry keyed by the `data-krt-combobox` marker value; read by krt-searchable-select.js.
    window.krtComboboxRemoteSources = window.krtComboboxRemoteSources || {};
    window.krtComboboxRemoteSources['remote-bank-accounts'] = function (query) {
        return fetchAccounts(query);
    };
})();

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

import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtComboboxRemoteSources } from '/js/kernel/krt-combobox-sources.js';

/** @type {Array<string | undefined>} */
const JUSTIFICATION_TYPES = ['CARTEL', 'CARTEL_BANK', 'SPECIAL'];

/** @type {Record<string, boolean>} */
export const krtBankAccountMeta = {};

/**
 * Maps a searched account to a combobox option and records whether it needs a justification.
 *
 * @param {ApiDto<'BankAccountDto'>} row the account the search returned
 * @returns {{ value: string | undefined, label: string | undefined }} the option
 */
function toOption(row) {
    const requiresJustification = JUSTIFICATION_TYPES.indexOf(row.type) !== -1;
    krtBankAccountMeta[/** @type {string} */ (row.id)] = requiresJustification;
    const accountNo = row.accountNo || '';
    const name = row.name || '';
    const label = accountNo && name ? `${accountNo} — ${name}` : accountNo || name || row.id;
    return { value: row.id, label };
}

/**
 * Searches the active bank accounts; an empty list on any failure.
 *
 * @param {string | null | undefined} query the name or account-number filter
 * @returns {Promise<Array<{ value: string | undefined, label: string | undefined }>>} the options
 */
function fetchAccounts(query) {
    return krtFetch
        .getJson(`/api/proxy/bank/accounts/search?query=${encodeURIComponent(query || '')}`)
        .then((list) => {
            return (list || []).map(toOption);
        })
        .catch(() => {
            return [];
        });
}

krtComboboxRemoteSources['remote-bank-accounts'] = function (query) {
    return fetchAccounts(query);
};

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

import { bankRefreshError } from '/js/bank/bank-messages.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';

const BANK_ACCOUNT_SECTIONS = {
    account: { container: '#bank-account-results', fragmentValue: 'accountBody' },
    bookings: { container: '#bank-account-results', fragmentValue: 'accountBody' },
    chart: { container: '#bank-account-results', fragmentValue: 'accountBody' },
};

const ORGUNIT_ACCOUNT_SECTIONS = {
    account: {
        container: '#org-unit-bank-settings-results',
        fragmentValue: 'orgUnitBankSettings',
    },
    bookings: {
        container: '#org-unit-bank-bookings-results',
        fragmentValue: 'orgUnitBankBookings',
    },
    chart: {
        container: '#org-unit-bank-chart-results',
        fragmentValue: 'orgUnitBalanceChart',
    },
};

const BANK_STAFF_SECTIONS = {
    grid: { container: '#bank-grid-results', fragmentValue: 'bankGrid' },
    requestQueue: { container: '#bank-request-queue-results', fragmentValue: 'requestQueue' },
    manage: { container: '#bank-manage-results', fragmentValue: 'manageBody' },
    grants: { container: '#bank-grants-results', fragmentValue: 'grantsMatrix' },
};

const ORGUNIT_BANK_SECTIONS = {
    orgUnitBank: { container: '#org-unit-bank-results', fragmentValue: 'orgUnitBank' },
    orgUnitBankSettings: {
        container: '#org-unit-bank-settings-results',
        fragmentValue: 'orgUnitBankSettings',
    },
};

/**
 * Reads the localized "updates available" pill label from `<main data-bank-livesync-updates>`.
 *
 * @returns {string|undefined} the pill label, or undefined for the receiver default
 */
function bankLiveSyncUpdates() {
    const main = document.querySelector('main[data-bank-livesync-updates]');
    return main
        ? /** @type {string} */ (main.getAttribute('data-bank-livesync-updates'))
        : undefined;
}

/**
 * The account id of an account-detail page, read from `<main data-bank-account-id>`, or null on a
 * list/overview page that has no single account.
 *
 * @returns {string|null} the account id, or null
 */
function bankAccountId() {
    const main = document.querySelector('main[data-bank-account-id]');
    const id = main ? main.getAttribute('data-bank-account-id') : null;
    return id || null;
}

/**
 * Builds a live-sync receiver refresh closure for one section map. It re-renders each present
 * container once (deduplicated by container) from the viewer's own pathname and query.
 *
 * @param {Record<string, {container: string, fragmentValue: string}>} sectionMap the section →
 *     {container, fragmentValue} map for the room
 * @returns {(keys: string[]) => void} the refresh handler passed to createReceiver
 */
function makeBankReceiverRefresh(sectionMap) {
    return function (keys) {
        if (!krtFetch || typeof krtFetch.swap !== 'function') {
            return;
        }
        const url = window.location.pathname + window.location.search;
        const errorMessage = bankRefreshError();
        /** @type {Record<string, boolean>} */
        const done = {};
        keys.forEach((key) => {
            const cfg = sectionMap[key];
            if (!cfg || done[cfg.container] || !document.querySelector(cfg.container)) {
                return;
            }
            done[cfg.container] = true;
            krtFetch.swap({
                url,
                container: cfg.container,
                fragmentValue: cfg.fragmentValue,
                errorMessage,
            });
        });
    };
}

/**
 * Opens the live-sync receivers the page's rooms call for: the staff overview, the org-unit bank
 * and the single-account room (REQ-FE-015).
 */
export function initLiveSyncReceivers() {
    if (
        !window.krtLiveSync ||
        typeof window.krtLiveSync.createReceiver !== 'function' ||
        !krtFetch ||
        typeof krtFetch.swap !== 'function'
    ) {
        return;
    }
    function pill() {
        return { label: bankLiveSyncUpdates };
    }

    const hasStaffRoom =
        document.querySelector('#bank-grid-results') ||
        document.querySelector('#bank-request-queue-results') ||
        document.querySelector('#bank-manage-results') ||
        document.querySelector('#bank-grants-results');
    if (hasStaffRoom) {
        window.krtLiveSync.createReceiver({
            topic: 'bank',
            sections: BANK_STAFF_SECTIONS,
            coalesceMs: 1500,
            refresh: makeBankReceiverRefresh(BANK_STAFF_SECTIONS),
            pill: pill(),
        });
    }

    const hasOrgUnitRoom =
        document.querySelector('#org-unit-bank-results') ||
        document.querySelector('#org-unit-bank-settings-results');
    if (hasOrgUnitRoom) {
        window.krtLiveSync.createReceiver({
            topic: 'orgunit-bank',
            sections: ORGUNIT_BANK_SECTIONS,
            coalesceMs: 1500,
            refresh: makeBankReceiverRefresh(ORGUNIT_BANK_SECTIONS),
            pill: pill(),
        });
    }

    const accountId = bankAccountId();
    if (accountId) {
        const sectionMap = document.querySelector('#bank-account-results')
            ? BANK_ACCOUNT_SECTIONS
            : ORGUNIT_ACCOUNT_SECTIONS;
        window.krtLiveSync.createReceiver({
            topic: `bank:${accountId}`,
            sections: sectionMap,
            refresh: makeBankReceiverRefresh(sectionMap),
            pill: pill(),
        });
    }
}

/**
 * Resolves a bank live-sync account placeholder to a concrete account id. `account` reads
 * `data-livesync-account`, `data-account-id` or a primed `_livesyncAccount` field; `destination`
 * reads an enabled `destinationAccountId` or a primed `_livesyncDestination` field.
 *
 * @param {HTMLFormElement} form the form whose write just succeeded
 * @param {string} ref the placeholder name (`account` or `destination`)
 * @returns {string|null} the resolved account id, or null when the form has none
 */
function resolveBankLiveSyncAccount(form, ref) {
    if (ref === 'account') {
        const primed = /** @type {HTMLInputElement | null} */ (
            form.querySelector('[name="_livesyncAccount"]')
        );
        return (
            form.getAttribute('data-livesync-account') ||
            form.getAttribute('data-account-id') ||
            (primed && primed.value ? primed.value : null)
        );
    }
    if (ref === 'destination') {
        const el = /** @type {HTMLInputElement | null} */ (
            form.querySelector('[name="destinationAccountId"]') ||
                form.querySelector('[name="_livesyncDestination"]')
        );
        return el && !el.disabled && el.value ? el.value : null;
    }
    return null;
}

/**
 * Broadcasts a bank form's live-sync publish matrix after a successful write (REQ-FE-015). The
 * `data-livesync` attribute lists space-separated `topic/section,section` entries; a
 * `bank:@account` / `bank:@destination` topic resolves to the form's account, and entries that
 * are duplicate or unresolvable are skipped.
 *
 * @param {HTMLFormElement} form the form whose write just succeeded
 */
export function publishBankLiveSync(form) {
    if (!window.krtLiveSync || typeof window.krtLiveSync.sendChanged !== 'function') {
        return;
    }
    const spec = form.getAttribute('data-livesync');
    if (!spec) {
        return;
    }
    /** @type {Record<string, boolean>} */
    const sent = {};
    spec.trim()
        .split(/\s+/)
        .forEach((entry) => {
            const slash = entry.indexOf('/');
            if (slash < 0) {
                return;
            }
            let topic = entry.slice(0, slash);
            const sections = entry
                .slice(slash + 1)
                .split(',')
                .filter(Boolean);
            if (!sections.length) {
                return;
            }
            const at = topic.indexOf(':@');
            if (at >= 0) {
                const id = resolveBankLiveSyncAccount(form, topic.slice(at + 2));
                if (!id) {
                    return;
                }
                topic = `${topic.slice(0, at)}:${id}`;
            }
            const key = `${topic}|${sections.join(',')}`;
            if (sent[key]) {
                return;
            }
            sent[key] = true;
            window.krtLiveSync.sendChanged(topic, sections);
        });
}

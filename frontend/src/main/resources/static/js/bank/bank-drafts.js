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

import { leadingControlsAdapter } from '/js/kernel/krt-draft-lead.js';
import { registerDraftAdapter } from '/js/kernel/krt-drafts.js';

/** The split toggle, which enables the split share of a deposit. */
const SPLIT_TOGGLE = '[data-role="bank-split-toggle"]';

/**
 * Registers the drafts of the bank's create dialogs (REQ-FE-041): the movement and the org-unit
 * request dialog set their type, account and split toggle first, because those enable the rows a
 * draft's values go into.
 */
export function initBankDrafts() {
    registerDraftAdapter(
        'bank-movement',
        leadingControlsAdapter([
            '[data-role="bank-movement-type"]',
            '[data-role="bank-movement-source"]',
            SPLIT_TOGGLE,
        ]),
    );
    registerDraftAdapter(
        'org-unit-bank-request',
        leadingControlsAdapter([
            '[data-role="org-unit-request-account"]',
            '[data-role="org-unit-request-type"]',
            SPLIT_TOGGLE,
        ]),
    );
}

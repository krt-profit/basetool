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

import { initAccountList } from '/js/bank/bank-account-list.js';
import { initAccountType } from '/js/bank/bank-account-type.js';
import { initConfirmModal } from '/js/bank/bank-confirm-modal.js';
import { initCounterparty } from '/js/bank/bank-counterparty.js';
import { initDownloadButtons, initStatementExport } from '/js/bank/bank-download.js';
import { initFormSubmit } from '/js/bank/bank-form-submit.js';
import { initGrantMatrix } from '/js/bank/bank-grant-matrix.js';
import { initLiveSyncReceivers } from '/js/bank/bank-livesync.js';
import { initModalPriming } from '/js/bank/bank-modal-prime.js';
import { initMovementRows } from '/js/bank/bank-movement-rows.js';
import { initFieldPreviews } from '/js/bank/bank-previews.js';
import { initRequestRows } from '/js/bank/bank-request-rows.js';
import { initTypeToConfirm } from '/js/bank/bank-type-to-confirm.js';

initLiveSyncReceivers();
initModalPriming();
initCounterparty();
initFormSubmit();
initFieldPreviews();
initGrantMatrix();
initAccountType();
initRequestRows();
initMovementRows();
initConfirmModal();
initDownloadButtons();
initTypeToConfirm();
initStatementExport();
initAccountList();

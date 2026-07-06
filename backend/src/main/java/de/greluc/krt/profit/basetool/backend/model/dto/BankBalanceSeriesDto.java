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

package de.greluc.krt.profit.basetool.backend.model.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response payload for the account-detail balance chart (REQ-BANK-049): the end-of-bucket running
 * balance series over a caller-chosen period plus the account's balance target so the frontend can
 * draw the target reference line. Computed on demand from the append-only ledger (opening balance
 * before the period + the period's postings, mirroring the statement math REQ-BANK-014); never
 * persisted. Series-only by design — no holder or counterparty data crosses the wire, so it needs
 * no redaction on the org-unit surface.
 *
 * @param points the end-of-bucket balances over the period, oldest first (possibly empty when the
 *     account has no postings and a zero opening balance)
 * @param balanceTarget the account's configured balance target (REQ-BANK-036) for the target line,
 *     or {@code null} when no target is set
 */
public record BankBalanceSeriesDto(List<BankBalancePointDto> points, BigDecimal balanceTarget) {}

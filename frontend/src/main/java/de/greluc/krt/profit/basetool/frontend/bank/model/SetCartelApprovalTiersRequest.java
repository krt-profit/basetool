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

package de.greluc.krt.profit.basetool.frontend.bank.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.math.BigDecimal;

/**
 * Write payload for the KRT-account approval thresholds T1/T2 (REQ-BANK-047).
 *
 * @param employeeCeiling the bank-employee ceiling T1, or {@code null} to clear it
 * @param areaLeadCeiling the Bereichsleiter-Profit ceiling T2, or {@code null} to clear it
 * @param version the account version the client read
 */
@DtoMirror
public record SetCartelApprovalTiersRequest(
    BigDecimal employeeCeiling, BigDecimal areaLeadCeiling, Long version) {}

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

package de.greluc.krt.profit.basetool.backend.model.dto.request;

import de.greluc.krt.profit.basetool.backend.validation.WholeNumber;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import org.jetbrains.annotations.Nullable;

/**
 * Write payload for the KRT-account (CARTEL) 3-stage approval thresholds T1/T2 (REQ-BANK-047),
 * managed by bank management in the Verwaltung tab. Each ceiling is an optional whole-aUEC amount
 * of at least 0 (a {@code null} ceiling clears that band; the
 * {@code @DecimalMin}/{@code @DecimalMax}/ {@code @WholeNumber} constraints skip a {@code null}
 * value by Bean-Validation semantics). The cross-field rule {@code T2 >= T1} and the CARTEL-only
 * rule are enforced in {@code BankAccountService.setCartelApprovalTiers} for clean 400s.
 *
 * @param employeeCeiling the bank-employee self-approval ceiling {@code T1}, or {@code null} to
 *     clear it (an unset ceiling is treated as {@code 0} at request time)
 * @param areaLeadCeiling the Bereichsleiter-Profit ceiling {@code T2}, or {@code null} to clear it
 *     (an unset ceiling means no OL band — the Bereichsleiter Profit covers everything above T1)
 * @param version optimistic-locking version the client read (REQ-BANK-018); a mismatch surfaces as
 *     409 {@code OPTIMISTIC_LOCK}
 */
public record SetCartelApprovalTiersRequest(
    @Nullable @DecimalMin("0") @DecimalMax("1000000000000.0") @WholeNumber
        BigDecimal employeeCeiling,
    @Nullable @DecimalMin("0") @DecimalMax("1000000000000.0") @WholeNumber
        BigDecimal areaLeadCeiling,
    @NotNull Long version) {}

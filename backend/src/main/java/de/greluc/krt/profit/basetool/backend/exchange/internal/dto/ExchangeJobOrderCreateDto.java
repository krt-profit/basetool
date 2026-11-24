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

package de.greluc.krt.profit.basetool.backend.exchange.internal.dto;

import de.greluc.krt.profit.basetool.backend.bank.api.BankAmounts;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * The terms of an aUEC order a member creates through a client (REQ-OXCH-004): both units from the
 * create options, the contact, the optional comment and the target in whole aUEC.
 *
 * @param requestingUnitId the unit that commissions the order, one of the member's writer units
 * @param responsibleUnitId the eligible Profit unit that processes the order
 * @param contact the contact handle, plain text
 * @param comment the optional comment, plain text
 * @param target the requested amount in whole aUEC
 */
public record ExchangeJobOrderCreateDto(
    @NotNull UUID requestingUnitId,
    @NotNull UUID responsibleUnitId,
    @NotBlank @Size(max = JobOrder.CONTACT_MAX_LENGTH) String contact,
    @Nullable @Size(max = JobOrder.COMMENT_MAX_LENGTH) String comment,
    @NotNull @Min(1) @Max(BankAmounts.MAX_WHOLE_AUEC) Long target) {}

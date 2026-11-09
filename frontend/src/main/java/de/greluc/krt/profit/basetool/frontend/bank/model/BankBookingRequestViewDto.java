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

import org.jetbrains.annotations.NotNull;

/**
 * One booking request as the caller may see it on its own page, with the roles that let them see it
 * (REQ-BANK-068).
 *
 * @param request the request; {@code callerMayConfirm} is judged for bank staff only
 * @param own whether the caller raised it
 * @param staff whether the caller sees it in the bank-staff queue
 * @param approver whether the caller acts on its approval
 */
public record BankBookingRequestViewDto(
    @NotNull BankBookingRequestDto request, boolean own, boolean staff, boolean approver) {}

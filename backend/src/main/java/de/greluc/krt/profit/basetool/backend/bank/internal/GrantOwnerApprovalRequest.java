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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import jakarta.validation.constraints.NotNull;

/**
 * Write payload for an approver granting the in-app approval of a booking request (REQ-BANK-041):
 * the version of the request as the approver read it, so the approval covers exactly that amount
 * and reason (REQ-BANK-056).
 *
 * @param version the request's echoed {@code @Version}; a mismatch surfaces as 409
 */
public record GrantOwnerApprovalRequest(@NotNull Long version) {}

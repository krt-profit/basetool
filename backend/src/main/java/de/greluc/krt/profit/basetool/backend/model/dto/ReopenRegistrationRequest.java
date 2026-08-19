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

import jakarta.validation.constraints.Size;
import org.jetbrains.annotations.Nullable;

/**
 * Body of a reopen-registration request — the reversal of an erroneous rejection (REQ-SEC-034).
 *
 * @param reason optional free-text note recorded in the approval audit, typically why the rejection
 *     was wrong; capped at 1000 chars to match {@link RejectRegistrationRequest}
 * @param version the optimistic-lock version the admin last read; {@code null} bypasses the check
 */
public record ReopenRegistrationRequest(
    @Nullable @Size(max = 1000) String reason, @Nullable Long version) {}

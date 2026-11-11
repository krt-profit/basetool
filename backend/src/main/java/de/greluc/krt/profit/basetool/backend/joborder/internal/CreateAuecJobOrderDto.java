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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * The fields required to commission a net aUEC amount.
 *
 * @param responsibleOrgUnitId eligible processing unit
 * @param requestingOrgUnitId commissioning unit
 * @param handle requester contact
 * @param comment optional order note
 * @param target exact whole-aUEC net amount
 */
public record CreateAuecJobOrderDto(
    @NotNull UUID responsibleOrgUnitId,
    @NotNull UUID requestingOrgUnitId,
    @NotBlank @Size(max = 200) String handle,
    @Nullable @Size(max = 1000) String comment,
    @NotBlank String target) {}

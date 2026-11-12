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

package de.greluc.krt.profit.basetool.frontend.joborder.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * The shared order fields and exact target of a new aUEC order.
 *
 * @param responsibleOrgUnitId the responsible Profit unit
 * @param requestingOrgUnitId the requesting unit
 * @param handle the order contact
 * @param comment the optional shared comment
 * @param target the net target in whole aUEC
 */
@DtoMirror
public record CreateAuecJobOrderDto(
    @NotNull UUID responsibleOrgUnitId,
    @NotNull UUID requestingOrgUnitId,
    @NotBlank @Size(max = 200) String handle,
    @Nullable @Size(max = 1000) String comment,
    @NotBlank String target) {}

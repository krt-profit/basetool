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

import de.greluc.krt.profit.basetool.frontend.kernel.model.BackendEnumAsString;
import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * An explicit order-local holder identity correction.
 *
 * @param kind the selected correction action
 * @param version observed revision of the original holder
 * @param name corrected accountless name, when selected
 * @param userId deliberately selected account, when applicable
 * @param targetHolderId the selected surviving holder for a merge
 * @param targetVersion observed survivor revision
 */
@DtoMirror
public record JobOrderMoneyHolderIdentityDto(
    @NotNull @BackendEnumAsString String kind,
    @NotNull @Positive Long version,
    @Nullable @Size(max = 200) String name,
    @Nullable UUID userId,
    @Nullable UUID targetHolderId,
    @Nullable @Positive Long targetVersion) {}

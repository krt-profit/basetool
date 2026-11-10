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

package de.greluc.krt.profit.basetool.backend.joborder.api;

import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Current creation choices whose selected pair must still pass the command's authority check.
 *
 * @param canCreate whether at least one valid unit pair is available
 * @param responsibleOptions eligible responsible units with at least one authorized requesting side
 * @param requestingOptions active requesting units with at least one authorized responsible side
 * @param suggestedResponsibleOrgUnitId the responsible side of one currently valid proposed pair
 * @param suggestedRequestingOrgUnitId the requesting side of that pair
 */
public record JobOrderCreateOptionsDto(
    boolean canCreate,
    @NotNull List<JobOrderCreateUnitOptionDto> responsibleOptions,
    @NotNull List<JobOrderCreateUnitOptionDto> requestingOptions,
    @Nullable UUID suggestedResponsibleOrgUnitId,
    @Nullable UUID suggestedRequestingOrgUnitId) {}

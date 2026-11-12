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
import de.greluc.krt.profit.basetool.frontend.orgunit.model.OrgUnitMembershipOptionDto;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * A unit's current or retained commitment occurrence.
 *
 * @param id the stable occurrence identity
 * @param unit the unit that made the commitment
 * @param outstanding the amount the unit still plans to provide as exact whole aUEC
 * @param openedAt when the unit made the commitment
 * @param endedAt when the commitment stopped being current
 * @param version the observed revision of this occurrence
 * @param canEdit whether the caller can revise this occurrence
 * @param changes the retained history of this occurrence
 */
@DtoMirror
public record MoneyCommitmentDto(
    UUID id,
    OrgUnitMembershipOptionDto unit,
    String outstanding,
    Instant openedAt,
    @Nullable Instant endedAt,
    long version,
    boolean canEdit,
    List<MoneyCommitmentChangeDto> changes) {}

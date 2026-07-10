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

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Write payload for applying an SCMDB blueprint import (#327, Phase 4): the user's per-name
 * resolutions in one batch (multi-row resolve). Names with a blank product key are skipped; the
 * remainder are added to the caller's owned set and, where the choice was manual, recorded as an
 * alias. The outcome is summarized in {@link BlueprintImportResultDto}.
 *
 * @param resolutions the per-name decisions; never {@code null}, may be empty (a no-op apply)
 */
public record BlueprintImportApplyRequest(
    @NotNull List<@Valid BlueprintImportResolutionDto> resolutions) {}

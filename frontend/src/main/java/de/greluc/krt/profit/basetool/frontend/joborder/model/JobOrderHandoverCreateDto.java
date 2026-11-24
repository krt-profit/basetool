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
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code JobOrderHandoverCreateDto}: the create payload of a
 * material handover.
 *
 * @param handoverTime when the handover occurred (UTC)
 * @param handingOverUserId the member of the processing unit who handed the materials over
 * @param recipientHandle the recipient's handle
 * @param recipientSquadron the recipient's squadron name, or {@code null}
 * @param items the handed-over stock rows (at least one)
 */
@DtoMirror
public record JobOrderHandoverCreateDto(
    @NotNull Instant handoverTime,
    @NotNull UUID handingOverUserId,
    @NotBlank String recipientHandle,
    String recipientSquadron,
    @NotEmpty List<JobOrderHandoverItemCreateDto> items) {}

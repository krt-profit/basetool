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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

/** Data transfer record carrying Job Order Handover Create payload. */
public record JobOrderHandoverCreateDto(
    @NotNull Instant handoverTime,
    @NotBlank String recipientHandle,
    String recipientSquadron,
    // @Valid on the element type cascades each item's @Positive amount into the list elements;
    // Bean Validation only descends into a collection element when the element type carries @Valid.
    // Audit M-4: without it a negative amount slipped through and *increased* stock + open
    // requirement.
    @NotEmpty List<@Valid JobOrderHandoverItemCreateDto> items) {}

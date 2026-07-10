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
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * Create payload for an item handover: the hand-over of one or more produced item quantities to a
 * recipient. Mirrors {@link JobOrderHandoverCreateDto} (the material counterpart) but itemises
 * delivered ordered-item lines instead of inventory items.
 *
 * @param handoverTime when the handover occurred (UTC)
 * @param recipientHandle the recipient's handle (≤ 255 chars)
 * @param entries the delivered item-line quantities (at least one)
 */
public record JobOrderItemHandoverCreateDto(
    @NotNull Instant handoverTime,
    @NotBlank @Size(max = 255) String recipientHandle,
    @NotEmpty List<@Valid JobOrderItemHandoverEntryCreateDto> entries) {}

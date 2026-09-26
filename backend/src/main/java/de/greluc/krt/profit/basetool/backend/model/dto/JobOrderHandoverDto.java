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

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * A material handover of a job order, including who executed it and that user's squadron at
 * handover time ({@code executingUser} is {@code null} on rows without audit data).
 */
public record JobOrderHandoverDto(
    UUID id,
    UUID jobOrderId,
    Instant handoverTime,
    String recipientHandle,
    String recipientSquadron,
    @Nullable UserReferenceDto executingUser,
    @Nullable SquadronReferenceDto executingSquadron,
    List<JobOrderHandoverItemDto> items,
    Long version) {}

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
import de.greluc.krt.profit.basetool.frontend.kernel.model.SquadronReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.UserReferenceDto;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Frontend mirror of the backend's {@code JobOrderHandoverDto}, including the executing user and
 * squadron snapshot, which may be {@code null}.
 *
 * @param id the handover id
 * @param jobOrderId the parent order id
 * @param handoverTime when the handover occurred (UTC)
 * @param handingOverHandle the handle snapshot of the member of the processing unit who handed the
 *     materials over, or {@code null} when none was recorded
 * @param recipientHandle the recipient's handle
 * @param recipientSquadron the recipient's squadron name, or {@code null}
 * @param executingUser slim reference to the user who recorded the handover, or {@code null}
 * @param executingSquadron snapshot of that user's squadron, or {@code null}
 * @param items the handed-over materials
 * @param version optimistic-lock version
 */
@DtoMirror
public record JobOrderHandoverDto(
    UUID id,
    UUID jobOrderId,
    Instant handoverTime,
    @Nullable String handingOverHandle,
    String recipientHandle,
    String recipientSquadron,
    @Nullable UserReferenceDto executingUser,
    @Nullable SquadronReferenceDto executingSquadron,
    List<JobOrderHandoverItemDto> items,
    Long version) {}

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

import de.greluc.krt.profit.basetool.backend.model.DeletionRequestStatus;
import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * A member's Art. 17 erasure request as read by the member and by an admin (REQ-SEC-061).
 *
 * @param id the request's id
 * @param version the optimistic-lock version
 * @param userId the requesting member
 * @param handle the member's effective name; {@code null} on the member's own projection
 * @param status where the request stands
 * @param eraseHistoryRequested whether the member also asked for the handle snapshots to be
 *     anonymised
 * @param requestedAt when the member raised it
 * @param decidedAt when it was decided or withdrawn, or {@code null} while pending
 * @param decisionNote the admin's reason, set on a refusal
 */
public record DeletionRequestDto(
    UUID id,
    Long version,
    UUID userId,
    @Nullable String handle,
    DeletionRequestStatus status,
    boolean eraseHistoryRequested,
    Instant requestedAt,
    @Nullable Instant decidedAt,
    @Nullable String decisionNote) {}

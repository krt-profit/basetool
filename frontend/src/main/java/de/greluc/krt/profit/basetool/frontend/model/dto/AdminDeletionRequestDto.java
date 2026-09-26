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

package de.greluc.krt.profit.basetool.frontend.model.dto;

import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * One row in the admin queue of members' Art. 17 erasure requests (REQ-SEC-061), mirroring the
 * backend {@code DeletionRequestDto} field for field.
 *
 * @param id the request's id, echoed back by the decide actions
 * @param userId the requesting member
 * @param handle the requesting member's effective name
 * @param status where the request stands; the queue only lists {@code PENDING}
 * @param eraseHistoryRequested whether the member also asked for their handle snapshots to be
 *     anonymised, which an admin decides
 * @param requestedAt when the member raised it; the Art. 12(3) one-month clock starts here
 * @param decidedAt when it was decided or withdrawn, or {@code null} while pending
 * @param decisionNote the admin's recorded reasoning on a refusal
 * @param version optimistic-lock version
 */
public record AdminDeletionRequestDto(
    UUID id,
    UUID userId,
    String handle,
    String status,
    boolean eraseHistoryRequested,
    Instant requestedAt,
    @Nullable Instant decidedAt,
    @Nullable String decisionNote,
    Long version) {}

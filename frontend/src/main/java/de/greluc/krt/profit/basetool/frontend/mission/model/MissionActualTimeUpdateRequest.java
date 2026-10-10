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

package de.greluc.krt.profit.basetool.frontend.mission.model;

import de.greluc.krt.profit.basetool.frontend.model.DtoMirror;
import java.time.Instant;
import org.jetbrains.annotations.Nullable;

/**
 * Request DTO for setting the actual start/end time of a mission via the "Now" buttons on the
 * mission detail page.
 *
 * <p>The {@code version} field is mandatory so that Spring Data JPA's optimistic locking ({@code
 * ObjectOptimisticLockingFailureException}) engages on concurrent changes and the endpoint can
 * respond with HTTP 409. Validation (allowed values for {@code field}, presence of {@code version})
 * is performed in the controller to consistently return HTTP 400.
 *
 * @param field Name of the field to update ({@code actualStartTime} or {@code actualEndTime}).
 * @param value UTC instant to set ({@link Instant}). {@code null} clears the value.
 * @param version Current entity version (optimistic locking).
 */
@DtoMirror
public record MissionActualTimeUpdateRequest(
    @Nullable String field, @Nullable Instant value, @Nullable Long version) {}

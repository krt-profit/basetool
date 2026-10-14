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

package de.greluc.krt.profit.basetool.frontend.purchasing.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.time.Instant;
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code DelegateDto}; must match it field for field.
 *
 * @param id the appointment
 * @param userId the member, or {@code null} once erased
 * @param displayName the member's name
 * @param appointedAt when appointed
 * @param appointedByName who appointed
 * @param validUntil the end date, or {@code null}
 * @param monthlyLimit the personal limit, or {@code null}
 * @param effectiveLimit the limit that applies
 * @param revokedAt when revoked, or {@code null}
 * @param active whether the appointment is active
 * @param version the optimistic-locking version
 */
@DtoMirror
public record DelegateDto(
    UUID id,
    UUID userId,
    String displayName,
    Instant appointedAt,
    String appointedByName,
    Instant validUntil,
    Long monthlyLimit,
    long effectiveLimit,
    Instant revokedAt,
    boolean active,
    Long version) {}

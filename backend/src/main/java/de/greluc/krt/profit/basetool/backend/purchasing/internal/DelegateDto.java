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

package de.greluc.krt.profit.basetool.backend.purchasing.internal;

import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * One appointment of a Handelsbeauftragter (REQ-PUR-003).
 *
 * @param id the appointment
 * @param userId the member, or {@code null} once the account is erased
 * @param displayName the member's display name, or {@code null} once erased
 * @param appointedAt when the appointment was made
 * @param appointedByName the display name of the appointing member, or {@code null}
 * @param validUntil the end of the appointment, or {@code null} for none
 * @param monthlyLimit the personal monthly limit in whole aUEC, or {@code null} for the default
 * @param effectiveLimit the limit that applies: the personal one, else the default
 * @param revokedAt when it was revoked, or {@code null}
 * @param active whether it confers the capability now
 * @param version the optimistic-locking version to echo
 */
public record DelegateDto(
    UUID id,
    @Nullable UUID userId,
    @Nullable String displayName,
    Instant appointedAt,
    @Nullable String appointedByName,
    @Nullable Instant validUntil,
    @Nullable Long monthlyLimit,
    long effectiveLimit,
    @Nullable Instant revokedAt,
    boolean active,
    Long version) {}

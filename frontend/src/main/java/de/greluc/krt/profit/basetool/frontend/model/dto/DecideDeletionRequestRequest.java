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

import org.jetbrains.annotations.Nullable;

/**
 * An admin's decision on an erasure request, for both refusing and carrying it out (REQ-SEC-061).
 *
 * @param grantHistoryErasure whether the surviving handle snapshots are anonymised too
 * @param note the reason of a refusal, or {@code null} on execution
 * @param version the request row's optimistic-lock version, or {@code null} to skip the check
 */
public record DecideDeletionRequestRequest(
    boolean grantHistoryErasure, @Nullable String note, @Nullable Long version) {}

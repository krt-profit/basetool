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

package de.greluc.krt.profit.basetool.backend.refinery.api;

import java.time.Instant;
import org.jetbrains.annotations.Nullable;

/**
 * Where a refinery order came from, its last change and how it left the refinery, as the web shows
 * them (REQ-REFINERY-027, REQ-REFINERY-028). A client is named by its registry display name, or by
 * its client id when the registry no longer holds it; {@code null} stands for the web. Read-only.
 *
 * @param createdByClient the client that created the order, {@code null} for the web
 * @param lastChangedByClient the client of the last change, {@code null} for the web
 * @param lastChangedAt when the order or its goods last changed
 * @param pickedUpAt when a client reported the pickup, or {@code null}
 * @param pickedUpByClient the client that reported the pickup, or {@code null}
 * @param storedAt when the yield was booked into the Lager, or {@code null}
 */
public record RefineryOrderProvenanceDto(
    @Nullable String createdByClient,
    @Nullable String lastChangedByClient,
    @Nullable Instant lastChangedAt,
    @Nullable Instant pickedUpAt,
    @Nullable String pickedUpByClient,
    @Nullable Instant storedAt) {}

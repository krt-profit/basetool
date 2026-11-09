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
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * What a removed refinery order held beyond the fields a client writes, so the undo of a client's
 * delete can recreate it as it was (REQ-XCH-056).
 *
 * @param createdAt when the order was created, or {@code null} for an order stored without one
 * @param createdClient the exchange client that created the order, or {@code null} for the web
 * @param orgUnitId the owning org unit, or {@code null} for an ownerless personal order
 * @param missionId the linked mission, or {@code null}
 * @param pickedUpAt when an exchange client reported the pickup, or {@code null}
 * @param pickedUpClient the client that reported the pickup, or {@code null}
 * @param readyNotifiedAt when the order-ready notice was raised, or {@code null}
 */
public record RefineryExchangeOrigin(
    @Nullable Instant createdAt,
    @Nullable String createdClient,
    @Nullable UUID orgUnitId,
    @Nullable UUID missionId,
    @Nullable Instant pickedUpAt,
    @Nullable String pickedUpClient,
    @Nullable Instant readyNotifiedAt) {}

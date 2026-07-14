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

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Frontend mirror of the backend delivered-toggle request. Since Variante C (REQ-INV-027) {@code
 * delivered} lives on the per-order job-order slice, so {@code jobOrderId} names the earmarked
 * order whose slice to flip; {@code version} is the owning entry's optimistic-locking token.
 *
 * @param delivered the new delivered state of the order's slice
 * @param jobOrderId the earmarked job order whose slice to toggle
 * @param version the owning inventory entry's optimistic-locking version
 */
public record UpdateDeliveredRequest(
    @NotNull Boolean delivered, @NotNull UUID jobOrderId, @NotNull Long version) {}

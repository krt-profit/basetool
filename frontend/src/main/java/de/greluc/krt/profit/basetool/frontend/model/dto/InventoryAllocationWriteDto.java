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

import java.util.UUID;

/**
 * Frontend mirror of the backend's per-allocation write payload (Variante C, REQ-INV-027), relayed
 * verbatim by {@code InventoryWriteController} to the add / change / remove allocation endpoints.
 * The amount is only meaningful for add/change (delete ignores it); the backend enforces its
 * presence and the over-allocation rule.
 *
 * @param field which quantity split the write targets.
 * @param targetId the earmarked job order or mission id.
 * @param amount the slice amount (add/change only).
 * @param version the owning entry's optimistic-lock version, echoed for the 409 check.
 */
public record InventoryAllocationWriteDto(
    InventoryAllocationDimension field, UUID targetId, Double amount, Long version) {}

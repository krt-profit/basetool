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
 * Frontend mirror of the backend {@code InventoryAllocationInput} (per the {@code
 * feedback_backend_frontend_dto_mirror} memory): one quantity-split assignment supplied at
 * inventory check-in (Variante C, REQ-INV-027, R4) — {@code amount} of the new entry earmarked to
 * the job order / mission {@code targetId}.
 *
 * @param targetId the job order / mission to earmark part of the new entry to.
 * @param amount the SCU to earmark to {@code targetId}.
 */
public record InventoryAllocationInput(UUID targetId, Double amount) {}

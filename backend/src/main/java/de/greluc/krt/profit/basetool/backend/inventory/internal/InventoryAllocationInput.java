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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import de.greluc.krt.profit.basetool.backend.inventory.api.MaterialProvisionSelection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * One quantity-split earmark supplied at inventory check-in (REQ-INV-027): {@code amount} of the
 * new entry is earmarked to the job order or mission {@code targetId}, depending on the {@link
 * InventoryItemCreateDto} list it appears in.
 *
 * @param targetId the job order or mission to earmark to; never {@code null}
 * @param amount the SCU to earmark; strictly positive
 * @param provision an explicitly selected matching unit promise, or null for unattributed stock
 */
public record InventoryAllocationInput(
    @NotNull UUID targetId,
    @NotNull @Positive Double amount,
    @Valid @Nullable MaterialProvisionSelection provision) {}

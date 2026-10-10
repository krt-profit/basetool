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

package de.greluc.krt.profit.basetool.frontend.inventory.model;

import de.greluc.krt.profit.basetool.frontend.model.DtoMirror;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code BulkCheckoutRequest} (per the {@code
 * feedback_backend_frontend_dto_mirror} memory): the ids of owned inventory items that the
 * personal-inventory page's bulk action books out (fully discards) in one call.
 *
 * @param itemIds the ids of the owned inventory items to fully book out; at least one, none null
 */
@DtoMirror
public record BulkCheckoutRequest(@NotNull @NotEmpty List<@NotNull UUID> itemIds) {}

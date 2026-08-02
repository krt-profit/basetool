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

package de.greluc.krt.profit.basetool.backend.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * Write payload for adding a single blueprint to the caller's owned set (#327). The product is
 * referenced by its normalized {@code productKey}; the server resolves the display name and output
 * item, so neither is accepted from the client.
 *
 * @param productKey normalized product key of the blueprint to add (max 255 chars, matching the
 *     {@code personal_blueprint.product_key} column)
 * @param acquiredAt optional in-game acquisition time
 * @param note optional free-form note (max 2000 chars)
 */
public record PersonalBlueprintCreateRequest(
    @NotBlank @Size(max = 255) String productKey,
    Instant acquiredAt,
    @Size(max = 2000) String note) {}

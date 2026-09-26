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

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * Payload editing an owned blueprint's acquisition date and note; the product is immutable.
 *
 * @param acquiredAt optional in-game acquisition time; {@code null} clears it
 * @param note optional free-form note, max 2000 chars; {@code null} clears it
 * @param version the expected optimistic-lock version
 */
public record PersonalBlueprintUpdateRequest(
    Instant acquiredAt, @Size(max = 2000) String note, @NotNull Long version) {}

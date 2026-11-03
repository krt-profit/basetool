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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * The location a {@link MaterialOccurrence} points at, as a value: its kind and the local id of the
 * location row of that kind.
 *
 * @param kind which location table {@code locationId} belongs to
 * @param locationId the local primary key in that table
 */
public record MaterialOccurrenceTarget(
    @NotNull MaterialOccurrenceKind kind, @NotNull UUID locationId) {}

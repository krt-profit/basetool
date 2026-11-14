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

package de.greluc.krt.profit.basetool.backend.identity.internal;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * The calling member's profile setting "my home location" with the version a client echoes back on
 * its next write (REQ-PATCH-030).
 *
 * @param homeLocationId the chosen home location, or {@code null} when the member made no choice
 * @param version the setting's optimistic-lock version, {@code 0} while the member made no choice
 */
public record MyHomeLocationResponse(@Nullable UUID homeLocationId, long version) {}

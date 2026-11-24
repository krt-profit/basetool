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

package de.greluc.krt.profit.basetool.backend.platform.api;

import java.time.ZoneId;
import org.jetbrains.annotations.Nullable;

/**
 * The caller's display time zone from the {@code X-User-Time-Zone} header, bound by {@link
 * UserZoneArgumentResolver} to every controller parameter of this type.
 *
 * <p>It is a type of its own because Spring MVC's built-in {@code
 * ServletRequestMethodArgumentResolver} answers every {@link ZoneId} parameter with the server's
 * zone before a custom resolver is asked.
 *
 * @param zone the parsed IANA zone, or {@code null} when the header is absent, blank or invalid;
 *     callers then render in UTC
 */
public record UserZone(@Nullable ZoneId zone) {}

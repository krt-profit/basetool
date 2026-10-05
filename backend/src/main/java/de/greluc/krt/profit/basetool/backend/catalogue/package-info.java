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

/**
 * The catalogue module: materials, items, locations, ship types and the UEX, SC Wiki and P4K
 * imports (plan §5.1 rank 2).
 */
@ApplicationModule(
    allowedDependencies = {
      "audit::api",
      "kernel",
      "livesync::api",
      "notification::api",
      "platform::api"
    })
package de.greluc.krt.profit.basetool.backend.catalogue;

import org.springframework.modulith.ApplicationModule;

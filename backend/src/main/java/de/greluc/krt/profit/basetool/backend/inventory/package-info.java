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

/** The inventory module (Lager): stock, holders, allocations, checkout and rebooking. */
@ApplicationModule(
    allowedDependencies = {
      "admin::api",
      "audit::api",
      "catalogue::api",
      "identity::api",
      "kernel",
      "livesync::api",
      "notification::api",
      "orgunit::api",
      "platform::api",
      "scope::api"
    })
package de.greluc.krt.profit.basetool.backend.inventory;

import org.springframework.modulith.ApplicationModule;

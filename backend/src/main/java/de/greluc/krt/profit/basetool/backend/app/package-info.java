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
 * The composition root: the security filter chains, the caller's layout read, the schedule of the
 * business gauges the modules publish, and the development and test data seeding (plan §5.1 rank
 * 14).
 */
@ApplicationModule(
    allowedDependencies = {
      "admin::api",
      "audit::api",
      "bank::api",
      "blueprint::api",
      "catalogue::api",
      "exchange::api",
      "hangar::api",
      "identity::api",
      "inventory::api",
      "joborder::api",
      "livesync::api",
      "materialexchange::api",
      "mission::api",
      "notification::api",
      "operation::api",
      "orgunit::api",
      "platform::api",
      "privacy::api",
      "refinery::api",
      "scope::api",
      "supply::api",
      "kernel"
    })
package de.greluc.krt.profit.basetool.backend.app;

import org.springframework.modulith.ApplicationModule;

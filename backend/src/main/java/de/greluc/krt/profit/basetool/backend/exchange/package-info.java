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
 * The exchange module: the external client exchange the ingest gateway relays to (REQ-INGEST-*).
 */
@ApplicationModule(
    allowedDependencies = {
      "admin::api",
      "audit::api",
      "bank::api",
      "catalogue::api",
      "identity::api",
      "inventory::api",
      "joborder::api",
      "kernel",
      "livesync::api",
      "materialexchange::api",
      "notification::api",
      "orgunit::api",
      "platform::api",
      "refinery::api",
      "scope::api"
    })
package de.greluc.krt.profit.basetool.backend.exchange;

import org.springframework.modulith.ApplicationModule;

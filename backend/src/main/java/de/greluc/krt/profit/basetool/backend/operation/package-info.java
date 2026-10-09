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
 * The operation module: Operationen, their finance view and payouts (plan §5.1 rank 11). The
 * Operation entity, its repository, mapper and the DTOs the mission module embeds stay in the layer
 * packages until Mission references an operation by id (plan §7.5).
 */
@ApplicationModule(
    allowedDependencies = {
      "admin::api",
      "audit::api",
      "catalogue::api",
      "identity::api",
      "inventory::api",
      "joborder::api",
      "kernel",
      "livesync::api",
      "materialexchange::api",
      "mission::api",
      "notification::api",
      "orgunit::api",
      "personalinventory::api",
      "platform::api",
      "refinery::api",
      "scope::api"
    })
package de.greluc.krt.profit.basetool.backend.operation;

import org.springframework.modulith.ApplicationModule;

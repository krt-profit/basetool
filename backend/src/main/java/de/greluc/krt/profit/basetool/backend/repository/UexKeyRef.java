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

package de.greluc.krt.profit.basetool.backend.repository;

import java.util.UUID;

/**
 * Projection row pairing an external UEX id with the local primary key it maps to — the unit of the
 * lookup maps the UEX syncs preload with one query per table instead of one lookup per row
 * (BE-PERF-09). Ids only, never entities: the map outlives the transaction that read it.
 */
public interface UexKeyRef {

  /**
   * The UEX-side id ({@code id_commodity}, {@code id_terminal}, the UEX item id, …).
   *
   * @return the external id; never {@code null} in a projection that filters on it
   */
  Integer getUexId();

  /**
   * The local primary key of the row carrying that UEX id.
   *
   * @return the local id
   */
  UUID getId();
}

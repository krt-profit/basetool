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

import de.greluc.krt.profit.basetool.backend.catalogue.api.UexKeyRef;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Spring Data repository for Moon. */
public interface MoonRepository extends JpaRepository<Moon, UUID> {
  /** Derived Spring-Data query - returns entities matching {@code IdMoon}. */
  Optional<Moon> findByIdMoon(Integer id);

  /** Derived Spring-Data query - returns entities matching {@code Name}. */
  Optional<Moon> findByName(String name);

  /**
   * Reads every Moon's (idMoon, local id) pair in one query, for the lookup map the commodity
   * location sync resolves UEX ids against (REQ-DATA-031).
   *
   * @return one row per Moon that carries a UEX id
   */
  @Query("SELECT e.idMoon AS uexId, e.id AS id FROM Moon e WHERE e.idMoon IS NOT NULL")
  List<UexKeyRef> findUexRefs();
}

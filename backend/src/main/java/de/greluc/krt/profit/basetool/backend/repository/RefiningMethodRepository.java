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

import de.greluc.krt.profit.basetool.backend.model.RefiningMethod;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Spring Data repository for Refining Method. */
@Repository
public interface RefiningMethodRepository extends JpaRepository<RefiningMethod, UUID> {
  /** Derived Spring-Data query - returns entities matching {@code Name}. */
  Optional<RefiningMethod> findByName(String name);

  /**
   * Case-insensitive name lookup for the refinery screenshot import, which reads method names in
   * upper case. {@code refining_method.name} is unique, so at most one row matches.
   *
   * @param name the method name to match ignoring case
   * @return the refining method with that name (any case), if one exists
   */
  Optional<RefiningMethod> findByNameIgnoreCase(String name);
}

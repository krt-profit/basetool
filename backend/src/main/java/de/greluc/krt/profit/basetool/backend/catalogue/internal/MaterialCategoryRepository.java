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

import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialCategory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Spring Data repository for the commodity categories (resource groups). */
@Repository
public interface MaterialCategoryRepository extends JpaRepository<MaterialCategory, UUID> {

  /**
   * Finds the category holding a P4K GUID.
   *
   * @param p4kUuid the GUID
   * @return the category, if any
   */
  Optional<MaterialCategory> findByP4kUuid(UUID p4kUuid);

  /**
   * Finds the categories of a class name, ignoring case.
   *
   * @param className the class name
   * @return every category carrying it
   */
  List<MaterialCategory> findByClassNameIgnoreCase(String className);

  /**
   * Finds the categories of a name, ignoring case.
   *
   * @param name the name
   * @return every category carrying it
   */
  List<MaterialCategory> findByNameIgnoreCase(String name);

  /**
   * Lists the categories a picker offers: neither stamped by the P4K sweep nor hidden
   * (REQ-P4K-013).
   *
   * @param sort the order
   * @return the live categories
   */
  List<MaterialCategory> findByP4kDeletedAtIsNullAndP4kHiddenAtIsNull(Sort sort);

  /**
   * Lists the live P4K resource groups, without the admin categories.
   *
   * @param sort the order
   * @return the groups with a P4K GUID, neither stamped nor hidden
   */
  List<MaterialCategory> findByP4kUuidIsNotNullAndP4kDeletedAtIsNullAndP4kHiddenAtIsNull(Sort sort);
}

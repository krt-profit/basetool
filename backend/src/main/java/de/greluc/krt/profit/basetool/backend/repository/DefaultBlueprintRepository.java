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

import de.greluc.krt.profit.basetool.backend.model.DefaultBlueprint;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for the admin-curated {@link DefaultBlueprint} set (REQ-INV-017). The set
 * is small (a handful of starter products), so callers load it whole rather than paging.
 */
@Repository
public interface DefaultBlueprintRepository extends JpaRepository<DefaultBlueprint, UUID> {

  /**
   * Existence check on the normalized product key — backs the admin add idempotency (a duplicate
   * add returns 409 before the unique constraint fires) and the seeder's per-entry guard.
   *
   * @param productKey normalized product key
   * @return {@code true} if a default already exists for the product
   */
  boolean existsByProductKey(String productKey);

  /**
   * Looks up a default by its normalized product key.
   *
   * @param productKey normalized product key
   * @return the default entry if present, empty otherwise
   */
  Optional<DefaultBlueprint> findByProductKey(String productKey);

  /**
   * Returns the normalized product keys of every default blueprint.
   *
   * @return every default product key; never {@code null}, possibly empty
   */
  @Query("SELECT db.productKey FROM DefaultBlueprint db")
  Set<String> findAllProductKeys();
}

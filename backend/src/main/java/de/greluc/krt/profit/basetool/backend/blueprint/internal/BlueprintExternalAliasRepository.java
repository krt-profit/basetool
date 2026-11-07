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

package de.greluc.krt.profit.basetool.backend.blueprint.internal;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for {@link BlueprintExternalAlias}. Read/write goes through the service
 * layer (no direct controller injection — pinned by the {@code
 * controllerLayerShouldNotDependOnRepositoryLayer} ArchUnit rule).
 */
@Repository
public interface BlueprintExternalAliasRepository
    extends JpaRepository<BlueprintExternalAlias, UUID> {

  /**
   * Finds an alias by source system and external name, ignoring case; at most one row exists per
   * case-folded pair (REQ-INV-020).
   *
   * @param sourceSystem catalogue the alias belongs to
   * @param externalName case-insensitive external blueprint name
   * @return the alias if present, empty otherwise
   */
  Optional<BlueprintExternalAlias> findBySourceSystemAndExternalNameIgnoreCase(
      BlueprintExternalAliasSource sourceSystem, String externalName);

  /**
   * Returns every product key an alias points at.
   *
   * @return the distinct {@code product_key} values; never {@code null}
   */
  @Query("SELECT DISTINCT a.productKey FROM BlueprintExternalAlias a")
  Set<String> findAllDistinctProductKeys();

  /**
   * Returns the aliases of one product.
   *
   * @param productKey the product key
   * @return the aliases
   */
  List<BlueprintExternalAlias> findAllByProductKey(String productKey);
}

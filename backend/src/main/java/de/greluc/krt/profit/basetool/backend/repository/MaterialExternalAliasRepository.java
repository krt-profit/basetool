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

import de.greluc.krt.profit.basetool.backend.model.MaterialExternalAlias;
import de.greluc.krt.profit.basetool.backend.model.MaterialExternalAliasSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for {@link MaterialExternalAlias}. Read access is sorted by external name
 * for stable rendering in the admin page; write access goes through the service layer (no direct
 * controller injection — pinned by the {@code controllerLayerShouldNotDependOnRepositoryLayer}
 * ArchUnit rule).
 */
@Repository
public interface MaterialExternalAliasRepository
    extends JpaRepository<MaterialExternalAlias, UUID> {

  /**
   * Returns every alias sorted by external name. The stored casing is preserved for display, but
   * uniqueness is case-insensitive (V146 index on {@code (source_system, LOWER(external_name))}),
   * so the table view never shows two case-variants of the same name.
   *
   * @return all alias rows ordered by external_name ascending
   */
  List<MaterialExternalAlias> findAllByOrderByExternalNameAsc();

  /**
   * Finds the alias for an external commodity name within one source system, ignoring case; at most
   * one row matches (REQ-REFINERY-010).
   *
   * @param sourceSystem catalogue the alias belongs to
   * @param externalName case-insensitive external commodity name
   * @return the alias if present, empty otherwise
   */
  Optional<MaterialExternalAlias> findBySourceSystemAndExternalNameIgnoreCase(
      MaterialExternalAliasSource sourceSystem, String externalName);

  /**
   * Returns every alias of one source system, unpaged.
   *
   * @param sourceSystem catalogue the aliases belong to
   * @return all alias rows of that source, in no guaranteed order
   */
  List<MaterialExternalAlias> findBySourceSystem(MaterialExternalAliasSource sourceSystem);
}

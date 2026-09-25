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

import de.greluc.krt.profit.basetool.backend.model.KommandoGroup;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for {@link KommandoGroup} (REQ-ROLE-003), backing Kommandogruppe CRUD on a
 * Staffel and the at-most-four-groups check.
 */
@Repository
public interface KommandoGroupRepository extends JpaRepository<KommandoGroup, UUID> {

  /**
   * Lists every Kommandogruppe of the given Staffel, ordered by {@link
   * KommandoGroup#getSortIndex()} ascending so the UI renders the up-to-four groups
   * deterministically.
   *
   * @param squadronOrgUnitId the Staffel's org-unit id; never {@code null}.
   * @return the squadron's Kommandogruppen in display order; never {@code null}, possibly empty.
   */
  List<KommandoGroup> findBySquadronIdOrderBySortIndexAsc(UUID squadronOrgUnitId);

  /**
   * Counts the Kommandogruppen of the given Staffel, so a fifth group is rejected with a 4xx before
   * the DB trigger fires.
   *
   * @param squadronOrgUnitId the Staffel's org-unit id; never {@code null}.
   * @return the number of Kommandogruppen on this squadron.
   */
  long countBySquadronId(UUID squadronOrgUnitId);
}

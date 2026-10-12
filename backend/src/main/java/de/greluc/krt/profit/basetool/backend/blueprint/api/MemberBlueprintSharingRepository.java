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

package de.greluc.krt.profit.basetool.backend.blueprint.api;

import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Spring Data repository of {@link MemberBlueprintSharing}, keyed by the member's id. */
public interface MemberBlueprintSharingRepository
    extends JpaRepository<MemberBlueprintSharing, UUID> {

  /**
   * Returns the ids of every member who shares their blueprints globally, for the
   * blueprint-availability aggregations (REQ-INV-018).
   *
   * @return the member ids; never {@code null}, possibly empty
   */
  @Query("SELECT s.id FROM MemberBlueprintSharing s WHERE s.shareGlobally = true")
  Set<UUID> findIdsSharingGlobally();
}

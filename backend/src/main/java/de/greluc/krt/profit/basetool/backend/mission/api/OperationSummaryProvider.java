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

package de.greluc.krt.profit.basetool.backend.mission.api;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * The read SPI through which the mission module learns what it shows of the operations its missions
 * belong to and whether the caller may link a mission to an operation (plan §5.3, §7.5 P3-8a);
 * owned by the mission module and implemented by the operation module. The mission module asks per
 * page, never per row.
 */
public interface OperationSummaryProvider {

  /**
   * Looks the operations up in one read. A mission shows its operation to everyone who may see the
   * mission (REQ-ORG-032), so the lookup applies no read scope.
   *
   * @param ids the operations
   * @return each existing operation by id; an unknown id is absent
   */
  @NotNull
  Map<UUID, MissionOperationSummary> operations(@NotNull Collection<UUID> ids);

  /**
   * Tells whether the caller may link a mission to the operation, by the operation's edit rule: a
   * Mission Manager grant in its owning unit inside the active context, for an ownerless operation
   * one in an Organisationsleitung or Bereich unit, an admin within their scope (REQ-MISSION-034).
   *
   * @param operationId the operation to link
   * @return whether the operation exists and the caller may link to it
   */
  @NotNull
  OperationLinkability linkability(@NotNull UUID operationId);
}

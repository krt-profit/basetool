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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The materials admins assigned to {@code ResourceType}s that join no commodity or several
 * (REQ-P4K-024), read from the resolved review cases once per run: a recipe line and a commodity's
 * {@code refinedSubtypeGuid} both resolve through them when the join does not.
 */
final class P4kResourceAssignments {

  /** Finds the material an admin assigned. */
  @NotNull private final MaterialRepository materialRepository;

  /** Finds the admin's resource assignments. */
  @NotNull private final P4kReviewCaseRepository reviewCaseRepository;

  /** The assigned material ids by resource GUID; read on first use. */
  @Nullable private Map<UUID, UUID> assignments;

  /**
   * Creates the assignments of one run.
   *
   * @param materialRepository finds the material an admin assigned
   * @param reviewCaseRepository finds the admin's resource assignments
   */
  P4kResourceAssignments(
      @NotNull MaterialRepository materialRepository,
      @NotNull P4kReviewCaseRepository reviewCaseRepository) {
    this.materialRepository = materialRepository;
    this.reviewCaseRepository = reviewCaseRepository;
  }

  /**
   * Returns the material an admin assigned to a {@code ResourceType}.
   *
   * @param resourceGuid the {@code ResourceType} GUID
   * @return the material, or {@code null} when no assignment names an existing one
   */
  @Nullable
  Material material(@NotNull UUID resourceGuid) {
    UUID assigned = assignments().get(resourceGuid);
    return assigned == null ? null : materialRepository.findById(assigned).orElse(null);
  }

  /**
   * Returns the assigned material ids, the earliest case per resource winning.
   *
   * @return the assigned material ids by resource GUID
   */
  @NotNull
  private Map<UUID, UUID> assignments() {
    if (assignments == null) {
      assignments = new HashMap<>();
      for (P4kReviewCase assignment :
          reviewCaseRepository.findByKindAndResolutionAndRowIdIsNotNullOrderByCreatedAtAsc(
              P4kKind.COMMODITY, P4kReviewResolution.ASSIGN)) {
        assignments.putIfAbsent(assignment.getGuid(), assignment.getRowId());
      }
    }
    return assignments;
  }
}

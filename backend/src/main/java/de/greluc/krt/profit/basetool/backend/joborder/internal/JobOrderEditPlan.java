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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.jetbrains.annotations.NotNull;

/**
 * Immutable resources and mutation of one authorized order edit.
 *
 * @param materialIds additional target materials required by the edit
 * @param stockRowIds every existing physical row the edit changes
 * @param prioritySequence whether the edit changes the existing priority sequence
 * @param mutation the mutation over the checked managed order
 */
record JobOrderEditPlan(
    @NotNull Set<UUID> materialIds,
    @NotNull Set<UUID> stockRowIds,
    boolean prioritySequence,
    @NotNull Function<JobOrder, JobOrderEdit> mutation) {
  JobOrderEditPlan {
    materialIds = Set.copyOf(materialIds);
    stockRowIds = Set.copyOf(stockRowIds);
    Objects.requireNonNull(mutation);
  }

  static JobOrderEditPlan of(Function<JobOrder, JobOrderEdit> mutation) {
    return new JobOrderEditPlan(Set.of(), Set.of(), false, mutation);
  }

  boolean sameResources(@NotNull JobOrderEditPlan other) {
    return materialIds.equals(other.materialIds)
        && stockRowIds.equals(other.stockRowIds)
        && prioritySequence == other.prioritySequence;
  }
}

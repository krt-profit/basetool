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

package de.greluc.krt.profit.basetool.backend.inventory.api;

import de.greluc.krt.profit.basetool.backend.model.projection.JobOrderEarmark;
import de.greluc.krt.profit.basetool.backend.model.projection.MissionEarmark;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The read SPIs through which the Lager learns what it shows of the targets its stock is earmarked
 * to (plan §5.3, §7.5 P3-5d); implemented by the modules that own the targets. The Lager asks per
 * page, never per row.
 */
public final class EarmarkTargets {

  /** Not instantiable. */
  private EarmarkTargets() {}

  /** The missions the Lager's stock is earmarked to; implemented by the mission module. */
  public interface Missions {

    /**
     * Looks the missions up in one read.
     *
     * @param ids the missions
     * @return each existing mission by id; an unknown id is absent
     */
    @NotNull
    Map<UUID, MissionEarmark> missions(@NotNull Collection<UUID> ids);
  }

  /** The job orders the Lager's stock is earmarked to; implemented by the job-order module. */
  public interface JobOrders {

    /**
     * Looks the job orders up in one read.
     *
     * @param ids the job orders
     * @return each existing job order by id; an unknown id is absent
     */
    @NotNull
    Map<UUID, JobOrderEarmark> jobOrders(@NotNull Collection<UUID> ids);

    /**
     * Returns the materials a job order requires, for the Lager's link check (REQ-ORDERS-018).
     *
     * @param jobOrderId the job order
     * @return the required materials, or {@code null} when the order does not exist
     */
    @Nullable
    Set<UUID> requiredMaterialIds(@NotNull UUID jobOrderId);

    /**
     * Returns the game items a job order requests, for the Lager's link check (REQ-INV-031); a
     * material order requests none.
     *
     * @param jobOrderId the job order
     * @return the requested game items, or {@code null} when the order does not exist
     */
    @Nullable
    Set<UUID> requiredGameItemIds(@NotNull UUID jobOrderId);
  }
}

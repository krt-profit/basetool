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

package de.greluc.krt.profit.basetool.backend.catalogue.api;

import de.greluc.krt.profit.basetool.backend.annotation.ObserverSpi;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Reacts to a job type losing its Einsatzleiter designation, inside the transaction that changes it
 * (plan §5.3); implemented by the mission module, whose participants carry the designation. Every
 * implementation joins the caller's transaction ({@code MANDATORY}).
 */
@ObserverSpi
public interface JobTypeDesignationObserver {

  /**
   * A job type no longer designates the Einsatzleiter.
   *
   * @param jobTypeId the job type that lost the designation
   * @return the number of participants whose lead flag was cleared
   */
  int missionLeadRevoked(@NotNull UUID jobTypeId);
}

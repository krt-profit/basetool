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

package de.greluc.krt.profit.basetool.backend.patchchange.api;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Tells the home-location gate whether a member must still choose (REQ-PATCH-020): a home-location
 * phase is open, the member's account existed before its live time, and the member has not chosen
 * in it.
 */
@FunctionalInterface
public interface HomeLocationGateCheck {

  /**
   * Reports whether the member must choose a new home location before using the platform.
   *
   * @param userId the member's {@code app_user.id}, the Keycloak {@code sub}
   * @return {@code true} while the member is pending in the open phase
   */
  boolean mustChoose(@NotNull UUID userId);
}

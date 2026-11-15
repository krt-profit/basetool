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

package de.greluc.krt.profit.basetool.frontend.identity.model;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Form-binding object for the profile's setting "my home location" (REQ-PATCH-030).
 *
 * @param homeLocationId the chosen curated home location; the select offers no way back to none
 * @param version the setting's own optimistic-lock version, echoed back so a concurrent change, a
 *     relocation's included, surfaces as a 409 rather than a silent overwrite
 */
public record ProfileHomeLocationForm(@NotNull UUID homeLocationId, Long version) {

  /**
   * Builds the backend body from the form.
   *
   * @return the body, with a missing version read as a first choice
   */
  public MyHomeLocationRequest toRequest() {
    return new MyHomeLocationRequest(homeLocationId, version == null ? 0L : version);
  }
}

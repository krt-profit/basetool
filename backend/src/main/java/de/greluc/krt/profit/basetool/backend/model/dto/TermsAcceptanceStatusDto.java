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

package de.greluc.krt.profit.basetool.backend.model.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the admin consent overview: a user and whether they accepted the Terms-of-Use version
 * in force (REQ-SEC-028).
 *
 * @param userId the user's {@code app_user.id}
 * @param username the login name
 * @param displayName the callsign; may be {@code null}
 * @param acceptedAt when the version in force was accepted, or {@code null} while pending
 */
public record TermsAcceptanceStatusDto(
    UUID userId, String username, String displayName, Instant acceptedAt) {

  /**
   * Whether this user has accepted the version currently in force.
   *
   * @return {@code true} exactly when {@link #acceptedAt()} is set
   */
  public boolean accepted() {
    return acceptedAt != null;
  }
}

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

package de.greluc.krt.profit.basetool.backend.audit.api;

import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Resolves the handle an audit row snapshots for its acting user (REQ-AUDIT-001, plan §5.3).
 *
 * <p>Owned by the audit module and implemented by the identity module. It runs inside the
 * recorder's transaction and joins it.
 */
public interface ActorHandleResolver {

  /**
   * The display handle of a user at the time of the call.
   *
   * @param userId the acting user's {@code sub}
   * @return the handle, or empty when no such user exists
   */
  @NotNull
  Optional<String> handleOf(@NotNull UUID userId);
}

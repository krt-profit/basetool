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

package de.greluc.krt.profit.basetool.backend.joborder.api;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Who performs a job-order write and through which channel; the commands attribute the write to it,
 * and {@link JobOrderWriterRule#DIRECT_LOGISTICIAN} reads its member's units (ADR-0408).
 */
public sealed interface JobOrderActor {

  /**
   * Returns the member the write is attributed to.
   *
   * @return the member's user id, or {@code null} for a web request without a resolved member
   */
  @Nullable
  UUID member();

  /**
   * Returns the registry client the write comes through, which the origin markers record
   * (REQ-ORDERS-078).
   *
   * @return the client's registry id, or {@code null} for a write in the Basetool web
   */
  @Nullable
  default String clientId() {
    return null;
  }

  /**
   * A member acting in the Basetool web.
   *
   * @param member the member's user id, or {@code null} when the request resolves to none
   */
  record Web(@Nullable UUID member) implements JobOrderActor {}

  /**
   * A member acting through a registered exchange client.
   *
   * @param member the member's user id
   * @param clientId the registry id of the client
   */
  record Client(@NotNull UUID member, @NotNull String clientId) implements JobOrderActor {}
}

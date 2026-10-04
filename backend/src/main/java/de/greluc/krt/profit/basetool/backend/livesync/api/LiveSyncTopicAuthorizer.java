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

package de.greluc.krt.profit.basetool.backend.livesync.api;

import java.util.Set;
import org.jetbrains.annotations.NotNull;

/**
 * Decides whether the current caller may join a live-sync room of the kinds a module owns, by
 * asking the module's own read gate (ADR-0143, plan §5.3).
 *
 * <p>Owned by the livesync module and implemented once per owning module. Every kind except {@link
 * LiveSyncAuthorization#MEMBER} and {@link LiveSyncAuthorization#SELF} has exactly one
 * implementation; a check that throws is a refusal.
 */
public interface LiveSyncTopicAuthorizer {

  /**
   * The authorization kinds this authorizer decides.
   *
   * @return a non-empty set of kinds, never {@code MEMBER} or {@code SELF}
   */
  @NotNull
  Set<LiveSyncAuthorization> authorizations();

  /**
   * Whether the current caller passes the read gate the topic's kind names.
   *
   * @param topic a parsed topic whose kind is one of {@link #authorizations()}
   * @return {@code true} if the room may be opened for the caller
   */
  boolean mayJoin(@NotNull LiveSyncTopic topic);
}

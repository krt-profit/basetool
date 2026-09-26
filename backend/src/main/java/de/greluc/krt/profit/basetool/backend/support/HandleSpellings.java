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

package de.greluc.krt.profit.basetool.backend.support;

import de.greluc.krt.profit.basetool.backend.model.User;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;

/**
 * Every name a member is stored under, shared by the Art. 15 export's handle scrubbing
 * (REQ-SEC-058) and the Art. 17 erasure of text-only handle snapshots (REQ-SEC-062).
 *
 * <p>{@code HandleSpellingCoverageTest} holds this list against {@link PersonSearchTargets}, so a
 * new {@code app_user} name column must be declared here as a spelling or in {@link
 * #NOT_A_SPELLING}.
 */
public final class HandleSpellings {

  /**
   * The {@code app_user} columns holding a name the member is known by, in the order {@link
   * #of(User)} yields them.
   *
   * <p>Physical column names, because the two registries this is held against are written in
   * physical names: {@link PersonSearchTargets} searches columns, not properties.
   */
  public static final List<String> COLUMNS =
      List.of("username", "display_name", "discord_guild_nickname", "rsi_handle");

  /**
   * The other {@code app_user} text columns the person search registers, keyed by physical column
   * name, each with the reason it is not a spelling.
   */
  public static final Map<String, String> NOT_A_SPELLING =
      Map.of(
          "description",
          "The member's own prose about themselves. Searchable because it can name somebody else,"
              + " but it is not a name this member is known by, and matching on a whole paragraph"
              + " would rewrite every row that happens to contain it.",
          "email",
          "An address, not a name. Nobody types a member's e-mail address into a job-order contact"
              + " or a handover receipt, and the local part of a common address is short enough to"
              + " occur inside unrelated text.");

  /** Not instantiable: a list and a projection over it. */
  private HandleSpellings() {}

  /**
   * Returns the member's names, one per {@link #COLUMNS} entry and in that order.
   *
   * <p>Nulls and blanks are passed through; filtering is up to the caller.
   *
   * @param user the member
   * @return their username, display name, Discord guild nickname and RSI handle, nulls included
   */
  public static @NotNull Stream<String> of(@NotNull User user) {
    return Stream.of(
        user.getUsername(),
        user.getDisplayName(),
        user.getDiscordGuildNickname(),
        user.getRsiHandle());
  }
}

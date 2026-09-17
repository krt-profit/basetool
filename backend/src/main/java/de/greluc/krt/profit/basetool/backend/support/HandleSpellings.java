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
 * Every name a member is stored under, as one list two data-protection surfaces share.
 *
 * <p><b>Why this exists as a type.</b> Two places have to match on <em>all</em> the names a member
 * is known by, not on their effective name alone: the Art. 15 export, which scrubs other members'
 * handles out of the subject's free text (REQ-SEC-058), and the Art. 17 erasure, which matches the
 * text-only handle snapshots — a job-order contact, a handover recipient — that carry no foreign
 * key to the account (REQ-SEC-062). Whoever typed one of those wrote what <em>they</em> call the
 * person, and that is as likely to be the Discord guild nickname as the display name. Both places
 * spelled the list out for themselves, so a fourth name column would have been added to one and
 * missed in the other, and the miss is silent in both directions: an export that reports a complete
 * redaction, or an erasure that reports a completed erasure while rows still name the member.
 *
 * <p>{@code HandleSpellingCoverageTest} holds this list against the person-search registry ({@link
 * PersonSearchTargets}), which is itself swept against {@code information_schema} — so a new name
 * column on {@code app_user} cannot reach the schema without being registered for the search, and
 * cannot be registered for the search without being declared here as a spelling or as {@link
 * #NOT_A_SPELLING} with a reason. A {@code DataExportService} Javadoc claimed that gate existed
 * before it did (corrected 2026-09-17).
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
      List.of("username", "display_name", "discord_guild_nickname");

  /**
   * The other {@code app_user} text columns the person search registers, and why each is not a
   * spelling.
   *
   * <p>This is the half that makes the coverage test catch an <em>addition</em>. Without it the
   * test could only check that every listed spelling is searched, which a new name column would
   * satisfy by being searched and never being listed. Keyed by physical column name.
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
   * The member's names, one per {@link #COLUMNS} entry and in that order.
   *
   * <p>Nulls and blanks are passed through, because what to do with them differs by caller: the
   * export drops them, and the erasure drops them <em>and</em> sorts the rest longest-first. Doing
   * either here would make this type a policy rather than a list.
   *
   * @param user the member
   * @return their username, display name and Discord guild nickname, in {@link #COLUMNS} order,
   *     nulls included
   */
  public static @NotNull Stream<String> of(@NotNull User user) {
    return Stream.of(user.getUsername(), user.getDisplayName(), user.getDiscordGuildNickname());
  }
}

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

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.model.User;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Requires every {@code app_user} name column in the person-search registry to be either a {@link
 * HandleSpellings} column or declared {@link HandleSpellings#NOT_A_SPELLING}, so the export
 * scrubber and the erasure cover the same names (REQ-SEC-058, REQ-SEC-062).
 */
class HandleSpellingCoverageTest {

  /** The table the spellings live on. */
  private static final String TABLE = "app_user";

  /** The {@code app_user} text columns the person search matches names against. */
  private static Set<String> searchedColumns() {
    return PersonSearchTargets.TARGETS.stream()
        .filter(t -> TABLE.equals(t.table()))
        .map(PersonSearchTargets.Target::column)
        .collect(Collectors.toSet());
  }

  @Test
  void everySpellingIsAColumnThePersonSearchMatchesOn() {
    assertThat(searchedColumns())
        .as(
            "A name a member is stored under must be findable by the Personensuche, because that is"
                + " the only route to the text-only snapshots after the account is gone. Add the"
                + " column to PersonSearchTargets.TARGETS.")
        .containsAll(HandleSpellings.COLUMNS);
  }

  @Test
  void everySearchedMemberColumnIsEitherASpellingOrDeclaredNotToBeOne() {
    Set<String> accounted =
        java.util.stream.Stream.concat(
                HandleSpellings.COLUMNS.stream(), HandleSpellings.NOT_A_SPELLING.keySet().stream())
            .collect(Collectors.toSet());

    assertThat(searchedColumns())
        .as(
            "Every app_user text column the search matches names against must be listed in"
                + " HandleSpellings.COLUMNS or, with a reason, in NOT_A_SPELLING. Leaving it absent"
                + " is the state where the export scrubs three of four names and the erasure erases"
                + " three of four.")
        .allSatisfy(column -> assertThat(accounted).contains(column));
  }

  @Test
  void everyDeclaredNonSpellingIsRealAndStatesAReason() {
    assertThat(HandleSpellings.NOT_A_SPELLING)
        .allSatisfy(
            (column, reason) -> {
              assertThat(searchedColumns())
                  .as("NOT_A_SPELLING names a searched app_user column: " + column)
                  .contains(column);
              assertThat(reason)
                  .as("reason for " + column)
                  .isNotBlank()
                  .hasSizeGreaterThan(30)
                  .doesNotContainIgnoringCase("todo");
            });
  }

  @Test
  void noColumnIsBothASpellingAndNotOne() {
    assertThat(HandleSpellings.COLUMNS)
        .as("a column is a spelling or it is not; both entries cannot be true")
        .doesNotContainAnyElementsOf(HandleSpellings.NOT_A_SPELLING.keySet());
  }

  /** The projection yields one distinct value per declared column, in declaration order. */
  @Test
  void theProjectionYieldsExactlyTheDeclaredColumnsInOrder() {
    User user = new User();
    user.setUsername("spelling-username");
    user.setDisplayName("spelling-display-name");
    user.setDiscordGuildNickname("spelling-guild-nickname");
    user.setRsiHandle("spelling-rsi-handle");

    List<String> yielded = HandleSpellings.of(user).toList();

    assertThat(yielded)
        .as("one value per declared column")
        .hasSameSizeAs(HandleSpellings.COLUMNS)
        .containsExactly(
            "spelling-username",
            "spelling-display-name",
            "spelling-guild-nickname",
            "spelling-rsi-handle");
  }

  /** Nulls are passed through, because the two callers drop them on different terms. */
  @Test
  void theProjectionPassesNullsThroughRatherThanDecidingForItsCallers() {
    User user = new User();
    user.setUsername("only-a-username");

    assertThat(HandleSpellings.of(user).toList())
        .as("a null column is still a slot; the caller decides what to do with it")
        .containsExactly("only-a-username", null, null, null);
  }
}

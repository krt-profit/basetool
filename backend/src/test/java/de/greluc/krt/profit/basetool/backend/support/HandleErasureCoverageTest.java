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

import de.greluc.krt.profit.basetool.backend.service.HandleAnonymisationService;
import de.greluc.krt.profit.basetool.backend.support.HandleErasureCoverage.Coverage;
import de.greluc.krt.profit.basetool.backend.support.HandleErasureCoverage.Disposition;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Keeps the Art. 17 erasure's set of columns closed against the schema (REQ-SEC-062, ADR-0183).
 *
 * <p>{@code HandleAnonymisationService} used to describe its set as closed in a comment, and the
 * comment went out of date: five columns survived a granted erasure, among them the bank custodian
 * registry, whose whole purpose is to outlive the account. The person search never drifted the same
 * way because {@code PersonSearchCoverageTest} would not let it. This is that gate for the erasure.
 *
 * <p>Every column {@link PersonSearchTargets} registers as a place a person is named must carry a
 * disposition in {@link HandleErasureCoverage}. Adding a search target therefore forces somebody to
 * answer "and what does an erasure do about this one?" while they still have the context to answer
 * it — which is the whole mechanism, not a side effect of it.
 */
class HandleErasureCoverageTest {

  /** Every registered person-name column, as {@code table.column}. */
  private static Set<String> registeredColumns() {
    return PersonSearchTargets.TARGETS.stream()
        .map(t -> t.table() + "." + t.column())
        .collect(Collectors.toSet());
  }

  // covers REQ-SEC-062 - no place a person is named may be left without a stated disposition
  @Test
  void everyPersonNameColumnHasADisposition() {
    List<String> missing =
        registeredColumns().stream()
            .filter(column -> HandleErasureCoverage.of(column) == null)
            .sorted()
            .toList();

    assertThat(missing)
        .as(
            "Each of these is a column the person search reports a name from, and nothing says what"
                + " a granted Art. 17 request does about it. Classify it in HandleErasureCoverage:"
                + " ANONYMISED if the erasure rewrites it, REMOVED_WITH_THE_ACCOUNT if the row"
                + " goes, NOT_THE_MEMBER if it names somebody else, REVIEWED_BY_HAND if an admin"
                + " has to edit the prose. Leaving it unclassified is how the set came to be"
                + " described as closed while five columns survived.")
        .isEmpty();
  }

  // covers REQ-SEC-062 - and no disposition may describe a column that is no longer searched
  @Test
  void noDispositionDescribesAColumnThatIsNotRegistered() {
    Set<String> registered = registeredColumns();
    List<String> stale =
        HandleErasureCoverage.COVERAGE.keySet().stream()
            .filter(column -> !registered.contains(column))
            .sorted()
            .toList();

    assertThat(stale)
        .as(
            "A disposition for a column the search no longer covers reads as a considered decision"
                + " about something that is not there -- and hides the fact that the pair has come"
                + " apart. Either the search target was dropped by mistake, or this entry is dead.")
        .isEmpty();
  }

  // covers REQ-SEC-062 - every disposition states a reason an admin serving a request can act on
  @Test
  void everyDispositionStatesAReason() {
    // A cross-reference ("See bank_transaction.note.") is a real answer and is deliberately short;
    // anything else has to be a sentence. The bar is against a placeholder, not against brevity.
    assertThat(HandleErasureCoverage.COVERAGE)
        .allSatisfy(
            (column, coverage) -> {
              String reason = coverage.reason();
              assertThat(reason)
                  .as("reason for " + column)
                  .isNotBlank()
                  .doesNotContainIgnoringCase("todo")
                  .doesNotContainIgnoringCase("tbd");
              if (!reason.startsWith("See ")) {
                assertThat(reason)
                    .as("reason for " + column + " is not a cross-reference, so it must explain")
                    .hasSizeGreaterThan(25);
              }
            });
  }

  // covers REQ-SEC-062 - the columns the service rewrites are exactly the ones marked ANONYMISED
  @Test
  void theAnonymisedSetMatchesWhatTheServiceActuallyRewrites() {
    // Read from the service, not restated here. A literal copy in this test is how the two came
    // apart: it held fourteen entries while the service rewrote fifteen columns, and adding the
    // missing one failed the sibling assertion because that column is EXEMPT from the search -- so
    // the only configuration in which everything was green was the one that understated the
    // erasure. One list, in the class that does the work.
    Set<String> declared =
        HandleErasureCoverage.COVERAGE.entrySet().stream()
            .filter(e -> e.getValue().disposition() == Disposition.ANONYMISED)
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());

    assertThat(declared)
        .as("HandleAnonymisationService.ANONYMISED_COLUMNS is the one list; this map must match it")
        .isEqualTo(Set.copyOf(HandleAnonymisationService.ANONYMISED_COLUMNS));
  }

  // covers REQ-SEC-062 - and every column it rewrites is one the person search can find again
  @Test
  void everyAnonymisedColumnIsAlsoSearchable() {
    Set<String> registered = registeredColumns();
    assertThat(HandleAnonymisationService.ANONYMISED_COLUMNS)
        .as(
            "A column the erasure rewrites but the search cannot reach is a column an admin cannot"
                + " verify afterwards -- and for an already-deleted account the search is the only"
                + " route to it at all.")
        .allSatisfy(column -> assertThat(registered).contains(column));
  }

  // covers REQ-SEC-062 - the manual residue is visible rather than implied
  @Test
  void theManualResidueIsAcknowledgedAndNotEmpty() {
    // If this ever hits zero, either every prose column became mechanically erasable -- which
    // cannot happen, a name inside a sentence is not a column -- or somebody reclassified the
    // residue away to make the registry look complete. The privacy record promises an admin walks
    // the Personensuche hits for exactly these; that promise has to have something behind it.
    long byHand =
        HandleErasureCoverage.COVERAGE.values().stream()
            .map(Coverage::disposition)
            .filter(d -> d == Disposition.REVIEWED_BY_HAND)
            .count();

    assertThat(byHand)
        .as("the columns an admin has to edit by hand, which docs/privacy names as a required step")
        .isPositive();
  }
}

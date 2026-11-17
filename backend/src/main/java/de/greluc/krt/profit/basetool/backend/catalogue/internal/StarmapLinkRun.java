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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Decides the links of one run's UEX location rows, parents first (REQ-P4K-035, REQ-P4K-060). A
 * terminal is never name-matched: its place is its nearest linked parent. A row of a star system
 * that links to no object links nothing. An orbit that shares its UEX id with a planet takes that
 * planet's link, and any other orbit links nothing. None of these opens a case. Every other row
 * links to its one candidate ({@link StarmapCandidates}); without one it opens a case unless {@link
 * StarmapCaseRules} says the case could settle nothing.
 */
final class StarmapLinkRun {

  private final StarmapCandidates candidates;
  private final StarmapCaseRules caseRules;

  /** The planet rows by UEX id, the twins of the orbits that share it. */
  private final Map<Integer, UUID> planetRowsByUexId = new HashMap<>();

  /** The links of the rows decided so far, by star system and name, for their children. */
  private final StarmapParentLinks<UUID> linked = new StarmapParentLinks<>();

  /** The links of the rows decided so far, by row. */
  private final Map<UUID, UUID> linkByRow = new HashMap<>();

  /**
   * Starts a run.
   *
   * @param candidates the candidate finder over the run's tree
   * @param caseRules the rules for a row left unlinked
   * @param planets the UEX planet rows
   */
  StarmapLinkRun(
      @NotNull StarmapCandidates candidates,
      @NotNull StarmapCaseRules caseRules,
      @NotNull List<StarmapPlaceRow> planets) {
    this.candidates = candidates;
    this.caseRules = caseRules;
    for (StarmapPlaceRow planet : planets) {
      Integer uexId = planet.uexId();
      if (uexId != null) {
        planetRowsByUexId.put(uexId, planet.id());
      }
    }
  }

  /**
   * Decides the link of a row the import owns; the rows of the kinds before it must be recorded.
   *
   * @param row the row
   * @return the object it links to and the case it opens without one
   */
  @NotNull
  Decision decide(@NotNull StarmapPlaceRow row) {
    Map<StarmapPlaceKind, Map<String, UUID>> links = linked.byKind();
    if (row.kind() == StarmapPlaceKind.TERMINAL
        || (row.kind() != StarmapPlaceKind.STAR_SYSTEM && row.starSystemLink(links) == null)) {
      return new Decision(null, null);
    }
    if (row.kind() == StarmapPlaceKind.ORBIT) {
      UUID twin = planetTwin(row);
      return new Decision(twin == null ? null : linkByRow.get(twin), null);
    }
    List<UUID> found = candidates.find(row, row.linkedParents(links));
    if (found.size() == 1) {
      return new Decision(found.getFirst(), null);
    }
    if (!caseRules.opensCase(row, found)) {
      return new Decision(null, null);
    }
    return new Decision(
        null,
        found.isEmpty()
            ? P4kReviewReason.STARMAP_LINK_NOT_FOUND
            : P4kReviewReason.STARMAP_LINK_AMBIGUOUS);
  }

  /**
   * Records a row's link after the run, so the rows of later kinds find it as their parent.
   *
   * @param row the row
   * @param link the GUID it links to, or {@code null}
   */
  void record(@NotNull StarmapPlaceRow row, @Nullable UUID link) {
    if (link != null) {
      linked.put(row, link);
      linkByRow.put(row.id(), link);
    }
  }

  /**
   * Returns the planet row an orbit shares its UEX id with.
   *
   * @param orbit the orbit row
   * @return the planet row's id, or {@code null} when the orbit is no planet
   */
  @Nullable
  private UUID planetTwin(@NotNull StarmapPlaceRow orbit) {
    Integer uexId = orbit.uexId();
    return uexId == null ? null : planetRowsByUexId.get(uexId);
  }

  /**
   * A row's decided link.
   *
   * @param target the GUID of the object the row links to, or {@code null}
   * @param caseReason the review case an unlinked row opens, or {@code null} for none
   */
  record Decision(@Nullable UUID target, @Nullable P4kReviewReason caseReason) {}
}

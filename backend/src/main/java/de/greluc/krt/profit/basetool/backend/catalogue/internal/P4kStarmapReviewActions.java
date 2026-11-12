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

import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Proposes starmap objects for the UEX location named by a review case. */
@Component
@RequiredArgsConstructor
class P4kStarmapReviewActions implements P4kReviewActionProvider {

  private static final int MAX_ALTERNATIVES = 20;

  private final StarmapPlaceRows placeRows;
  private final StarmapObjectRepository objectRepository;
  private final StarmapAdminService starmapAdmin;

  @Override
  public boolean supports(@NotNull P4kKind kind) {
    return kind == P4kKind.STARMAP_OBJECT;
  }

  @Override
  @NotNull
  public Map<UUID, P4kReviewReadiness> summarize(@NotNull List<P4kReviewCaseSource> reviews) {
    if (reviews.isEmpty()) {
      return Map.of();
    }
    Set<StarmapPlaceKind> kinds = EnumSet.noneOf(StarmapPlaceKind.class);
    reviews.forEach(source -> kind(source.review()).ifPresent(kinds::add));
    P4kStarmapReviewIndex index =
        new P4kStarmapReviewIndex(
            P4kStarmapTree.stored(objectRepository.findAll()), placeRows, kinds);
    Map<UUID, P4kReviewReadiness> readiness = new HashMap<>();
    for (P4kReviewCaseSource source : reviews) {
      P4kReviewCaseDto review = source.review();
      readiness.put(review.id(), match(index, index.subject(review.guid())).readiness());
    }
    return Map.copyOf(readiness);
  }

  @Override
  @NotNull
  public P4kReviewOptions options(@NotNull P4kReviewCaseDto review, @Nullable String recordPath) {
    Optional<StarmapPlaceKind> kind = kind(review);
    if (kind.isEmpty()) {
      return new P4kReviewOptions(P4kReviewReadiness.BLOCKED, review.nameEn(), List.of());
    }
    P4kStarmapTree tree = P4kStarmapTree.stored(objectRepository.findAll());
    P4kStarmapReviewIndex index =
        new P4kStarmapReviewIndex(tree, placeRows, Set.of(kind.orElseThrow()));
    StarmapPlaceRow row = index.subject(review.guid());
    Match match = match(index, row);
    String path = row == null ? review.nameEn() : row.reviewPath();
    if (row == null || match.readiness() == P4kReviewReadiness.BLOCKED) {
      return new P4kReviewOptions(match.readiness(), path, List.of());
    }
    UUID ancestor = match.ancestor();
    boolean exact = !match.exact().isEmpty();
    List<P4kReviewCandidate> candidates =
        (exact
                ? match.exact().stream()
                : tree.all().stream()
                    .filter(object -> ancestor != null && index.compatible(row, ancestor, object)))
            .sorted(
                Comparator.comparing(P4kStarmapTree::objectLabel, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(object -> Objects.requireNonNull(object.getId())))
            .limit(MAX_ALTERNATIVES)
            .map(object -> candidate(tree, object, ancestor != null, exact))
            .toList();
    return new P4kReviewOptions(match.readiness(), path, candidates);
  }

  @Override
  public void apply(
      @NotNull P4kReviewCaseDto review,
      @Nullable String recordPath,
      @NotNull P4kReviewCandidate candidate,
      @NotNull UUID adminUserId) {
    StarmapPlaceRow row =
        kind(review)
            .flatMap(kind ->
                placeRows.rows(kind).stream()
                    .filter(source ->
                        P4kStarmapLinker.caseGuid(kind, source.id()).equals(review.guid()))
                    .findFirst())
            .orElseThrow(() -> new BadRequestException("The UEX location is unavailable."));
    starmapAdmin.changeLink(
        row.kind(), row.id(), StarmapLinkAction.SET, candidate.targetId(), null, false);
  }

  /**
   * Recovers the UEX table recorded by the linker.
   *
   * @param review the location case
   * @return the supported source kind, if present
   */
  private static Optional<StarmapPlaceKind> kind(@NotNull P4kReviewCaseDto review) {
    for (StarmapPlaceKind kind : StarmapPlaceKind.values()) {
      if (kind.getTable().equals(review.className())) {
        return Optional.of(kind);
      }
    }
    return Optional.empty();
  }

  /**
   * Computes the readiness and eligible exact matches from the same current parent scope.
   *
   * @param index the current request's UEX and P4K lookup
   * @param row the source location, if present
   * @return the scope and exact evidence for the case
   */
  private static Match match(@NotNull P4kStarmapReviewIndex index, @Nullable StarmapPlaceRow row) {
    if (row == null || row.manual() || row.starmapObjectId() != null) {
      return new Match(P4kReviewReadiness.BLOCKED, null, List.of());
    }
    UUID ancestor = index.ancestor(row);
    if (ancestor == null && row.kind() != StarmapPlaceKind.STAR_SYSTEM) {
      return new Match(P4kReviewReadiness.BLOCKED, null, List.of());
    }
    List<StarmapObject> exact = index.exact(row, ancestor);
    return new Match(
        exact.size() == 1 ? P4kReviewReadiness.ACTIONABLE : P4kReviewReadiness.CHECK_REQUIRED,
        ancestor,
        exact);
  }

  /**
   * Builds the link action and reports the evidence that scoped the target.
   *
   * @param tree the tree supplying the target path
   * @param object the target
   * @param parentMatched whether the target falls below a linked UEX parent
   * @param nameMatched whether the normalized English name matches
   * @return the link proposal
   */
  private static P4kReviewCandidate candidate(
      @NotNull P4kStarmapTree tree,
      @NotNull StarmapObject object,
      boolean parentMatched,
      boolean nameMatched) {
    List<P4kReviewEvidence> evidence;
    if (!parentMatched) {
      evidence = List.of(P4kReviewEvidence.SAME_TYPE, P4kReviewEvidence.SAME_NAME);
    } else if (nameMatched) {
      evidence =
          List.of(
              P4kReviewEvidence.PARENT_LINK,
              P4kReviewEvidence.SAME_TYPE,
              P4kReviewEvidence.SAME_NAME);
    } else {
      evidence = List.of(P4kReviewEvidence.PARENT_LINK, P4kReviewEvidence.SAME_TYPE);
    }
    return new P4kReviewCandidate(
        P4kReviewAction.LINK_UEX_PLACE,
        Objects.requireNonNull(object.getId()),
        object.getVersion(),
        null,
        tree.path(object.getP4kUuid()),
        object.getClassName(),
        object.getP4kUuid(),
        null,
        List.of(),
        evidence);
  }

  /**
   * The evidence available for a UEX location in its current parent scope.
   *
   * @param readiness the category shown to the reviewer
   * @param ancestor the active parent scope when present
   * @param exact the eligible name and type matches in that scope
   */
  private record Match(
      @NotNull P4kReviewReadiness readiness,
      @Nullable UUID ancestor,
      @NotNull List<StarmapObject> exact) {}
}

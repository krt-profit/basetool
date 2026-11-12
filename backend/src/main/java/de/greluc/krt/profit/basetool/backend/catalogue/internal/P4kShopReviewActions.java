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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Terminal;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Proposes UEX terminals at the P4K place of a shop under review. */
@Component
@RequiredArgsConstructor
class P4kShopReviewActions implements P4kReviewActionProvider {

  private final ShopRepository shopRepository;
  private final TerminalRepository terminalRepository;
  private final StarmapObjectRepository objectRepository;
  private final P4kShopLinker shopLinker;
  private final ShopAdminService shopAdmin;

  @Override
  public boolean supports(@NotNull P4kKind kind) {
    return kind == P4kKind.SHOP;
  }

  @Override
  @NotNull
  public Map<UUID, P4kReviewReadiness> summarize(@NotNull List<P4kReviewCaseSource> reviews) {
    if (reviews.isEmpty()) {
      return Map.of();
    }
    List<UUID> ids =
        reviews.stream()
            .map(source -> source.review().rowId())
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    Map<UUID, Shop> shops =
        ids.isEmpty()
            ? Map.of()
            : shopRepository.findAllById(ids).stream()
                .collect(
                    Collectors.toMap(
                        shop -> Objects.requireNonNull(shop.getId()), Function.identity()));
    List<UUID> placeIds =
        shops.values().stream()
            .filter(P4kShopReviewActions::unlinked)
            .map(Shop::getStarmapObjectId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    Set<UUID> places =
        placeIds.isEmpty()
            ? Set.of()
            : objectRepository.findByIdIn(placeIds).stream()
                .filter(place -> !place.isP4kRetired())
                .map(StarmapObject::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    Map<UUID, List<StarmapPlaceRow>> terminals =
        places.isEmpty() ? Map.of() : shopLinker.terminalsByPlace();
    Map<UUID, P4kReviewReadiness> results = new HashMap<>();
    for (P4kReviewCaseSource source : reviews) {
      P4kReviewCaseDto review = source.review();
      UUID id = review.rowId();
      Shop shop = id == null ? null : shops.get(id);
      UUID placeId = shop == null ? null : shop.getStarmapObjectId();
      P4kReviewReadiness readiness = P4kReviewReadiness.BLOCKED;
      if (shop != null && unlinked(shop) && placeId != null && places.contains(placeId)) {
        List<StarmapPlaceRow> matches =
            ShopTerminalNames.bestMatches(shop, terminals.getOrDefault(placeId, List.of()));
        readiness = scopedReadiness(matches.size());
      }
      results.put(review.id(), readiness);
    }
    return Map.copyOf(results);
  }

  @Override
  @NotNull
  public P4kReviewOptions options(@NotNull P4kReviewCaseDto review, @Nullable String recordPath) {
    UUID id = review.rowId();
    if (id == null) {
      return new P4kReviewOptions(P4kReviewReadiness.BLOCKED, review.nameEn(), List.of());
    }
    return shopRepository
        .findById(id)
        .map(this::terminalOptions)
        .orElseGet(
            () -> new P4kReviewOptions(P4kReviewReadiness.BLOCKED, review.nameEn(), List.of()));
  }

  @Override
  public void apply(
      @NotNull P4kReviewCaseDto review,
      @Nullable String recordPath,
      @NotNull P4kReviewCandidate candidate,
      @NotNull UUID adminUserId) {
    UUID id = review.rowId();
    if (id == null) {
      throw new BadRequestException("The P4K shop is unavailable.");
    }
    shopAdmin.changeLink(id, StarmapLinkAction.SET, candidate.targetId(), null, false);
  }

  /**
   * Restricts the terminal choices to the shop's active place.
   *
   * @param shop the shop
   * @return the current place and terminal choices
   */
  private P4kReviewOptions terminalOptions(@NotNull Shop shop) {
    String sourcePath =
        Stream.of(
                shop.getStarSystem(),
                shop.getLocationName(),
                shop.getRegistryName() == null ? shop.getEntityName() : shop.getRegistryName())
            .filter(part -> part != null && !part.isBlank())
            .distinct()
            .collect(Collectors.joining(" / "));
    UUID placeId = shop.getStarmapObjectId();
    if (!unlinked(shop) || placeId == null) {
      return new P4kReviewOptions(P4kReviewReadiness.BLOCKED, sourcePath, List.of());
    }
    P4kStarmapTree tree = P4kStarmapTree.stored(objectRepository.findAll());
    StarmapObject place =
        tree.all().stream()
            .filter(object -> placeId.equals(object.getId()) && !object.isP4kRetired())
            .findFirst()
            .orElse(null);
    if (place == null) {
      return new P4kReviewOptions(P4kReviewReadiness.BLOCKED, sourcePath, List.of());
    }
    String path = tree.path(place.getP4kUuid());
    List<StarmapPlaceRow> terminals =
        shopLinker.terminalsByPlace().getOrDefault(placeId, List.of());
    if (terminals.isEmpty()) {
      return new P4kReviewOptions(P4kReviewReadiness.CHECK_REQUIRED, path, List.of());
    }
    List<StarmapPlaceRow> matches = ShopTerminalNames.bestMatches(shop, terminals);
    boolean matched = !matches.isEmpty();
    List<StarmapPlaceRow> shortlist =
        (matched ? matches : terminals)
            .stream()
                .sorted(
                    Comparator.comparing(StarmapPlaceRow::reviewPath, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(StarmapPlaceRow::id))
                .limit(ShopAdminService.TERMINAL_OPTIONS)
                .toList();
    Map<UUID, Terminal> identities =
        terminalRepository
            .findAllById(shortlist.stream().map(StarmapPlaceRow::id).toList())
            .stream()
            .collect(
                Collectors.toMap(
                    terminal -> Objects.requireNonNull(terminal.getId()), Function.identity()));
    List<P4kReviewCandidate> candidates =
        shortlist.stream()
            .flatMap(terminal ->
                Optional.ofNullable(identities.get(terminal.id())).stream()
                    .map(identity -> candidate(shop, terminal, identity, matched)))
            .toList();
    return new P4kReviewOptions(
        scopedReadiness(candidates.isEmpty() ? 0 : matches.size()), path, candidates);
  }

  /**
   * Checks that the shop can receive an unresolved terminal link.
   *
   * @param shop the current shop
   * @return whether an active, unlinked shop is eligible
   */
  private static boolean unlinked(@NotNull Shop shop) {
    return !shop.isP4kRetired() && !shop.isTerminalLinkManual() && shop.getTerminalId() == null;
  }

  /**
   * Interprets the name evidence available at an active place.
   *
   * @param matches the number of best name matches
   * @return the case readiness at that place
   */
  private static P4kReviewReadiness scopedReadiness(int matches) {
    return matches == 1 ? P4kReviewReadiness.ACTIONABLE : P4kReviewReadiness.CHECK_REQUIRED;
  }

  /**
   * Builds a terminal link proposal scoped to the shop's place.
   *
   * @param shop the shop being linked
   * @param terminal the terminal at its place
   * @param identity the terminal's persisted UEX identifier and version
   * @param matched whether the terminal also matches the shop-name rule
   * @return the terminal choice
   */
  private static P4kReviewCandidate candidate(
      @NotNull Shop shop,
      @NotNull StarmapPlaceRow terminal,
      @NotNull Terminal identity,
      boolean matched) {
    String path = terminal.reviewPath();
    Integer uexId = identity.getIdTerminal();
    List<P4kReviewEvidence> evidence =
        matched
            ? List.of(P4kReviewEvidence.SAME_PLACE, P4kReviewEvidence.SHOP_NAME_MATCH)
            : List.of(P4kReviewEvidence.SAME_PLACE);
    return new P4kReviewCandidate(
        P4kReviewAction.LINK_UEX_TERMINAL,
        terminal.id(),
        identity.getVersion(),
        shop.getVersion(),
        path.isBlank() ? terminal.id().toString() : path,
        null,
        null,
        null,
        uexId == null ? List.of() : List.of(uexId),
        evidence);
  }
}

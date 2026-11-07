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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Links the shops of a run to the UEX terminals on APPLY (REQ-P4K-043, ADR-0347): a shop links to
 * the one terminal of its place whose name matches it ({@link ShopTerminalNames}). A terminal's
 * place is its own starmap link, else the link of its nearest linked UEX parent (REQ-P4K-035).
 * Several candidates or none leave the shop unlinked, count it as unmatched and open a review case;
 * a shop an admin linked or unlinked by hand is left alone. A run closes the cases it no longer
 * finds. The links are read as stored, so a preview links against the starmap links of the last
 * APPLY.
 */
@Component
@RequiredArgsConstructor
public class P4kShopLinker {

  /** The kind the link cases and report rows belong to. */
  private static final P4kKind KIND = P4kKind.SHOP;

  /** The reasons this linker owns. */
  private static final List<P4kReviewReason> REASONS =
      List.of(P4kReviewReason.SHOP_LINK_AMBIGUOUS, P4kReviewReason.SHOP_LINK_NOT_FOUND);

  private final StarmapPlaceRows placeRows;
  private final ShopRepository shopRepository;
  private final P4kRunResolvedCases runResolvedCases;

  /**
   * Links every shop of the run an admin did not link by hand.
   *
   * @param shops the shops of the run with their records
   * @param context the run
   */
  public void link(
      @NotNull List<P4kShopReconciler.Reconciled> shops, @NotNull P4kImportContext context) {
    Map<UUID, List<StarmapPlaceRow>> terminalsByPlace = terminalsByPlace();
    Set<P4kRunResolvedCases.Found> found = new HashSet<>();
    for (P4kShopReconciler.Reconciled shop : shops) {
      if (!shop.row().isTerminalLinkManual()) {
        linkShop(shop, terminalsByPlace, found, context);
      }
    }
    if (context.isApply()) {
      runResolvedCases.closeUnfound(KIND, REASONS, found, context.getNow());
    }
  }

  /**
   * Links one shop, reporting a changed link, an unmatched shop and its case.
   *
   * @param shop the shop and its record
   * @param terminalsByPlace the UEX terminals by the starmap object of their place
   * @param found the cases this run found, extended
   * @param context the run
   */
  private void linkShop(
      @NotNull P4kShopReconciler.Reconciled shop,
      @NotNull Map<UUID, List<StarmapPlaceRow>> terminalsByPlace,
      @NotNull Set<P4kRunResolvedCases.Found> found,
      @NotNull P4kImportContext context) {
    Shop row = shop.row();
    UUID place = row.getStarmapObjectId();
    List<StarmapPlaceRow> candidates =
        place == null
            ? List.of()
            : ShopTerminalNames.bestMatches(row, terminalsByPlace.getOrDefault(place, List.of()));
    UUID target = candidates.size() == 1 ? candidates.getFirst().id() : null;
    UUID current = row.getTerminalId();
    if (!Objects.equals(current, target)) {
      context
          .getReport()
          .changed(
              KIND,
              shop.record(),
              "terminal_id",
              current == null ? null : current.toString(),
              target == null ? null : target.toString());
      if (context.isApply()) {
        shopRepository.autoLinkTerminal(row.getId(), target);
      }
    }
    if (target == null) {
      P4kReviewReason reason =
          candidates.isEmpty()
              ? P4kReviewReason.SHOP_LINK_NOT_FOUND
              : P4kReviewReason.SHOP_LINK_AMBIGUOUS;
      P4kRunResolvedCases.Found finding = new P4kRunResolvedCases.Found(row.getP4kUuid(), reason);
      found.add(finding);
      context.getReport().unmatched(KIND, shop.record());
      context.getReport().review(KIND, shop.record(), row.getP4kUuid(), reason, row.getId());
      if (context.isApply()) {
        runResolvedCases.reopenIfRunResolved(KIND, finding);
      }
    }
  }

  /**
   * Groups the UEX terminals by the starmap object of their place: the terminal's own link, else
   * that of its nearest linked parent.
   *
   * @return the terminals by place; a terminal without a linked place is left out
   */
  @NotNull
  private Map<UUID, List<StarmapPlaceRow>> terminalsByPlace() {
    Map<StarmapPlaceKind, Map<String, UUID>> linked = new EnumMap<>(StarmapPlaceKind.class);
    for (StarmapPlaceKind kind : StarmapPlaceKind.values()) {
      if (kind == StarmapPlaceKind.TERMINAL) {
        continue;
      }
      Map<String, UUID> ofKind = new HashMap<>();
      for (StarmapPlaceRow row : placeRows.rows(kind)) {
        if (row.starmapObjectId() != null && row.name() != null) {
          ofKind.put(StarmapNames.key(row.starSystem(), row.name()), row.starmapObjectId());
        }
      }
      linked.put(kind, ofKind);
    }
    Map<UUID, List<StarmapPlaceRow>> byPlace = new HashMap<>();
    for (StarmapPlaceRow terminal : placeRows.rows(StarmapPlaceKind.TERMINAL)) {
      UUID place = placeOf(terminal, linked);
      if (place != null) {
        byPlace.computeIfAbsent(place, _ -> new ArrayList<>()).add(terminal);
      }
    }
    return byPlace;
  }

  /**
   * Returns the starmap object of a terminal's place.
   *
   * @param terminal the terminal
   * @param linked the links of the other UEX rows by kind, star system and name
   * @return the terminal's own link, else its nearest linked parent's, or {@code null}
   */
  @Nullable
  private static UUID placeOf(
      @NotNull StarmapPlaceRow terminal, @NotNull Map<StarmapPlaceKind, Map<String, UUID>> linked) {
    return terminal.starmapObjectId() != null
        ? terminal.starmapObjectId()
        : terminal.nearestLinkedParent(linked);
  }
}

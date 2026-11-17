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
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kShopDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kStarmapObjectDto;
import de.greluc.krt.profit.basetool.backend.kernel.UuidArrayLiteral;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Component;

/**
 * Links the shops of a run to the UEX terminals on APPLY (REQ-P4K-043, REQ-P4K-060, ADR-0347,
 * ADR-0395): a shop links to the one terminal at or around its place ({@link ShopPlaces}) whose
 * name matches it ({@link ShopTerminalNames}), a tie decided by {@link ShopTerminalTieBreaks}, else
 * to the one terminal around it that names it by an alias ({@link ShopTerminalAliases}). A
 * terminal's place is its own starmap link, else the link of its nearest linked UEX parent
 * (REQ-P4K-035). Several candidates or none leave the shop unlinked and count it as unmatched; it
 * opens a review case unless its record names no place, it has no item list, or it has no match and
 * every terminal around it is linked to a shop. A shop an admin linked or unlinked by hand is left
 * alone. A run closes the cases of the shops it lists that it no longer finds; a shop a partial
 * catalogue leaves out keeps its case (REQ-P4K-006). The links are read as stored, so a preview
 * links against the starmap links of the last APPLY.
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
  private final StarmapObjectRepository objectRepository;
  private final ShopRepository shopRepository;
  private final P4kRunResolvedCases runResolvedCases;

  /**
   * Links every shop of the run an admin did not link by hand.
   *
   * @param shops the shops of the run with their records
   * @param starmapRecords the run's starmap records, which name the entities placing each object
   * @param context the run
   */
  public void link(
      @NotNull List<P4kShopReconciler.Reconciled> shops,
      @NotNull List<P4kStarmapObjectDto> starmapRecords,
      @NotNull P4kImportContext context) {
    Map<UUID, List<StarmapPlaceRow>> terminalsByPlace = terminalsByPlace();
    ShopPlaces places = new ShopPlaces(objectRepository.findAll(), starmapRecords);
    Set<UUID> evaluated = new HashSet<>();
    Set<UUID> taken = new HashSet<>();
    List<Match> matches = new ArrayList<>();
    for (P4kShopReconciler.Reconciled shop : shops) {
      evaluated.add(shop.row().getP4kUuid());
      if (shop.row().isTerminalLinkManual()) {
        UUID manual = shop.row().getTerminalId();
        if (manual != null) {
          taken.add(manual);
        }
        continue;
      }
      Match match = match(shop, terminalsByPlace, places);
      UUID target = match.target();
      if (target != null) {
        taken.add(target);
      }
      matches.add(match);
    }
    Set<P4kRunResolvedCases.Found> found = new HashSet<>();
    Map<UUID, UUID> writes = new LinkedHashMap<>();
    matches.forEach(match -> report(match, taken, found, writes, context));
    if (context.isApply()) {
      if (!writes.isEmpty()) {
        shopRepository.autoLinkTerminals(
            UuidArrayLiteral.of(writes.keySet()),
            UuidArrayLiteral.of(new ArrayList<>(writes.values())));
      }
      runResolvedCases.settleWithin(KIND, REASONS, found, evaluated::contains, context.getNow());
    }
  }

  /**
   * Finds a shop's terminal candidates: by name at the nearest of its places that holds a match, a
   * tie decided by type or district, else by alias anywhere around it.
   *
   * @param shop the shop and its record
   * @param terminalsByPlace the UEX terminals by the starmap object of their place
   * @param places the places of the shops
   * @return the shop's candidates and the terminals around it
   */
  @NotNull
  private static Match match(
      @NotNull P4kShopReconciler.Reconciled shop,
      @NotNull Map<UUID, List<StarmapPlaceRow>> terminalsByPlace,
      @NotNull ShopPlaces places) {
    UUID place = shop.row().getStarmapObjectId();
    if (place == null) {
      return new Match(shop, List.of(), List.of());
    }
    List<String> containers = containers(shop.record());
    List<StarmapPlaceRow> candidates = List.of();
    List<StarmapPlaceRow> around = new ArrayList<>();
    for (UUID scope : places.scopes(place, containers)) {
      List<StarmapPlaceRow> terminals = terminalsByPlace.getOrDefault(scope, List.of());
      around.addAll(terminals);
      if (candidates.isEmpty()) {
        candidates = ShopTerminalNames.bestMatches(shop.row(), terminals);
      }
    }
    candidates = ShopTerminalTieBreaks.pick(shop.row(), containers, candidates);
    if (candidates.isEmpty()) {
      candidates = ShopTerminalAliases.matches(shop.row(), around);
    }
    return new Match(shop, candidates, List.copyOf(around));
  }

  /**
   * Reports one shop's link: a changed link, an unmatched shop and its case.
   *
   * @param match the shop's candidates
   * @param taken the terminals a shop of the run links to after the run
   * @param found the cases this run found, extended
   * @param writes the terminal links the APPLY writes, extended by the shop's
   * @param context the run
   */
  private static void report(
      @NotNull Match match,
      @NotNull Set<UUID> taken,
      @NotNull Set<P4kRunResolvedCases.Found> found,
      @NotNull Map<UUID, UUID> writes,
      @NotNull P4kImportContext context) {
    Shop row = match.shop().row();
    P4kShopDto record = match.shop().record();
    UUID target = match.target();
    UUID current = row.getTerminalId();
    if (!Objects.equals(current, target)) {
      context
          .getReport()
          .changed(
              KIND,
              record,
              "terminal_id",
              current == null ? null : current.toString(),
              target == null ? null : target.toString());
      if (context.isApply()) {
        writes.put(row.getId(), target);
      }
    }
    if (target != null) {
      return;
    }
    context.getReport().unmatched(KIND, record);
    P4kReviewReason reason = caseReason(match, taken);
    if (reason != null) {
      found.add(new P4kRunResolvedCases.Found(row.getP4kUuid(), reason));
      context.getReport().review(KIND, record, row.getP4kUuid(), reason, row.getId());
    }
  }

  /**
   * Returns the case an unlinked shop opens: none for a shop whose record names no place, for one
   * without an item list, whose link would show nowhere, and for one without a match whose
   * surroundings hold no terminal a shop does not link to already.
   *
   * @param match the shop's candidates
   * @param taken the terminals a shop of the run links to after the run
   * @return the reason of the case, or {@code null} for none
   */
  @Nullable
  private static P4kReviewReason caseReason(@NotNull Match match, @NotNull Set<UUID> taken) {
    P4kShopDto record = match.shop().record();
    List<String> items = record.itemGuids();
    if (record.starmapObjectGuid() == null || items == null || items.isEmpty()) {
      return null;
    }
    if (!match.candidates().isEmpty()) {
      return P4kReviewReason.SHOP_LINK_AMBIGUOUS;
    }
    boolean freeTerminal =
        match.around().stream().anyMatch(terminal -> !taken.contains(terminal.id()));
    return freeTerminal ? P4kReviewReason.SHOP_LINK_NOT_FOUND : null;
  }

  /**
   * Returns the containers a shop is placed in.
   *
   * @param record the shop's record
   * @return the container names from the system root down, empty when the record lists none
   */
  @NotNull
  @Unmodifiable
  private static List<String> containers(@NotNull P4kShopDto record) {
    List<String> containers = record.containers();
    return containers == null ? List.of() : containers.stream().filter(Objects::nonNull).toList();
  }

  /**
   * Groups the UEX terminals by the starmap object of their place: the terminal's own link, else
   * that of its nearest linked parent.
   *
   * @return the terminals by place; a terminal without a linked place is left out
   */
  @NotNull
  Map<UUID, List<StarmapPlaceRow>> terminalsByPlace() {
    StarmapParentLinks<UUID> links = new StarmapParentLinks<>();
    for (StarmapPlaceKind kind : StarmapPlaceKind.values()) {
      if (kind == StarmapPlaceKind.TERMINAL) {
        continue;
      }
      for (StarmapPlaceRow row : placeRows.rows(kind)) {
        if (row.starmapObjectId() != null) {
          links.put(row, row.starmapObjectId());
        }
      }
    }
    Map<StarmapPlaceKind, Map<String, UUID>> linked = links.byKind();
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

  /**
   * A shop's terminal candidates.
   *
   * @param shop the shop and its record
   * @param candidates the terminals that match it: one links, several are ambiguous
   * @param around the terminals at and around its place
   */
  private record Match(
      @NotNull P4kShopReconciler.Reconciled shop,
      @NotNull @Unmodifiable List<StarmapPlaceRow> candidates,
      @NotNull @Unmodifiable List<StarmapPlaceRow> around) {

    /**
     * Returns the terminal the shop links to.
     *
     * @return the one candidate's id, or {@code null} for none or several
     */
    @Nullable
    UUID target() {
      return candidates.size() == 1 ? candidates.getFirst().id() : null;
    }
  }
}

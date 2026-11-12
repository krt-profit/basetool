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

import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCatalogue;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kClosedSet;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kGuids;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kLoadoutDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kShipDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kShipPlayability;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Validates deferred ship-base corrections against a complete archive and current identities. */
@Component
@RequiredArgsConstructor
class P4kShipBaseRepairs {

  private final P4kReviewCaseRepository cases;
  private final P4kShipBaseData baseData;
  private final EntityManager entityManager;

  /**
   * Selects repairs whose source record, variant and dependent archive data can be refreshed.
   *
   * @param catalogue the incoming catalogue
   * @param loadouts the repeatable stream of its loadouts
   * @param ships the current stored ship types
   * @param aliases the current UEX class aliases and their ship rows
   * @param context the run after item and manufacturer reconciliation
   * @return the repairs eligible in this run
   */
  @NotNull
  List<Repair> eligible(
      @NotNull P4kCatalogue catalogue,
      @NotNull P4kLoadoutReconciler.LoadoutStream loadouts,
      @NotNull List<ShipType> ships,
      @NotNull Map<String, List<ShipType>> aliases,
      @NotNull P4kImportContext context) {
    if (!catalogue.head().isComplete(P4kKind.SHIP)
        || !catalogue.head().isComplete(P4kCatalogue.PAINTS)
        || !catalogue.head().isComplete(P4kCatalogue.LOADOUTS)
        || catalogue.paints().isEmpty()) {
      return List.of();
    }
    List<P4kReviewCase> pending = cases.findByShipRepairRowIdIsNotNull();
    if (pending.isEmpty()) {
      return List.of();
    }
    Map<UUID, ShipType> rows = new HashMap<>();
    ships.forEach(ship -> rows.put(Objects.requireNonNull(ship.getId()), ship));
    Map<UUID, List<P4kShipDto>> byGuid = new HashMap<>();
    for (P4kShipDto record : catalogue.ships()) {
      UUID guid = record.parsedGuid();
      if (guid != null) {
        byGuid.computeIfAbsent(guid, _ -> new ArrayList<>()).add(record);
      }
    }
    List<Repair> candidates = new ArrayList<>();
    for (P4kReviewCase selection : pending) {
      Repair repair = eligibleBase(selection, rows, byGuid, context);
      if (repair != null
          && ownsProjectedIdentity(repair, catalogue.ships(), ships, aliases)
          && paintsResolve(catalogue, repair.base(), context)) {
        candidates.add(repair);
      }
    }
    if (candidates.isEmpty()) {
      return List.of();
    }
    Set<UUID> targets =
        candidates.stream()
            .map(repair -> repair.selection().getGuid())
            .collect(Collectors.toUnmodifiableSet());
    Map<UUID, Boolean> loadoutProof = new HashMap<>();
    loadouts.forEach(loadout -> recordLoadoutProof(loadout, targets, loadoutProof));
    return candidates.stream()
        .filter(repair -> Boolean.TRUE.equals(loadoutProof.get(repair.selection().getGuid())))
        .toList();
  }

  /**
   * Verifies the exact base and variant pair against the current stored row and incoming archive.
   *
   * @param selection the pending choice
   * @param rows the stored ships by local id
   * @param byGuid the incoming ships by P4K GUID
   * @param context the run
   * @return the approved base input, or null while proof is missing
   */
  @Nullable
  private Repair eligibleBase(
      @NotNull P4kReviewCase selection,
      @NotNull Map<UUID, ShipType> rows,
      @NotNull Map<UUID, List<P4kShipDto>> byGuid,
      @NotNull P4kImportContext context) {
    UUID baseGuid = selection.getGuid();
    UUID oldGuid = selection.getShipRepairVariantGuid();
    UUID rowId = selection.getShipRepairRowId();
    ShipType row = rowId == null ? null : rows.get(rowId);
    if (row == null
        || oldGuid == null
        || !oldGuid.equals(row.getP4kUuid())
        || !baseGuid.equals(row.getExternalUuid())
        || !sameKey(selection.getShipRepairVariantClass(), row.getClassName())
        || row.isHidden()
        || row.isP4kRetired()
        || !P4kShipReviewActions.wikiLive(row)) {
      return null;
    }
    P4kShipDto base = soleRecord(byGuid.get(baseGuid));
    P4kShipDto variant = soleRecord(byGuid.get(oldGuid));
    if (base == null
        || variant == null
        || !sameKey(selection.getShipRepairBaseClass(), base.className())
        || !sameKey(selection.getShipRepairVariantClass(), variant.className())
        || !baseGuid.equals(P4kGuids.parse(variant.variantOf()))
        || StringUtils.hasText(base.variantOf())
        || !StringUtils.hasText(base.nameEn())
        || !StringUtils.hasText(base.className())
        || P4kClosedSet.parse(P4kShipPlayability.class, base.playability())
            != P4kShipPlayability.PLAYER
        || P4kShipBaseData.vehicleKind(base) == null
        || context.isRepeatedClassName(P4kKind.SHIP, base.className())
        || context.isRepeatedClassName(P4kKind.SHIP, variant.className())
        || rows.values().stream()
            .anyMatch(other ->
                !Objects.equals(rowId, other.getId())
                    && (baseGuid.equals(other.getP4kUuid())
                        || sameKey(base.className(), other.getClassName())
                        || sameKey(base.nameEn(), other.getName())))
        || !baseData.canRefreshBase(row, base, context)) {
      return null;
    }
    return new Repair(selection, row, base);
  }

  /**
   * Checks aliases and every other archive record that could claim the proposed display name.
   *
   * @param repair the proposed correction
   * @param records the incoming ship records
   * @param ships the stored ship rows
   * @param aliases the UEX class aliases by normalized name
   * @return whether any same-name variants can continue to resolve through the base
   */
  private static boolean ownsProjectedIdentity(
      @NotNull Repair repair,
      @NotNull List<P4kShipDto> records,
      @NotNull List<ShipType> ships,
      @NotNull Map<String, List<ShipType>> aliases) {
    P4kShipDto base = repair.base();
    UUID rowId = Objects.requireNonNull(repair.row().getId());
    UUID baseGuid = repair.selection().getGuid();
    if (hasOtherAlias(base.className(), rowId, aliases)) {
      return false;
    }
    for (P4kShipDto record : records) {
      if (!sameKey(base.nameEn(), record.nameEn())) {
        continue;
      }
      UUID guid = record.parsedGuid();
      if (baseGuid.equals(guid)) {
        continue;
      }
      if (guid == null
          || !baseGuid.equals(P4kGuids.parse(record.variantOf()))
          || hasOtherAlias(record.className(), rowId, aliases)
          || ships.stream()
              .anyMatch(other ->
                  !rowId.equals(other.getId())
                      && (guid.equals(other.getP4kUuid())
                          || guid.equals(other.getExternalUuid())
                          || sameKey(record.className(), other.getClassName())))) {
        return false;
      }
    }
    return true;
  }

  /**
   * Checks whether a curated UEX class alias resolves to a different local ship.
   *
   * @param className the incoming archive class
   * @param rowId the confirmed local ship id
   * @param aliases the UEX class aliases by normalized name
   * @return whether another ship claims this alias
   */
  private static boolean hasOtherAlias(
      @Nullable String className,
      @NotNull UUID rowId,
      @NotNull Map<String, List<ShipType>> aliases) {
    String key = P4kMatchKeys.fold(className);
    return key != null
        && aliases.getOrDefault(key, List.of()).stream()
            .anyMatch(other -> !rowId.equals(other.getId()));
  }

  /**
   * Ensures every paint that names the base can resolve the item it links.
   *
   * @param catalogue the incoming paints
   * @param base the verified base
   * @param context the run's item mappings
   * @return whether the base's paint references are available
   */
  private boolean paintsResolve(
      @NotNull P4kCatalogue catalogue,
      @NotNull P4kShipDto base,
      @NotNull P4kImportContext context) {
    UUID baseGuid = base.parsedGuid();
    P4kRowIds items = P4kRowIds.load(entityManager, P4kKind.ITEM, context);
    return catalogue.paints().stream()
        .filter(paint ->
            paint.shipGuids() != null
                && paint.shipGuids().stream()
                    .map(P4kGuids::parse)
                    .anyMatch(guid -> guid != null && guid.equals(baseGuid)))
        .allMatch(paint -> items.id(paint.itemGuid()) != null);
  }

  /**
   * Requires one parsed loadout with an explicit port tree and no extractor issues per repair.
   *
   * @param loadout one streamed record
   * @param targets the bases being considered
   * @param proof records validated bases and rejects duplicate or incomplete records
   */
  private static void recordLoadoutProof(
      @NotNull P4kLoadoutDto loadout,
      @NotNull Set<UUID> targets,
      @NotNull Map<UUID, Boolean> proof) {
    UUID guid = P4kGuids.parse(loadout.entityGuid());
    if (guid != null && targets.contains(guid)) {
      boolean valid =
          loadout.isShip()
              && loadout.ports() != null
              && (loadout.issues() == null || loadout.issues().isEmpty());
      proof.merge(guid, valid, (_, _) -> false);
    }
  }

  /**
   * Returns the single incoming record of a GUID.
   *
   * @param records the records of the GUID
   * @return the record, or null when absent or duplicated
   */
  @Nullable
  private static P4kShipDto soleRecord(@Nullable List<P4kShipDto> records) {
    return records != null && records.size() == 1 ? records.getFirst() : null;
  }

  /**
   * Compares nonblank catalogue keys using the import matcher folding.
   *
   * @param expected the expected key
   * @param actual the observed key
   * @return whether both identify the same key
   */
  private static boolean sameKey(@Nullable String expected, @Nullable String actual) {
    return StringUtils.hasText(expected)
        && Objects.equals(P4kMatchKeys.fold(expected), P4kMatchKeys.fold(actual));
  }

  /**
   * A repair with the authoritative source record and the existing local ship to retain.
   *
   * @param selection the pending review choice
   * @param row the selected stored row
   * @param base the current archive base record
   */
  record Repair(
      @NotNull P4kReviewCase selection, @NotNull ShipType row, @NotNull P4kShipDto base) {}
}

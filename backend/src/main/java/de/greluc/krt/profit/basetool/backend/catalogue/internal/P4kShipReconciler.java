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

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCatalogue;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kClosedSet;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kLocalisedText;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kShipDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kShipPlayability;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kVehicleKind;
import de.greluc.krt.profit.basetool.backend.kernel.StringNormalization;
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

/**
 * Reconciles the catalogue's {@code ships} with {@code ship_type}: match (REQ-P4K-007, the class
 * name step includes the UEX alias of REQ-DATA-026), write the base data the catalogue leads
 * (REQ-P4K-031), fill the descriptions where empty, stamp, create a playable unmatched ship behind
 * the run's checkbox (REQ-P4K-009), keep the variant groups (REQ-P4K-032) and cross-check the store
 * slugs.
 */
@Component
@RequiredArgsConstructor
public class P4kShipReconciler {

  /** The catalogue field holding a ship's playability. */
  private static final String PLAYABILITY = "playability";

  /** The catalogue field holding a ship's vehicle kind. */
  private static final String VEHICLE_KIND = "vehicleKind";

  private final ShipTypeRepository shipTypeRepository;
  private final P4kShipBaseData baseData;
  private final P4kShipVariants variants;
  private final P4kShipBaseRepairs baseRepairs;
  private final P4kShipStoreSlugCheck storeSlugCheck;
  private final P4kManufacturerReconciler manufacturerReconciler;
  private final P4kShipClassAliases classAliases;

  /**
   * Reconciles every ship, then the variant groups and the store slugs; the manufacturers must be
   * reconciled first.
   *
   * @param catalogue the catalogue
   * @param loadouts the repeatable loadout stream
   * @param context the run
   * @return the repairs that can finish after paint and loadout reconciliation
   */
  List<P4kShipBaseRepairs.Repair> reconcile(
      @NotNull P4kCatalogue catalogue,
      @NotNull P4kLoadoutReconciler.LoadoutStream loadouts,
      @NotNull P4kImportContext context) {
    List<ShipType> all = shipTypeRepository.findAll();
    Map<String, List<ShipType>> aliased = classAliases.byClassName(all);
    List<P4kShipBaseRepairs.Repair> repairs =
        baseRepairs.eligible(catalogue, loadouts, all, aliased, context);
    Set<UUID> repairRows =
        repairs.stream()
            .map(repair -> Objects.requireNonNull(repair.row().getId()))
            .collect(Collectors.toUnmodifiableSet());
    List<ShipType> stored =
        repairs.isEmpty()
            ? all
            : all.stream().filter(row -> !repairRows.contains(row.getId())).toList();
    P4kRowIndex<ShipType> rows =
        new P4kRowIndex<>(
            stored, ShipType::getExternalUuid, ShipType::getClassName, ShipType::getName);
    context.indexShipRows(rows);
    repairs.forEach(repair -> repair(repair, context));
    if (!repairRows.isEmpty()) {
      aliased.replaceAll((_, matches) ->
          matches.stream().filter(row -> !repairRows.contains(row.getId())).toList());
    }
    P4kRowMatcher<ShipType> matcher =
        new P4kRowMatcher<>(
            P4kKind.SHIP,
            rows::byP4kUuid,
            rows::byWikiUuid,
            ShipType::getExternalUuid,
            "external_uuid",
            className -> P4kShipClassAliases.classMatches(rows, aliased, className),
            rows::byName,
            row -> row.getSourceSystems() == GameItemSourceSystem.P4K);
    Set<UUID> bases =
        repairs.stream()
            .map(repair -> repair.selection().getGuid())
            .collect(Collectors.toUnmodifiableSet());
    for (P4kShipDto ship : catalogue.ships()) {
      UUID guid = ship.parsedGuid();
      if (guid != null && bases.contains(guid)) {
        continue;
      }
      String rawPlayability = ship.playability();
      P4kShipPlayability playability = P4kClosedSet.parse(P4kShipPlayability.class, rawPlayability);
      if (rawPlayability != null && playability == null) {
        context.getReport().unknownValue(P4kKind.SHIP, ship, PLAYABILITY, rawPlayability);
      }
      String rawVehicleKind = ship.vehicleKind();
      if (rawVehicleKind != null
          && P4kClosedSet.parse(P4kVehicleKind.class, rawVehicleKind) == null) {
        context.getReport().unknownValue(P4kKind.SHIP, ship, VEHICLE_KIND, rawVehicleKind);
      }
      P4kSeeding seeding = P4kSeeding.forShip(playability);
      matcher.reconcile(
          ship,
          ship.className(),
          ship.nameEn(),
          seeding == P4kSeeding.CREATE,
          context,
          rows,
          (row, _) -> enrich(row, ship, context),
          unmatchedGuid -> unmatched(ship, unmatchedGuid, seeding, context));
    }
    if (context.isApply()) {
      repairs.forEach(repair -> rows.add(repair.row()));
    }
    variants.write(catalogue.ships(), catalogue.head().isComplete(P4kKind.SHIP), context);
    storeSlugCheck.check(catalogue.shipStoreSlugs(), context);
    return repairs;
  }

  /**
   * Writes the base with the same fact writer used for an ordinary matched ship.
   *
   * @param repair the approved repair
   * @param context the run
   */
  private void repair(
      @NotNull P4kShipBaseRepairs.Repair repair, @NotNull P4kImportContext context) {
    ShipType row = repair.row();
    P4kShipDto base = repair.base();
    UUID guid = repair.selection().getGuid();
    final UUID oldGuid = Objects.requireNonNull(row.getP4kUuid());
    if (!context.claim(P4kKind.SHIP, Objects.requireNonNull(row.getId()), guid)) {
      throw new IllegalStateException("A ship repair collided with another source record.");
    }
    enrich(row, base, context);
    context.stamp(P4kKind.SHIP, base, row, guid);
    context
        .getReport()
        .changed(P4kKind.SHIP, base, "p4k_uuid", oldGuid.toString(), guid.toString());
    context.getReport().matched(P4kKind.SHIP, true);
  }

  /**
   * Writes the led base data, fills the empty descriptions and refreshes the name key.
   *
   * @param row the matched ship
   * @param ship the record
   * @param context the run
   * @return whether a field changes
   */
  private boolean enrich(
      @NotNull ShipType row, @NotNull P4kShipDto ship, @NotNull P4kImportContext context) {
    P4kRowWriter writer = new P4kRowWriter(context, P4kKind.SHIP, ship);
    baseData.lead(row, ship, writer, context);
    writer.lead(
        P4kReportColumns.NAME_DE,
        row.getNameDe(),
        P4kFields.germanName(ship.name()),
        row::setNameDe);
    writer.fillGameText(
        "description_en",
        row.getDescriptionEn(),
        P4kLocalisedText.enOf(ship.description()),
        row::setDescriptionEn);
    writer.fillGameText(
        "description_de",
        row.getDescriptionDe(),
        P4kLocalisedText.deOf(ship.description()),
        row::setDescriptionDe);
    writer.nameKey(row.getNameKey(), P4kLocalisedText.keyOf(ship.name()), row::setNameKey);
    return writer.isChanged();
  }

  /**
   * Creates an unmatched ship when the run's checkbox and the seeding rule allow it and its name is
   * free, else reports it unmatched.
   *
   * @param ship the record
   * @param guid the record's GUID, or {@code null}
   * @param seeding the seeding rule's decision
   * @param context the run
   */
  private void unmatched(
      @NotNull P4kShipDto ship,
      @Nullable UUID guid,
      @NotNull P4kSeeding seeding,
      @NotNull P4kImportContext context) {
    String name = StringNormalization.blankToNull(ship.nameEn());
    if (guid == null
        || !context.isCreateItemsAndShips()
        || seeding != P4kSeeding.CREATE
        || name == null
        || context.isRepeatedName(P4kKind.SHIP, name)
        || !context.shipRows().byName(name.strip()).isEmpty()) {
      seeding.notCreated(P4kKind.SHIP, ship, guid, context);
      return;
    }
    manufacturerReconciler.seed(ship.manufacturerGuid(), context);
    if (context.isApply()) {
      ShipType row = new ShipType();
      row.setName(name.strip());
      row.setNameDe(P4kFields.germanName(ship.name()));
      row.setClassName(StringNormalization.blankToNull(ship.className()));
      row.setManufacturer(context.manufacturer(ship.manufacturerGuid()));
      row.setDescriptionEn(
          StringNormalization.blankToNull(P4kLocalisedText.enOf(ship.description())));
      row.setDescriptionDe(
          StringNormalization.blankToNull(P4kLocalisedText.deOf(ship.description())));
      baseData.seed(row, ship);
      row.setSourceSystems(GameItemSourceSystem.P4K);
      row.setNameKey(P4kFields.storedNameKey(P4kLocalisedText.keyOf(ship.name())));
      context.stamp(P4kKind.SHIP, ship, row, guid);
      context.shipRows().add(shipTypeRepository.save(row));
    }
    context.getReport().added(P4kKind.SHIP, ship);
  }
}

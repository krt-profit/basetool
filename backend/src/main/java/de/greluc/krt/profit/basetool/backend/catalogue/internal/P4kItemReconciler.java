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

import de.greluc.krt.profit.basetool.backend.catalogue.api.CatalogueMetricNames;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipComponentClass;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kClosedSet;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kItemDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kItemPlayability;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kLocalisedText;
import de.greluc.krt.profit.basetool.backend.kernel.StringNormalization;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Reconciles the catalogue's {@code items} with {@code game_item}: match (REQ-P4K-007), lead the
 * attributes the P4K owns and fill the rest where empty (REQ-P4K-017…019), stamp, and create a
 * playable unmatched item behind the run's checkbox (REQ-P4K-009).
 */
@Component
public class P4kItemReconciler {

  /** The catalogue field holding an item's playability. */
  private static final String PLAYABILITY = "playability";

  /** The catalogue field holding an item's raw grade. */
  private static final String GRADE = "grade";

  /** The catalogue field holding an item's class. */
  private static final String ITEM_CLASS = "itemClass";

  /** The source name the class resolver logs. */
  private static final String SOURCE = "P4K";

  /** Finds and saves the items. */
  private final GameItemRepository gameItemRepository;

  /** Resolves, counts and logs the catalogue's class values on an APPLY. */
  private final ItemClassResolver itemClassResolver;

  /** {@code basetool_catalogue_item_grade_unknown_total}. */
  private final Counter unknownGrades;

  /**
   * Registers the unknown-grade counter, so it exists before the first increment.
   *
   * @param gameItemRepository the item repository
   * @param itemClassResolver resolves and counts the catalogue's class values
   * @param meterRegistry the registry the counter lives in
   */
  public P4kItemReconciler(
      @NotNull GameItemRepository gameItemRepository,
      @NotNull ItemClassResolver itemClassResolver,
      @NotNull MeterRegistry meterRegistry) {
    this.gameItemRepository = gameItemRepository;
    this.itemClassResolver = itemClassResolver;
    this.unknownGrades = meterRegistry.counter(CatalogueMetricNames.CATALOGUE_ITEM_GRADE_UNKNOWN);
  }

  /**
   * Reconciles every item; the manufacturers must be reconciled first.
   *
   * @param items the catalogue's items
   * @param context the run
   */
  public void reconcile(@NotNull List<P4kItemDto> items, @NotNull P4kImportContext context) {
    P4kRowMatcher<GameItem> matcher =
        new P4kRowMatcher<>(
            P4kKind.ITEM,
            gameItemRepository::findByP4kUuid,
            gameItemRepository::findByExternalUuid,
            GameItem::getExternalUuid,
            "external_uuid",
            gameItemRepository::findByClassNameIgnoreCase,
            gameItemRepository::findByNameIgnoreCase,
            row -> row.getSourceSystems() == GameItemSourceSystem.P4K);
    for (P4kItemDto item : items) {
      String rawPlayability = item.playability();
      P4kItemPlayability playability = P4kClosedSet.parse(P4kItemPlayability.class, rawPlayability);
      if (rawPlayability != null && playability == null) {
        context.getReport().unknownValue(P4kKind.ITEM, item, PLAYABILITY, rawPlayability);
      }
      P4kSeeding seeding = P4kSeeding.forItem(playability);
      P4kRowMatcher.Match<GameItem> match =
          matcher.match(
              item, item.className(), item.nameEn(), seeding == P4kSeeding.CREATE, context);
      if (match instanceof P4kRowMatcher.Matched<GameItem>(GameItem row, UUID guid)) {
        boolean enriched = enrich(row, item, values(item, context), context);
        context.stamp(P4kKind.ITEM, item, row, guid);
        context.getReport().matched(P4kKind.ITEM, enriched);
      } else if (match instanceof P4kRowMatcher.Unmatched<GameItem>(UUID guid)) {
        unmatched(item, guid, seeding, context);
      }
    }
  }

  /**
   * Reads the attributes the P4K leads from a record; an unknown grade is reported and, on an
   * APPLY, counted.
   *
   * @param item the record
   * @param context the run
   * @return the P4K's values
   */
  @NotNull
  private P4kItemValues values(@NotNull P4kItemDto item, @NotNull P4kImportContext context) {
    Integer grade = item.grade();
    if (P4kGradeMapping.isUnknown(grade)) {
      context.getReport().unknownValue(P4kKind.ITEM, item, GRADE, String.valueOf(grade));
      if (context.isApply()) {
        unknownGrades.increment();
      }
    }
    return new P4kItemValues(
        P4kItemValues.usableName(item.nameEn()),
        context.manufacturer(item.manufacturerGuid()),
        StringNormalization.blankToNull(item.type()),
        StringNormalization.blankToNull(item.subType()),
        item.size(),
        grade,
        itemClass(item, context),
        item.mass());
  }

  /**
   * Resolves the record's class; an unknown value reads as {@code null} and is reported, and on an
   * APPLY counted and logged.
   *
   * @param item the record
   * @param context the run
   * @return the class, or {@code null}
   */
  @Nullable
  private ShipComponentClass itemClass(
      @NotNull P4kItemDto item, @NotNull P4kImportContext context) {
    String raw = StringNormalization.blankToNull(item.itemClass());
    if (raw == null) {
      return null;
    }
    ShipComponentClass resolved =
        context.isApply()
            ? itemClassResolver.resolve(raw, SOURCE)
            : ShipComponentClass.fromWikiClass(raw);
    if (resolved == null) {
      context.getReport().unknownValue(P4kKind.ITEM, item, ITEM_CLASS, raw);
    }
    return resolved;
  }

  /**
   * Leads the item's P4K attributes, keeps the P4K's own values, fills the other empty fields and
   * refreshes the name key.
   *
   * @param row the matched item
   * @param item the record
   * @param values the P4K's values of the led attributes
   * @param context the run
   * @return whether a field changes
   */
  private boolean enrich(
      @NotNull GameItem row,
      @NotNull P4kItemDto item,
      @NotNull P4kItemValues values,
      @NotNull P4kImportContext context) {
    P4kRowWriter writer = new P4kRowWriter(context, P4kKind.ITEM, item);
    writer.fill("class_name", row.getClassName(), item.className(), row::setClassName);
    writer.lead("name", row.getName(), values.name(), row::setName);
    writer.lead(
        "manufacturer_id",
        row.getManufacturer(),
        values.manufacturer(),
        row::setManufacturer,
        Manufacturer::getName);
    writer.lead("wiki_type", row.getWikiType(), values.type(), row::setWikiType);
    writer.lead("wiki_sub_type", row.getWikiSubType(), values.subType(), row::setWikiSubType);
    writer.lead("size_class", row.getSizeClass(), values.size(), row::setSizeClass);
    writer.lead("grade", row.getGrade(), values.gradeLetter(), row::setGrade);
    writer.mirror("p4k_grade_raw", row.getP4kGradeRaw(), values.gradeRaw(), row::setP4kGradeRaw);
    writer.lead("item_class", row.getItemClass(), values.itemClass(), row::setItemClass);
    writer.lead("mass", row.getMass(), values.mass(), row::setMass);
    writer.fillGameText(
        "description_en",
        row.getDescriptionEn(),
        P4kLocalisedText.enOf(item.description()),
        row::setDescriptionEn);
    writer.fillGameText(
        "description_de",
        row.getDescriptionDe(),
        P4kLocalisedText.deOf(item.description()),
        row::setDescriptionDe);
    writer.nameKey(row.getNameKey(), P4kLocalisedText.keyOf(item.name()), row::setNameKey);
    if (context.isApply()) {
      values.storeOn(row);
    }
    return writer.isChanged();
  }

  /**
   * Creates an unmatched item when the run's checkbox and the seeding rule allow it, else reports
   * it unmatched.
   *
   * @param item the record
   * @param guid the record's GUID, or {@code null}
   * @param seeding the seeding rule's decision
   * @param context the run
   */
  private void unmatched(
      @NotNull P4kItemDto item,
      @Nullable UUID guid,
      @NotNull P4kSeeding seeding,
      @NotNull P4kImportContext context) {
    if (guid == null
        || !context.isCreateItemsAndShips()
        || seeding != P4kSeeding.CREATE
        || P4kItemValues.usableName(item.nameEn()) == null) {
      seeding.notCreated(P4kKind.ITEM, item, guid, context);
      return;
    }
    P4kItemValues values = values(item, context);
    if (context.isApply()) {
      GameItem row = new GameItem();
      row.setClassName(StringNormalization.blankToNull(item.className()));
      values.storeOn(row);
      values.leadOnto(row);
      row.setDescriptionEn(
          StringNormalization.blankToNull(P4kLocalisedText.enOf(item.description())));
      row.setDescriptionDe(
          StringNormalization.blankToNull(P4kLocalisedText.deOf(item.description())));
      row.setKind(GameItemKind.GENERIC);
      row.setSourceSystems(GameItemSourceSystem.P4K);
      row.setNameKey(P4kFields.storedNameKey(P4kLocalisedText.keyOf(item.name())));
      context.stamp(P4kKind.ITEM, item, row, guid);
      gameItemRepository.save(row);
    }
    context.getReport().added(P4kKind.ITEM, item);
  }
}

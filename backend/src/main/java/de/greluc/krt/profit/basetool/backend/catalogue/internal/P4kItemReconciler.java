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

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kClosedSet;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kItemDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kItemPlayability;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kLocalisedText;
import de.greluc.krt.profit.basetool.backend.kernel.StringNormalization;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Reconciles the catalogue's {@code items} with {@code game_item}: match (REQ-P4K-007), enrich
 * fill-if-null, stamp, and create a playable unmatched item behind the run's checkbox
 * (REQ-P4K-009).
 */
@Component
@RequiredArgsConstructor
public class P4kItemReconciler {

  /** The catalogue field holding an item's playability. */
  private static final String PLAYABILITY = "playability";

  private final GameItemRepository gameItemRepository;

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
        boolean enriched = enrich(row, item, context);
        context.stamp(row, guid);
        context.getReport().matched(P4kKind.ITEM, enriched);
      } else if (match instanceof P4kRowMatcher.Unmatched<GameItem>(UUID guid)) {
        unmatched(item, guid, seeding, context);
      }
    }
  }

  /**
   * Fills the item's empty fields from the catalogue and refreshes its name key.
   *
   * @param row the matched item
   * @param item the record
   * @param context the run
   * @return whether a field changes
   */
  private boolean enrich(
      @NotNull GameItem row, @NotNull P4kItemDto item, @NotNull P4kImportContext context) {
    boolean apply = context.isApply();
    boolean enriched =
        P4kFields.fillIfNull(row.getClassName(), item.className(), row::setClassName, apply);
    enriched |=
        P4kFields.fillIfNull(
            row.getManufacturer(),
            context.manufacturer(item.manufacturerGuid()),
            row::setManufacturer,
            apply);
    enriched |= P4kFields.fillIfNull(row.getMass(), item.mass(), row::setMass, apply);
    enriched |=
        P4kFields.fillIfNull(
            row.getDescriptionEn(),
            P4kLocalisedText.enOf(item.description()),
            row::setDescriptionEn,
            apply);
    enriched |=
        P4kFields.fillIfNull(
            row.getDescriptionDe(),
            P4kLocalisedText.deOf(item.description()),
            row::setDescriptionDe,
            apply);
    enriched |=
        P4kFields.refreshNameKey(
            row.getNameKey(), P4kLocalisedText.keyOf(item.name()), row::setNameKey, apply);
    return enriched;
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
    String name = StringNormalization.blankToNull(item.nameEn());
    if (guid == null
        || !context.isCreateItemsAndShips()
        || seeding != P4kSeeding.CREATE
        || name == null) {
      seeding.notCreated(P4kKind.ITEM, item, guid, context);
      return;
    }
    if (context.isApply()) {
      GameItem row = new GameItem();
      row.setName(name.strip());
      row.setClassName(StringNormalization.blankToNull(item.className()));
      row.setManufacturer(context.manufacturer(item.manufacturerGuid()));
      Double mass = item.mass();
      if (mass != null) {
        row.setMass(mass);
      }
      row.setDescriptionEn(
          StringNormalization.blankToNull(P4kLocalisedText.enOf(item.description())));
      row.setDescriptionDe(
          StringNormalization.blankToNull(P4kLocalisedText.deOf(item.description())));
      row.setKind(GameItemKind.GENERIC);
      row.setSourceSystems(GameItemSourceSystem.P4K);
      row.setNameKey(P4kFields.storedNameKey(P4kLocalisedText.keyOf(item.name())));
      context.stamp(row, guid);
      gameItemRepository.save(row);
    }
    context.getReport().added(P4kKind.ITEM, item);
  }
}

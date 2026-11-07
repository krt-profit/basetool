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
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiItemDto;
import de.greluc.krt.profit.basetool.backend.kernel.StringNormalization;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Applies Wiki item metadata without changing canonical identity or UEX-owned attributes; the
 * attributes the P4K leads are written only where the P4K value is {@code null}, and a differing
 * Wiki value is reported ({@link P4kLedColumns#fillGap}, REQ-P4K-017…019); the canonical name
 * follows {@link GameItemNamePrecedence}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScWikiItemFieldWriter {

  /** The source name the class resolver logs. */
  private static final String SOURCE = "SC Wiki";

  /** Resolves, counts and logs the Wiki's class values. */
  private final ItemClassResolver itemClassResolver;

  /** Receives the disagreements with the P4K. */
  private final SyncReportService syncReportService;

  /**
   * Writes the Wiki-owned attributes, source presence and, on a row without a P4K name, the
   * canonical name; omitted size, grade and class stay intact, and an attribute the P4K holds a
   * value for keeps it.
   *
   * @param item managed catalogue item
   * @param dto identity-verified source payload
   * @param runId the Wiki run a disagreement is reported under
   * @param now source-sync timestamp
   */
  public void apply(
      @NotNull GameItem item,
      @NotNull ScWikiItemDto dto,
      @NotNull UUID runId,
      @NotNull Instant now) {
    item.setScwikiName(dto.name());
    GameItemNamePrecedence.applyWikiName(item, dto.name());
    item.setWikiMetadata(dto.metadata());
    item.setScwikiSlug(dto.slug());
    item.setClassName(dto.className());
    item.setClassification(dto.classification());
    item.setClassificationLabel(dto.classificationLabel());
    P4kLedColumns led = new P4kLedColumns(item);
    led.fillGap("wiki_type", item.getP4kType(), dto.type(), item::setWikiType);
    item.setWikiTypeLabel(dto.typeLabel());
    led.fillGap("wiki_sub_type", item.getP4kSubType(), dto.subType(), item::setWikiSubType);
    item.setWikiSubTypeLabel(dto.subTypeLabel());
    if (StringUtils.hasText(dto.size())) {
      led.fillGap("size_class", item.getP4kSize(), parseSize(dto.size()), item::setSizeClass);
    }
    if (StringUtils.hasText(dto.grade())) {
      String p4kGrade = P4kGradeMapping.letter(item.getP4kType(), item.getP4kGradeRaw());
      led.fillGap("grade", p4kGrade, dto.grade(), item::setGrade);
    }
    if (StringUtils.hasText(dto.itemClass())) {
      led.fillGap(
          "item_class",
          item.getP4kItemClass(),
          itemClassResolver.resolve(dto.itemClass(), SOURCE),
          item::setItemClass);
    }
    item.setRarity(dto.rarity());
    led.fillGap("mass", item.getP4kMass(), dto.mass(), item::setMass);
    for (String disagreement : led.disagreements()) {
      syncReportService.logScwikiEvent(
          runId,
          SyncEventType.P4K_DISAGREEMENT,
          "game_item",
          item.getExternalUuid(),
          item.getName(),
          disagreement);
    }
    if (dto.dimension() != null) {
      item.setDimensionX(dto.dimension().width());
      item.setDimensionY(dto.dimension().height());
      item.setDimensionZ(dto.dimension().length());
    }
    if (dto.description() != null) {
      writeIfPresent(dto.description().get("en_EN"), item::setDescriptionEn);
      writeIfPresent(dto.description().get("de_DE"), item::setDescriptionDe);
    }
    item.setIsBaseVariant(dto.isBaseVariant());
    item.setIsCraftable(dto.isCraftable());
    item.setScwikiGameVersionSeen(dto.version());
    item.setScwikiSyncedAt(now);
    item.setScwikiDeletedAt(null);
  }

  /**
   * Writes a Wiki text that is present; a missing or blank locale keeps the stored text, which the
   * P4K import may have filled.
   *
   * @param text the Wiki's text of one locale
   * @param setter writes the column
   */
  private static void writeIfPresent(@Nullable String text, @NotNull Consumer<String> setter) {
    String present = StringNormalization.blankToNull(text);
    if (present != null) {
      setter.accept(present);
    }
  }

  private static @Nullable Integer parseSize(String size) {
    try {
      return Integer.valueOf(size.trim());
    } catch (NumberFormatException _) {
      log.debug("Non-numeric Wiki item size '{}' — leaving size_class null", size);
      return null;
    }
  }

  /**
   * Rejects new rows whose source identity is blank, markup or an internal vehicle placeholder.
   *
   * @param dto Wiki source identity
   * @return whether this payload may not seed a new catalogue row
   */
  static boolean shouldSkipNewItem(ScWikiItemDto dto) {
    return !GameItemNamePrecedence.isUsableWikiName(dto.name())
        || "NOITEM_Vehicle".equalsIgnoreCase(dto.type());
  }
}

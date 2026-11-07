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
import java.time.Instant;
import java.util.Objects;
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
 * Wiki value is reported (REQ-P4K-017…019).
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
   * Writes the Wiki-owned attributes and source presence; omitted size, grade and class stay
   * intact, and an attribute the P4K holds a value for keeps it.
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
    item.setWikiMetadata(dto.metadata());
    item.setScwikiSlug(dto.slug());
    item.setClassName(dto.className());
    item.setClassification(dto.classification());
    item.setClassificationLabel(dto.classificationLabel());
    fillGap(item, runId, "wiki_type", item.getP4kType(), dto.type(), item::setWikiType);
    item.setWikiTypeLabel(dto.typeLabel());
    fillGap(
        item, runId, "wiki_sub_type", item.getP4kSubType(), dto.subType(), item::setWikiSubType);
    item.setWikiSubTypeLabel(dto.subTypeLabel());
    if (StringUtils.hasText(dto.size())) {
      fillGap(
          item, runId, "size_class", item.getP4kSize(), parseSize(dto.size()), item::setSizeClass);
    }
    if (StringUtils.hasText(dto.grade())) {
      String p4kGrade = P4kGradeMapping.letter(item.getP4kType(), item.getP4kGradeRaw());
      fillGap(item, runId, "grade", p4kGrade, dto.grade(), item::setGrade);
    }
    if (StringUtils.hasText(dto.itemClass())) {
      fillGap(
          item,
          runId,
          "item_class",
          item.getP4kItemClass(),
          itemClassResolver.resolve(dto.itemClass(), SOURCE),
          item::setItemClass);
    }
    item.setRarity(dto.rarity());
    fillGap(item, runId, "mass", item.getP4kMass(), dto.mass(), item::setMass);
    if (dto.dimension() != null) {
      item.setDimensionX(dto.dimension().width());
      item.setDimensionY(dto.dimension().height());
      item.setDimensionZ(dto.dimension().length());
    }
    if (dto.description() != null) {
      item.setDescriptionEn(dto.description().get("en_EN"));
      item.setDescriptionDe(dto.description().get("de_DE"));
    }
    item.setIsBaseVariant(dto.isBaseVariant());
    item.setIsCraftable(dto.isCraftable());
    item.setScwikiGameVersionSeen(dto.version());
    item.setScwikiSyncedAt(now);
    item.setScwikiDeletedAt(null);
  }

  /**
   * Writes the Wiki's value where the P4K holds none; otherwise keeps the column and reports a Wiki
   * value that differs from the P4K's.
   *
   * @param item the item
   * @param runId the Wiki run
   * @param column the column written
   * @param p4kValue the P4K's value of the column
   * @param wikiValue the Wiki's value
   * @param setter writes the column
   * @param <V> the value type
   */
  private <V> void fillGap(
      @NotNull GameItem item,
      @NotNull UUID runId,
      @NotNull String column,
      @Nullable V p4kValue,
      @Nullable V wikiValue,
      @NotNull Consumer<V> setter) {
    if (p4kValue == null) {
      setter.accept(wikiValue);
      return;
    }
    if (wikiValue == null || same(p4kValue, wikiValue)) {
      return;
    }
    syncReportService.logScwikiEvent(
        runId,
        SyncEventType.P4K_DISAGREEMENT,
        "game_item",
        item.getExternalUuid(),
        item.getName(),
        "column=" + column + ";p4k=" + p4kValue + ";wiki=" + wikiValue);
  }

  private static boolean same(@NotNull Object p4kValue, @NotNull Object wikiValue) {
    if (p4kValue instanceof String p4k && wikiValue instanceof String wiki) {
      return p4k.strip().equalsIgnoreCase(wiki.strip());
    }
    return Objects.equals(p4kValue, wikiValue);
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
    if (!StringUtils.hasText(dto.name())) {
      return true;
    }
    String name = dto.name().trim();
    return name.contains("<")
        || name.contains(">")
        || "NOITEM_Vehicle".equalsIgnoreCase(dto.type());
  }
}

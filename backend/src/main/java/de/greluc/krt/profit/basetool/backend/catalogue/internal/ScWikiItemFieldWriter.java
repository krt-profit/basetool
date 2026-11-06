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
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipComponentClass;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiItemDto;
import de.greluc.krt.profit.basetool.logging.LogSafe;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Applies Wiki item metadata without changing canonical identity or UEX-owned attributes. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScWikiItemFieldWriter {
  private static final int MAX_CLASS_LOG_LENGTH = 64;
  private static final int MAX_LOGGED_UNKNOWN_CLASSES = 32;
  private final Set<String> unknownClassesLogged = ConcurrentHashMap.newKeySet();
  private final MeterRegistry meterRegistry;

  /**
   * Writes the Wiki-owned attributes and source presence; omitted size, grade and class stay
   * intact.
   *
   * @param item managed catalogue item
   * @param dto identity-verified source payload
   * @param now source-sync timestamp
   */
  public void apply(@NotNull GameItem item, @NotNull ScWikiItemDto dto, @NotNull Instant now) {
    item.setScwikiName(dto.name());
    item.setWikiMetadata(dto.metadata());
    item.setScwikiSlug(dto.slug());
    item.setClassName(dto.className());
    item.setClassification(dto.classification());
    item.setClassificationLabel(dto.classificationLabel());
    item.setWikiType(dto.type());
    item.setWikiTypeLabel(dto.typeLabel());
    item.setWikiSubType(dto.subType());
    item.setWikiSubTypeLabel(dto.subTypeLabel());
    if (StringUtils.hasText(dto.size())) {
      item.setSizeClass(parseSize(dto.size()));
    }
    if (StringUtils.hasText(dto.grade())) {
      item.setGrade(dto.grade());
    }
    if (StringUtils.hasText(dto.itemClass())) {
      item.setItemClass(resolveClass(dto.itemClass()));
    }
    item.setRarity(dto.rarity());
    item.setMass(dto.mass());
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

  private @Nullable ShipComponentClass resolveClass(@NotNull String wikiClass) {
    ShipComponentClass resolved = ShipComponentClass.fromWikiClass(wikiClass);
    if (resolved == null) {
      meterRegistry.counter(CatalogueMetricNames.CATALOGUE_ITEM_CLASS_UNKNOWN).increment();
      String value = LogSafe.text(wikiClass.strip(), MAX_CLASS_LOG_LENGTH);
      if (unknownClassesLogged.size() < MAX_LOGGED_UNKNOWN_CLASSES
          && unknownClassesLogged.add(value.toLowerCase(Locale.ROOT))) {
        log.warn(
            "Unknown SC Wiki item class '{}' — storing no class; add it to ShipComponentClass.",
            value);
      }
    }
    return resolved;
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

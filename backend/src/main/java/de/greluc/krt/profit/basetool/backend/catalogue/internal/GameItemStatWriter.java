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

import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintStatProperty;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemStat;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemStatRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemStatSource;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiItemDto;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores the blueprint stat base values of a crafted item in {@code game_item_stat} (REQ-P4K-030,
 * ADR-0342): the P4K value wins, the SC Wiki writes only where no P4K value exists, and a Wiki
 * value more than {@value #TOLERANCE} relative off the P4K value is reported as {@link
 * SyncEventType#P4K_DISAGREEMENT}.
 */
@Component
@RequiredArgsConstructor
public class GameItemStatWriter {

  /** The relative difference up to which a Wiki value agrees with the P4K value. */
  static final double TOLERANCE = 0.01;

  /** The {@code column} a stat disagreement names, followed by the property. */
  private static final String COLUMN_PREFIX = "game_item_stat.";

  private final GameItemStatRepository gameItemStatRepository;

  /** Receives the Wiki values that disagree with the P4K. */
  private final SyncReportService syncReportService;

  /**
   * Writes the SC Wiki payload's base values where no P4K value exists, inside the caller's
   * transaction.
   *
   * <p>A payload of an item that is not craftable, or one without any value, leaves the stored rows
   * untouched, so a list row without stat blocks never wipes what a detail fetch stored. A P4K row
   * keeps its value; a Wiki value that differs from it by more than {@value #TOLERANCE} relative is
   * reported under the run.
   *
   * @param item the persisted game item the payload belongs to
   * @param dto the SC Wiki item payload
   * @param runId the Wiki run a disagreement is reported under
   * @return {@code true} when the payload carried values
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean write(@NotNull GameItem item, @NotNull ScWikiItemDto dto, @NotNull UUID runId) {
    if (!Boolean.TRUE.equals(dto.isCraftable())) {
      return false;
    }
    Map<BlueprintStatProperty, Double> values = ScWikiItemStatExtractor.extract(dto);
    if (values.isEmpty()) {
      return false;
    }
    Set<BlueprintStatProperty> handled = EnumSet.noneOf(BlueprintStatProperty.class);
    for (GameItemStat stat : stored(item)) {
      BlueprintStatProperty property = stat.getProperty();
      Double value = values.get(property);
      handled.add(property);
      if (stat.getSource() == GameItemStatSource.P4K) {
        reportDisagreement(item, stat, value, runId);
      } else if (value == null) {
        gameItemStatRepository.delete(stat);
      } else if (!Objects.equals(stat.getBaseValue(), value)) {
        stat.setBaseValue(value);
      }
    }
    insertMissing(item, values, handled, GameItemStatSource.SCWIKI);
    return true;
  }

  /**
   * Writes the P4K catalogue's base values of an item, inside the caller's transaction: a value
   * replaces the stored one of either source, and, when {@code removeMissing} holds, a stored P4K
   * value the catalogue no longer carries is removed; an SC Wiki value the catalogue does not cover
   * stays.
   *
   * @param item the persisted game item
   * @param values the catalogue's base values of the item
   * @param stored the item's stored stat rows, read by the caller
   * @param removeMissing whether the catalogue's items are complete, so a missing value is a fact
   * @return {@code true} when a row changed
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean writeP4k(
      @NotNull GameItem item,
      @NotNull Map<BlueprintStatProperty, Double> values,
      @NotNull List<GameItemStat> stored,
      boolean removeMissing) {
    boolean changed = false;
    Set<BlueprintStatProperty> handled = EnumSet.noneOf(BlueprintStatProperty.class);
    for (GameItemStat stat : stored) {
      BlueprintStatProperty property = stat.getProperty();
      Double value = values.get(property);
      handled.add(property);
      if (value == null) {
        if (removeMissing && stat.getSource() == GameItemStatSource.P4K) {
          gameItemStatRepository.delete(stat);
          changed = true;
        }
      } else if (stat.getSource() != GameItemStatSource.P4K
          || !Objects.equals(stat.getBaseValue(), value)) {
        stat.setBaseValue(value);
        stat.setSource(GameItemStatSource.P4K);
        changed = true;
      }
    }
    return insertMissing(item, values, handled, GameItemStatSource.P4K) || changed;
  }

  @NotNull
  private List<GameItemStat> stored(@NotNull GameItem item) {
    return item.getId() == null ? List.of() : gameItemStatRepository.findByGameItemId(item.getId());
  }

  private boolean insertMissing(
      @NotNull GameItem item,
      @NotNull Map<BlueprintStatProperty, Double> values,
      @NotNull Set<BlueprintStatProperty> handled,
      @NotNull GameItemStatSource source) {
    boolean inserted = false;
    for (Map.Entry<BlueprintStatProperty, Double> entry : values.entrySet()) {
      if (!handled.contains(entry.getKey())) {
        GameItemStat stat = new GameItemStat();
        stat.setGameItem(item);
        stat.setProperty(entry.getKey());
        stat.setBaseValue(entry.getValue());
        stat.setSource(source);
        gameItemStatRepository.save(stat);
        inserted = true;
      }
    }
    return inserted;
  }

  private void reportDisagreement(
      @NotNull GameItem item,
      @NotNull GameItemStat stat,
      @Nullable Double wikiValue,
      @NotNull UUID runId) {
    double p4kValue = stat.getBaseValue();
    if (wikiValue == null || Math.abs(wikiValue - p4kValue) <= TOLERANCE * Math.abs(p4kValue)) {
      return;
    }
    syncReportService.logScwikiEvent(
        runId,
        SyncEventType.P4K_DISAGREEMENT,
        "game_item",
        item.getExternalUuid(),
        item.getName(),
        P4kFields.disagreement(
            COLUMN_PREFIX + stat.getProperty().name(),
            String.valueOf(p4kValue),
            String.valueOf(wikiValue)));
  }
}

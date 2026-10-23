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
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiItemDto;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores the blueprint stat base values of a craftable SC Wiki item in {@code game_item_stat}
 * (REQ-DATA-027).
 */
@Component
@RequiredArgsConstructor
public class GameItemStatWriter {

  private final GameItemStatRepository gameItemStatRepository;

  /**
   * Replaces the item's stored base values with those of the payload, inside the caller's
   * transaction.
   *
   * <p>A payload of an item that is not craftable, or one without any value, leaves the stored rows
   * untouched, so a list row without stat blocks never wipes what a detail fetch stored.
   *
   * @param item the persisted game item the payload belongs to
   * @param dto the SC Wiki item payload
   * @return {@code true} when the payload carried values and they were written
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean write(@NotNull GameItem item, @NotNull ScWikiItemDto dto) {
    if (!Boolean.TRUE.equals(dto.isCraftable())) {
      return false;
    }
    Map<BlueprintStatProperty, Double> values = ScWikiItemStatExtractor.extract(dto);
    if (values.isEmpty()) {
      return false;
    }
    List<GameItemStat> existing =
        item.getId() == null ? List.of() : gameItemStatRepository.findByGameItemId(item.getId());
    Map<BlueprintStatProperty, GameItemStat> stored = new EnumMap<>(BlueprintStatProperty.class);
    for (GameItemStat stat : existing) {
      Double value = values.get(stat.getProperty());
      if (value == null) {
        gameItemStatRepository.delete(stat);
      } else {
        if (!Objects.equals(stat.getBaseValue(), value)) {
          stat.setBaseValue(value);
        }
        stored.put(stat.getProperty(), stat);
      }
    }
    values.forEach(
        (property, value) -> {
          if (!stored.containsKey(property)) {
            GameItemStat stat = new GameItemStat();
            stat.setGameItem(item);
            stat.setProperty(property);
            stat.setBaseValue(value);
            gameItemStatRepository.save(stat);
          }
        });
    return true;
  }
}

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintStatProperty;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemStat;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemStatRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiItemDto;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

/** Unit tests for {@link GameItemStatWriter}. */
@ExtendWith(MockitoExtension.class)
class GameItemStatWriterTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Mock private GameItemStatRepository gameItemStatRepository;

  private GameItemStatWriter writer;
  private GameItem item;

  @BeforeEach
  void setUp() {
    writer = new GameItemStatWriter(gameItemStatRepository);
    item = new GameItem();
    item.setId(UUID.randomUUID());
  }

  private static ScWikiItemDto dto(String json) {
    return MAPPER.readValue(json, ScWikiItemDto.class);
  }

  private GameItemStat stored(BlueprintStatProperty property, double value) {
    GameItemStat stat = new GameItemStat();
    stat.setGameItem(item);
    stat.setProperty(property);
    stat.setBaseValue(value);
    return stat;
  }

  @Test
  void craftableItem_insertsEveryNewValue() {
    when(gameItemStatRepository.findByGameItemId(item.getId())).thenReturn(List.of());

    boolean written =
        writer.write(
            item,
            dto(
                "{\"is_craftable\":true,\"durability\":{\"health\":550},"
                    + "\"vehicle_weapon\":{\"damage_per_shot\":97.2}}"));

    assertTrue(written);
    ArgumentCaptor<GameItemStat> saved = ArgumentCaptor.forClass(GameItemStat.class);
    verify(gameItemStatRepository, org.mockito.Mockito.times(2)).save(saved.capture());
    assertTrue(
        saved.getAllValues().stream()
            .allMatch(stat -> stat.getGameItem() == item && stat.getBaseValue() > 0));
  }

  @Test
  void existingRows_areUpdatedInPlaceAndDroppedWhenThePayloadNoLongerCarriesThem() {
    GameItemStat health = stored(BlueprintStatProperty.HEALTH_MAX_HEALTH, 500.0);
    GameItemStat stale = stored(BlueprintStatProperty.SHIELD_MAX_HEALTH, 100.0);
    when(gameItemStatRepository.findByGameItemId(item.getId())).thenReturn(List.of(health, stale));

    writer.write(item, dto("{\"is_craftable\":true,\"durability\":{\"health\":550}}"));

    assertEquals(550.0, health.getBaseValue());
    verify(gameItemStatRepository).delete(stale);
    verify(gameItemStatRepository, never()).save(any());
  }

  @Test
  void unchangedRow_keepsItsValueObject() {
    GameItemStat health = stored(BlueprintStatProperty.HEALTH_MAX_HEALTH, 550.0);
    Double before = health.getBaseValue();
    when(gameItemStatRepository.findByGameItemId(item.getId())).thenReturn(List.of(health));

    writer.write(item, dto("{\"is_craftable\":true,\"durability\":{\"health\":550}}"));

    assertSame(before, health.getBaseValue());
  }

  @Test
  void itemThatIsNotCraftable_isLeftAlone() {
    assertFalse(writer.write(item, dto("{\"is_craftable\":false,\"durability\":{\"health\":5}}")));
    verifyNoInteractions(gameItemStatRepository);
  }

  @Test
  void payloadWithoutValues_keepsTheStoredRows() {
    assertFalse(writer.write(item, dto("{\"is_craftable\":true,\"name\":\"Aves Arms\"}")));
    verifyNoInteractions(gameItemStatRepository);
  }

  @Test
  void unsavedItem_insertsWithoutReadingExistingRows() {
    item.setId(null);

    writer.write(item, dto("{\"is_craftable\":true,\"durability\":{\"health\":69}}"));

    verify(gameItemStatRepository, never()).findByGameItemId(any());
    verify(gameItemStatRepository).save(any(GameItemStat.class));
  }
}

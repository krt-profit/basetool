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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintStatProperty;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemStat;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemStatRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemStatSource;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiItemDto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link GameItemStatWriter}: the P4K value wins, the SC Wiki fills gaps, and a Wiki value more
 * than 1 % off a P4K value is reported (REQ-P4K-030).
 */
@ExtendWith(MockitoExtension.class)
class GameItemStatWriterTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static final String SHIELD = "{\"is_craftable\":true,\"shield\":{\"max_health\":%s}}";

  @Mock private GameItemStatRepository gameItemStatRepository;
  @Mock private SyncReportService syncReportService;

  private GameItemStatWriter writer;
  private GameItem item;
  private final UUID runId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    writer = new GameItemStatWriter(gameItemStatRepository, syncReportService);
    item = new GameItem();
    item.setId(UUID.randomUUID());
    item.setExternalUuid(UUID.randomUUID());
    item.setName("AllStop");
  }

  private static ScWikiItemDto dto(String json) {
    return MAPPER.readValue(json, ScWikiItemDto.class);
  }

  private GameItemStat stored(
      BlueprintStatProperty property, double value, GameItemStatSource source) {
    GameItemStat stat = new GameItemStat();
    stat.setGameItem(item);
    stat.setProperty(property);
    stat.setBaseValue(value);
    stat.setSource(source);
    return stat;
  }

  @Test
  void craftableItem_insertsEveryNewValueWithTheWikiAsSource() {
    when(gameItemStatRepository.findByGameItemId(item.getId())).thenReturn(List.of());

    boolean written =
        writer.write(
            item,
            dto(
                "{\"is_craftable\":true,\"durability\":{\"health\":550},"
                    + "\"vehicle_weapon\":{\"damage_per_shot\":97.2}}"),
            runId);

    assertTrue(written);
    ArgumentCaptor<GameItemStat> saved = ArgumentCaptor.forClass(GameItemStat.class);
    verify(gameItemStatRepository, times(2)).save(saved.capture());
    assertTrue(
        saved.getAllValues().stream()
            .allMatch(stat ->
                stat.getGameItem() == item
                    && stat.getBaseValue() > 0
                    && stat.getSource() == GameItemStatSource.SCWIKI));
  }

  @Test
  void wikiRows_areUpdatedInPlaceAndDroppedWhenThePayloadNoLongerCarriesThem() {
    GameItemStat health =
        stored(BlueprintStatProperty.HEALTH_MAX_HEALTH, 500.0, GameItemStatSource.SCWIKI);
    GameItemStat stale =
        stored(BlueprintStatProperty.SHIELD_MAX_HEALTH, 100.0, GameItemStatSource.SCWIKI);
    when(gameItemStatRepository.findByGameItemId(item.getId())).thenReturn(List.of(health, stale));

    writer.write(item, dto("{\"is_craftable\":true,\"durability\":{\"health\":550}}"), runId);

    assertEquals(550.0, health.getBaseValue());
    verify(gameItemStatRepository).delete(stale);
    verify(gameItemStatRepository, never()).save(any());
  }

  @Test
  void unchangedRow_keepsItsValueObject() {
    GameItemStat health =
        stored(BlueprintStatProperty.HEALTH_MAX_HEALTH, 550.0, GameItemStatSource.SCWIKI);
    Double before = health.getBaseValue();
    when(gameItemStatRepository.findByGameItemId(item.getId())).thenReturn(List.of(health));

    writer.write(item, dto("{\"is_craftable\":true,\"durability\":{\"health\":550}}"), runId);

    assertSame(before, health.getBaseValue());
  }

  @Test
  void aP4kRow_keepsItsValue_andAWikiValueHalfAPercentOffIsNotReported() {
    GameItemStat shield =
        stored(BlueprintStatProperty.SHIELD_MAX_HEALTH, 3168.0, GameItemStatSource.P4K);
    when(gameItemStatRepository.findByGameItemId(item.getId())).thenReturn(List.of(shield));

    writer.write(item, dto(SHIELD.formatted("3183.84")), runId);

    assertEquals(3168.0, shield.getBaseValue());
    assertEquals(GameItemStatSource.P4K, shield.getSource());
    verify(gameItemStatRepository, never()).save(any());
    verifyNoInteractions(syncReportService);
  }

  @Test
  void aWikiValueTwoPercentOffAP4kValue_isReportedAsADisagreement() {
    GameItemStat shield =
        stored(BlueprintStatProperty.SHIELD_MAX_HEALTH, 3168.0, GameItemStatSource.P4K);
    when(gameItemStatRepository.findByGameItemId(item.getId())).thenReturn(List.of(shield));

    writer.write(item, dto(SHIELD.formatted("3231.36")), runId);

    assertEquals(3168.0, shield.getBaseValue());
    verify(syncReportService)
        .logScwikiEvent(
            eq(runId),
            eq(SyncEventType.P4K_DISAGREEMENT),
            eq("game_item"),
            eq(item.getExternalUuid()),
            eq("AllStop"),
            eq("column=game_item_stat.SHIELD_MAX_HEALTH;stored=3168.0;source=3231.36"));
  }

  @Test
  void aWikiPayloadWithoutTheP4kStat_keepsTheP4kRowAndFillsTheGap() {
    GameItemStat shield =
        stored(BlueprintStatProperty.SHIELD_MAX_HEALTH, 3168.0, GameItemStatSource.P4K);
    when(gameItemStatRepository.findByGameItemId(item.getId())).thenReturn(List.of(shield));

    writer.write(item, dto("{\"is_craftable\":true,\"durability\":{\"health\":210}}"), runId);

    verify(gameItemStatRepository, never()).delete(any());
    verify(gameItemStatRepository).save(any(GameItemStat.class));
    verifyNoInteractions(syncReportService);
  }

  @Test
  void itemThatIsNotCraftable_isLeftAlone() {
    assertFalse(
        writer.write(item, dto("{\"is_craftable\":false,\"durability\":{\"health\":5}}"), runId));
    verifyNoInteractions(gameItemStatRepository);
  }

  @Test
  void payloadWithoutValues_keepsTheStoredRows() {
    assertFalse(writer.write(item, dto("{\"is_craftable\":true,\"name\":\"Aves Arms\"}"), runId));
    verifyNoInteractions(gameItemStatRepository);
  }

  @Test
  void unsavedItem_insertsWithoutReadingExistingRows() {
    item.setId(null);

    writer.write(item, dto("{\"is_craftable\":true,\"durability\":{\"health\":69}}"), runId);

    verify(gameItemStatRepository, never()).findByGameItemId(any());
    verify(gameItemStatRepository).save(any(GameItemStat.class));
  }

  @Test
  void theP4kValue_replacesAWikiValueAndTakesItsSource() {
    GameItemStat shield =
        stored(BlueprintStatProperty.SHIELD_MAX_HEALTH, 3000.0, GameItemStatSource.SCWIKI);

    boolean changed =
        writer.writeP4k(
            item, Map.of(BlueprintStatProperty.SHIELD_MAX_HEALTH, 3168.0), List.of(shield), true);

    assertTrue(changed);
    assertEquals(3168.0, shield.getBaseValue());
    assertEquals(GameItemStatSource.P4K, shield.getSource());
  }

  @Test
  void aP4kValueACompleteCatalogueNoLongerCarries_isRemoved_whileAWikiValueStays() {
    GameItemStat shield =
        stored(BlueprintStatProperty.SHIELD_MAX_HEALTH, 3168.0, GameItemStatSource.P4K);
    GameItemStat health =
        stored(BlueprintStatProperty.HEALTH_MAX_HEALTH, 210.0, GameItemStatSource.SCWIKI);

    assertTrue(writer.writeP4k(item, Map.of(), List.of(shield, health), true));

    verify(gameItemStatRepository).delete(shield);
    verify(gameItemStatRepository, never()).delete(health);
  }

  @Test
  void aP4kValueAPartialCatalogueDoesNotCarry_stays() {
    GameItemStat shield =
        stored(BlueprintStatProperty.SHIELD_MAX_HEALTH, 3168.0, GameItemStatSource.P4K);

    assertFalse(writer.writeP4k(item, Map.of(), List.of(shield), false));

    verify(gameItemStatRepository, never()).delete(any());
  }

  @Test
  void anUnchangedP4kValue_changesNothing() {
    GameItemStat shield =
        stored(BlueprintStatProperty.SHIELD_MAX_HEALTH, 3168.0, GameItemStatSource.P4K);

    assertFalse(
        writer.writeP4k(
            item, Map.of(BlueprintStatProperty.SHIELD_MAX_HEALTH, 3168.0), List.of(shield), true));
    verify(gameItemStatRepository, never()).save(any());
  }
}

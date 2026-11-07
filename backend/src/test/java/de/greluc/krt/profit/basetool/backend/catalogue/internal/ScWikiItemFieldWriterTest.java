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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.greluc.krt.profit.basetool.backend.catalogue.api.CatalogueMetricNames;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipComponentClass;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiItemDto;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

/**
 * The SC Wiki writes the attributes the P4K leads only where the P4K value is {@code null}, and
 * reports a differing value (REQ-P4K-017…019).
 */
@ExtendWith(MockitoExtension.class)
class ScWikiItemFieldWriterTest {

  private static final UUID RUN = UUID.randomUUID();
  private static final UUID ITEM = UUID.fromString("339bd641-8dfb-4bc1-b273-5ffc5f702fbb");

  @Mock private SyncReportService syncReportService;

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  private ScWikiItemFieldWriter writer;

  @BeforeEach
  void setUp() {
    writer = new ScWikiItemFieldWriter(new ItemClassResolver(meterRegistry), syncReportService);
  }

  @Test
  void aP4kSize_survivesAWikiSize_andTheDisagreementIsReported() {
    GameItem item = item();
    item.setSizeClass(2);
    item.setP4kSize(2);

    writer.apply(item, dto("\"size\":\"3\""), RUN, Instant.now());

    assertThat(item.getSizeClass()).isEqualTo(2);
    verify(syncReportService)
        .logScwikiEvent(
            eq(RUN),
            eq(SyncEventType.P4K_DISAGREEMENT),
            eq("game_item"),
            eq(ITEM),
            any(),
            eq("column=size_class;stored=2;source=3"));
  }

  @Test
  void aWikiMass_isWrittenWhereTheP4kMassIsNull() {
    GameItem item = item();

    writer.apply(item, dto("\"mass\":42.5"), RUN, Instant.now());

    assertThat(item.getMass()).isEqualTo(42.5);
    verify(syncReportService, never())
        .logScwikiEvent(any(), any(), anyString(), any(), any(), anyString());
  }

  @Test
  void anAgreeingWikiValue_isNoDisagreement() {
    GameItem item = item();
    item.setMass(120.0);
    item.setP4kMass(120.0);
    item.setWikiType("Shield");
    item.setP4kType("Shield");

    writer.apply(item, dto("\"mass\":120.0,\"type\":\"shield\""), RUN, Instant.now());

    assertThat(item.getWikiType()).isEqualTo("Shield");
    verify(syncReportService, never())
        .logScwikiEvent(any(), any(), anyString(), any(), any(), anyString());
  }

  @Test
  void theGradeLetterTheP4kMaps_survives_butAnUnmappedTypeTakesTheWikiGrade() {
    GameItem shield = item();
    shield.setGrade("C");
    shield.setP4kType("Shield");
    shield.setP4kGradeRaw(3);
    GameItem pistol = item();
    pistol.setP4kType("WeaponPersonal");
    pistol.setP4kGradeRaw(1);

    writer.apply(shield, dto("\"grade\":\"A\""), RUN, Instant.now());
    writer.apply(pistol, dto("\"grade\":\"B\""), RUN, Instant.now());

    assertThat(shield.getGrade()).isEqualTo("C");
    assertThat(pistol.getGrade()).isEqualTo("B");
    verify(syncReportService)
        .logScwikiEvent(
            eq(RUN),
            eq(SyncEventType.P4K_DISAGREEMENT),
            eq("game_item"),
            eq(ITEM),
            any(),
            eq("column=grade;stored=C;source=A"));
  }

  @Test
  void aP4kClass_survives_andANullP4kClassTakesTheWikiClass() {
    GameItem military = item();
    military.setItemClass(ShipComponentClass.MILITARY);
    military.setP4kItemClass(ShipComponentClass.MILITARY);
    GameItem weapon = item();

    writer.apply(military, dto("\"class\":\"Stealth\""), RUN, Instant.now());
    writer.apply(weapon, dto("\"class\":\"Stealth\""), RUN, Instant.now());

    assertThat(military.getItemClass()).isEqualTo(ShipComponentClass.MILITARY);
    assertThat(weapon.getItemClass()).isEqualTo(ShipComponentClass.STEALTH);
  }

  @Test
  void aWikiDescription_isWritten_butAMissingOrBlankLocaleKeepsTheStoredText() {
    GameItem item = item();
    item.setDescriptionEn("Stored English");
    item.setDescriptionDe("Vom P4K gefüllt");

    writer.apply(
        item,
        dto("\"description\":{\"en_EN\":\"Wiki English\",\"fr_FR\":\" \"}"),
        RUN,
        Instant.now());
    writer.apply(item, dto("\"description\":{\"de_DE\":\"  \"}"), RUN, Instant.now());

    assertThat(item.getDescriptionEn()).isEqualTo("Wiki English");
    assertThat(item.getDescriptionDe()).isEqualTo("Vom P4K gefüllt");
  }

  @Test
  void anUnknownWikiClass_countsOnce() {
    GameItem item = item();

    writer.apply(item, dto("\"class\":\"Prototype\""), RUN, Instant.now());

    assertThat(item.getItemClass()).isNull();
    assertThat(meterRegistry.counter(CatalogueMetricNames.CATALOGUE_ITEM_CLASS_UNKNOWN).count())
        .isEqualTo(1.0);
  }

  private static GameItem item() {
    GameItem item = new GameItem();
    item.setName("AllStop");
    item.setExternalUuid(ITEM);
    return item;
  }

  private static ScWikiItemDto dto(String fields) {
    return JsonMapper.builder()
        .build()
        .readValue(
            "{\"uuid\":\"" + ITEM + "\",\"name\":\"AllStop\"," + fields + "}", ScWikiItemDto.class);
  }
}

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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.api.events.InventoryNotices;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationMuteService;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import de.greluc.krt.profit.basetool.backend.notification.internal.NotificationCreationService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

/**
 * The low-stock notice end to end against PostgreSQL: hysteresis, the recipients the seeded rule
 * resolves, the silent lifecycle, the marker leaving the level's version alone, and the gauge
 * (REQ-NOTIF-030, REQ-OBS-011).
 */
@SpringBootTest
@Transactional
@RecordApplicationEvents
class StockLevelNoticeIntegrationTest {

  private static final int LIMIT = 100;
  private static final int STOCK_QUALITY = 500;
  private static final int GOOD_QUALITY = 700;
  private static final UUID GOOD_TIER = UUID.fromString("6b1f2e0a-3c1d-4f5e-9a10-000000000650");
  private static final String VERSION_OF_LEVEL =
      "SELECT version FROM inventory_stock_level WHERE id = ?";

  @Autowired private StockLevelNoticeProducer producer;
  @Autowired private InventoryBusinessGauges gauges;
  @Autowired private NotificationCreationService notificationCreationService;
  @Autowired private NotificationMuteService muteService;
  @Autowired private StockLevelRepository stockLevelRepository;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private UserRepository userRepository;
  @Autowired private EntityManager entityManager;
  @Autowired private ApplicationEvents events;

  private StockLevelTestData data;
  private UUID bereich;
  private UUID unit;
  private UUID holder;
  private UUID location;
  private UUID material;
  private UUID level;

  @BeforeEach
  void setUp() {
    data = new StockLevelTestData(jdbc, userRepository);
    UUID leitung = data.orgUnit("ORGANISATIONSLEITUNG", null);
    bereich = data.orgUnit("BEREICH", leitung);
    unit = data.orgUnit("SQUADRON", bereich);
    holder = data.user("holder");
    location = data.location();
    material = data.material();
    level = data.level(unit, material, null, null, 8.0, 12.0);
  }

  @Test
  void aLevelThatTurnsLowNotifiesTheUnitsDirectLogisticiansAndSeatHoldersOnly() {
    UUID logistician = data.user("logistician");
    UUID seatHolder = data.user("seat");
    UUID member = data.user("member");
    UUID bereichLead = data.user("bereich-lead");
    data.joins(logistician, unit, "MEMBER", true);
    data.joins(seatHolder, unit, "STAFFELLEITER", false);
    data.joins(member, unit, "MEMBER", false);
    data.joins(bereichLead, bereich, "BEREICHSLEITER", true);
    stockOf(3.0);

    assertThat(produce()).isEqualTo(1);

    NoticeEvent notice = onlyNotice();
    assertThat(notice.eventType()).isEqualTo(NotificationEventType.INVENTORY_STOCK_LEVEL_LOW);
    assertThat(notice.entityType()).isEqualTo(InventoryNotices.STOCK_LEVEL_ENTITY_TYPE);
    assertThat(notice.renderParams())
        .containsEntry("minimum", "8")
        .containsEntry("amount", "3")
        .doesNotContainKey("actor");
    assertThat(notificationCreationService.createFromEvent(notice))
        .containsExactlyInAnyOrder(logistician, seatHolder);
  }

  @Test
  void aMemberWhoMutedTheTypeIsNotTold() {
    UUID logistician = data.user("logistician");
    UUID muted = data.user("muted");
    data.joins(logistician, unit, "MEMBER", true);
    data.joins(muted, unit, "MEMBER", true);
    muteService.setMuted(muted, NotificationType.INVENTORY_STOCK_LEVEL_LOW, true);
    entityManager.flush();
    stockOf(3.0);

    produce();

    assertThat(notificationCreationService.createFromEvent(onlyNotice()))
        .containsExactly(logistician);
  }

  @Test
  void theNoticeFiresOnceAndReArmsOnlyAfterTheTargetWasReached() {
    stockOf(3.0);
    assertThat(produce()).isEqualTo(1);
    assertThat(produce()).isZero();

    stockOf(9.0);
    assertThat(produce()).isZero();
    stockOf(3.0);
    assertThat(produce()).isZero();
    assertThat(markerSet()).isTrue();

    stockOf(12.0);
    assertThat(produce()).isZero();
    assertThat(markerSet()).isFalse();

    stockOf(3.0);
    assertThat(produce()).isEqualTo(1);
    assertThat(events.stream(NoticeEvent.class).count()).isEqualTo(2);
  }

  @Test
  void aLevelThatIsNotLowRaisesNothing() {
    stockOf(8.0);

    assertThat(produce()).isZero();
    assertThat(markerSet()).isFalse();
  }

  @Test
  void aGameItemLevelNotifiesToo() {
    UUID itemLevel = data.level(unit, null, data.gameItem(), null, 5.0, 5.0);
    stockOf(8.0);

    assertThat(produce()).isEqualTo(1);

    assertThat(events.stream(NoticeEvent.class).map(NoticeEvent::entityId).toList())
        .containsExactly(itemLevel);
  }

  @Test
  void theLimitHoldsBackTheRestForTheNextRun() {
    data.level(unit, null, data.gameItem(), null, 5.0, 5.0);

    assertThat(produceUpTo(1)).isEqualTo(1);
    assertThat(produceUpTo(1)).isEqualTo(1);
    assertThat(produceUpTo(1)).isZero();
  }

  @Test
  void aLevelOfAnInactiveUnitOrADelistedEntryRaisesNothingUntilItReturns() {
    jdbc.update("UPDATE org_unit SET active = FALSE WHERE id = ?", unit);
    assertThat(produce()).isZero();
    jdbc.update("UPDATE org_unit SET active = TRUE WHERE id = ?", unit);
    jdbc.update("UPDATE material SET scwiki_deleted_at = now() WHERE id = ?", material);
    assertThat(produce()).isZero();
    assertThat(markerSet()).isFalse();

    jdbc.update("UPDATE material SET scwiki_deleted_at = NULL WHERE id = ?", material);
    assertThat(produce()).isEqualTo(1);
  }

  @Test
  void aDeactivatedTierAndADeletedLevelRaiseNothing() {
    UUID tieredMaterial = data.material();
    UUID tiered = data.level(unit, tieredMaterial, null, GOOD_TIER, 8.0, 8.0);
    data.setMaterialStock(holder, unit, location, tieredMaterial, GOOD_QUALITY, 12.0);
    stockOf(12.0);
    jdbc.update("UPDATE quality_tier SET active = FALSE WHERE id = ?", GOOD_TIER);
    jdbc.update("DELETE FROM inventory_stock_level WHERE id = ?", tiered);

    assertThat(produce()).isZero();

    jdbc.update("DELETE FROM inventory_stock_level WHERE id = ?", level);
    assertThat(produce()).isZero();
  }

  @Test
  void theMarkerNeverBumpsTheVersionSoALevelEditDoesNotConflict() {
    stockOf(3.0);
    assertThat(produce()).isEqualTo(1);

    assertThat(jdbc.queryForObject(VERSION_OF_LEVEL, Long.class, level)).isZero();
    entityManager.clear();
    StockLevel loaded = stockLevelRepository.findById(level).orElseThrow();
    loaded.setMinimum(9.0);
    loaded.setTarget(13.0);
    stockLevelRepository.saveAndFlush(loaded);

    assertThat(markerSet()).isTrue();
    assertThat(jdbc.queryForObject(VERSION_OF_LEVEL, Long.class, level)).isEqualTo(1L);
  }

  @Test
  void theGaugeCountsTheLowLevelsWhetherOrNotTheyWereAnnounced() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    gauges.register(registry);
    stockOf(12.0);
    gauges.sample();
    long before = lowLevels(registry);
    stockOf(3.0);
    produce();

    gauges.sample();

    assertThat(markerSet()).isTrue();
    assertThat(lowLevels(registry)).isEqualTo(before + 1);
    stockOf(9.0);
    gauges.sample();
    assertThat(lowLevels(registry)).isEqualTo(before);
  }

  private void stockOf(double amount) {
    data.setMaterialStock(holder, unit, location, material, STOCK_QUALITY, amount);
  }

  private int produce() {
    return produceUpTo(LIMIT);
  }

  /** Runs the producer on a fresh persistence context, as every timed run has its own. */
  private int produceUpTo(int limit) {
    entityManager.clear();
    return producer.produce(Instant.now(), limit);
  }

  private NoticeEvent onlyNotice() {
    List<NoticeEvent> notices =
        events.stream(NoticeEvent.class).filter(e -> e.entityId().equals(level)).toList();
    assertThat(notices).hasSize(1);
    return notices.getFirst();
  }

  private boolean markerSet() {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT low_notified_at IS NOT NULL FROM inventory_stock_level WHERE id = ?",
            Boolean.class,
            level));
  }

  private static long lowLevels(SimpleMeterRegistry registry) {
    Gauge gauge = registry.find("basetool.inventory.minimum.levels.below").gauge();
    assertThat(gauge).isNotNull();
    return (long) gauge.value();
  }
}

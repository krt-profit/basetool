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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTier;
import de.greluc.krt.profit.basetool.backend.inventory.internal.StockLevelAllocation.Fill;
import de.greluc.krt.profit.basetool.backend.inventory.internal.StockLevelStates.State;
import de.greluc.krt.profit.basetool.backend.kernel.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationContextRole;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/** The once-per-low notice of a minimum stock level and its hysteresis (REQ-NOTIF-030). */
@ExtendWith(MockitoExtension.class)
class StockLevelNoticeProducerTest {

  private static final Instant NOW = Instant.parse("2026-10-31T12:00:00Z");
  private static final double MINIMUM = 8.0;
  private static final double TARGET = 12.0;

  @Mock private StockLevelStates states;
  @Mock private StockLevelRepository stockLevelRepository;
  @Mock private ApplicationEventPublisher eventPublisher;

  private StockLevelNoticeProducer producer;
  private Squadron unit;
  private Material material;

  @BeforeEach
  void setUp() {
    producer = new StockLevelNoticeProducer(states, stockLevelRepository, eventPublisher);
    unit = new Squadron();
    unit.setId(UUID.randomUUID());
    unit.setName("Argon");
    material = new Material();
    material.setId(UUID.randomUUID());
    material.setName("Stileron");
  }

  private @NotNull State state(boolean notified, double allocated) {
    StockLevel level = new StockLevel();
    level.setId(UUID.randomUUID());
    level.setOrgUnit(unit);
    level.setMaterial(material);
    level.setMinimum(MINIMUM);
    level.setTarget(TARGET);
    if (notified) {
      ReflectionTestUtils.setField(level, "lowNotifiedAt", NOW.minusSeconds(60));
    }
    return new State(level, new Fill(allocated, 0.0, allocated / MINIMUM, allocated < MINIMUM));
  }

  @Test
  void theKindNamesTheProducer() {
    assertThat(producer.kind()).isEqualTo("stock_level_low");
  }

  @Test
  void anArmedLowLevelIsMarkedAndPublishedWithTheUnitAsContext() {
    State low = state(false, 3.0);
    when(states.ofActiveLevels()).thenReturn(List.of(low));
    when(stockLevelRepository.markLowNotified(low.level().getId(), NOW)).thenReturn(1);

    assertThat(producer.produce(NOW, 10)).isEqualTo(1);

    ArgumentCaptor<NoticeEvent> published = ArgumentCaptor.forClass(NoticeEvent.class);
    verify(eventPublisher).publishEvent(published.capture());
    NoticeEvent notice = published.getValue();
    assertThat(notice.eventType()).isEqualTo(NotificationEventType.INVENTORY_STOCK_LEVEL_LOW);
    assertThat(notice.entityId()).isEqualTo(low.level().getId());
    assertThat(notice.contextOrgUnits().get(NotificationContextRole.RESPONSIBLE).id())
        .isEqualTo(unit.getId());
    assertThat(notice.contextOrgUnits().get(NotificationContextRole.RESPONSIBLE).kind())
        .isEqualTo(OrgUnitKind.SQUADRON);
    assertThat(notice.renderParams())
        .containsEntry("unit", "Argon")
        .containsEntry("entry", "Stileron")
        .containsEntry("minimum", "8")
        .containsEntry("amount", "3");
  }

  @Test
  void aTieredLevelNamesItsTierCodeInTheEntry() {
    State low = state(false, 2.5);
    QualityTier tier = new QualityTier();
    tier.setCode("GOOD");
    low.level().setQualityTier(tier);
    when(states.ofActiveLevels()).thenReturn(List.of(low));
    when(stockLevelRepository.markLowNotified(low.level().getId(), NOW)).thenReturn(1);

    producer.produce(NOW, 10);

    ArgumentCaptor<NoticeEvent> published = ArgumentCaptor.forClass(NoticeEvent.class);
    verify(eventPublisher).publishEvent(published.capture());
    assertThat(published.getValue().renderParams())
        .containsEntry("entry", "Stileron · GOOD")
        .containsEntry("amount", "2.5");
  }

  @Test
  void aLevelWhoseMarkerAnotherInstanceSetFirstIsNotPublished() {
    State low = state(false, 3.0);
    when(states.ofActiveLevels()).thenReturn(List.of(low));
    when(stockLevelRepository.markLowNotified(low.level().getId(), NOW)).thenReturn(0);

    assertThat(producer.produce(NOW, 10)).isZero();

    verify(eventPublisher, never()).publishEvent(any(Object.class));
  }

  @Test
  void aLevelThatIsNotLowIsLeftAlone() {
    when(states.ofActiveLevels()).thenReturn(List.of(state(false, 8.0)));

    assertThat(producer.produce(NOW, 10)).isZero();

    verify(stockLevelRepository, never()).markLowNotified(any(), any());
  }

  @Test
  void anAnnouncedLevelBelowTheTargetStaysQuietWhetherLowOrNot() {
    when(states.ofActiveLevels()).thenReturn(List.of(state(true, 3.0), state(true, 9.0)));

    assertThat(producer.produce(NOW, 10)).isZero();

    verify(stockLevelRepository, never()).markLowNotified(any(), any());
    verify(stockLevelRepository, never()).clearLowNotified(any());
  }

  @Test
  void anAnnouncedLevelThatReachedItsTargetIsArmedAgain() {
    State full = state(true, TARGET);
    when(states.ofActiveLevels()).thenReturn(List.of(full));

    assertThat(producer.produce(NOW, 10)).isZero();

    verify(stockLevelRepository).clearLowNotified(full.level().getId());
    verify(eventPublisher, never()).publishEvent(any(Object.class));
  }

  @Test
  void theLimitLeavesTheRestUnmarkedForTheNextRun() {
    State first = state(false, 1.0);
    State second = state(false, 2.0);
    when(states.ofActiveLevels()).thenReturn(List.of(first, second));
    when(stockLevelRepository.markLowNotified(first.level().getId(), NOW)).thenReturn(1);

    assertThat(producer.produce(NOW, 1)).isEqualTo(1);

    verify(stockLevelRepository, never()).markLowNotified(second.level().getId(), NOW);
  }
}

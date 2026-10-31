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

package de.greluc.krt.profit.basetool.backend.supply.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTier;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterialCoverage;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterialCoverage.BucketCoverage;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterialCoverage.OrderCoverage;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.kernel.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationContextRole;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.supply.api.NeedStatus;
import de.greluc.krt.profit.basetool.backend.supply.api.events.SupplyNotices;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/** The once-per-link notice that a need's linked job order is covered (REQ-SUP-024). */
@ExtendWith(MockitoExtension.class)
class SupplyOrderCoveredNoticeProducerTest {

  private static final Instant NOW = Instant.parse("2026-10-31T12:00:00Z");

  @Mock private SupplySettingsService settingsService;
  @Mock private SupplyNeedRepository needRepository;
  @Mock private JobOrderMaterialCoverage jobOrderMaterialCoverage;
  @Mock private OrgUnitRepository orgUnitRepository;
  @Mock private ApplicationEventPublisher eventPublisher;

  private SupplyOrderCoveredNoticeProducer producer;
  private Squadron unit;
  private Material material;
  private QualityTier tier;

  @BeforeEach
  void setUp() {
    producer =
        new SupplyOrderCoveredNoticeProducer(
            settingsService,
            needRepository,
            jobOrderMaterialCoverage,
            orgUnitRepository,
            eventPublisher);
    unit = new Squadron();
    unit.setId(UUID.randomUUID());
    unit.setName("Argon");
    material = new Material();
    material.setId(UUID.randomUUID());
    material.setName("Stileron");
    tier = new QualityTier();
    tier.setId(UUID.randomUUID());
  }

  private @NotNull SupplyNeed linkedNeed() {
    SupplyNeed need = new SupplyNeed();
    need.setId(UUID.randomUUID());
    need.setOrgUnitId(unit.getId());
    need.setMaterial(material);
    need.setQualityTier(tier);
    need.setAmount(15.0);
    need.setStatus(NeedStatus.OPEN);
    need.setJobOrderId(UUID.randomUUID());
    return need;
  }

  private void loads(@NotNull SupplyNeed need, double required, double booked) {
    when(settingsService.isEnabled()).thenReturn(true);
    when(needRepository.findOrderCoverageCandidates(NeedStatus.OPEN)).thenReturn(List.of(need));
    when(jobOrderMaterialCoverage.coverageOf(Set.of(need.getJobOrderId())))
        .thenReturn(
            Map.of(
                need.getJobOrderId(),
                new OrderCoverage(
                    need.getJobOrderId(),
                    42,
                    JobOrderStatus.IN_PROGRESS,
                    List.of(
                        new BucketCoverage(material.getId(), tier.getId(), required, booked)))));
    when(orgUnitRepository.findAllById(Set.of(unit.getId()))).thenReturn(List.of(unit));
  }

  @Test
  void aCoveredOrderTellsTheRaisingUnitOnce() {
    SupplyNeed need = linkedNeed();
    loads(need, 12.4, 12.4);
    when(needRepository.markOrderCoveredNotified(need.getId(), NOW)).thenReturn(1);

    assertThat(producer.produce(NOW, 10)).isEqualTo(1);

    assertThat(producer.kind()).isEqualTo("supply_order_covered");
    ArgumentCaptor<NoticeEvent> event = ArgumentCaptor.forClass(NoticeEvent.class);
    verify(eventPublisher).publishEvent(event.capture());
    NoticeEvent notice = event.getValue();
    assertThat(notice.eventType()).isEqualTo(NotificationEventType.SUPPLY_NEED_ORDER_COVERED);
    assertThat(notice.entityType()).isEqualTo(SupplyNotices.NEED_ENTITY_TYPE);
    assertThat(notice.entityId()).isEqualTo(need.getId());
    assertThat(notice.contextOrgUnits().get(NotificationContextRole.RESPONSIBLE).id())
        .isEqualTo(unit.getId());
    assertThat(notice.contextOrgUnits().get(NotificationContextRole.RESPONSIBLE).kind())
        .isEqualTo(OrgUnitKind.SQUADRON);
    assertThat(notice.renderParams())
        .containsEntry("unit", "Argon")
        .containsEntry("entry", "Stileron")
        .containsEntry("order", "#42");
  }

  @Test
  void anOrderStillShortOfItsRequirementStaysSilent() {
    SupplyNeed need = linkedNeed();
    loads(need, 12.4, 5.0);

    assertThat(producer.produce(NOW, 10)).isZero();

    verify(needRepository, never()).markOrderCoveredNotified(any(), any());
    verifyNoInteractions(eventPublisher);
  }

  @Test
  void aNeedAnotherRunAlreadyMarkedIsNotAnnouncedAgain() {
    SupplyNeed need = linkedNeed();
    loads(need, 12.4, 12.4);
    when(needRepository.markOrderCoveredNotified(need.getId(), NOW)).thenReturn(0);

    assertThat(producer.produce(NOW, 10)).isZero();

    verifyNoInteractions(eventPublisher);
  }

  @Test
  void aSwitchedOffSurfaceReadsNothing() {
    when(settingsService.isEnabled()).thenReturn(false);

    assertThat(producer.produce(NOW, 10)).isZero();

    verifyNoInteractions(needRepository, jobOrderMaterialCoverage, eventPublisher);
  }
}

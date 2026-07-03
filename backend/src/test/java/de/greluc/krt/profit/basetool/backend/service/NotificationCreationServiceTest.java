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

package de.greluc.krt.profit.basetool.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.event.JobOrderCreatedEvent;
import de.greluc.krt.profit.basetool.backend.event.OrgUnitRef;
import de.greluc.krt.profit.basetool.backend.model.Notification;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.repository.NotificationRepository;
import de.greluc.krt.profit.basetool.backend.support.NotificationParamsCodec;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationCreationServiceTest {

  private static final UUID A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
  private static final UUID B = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

  @Mock private RuleEvaluationService ruleEvaluationService;
  @Mock private NotificationRepository notificationRepository;
  @Mock private NotificationParamsCodec notificationParamsCodec;
  @Mock private NotificationStreamService notificationStreamService;
  @InjectMocks private NotificationCreationService service;

  private static JobOrderCreatedEvent event() {
    return new JobOrderCreatedEvent(
        UUID.fromString("00000000-0000-0000-0000-00000000e001"),
        9,
        "h",
        new OrgUnitRef(UUID.randomUUID(), OrgUnitKind.SQUADRON),
        "IRI",
        new OrgUnitRef(UUID.randomUUID(), OrgUnitKind.SQUADRON),
        "MATERIAL",
        null);
  }

  @Test
  void createsOneRowPerRecipientWithEventFields() {
    JobOrderCreatedEvent event = event();
    when(ruleEvaluationService.resolveRecipients(event))
        .thenReturn(Map.of(NotificationType.JOB_ORDER_CREATED, Set.of(A, B)));
    when(notificationParamsCodec.serialize(any())).thenReturn("{\"displayId\":\"9\"}");

    int created = service.createFromEvent(event);

    assertThat(created).isEqualTo(2);
    ArgumentCaptor<List<Notification>> captor = ArgumentCaptor.captor();
    verify(notificationRepository).saveAll(captor.capture());
    List<Notification> saved = captor.getValue();
    assertThat(saved).hasSize(2);
    assertThat(saved)
        .allSatisfy(
            n -> {
              assertThat(n.getType()).isEqualTo(NotificationType.JOB_ORDER_CREATED);
              assertThat(n.getEntityType()).isEqualTo("JOB_ORDER");
              assertThat(n.getEntityId()).isEqualTo(event.entityId());
              assertThat(n.getParams()).isEqualTo("{\"displayId\":\"9\"}");
              assertThat(n.isRead()).isFalse();
            });
    assertThat(saved).extracting(Notification::getRecipientSub).containsExactlyInAnyOrder(A, B);
  }

  @Test
  void writesNothingWhenNoRecipients() {
    JobOrderCreatedEvent event = event();
    when(ruleEvaluationService.resolveRecipients(event)).thenReturn(Map.of());

    int created = service.createFromEvent(event);

    assertThat(created).isZero();
    verify(notificationRepository, never()).saveAll(any());
  }
}

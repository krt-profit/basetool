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

package de.greluc.krt.profit.basetool.backend.hangar.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.hangar.api.Ship;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipOrigin;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.ActorRef;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/** The admin's bulk origin change tells the member once per action (REQ-HANGAR-018). */
@ExtendWith(MockitoExtension.class)
class HangarShipOriginServiceTest {

  private static final ActorRef ADMIN = new ActorRef(UUID.randomUUID(), "Ada");

  @Mock private ShipRepository shipRepository;
  @Mock private AuditRecorder auditRecorder;
  @Mock private UserService userService;
  @Mock private ApplicationEventPublisher eventPublisher;

  private HangarShipOriginService service;

  @BeforeEach
  void setUp() {
    service =
        new HangarShipOriginService(
            shipRepository, auditRecorder, new HangarAdminNotifier(userService, eventPublisher));
  }

  @Test
  void anAdminChangingOneShipTellsTheMemberAboutThatShip() {
    UUID member = UUID.randomUUID();
    Ship ship = shipOf(member);
    when(shipRepository.lockAllByIdOrderById(List.of(ship.getId()))).thenReturn(List.of(ship));
    when(userService.currentActor()).thenReturn(ADMIN);

    service.bulkChangeOriginByAdmin(member, List.of(ship.getId()), ShipOrigin.IN_GAME);

    NoticeEvent notice = published();
    assertThat(notice.eventType()).isEqualTo(NotificationEventType.HANGAR_CHANGED_BY_ADMIN);
    assertThat(notice.renderParams()).containsEntry("changeCode", "UPDATED");
    assertThat(notice.renderParams()).containsEntry("shipType", "Cutlass Black");
  }

  @Test
  void anAdminChangingSeveralShipsTellsTheMemberOnceWithTheCountOnly() {
    UUID member = UUID.randomUUID();
    Ship first = shipOf(member);
    Ship second = shipOf(member);
    List<UUID> ids = List.of(first.getId(), second.getId());
    when(shipRepository.lockAllByIdOrderById(ids)).thenReturn(List.of(first, second));
    when(userService.currentActor()).thenReturn(ADMIN);

    service.bulkChangeOriginByAdmin(member, ids, ShipOrigin.PLEDGE);

    NoticeEvent notice = published();
    assertThat(notice.eventType())
        .isEqualTo(NotificationEventType.HANGAR_SHIPS_ORIGIN_CHANGED_BY_ADMIN);
    assertThat(notice.renderParams()).containsEntry("count", "2").doesNotContainValue("PLEDGE");
  }

  @Test
  void anAdminChangingNothingTellsNobody() {
    UUID member = UUID.randomUUID();
    Ship locked = shipOf(member);
    locked.setOriginLocked(true);
    when(shipRepository.lockAllByIdOrderById(List.of(locked.getId()))).thenReturn(List.of(locked));

    service.bulkChangeOriginByAdmin(member, List.of(locked.getId()), ShipOrigin.IN_GAME);

    verify(eventPublisher, never()).publishEvent(any(Object.class));
  }

  private NoticeEvent published() {
    ArgumentCaptor<NoticeEvent> captor = ArgumentCaptor.forClass(NoticeEvent.class);
    verify(eventPublisher).publishEvent(captor.capture());
    return captor.getValue();
  }

  private static Ship shipOf(UUID ownerId) {
    User owner = new User();
    owner.setId(ownerId);
    ShipType type = new ShipType();
    type.setName("Cutlass Black");
    Ship ship = new Ship();
    ship.setId(UUID.randomUUID());
    ship.setOwner(owner);
    ship.setShipType(type);
    return ship;
  }
}

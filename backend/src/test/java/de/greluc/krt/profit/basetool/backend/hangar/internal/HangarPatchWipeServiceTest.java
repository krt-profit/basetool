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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.hangar.api.Ship;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRepository;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipWipeCount;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The hangar side of a patch wipe (REQ-PATCH-015, REQ-PATCH-017, REQ-PATCH-018): the selected ships
 * go through the ordinary removal, the hangar trail gets one summary event, and every owner's
 * hangar room refreshes after the commit.
 */
@ExtendWith(MockitoExtension.class)
class HangarPatchWipeServiceTest {

  private static final Instant LIVE_AT = Instant.parse("2000-01-01T00:00:00Z");
  private static final UUID PATCH_CHANGE = UUID.randomUUID();

  @Mock private ShipRepository shipRepository;
  @Mock private HangarShipRemoval shipRemoval;
  @Mock private AuditRecorder auditRecorder;
  @Mock private LiveSyncPublisher liveSyncPublisher;

  private HangarPatchWipeService service;

  @BeforeEach
  void setUp() {
    service =
        new HangarPatchWipeService(shipRepository, shipRemoval, auditRecorder, liveSyncPublisher);
  }

  @Test
  void theShipsGoThroughTheOrdinaryRemovalWithOneSummaryAndTheirOwnersRooms() {
    User owner = new User();
    owner.setId(UUID.randomUUID());
    List<Ship> ships = List.of(ship(owner), ship(owner));
    when(shipRepository.lockPatchWipeable(LIVE_AT)).thenReturn(ships);
    when(shipRemoval.remove(ships)).thenReturn(1);

    ShipWipeCount wiped = service.wipe(LIVE_AT, PATCH_CHANGE, "4.4");

    assertThat(wiped).isEqualTo(new ShipWipeCount(2, 1));
    verify(shipRemoval).remove(ships);
    verify(auditRecorder)
        .record(
            eq(AuditEventType.HANGAR_SHIPS_WIPED_BY_PATCH),
            eq(PATCH_CHANGE),
            eq("4.4"),
            isNull(),
            argThat(details ->
                details.toString().contains("ships=2")
                    && details.toString().contains("owners=1")
                    && details.toString().contains("detachedUnits=1")));
    verify(liveSyncPublisher)
        .publishAfterCommit(
            LiveSyncTopic.of(LiveSyncTopicClass.HANGAR_OWN, owner.getId()), List.of("ships"));
  }

  @Test
  void withoutShipsOnlyTheSummaryIsRecorded() {
    when(shipRepository.lockPatchWipeable(LIVE_AT)).thenReturn(List.of());

    assertThat(service.wipe(LIVE_AT, PATCH_CHANGE, "4.4")).isEqualTo(new ShipWipeCount(0, 0));

    verify(auditRecorder)
        .record(
            eq(AuditEventType.HANGAR_SHIPS_WIPED_BY_PATCH),
            eq(PATCH_CHANGE),
            eq("4.4"),
            isNull(),
            argThat(details -> details.toString().contains("ships=0")));
    verifyNoInteractions(liveSyncPublisher);
  }

  private static Ship ship(User owner) {
    Ship ship = new Ship();
    ship.setId(UUID.randomUUID());
    ship.setOwner(owner);
    return ship;
  }
}

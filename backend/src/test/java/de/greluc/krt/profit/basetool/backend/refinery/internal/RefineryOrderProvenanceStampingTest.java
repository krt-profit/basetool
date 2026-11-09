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

package de.greluc.krt.profit.basetool.backend.refinery.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditService;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.RefiningMethodRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.SpaceStation;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import de.greluc.krt.profit.basetool.backend.notification.api.events.ActorRef;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryOrderStatus;
import de.greluc.krt.profit.basetool.backend.scope.api.OrgUnitStampingService;
import de.greluc.krt.profit.basetool.backend.scope.api.OwnerScopeService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The web's writes of a refinery order record their provenance (REQ-REFINERY-027): a create in the
 * web names no client, and an update or a cancel moves only the last change to the web while the
 * creator stays.
 */
@ExtendWith(MockitoExtension.class)
class RefineryOrderProvenanceStampingTest {

  private static final UUID OWNER_ID = UUID.randomUUID();
  private static final UUID ORDER_ID = UUID.randomUUID();
  private static final UUID LOCATION_ID = UUID.randomUUID();
  private static final String CLIENT = "versekit";
  private static final Instant CLIENT_CREATED = Instant.parse("2026-11-01T10:00:00Z");

  @Mock private RefineryOrderRepository refineryOrderRepository;
  @Mock private UserRepository userRepository;
  @Mock private LocationRepository locationRepository;
  @Mock private MissionRepository missionRepository;
  @Mock private MissionParticipantRepository missionParticipantRepository;
  @Mock private RefiningMethodRepository refiningMethodRepository;
  @Mock private MaterialRepository materialRepository;
  @Mock private OwnerScopeService ownerScopeService;
  @Mock private OrgUnitStampingService orgUnitStampingService;
  @Mock private AuditService auditService;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private UserService userService;
  @InjectMocks private RefineryOrderService service;

  private User owner;
  private Location refinery;

  @BeforeEach
  void setUp() {
    lenient().when(userService.currentActor()).thenReturn(ActorRef.system());
    lenient().when(refineryOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    owner = new User();
    owner.setId(OWNER_ID);
    refinery = new Location();
    refinery.setId(LOCATION_ID);
    refinery.setName("ARC-L1");
    SpaceStation station = new SpaceStation();
    station.setHasRefineryTerminal(true);
    refinery.setSpaceStation(station);
  }

  /** A web create names no client as creator or last change. */
  @Test
  void aWebCreateRecordsTheWebAsCreatorAndLastChange() {
    when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner));
    when(locationRepository.findById(LOCATION_ID)).thenReturn(Optional.of(refinery));
    RefineryOrder incoming = new RefineryOrder();
    incoming.setLocation(refinery);
    Instant before = Instant.now();

    RefineryOrderProvenance provenance =
        service.createRefineryOrder(OWNER_ID, incoming, null).getProvenance();

    assertThat(provenance.getCreatedSource()).isEqualTo(RefineryChangeSource.WEB);
    assertThat(provenance.getCreatedClient()).isNull();
    assertThat(provenance.getLastChangeSource()).isEqualTo(RefineryChangeSource.WEB);
    assertThat(provenance.getLastChangeClient()).isNull();
    assertThat(provenance.getLastChangedAt()).isAfterOrEqualTo(before);
  }

  /** A web edit of a client's order keeps the client as creator and moves the last change. */
  @Test
  void aWebEditOfAClientOrderKeepsTheCreatorAndMovesTheLastChange() {
    RefineryOrder stored = clientOrder();
    when(refineryOrderRepository.lockById(ORDER_ID)).thenReturn(Optional.of(stored));
    RefineryOrder details = new RefineryOrder();
    details.setStatus(RefineryOrderStatus.IN_PROGRESS);

    RefineryOrderProvenance provenance =
        service.updateRefineryOrder(OWNER_ID, ORDER_ID, details, false).getProvenance();

    assertThat(provenance.getCreatedSource()).isEqualTo(RefineryChangeSource.CLIENT);
    assertThat(provenance.getCreatedClient()).isEqualTo(CLIENT);
    assertThat(provenance.getLastChangeSource()).isEqualTo(RefineryChangeSource.WEB);
    assertThat(provenance.getLastChangeClient()).isNull();
    assertThat(provenance.getLastChangedAt()).isAfter(CLIENT_CREATED);
  }

  /** A cancel in the web is a web change. */
  @Test
  void aWebCancelIsAWebChange() {
    RefineryOrder stored = clientOrder();
    when(refineryOrderRepository.lockById(ORDER_ID)).thenReturn(Optional.of(stored));

    service.deleteRefineryOrder(OWNER_ID, ORDER_ID, false);

    assertThat(stored.getProvenance().getLastChangeSource()).isEqualTo(RefineryChangeSource.WEB);
    assertThat(stored.getProvenance().getLastChangedAt()).isAfter(CLIENT_CREATED);
    assertThat(stored.getProvenance().getCreatedClient()).isEqualTo(CLIENT);
  }

  /**
   * An order of the owner that a client created.
   *
   * @return the stored order
   */
  private RefineryOrder clientOrder() {
    RefineryOrder order = new RefineryOrder();
    order.setId(ORDER_ID);
    order.setOwner(owner);
    order.setLocation(refinery);
    order.setProvenance(RefineryOrderProvenance.createdByClient(CLIENT, CLIENT_CREATED));
    return order;
  }
}

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.bank.internal.OrgUnitBankAccessService;
import de.greluc.krt.profit.basetool.backend.bank.internal.OrgUnitBankLiveSyncTopicAuthorizer;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncAuthorization;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicAuthorizer;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.operation.internal.OperationAccessPolicy;
import de.greluc.krt.profit.basetool.backend.operation.internal.OperationLiveSyncTopicAuthorizer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Who is let into a live-sync room, and who is not (ADR-0143). */
@ExtendWith(MockitoExtension.class)
class LiveSyncSubscriptionAuthorizerTest {

  private static final UUID RESOURCE = UUID.fromString("8f14e45f-ceea-467a-9c5b-5f1f52a3a1c2");

  @Mock private OwnerScopeService ownerScopeService;

  @Mock private OperationAccessPolicy operationAccessPolicy;
  @Mock private AuthHelperService authHelperService;
  @Mock private OrgUnitBankAccessService orgUnitBankAccessService;

  private MeterRegistry meterRegistry;
  private LiveSyncSubscriptionAuthorizer authorizer;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    authorizer =
        new LiveSyncSubscriptionAuthorizer(authHelperService, meterRegistry, moduleAuthorizers());
    lenient().when(authHelperService.isMemberOrAbove()).thenReturn(true);
  }

  private List<LiveSyncTopicAuthorizer> moduleAuthorizers() {
    return List.of(
        new MissionLiveSyncTopicAuthorizer(ownerScopeService),
        new OperationLiveSyncTopicAuthorizer(operationAccessPolicy),
        new JobOrderLiveSyncTopicAuthorizer(ownerScopeService),
        new RefineryLiveSyncTopicAuthorizer(ownerScopeService),
        new OrgUnitBankLiveSyncTopicAuthorizer(orgUnitBankAccessService));
  }

  @Test
  @DisplayName("every kind but member and self is delegated to exactly one module authorizer")
  void startupRefusesAGapOrAnOverlap() {
    List<LiveSyncTopicAuthorizer> complete = moduleAuthorizers();
    List<LiveSyncTopicAuthorizer> missingBank = complete.subList(0, complete.size() - 1);
    List<LiveSyncTopicAuthorizer> twoMission = new ArrayList<>(complete);
    twoMission.add(new MissionLiveSyncTopicAuthorizer(ownerScopeService));
    LiveSyncTopicAuthorizer claimsSelf =
        new LiveSyncTopicAuthorizer() {
          @Override
          public Set<LiveSyncAuthorization> authorizations() {
            return Set.of(LiveSyncAuthorization.SELF);
          }

          @Override
          public boolean mayJoin(LiveSyncTopic topic) {
            return true;
          }
        };
    List<LiveSyncTopicAuthorizer> withSelf = new ArrayList<>(complete);
    withSelf.add(claimsSelf);

    assertThatThrownBy(
            () -> new LiveSyncSubscriptionAuthorizer(authHelperService, meterRegistry, missingBank))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("BANK_ACCOUNT");
    assertThatThrownBy(
            () -> new LiveSyncSubscriptionAuthorizer(authHelperService, meterRegistry, twoMission))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("MISSION");
    assertThatThrownBy(
            () -> new LiveSyncSubscriptionAuthorizer(authHelperService, meterRegistry, withSelf))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SELF");
  }

  @Test
  @DisplayName("a roleless guest is refused every room, including the global ones")
  void aGuestIsRefusedEverything() {
    when(authHelperService.isMemberOrAbove()).thenReturn(false);

    assertThat(authorizer.maySubscribe(LiveSyncTopic.parse("materialboard"))).isFalse();
    assertThat(authorizer.maySubscribe(LiveSyncTopic.parse("inventory"))).isFalse();
    verify(ownerScopeService, never()).canViewJobOrders();
  }

  @Test
  @DisplayName("a member is admitted to the global rooms without a resource question")
  void aMemberJoinsTheGlobalRooms() {
    assertThat(authorizer.maySubscribe(LiveSyncTopic.parse("materialboard"))).isTrue();
    assertThat(authorizer.maySubscribe(LiveSyncTopic.parse("missions"))).isTrue();
    assertThat(authorizer.maySubscribe(LiveSyncTopic.parse("refinery"))).isTrue();
    assertThat(authorizer.maySubscribe(LiveSyncTopic.parse("orgunit-bank"))).isTrue();
  }

  @Test
  @DisplayName("the Einsatz room asks exactly what the Einsatz read asks")
  void theMissionRoomUsesTheMissionScope() {
    when(ownerScopeService.canSeeMission(RESOURCE)).thenReturn(true);
    assertThat(authorizer.maySubscribe(LiveSyncTopic.parse("mission:" + RESOURCE))).isTrue();

    when(ownerScopeService.canSeeMission(RESOURCE)).thenReturn(false);
    assertThat(authorizer.maySubscribe(LiveSyncTopic.parse("mission:" + RESOURCE))).isFalse();
  }

  @Test
  @DisplayName("the Operation, Auftrag and Raffinerie-Order rooms each use their own scope")
  void perResourceRoomsUseTheirOwnScope() {
    when(operationAccessPolicy.canSeeOperation(RESOURCE)).thenReturn(true);
    when(ownerScopeService.canSeeJobOrder(RESOURCE)).thenReturn(false);
    when(ownerScopeService.canSeeRefineryOrder(RESOURCE)).thenReturn(true);

    assertThat(authorizer.maySubscribe(LiveSyncTopic.parse("operation:" + RESOURCE))).isTrue();
    assertThat(authorizer.maySubscribe(LiveSyncTopic.parse("order:" + RESOURCE))).isFalse();
    assertThat(authorizer.maySubscribe(LiveSyncTopic.parse("refinery-order:" + RESOURCE))).isTrue();
  }

  @Test
  @DisplayName("the Auftrags-queue room needs the queue capability, not just membership")
  void theQueueRoomNeedsTheQueueCapability() {
    when(ownerScopeService.canViewJobOrders()).thenReturn(false);
    assertThat(authorizer.maySubscribe(LiveSyncTopic.parse("orders"))).isFalse();

    when(ownerScopeService.canViewJobOrders()).thenReturn(true);
    assertThat(authorizer.maySubscribe(LiveSyncTopic.parse("orders"))).isTrue();
  }

  @Test
  @DisplayName("a personal hangar or blueprints room admits its own member and nobody else")
  void aPersonalRoomAdmitsOnlyItsMember() {
    when(authHelperService.currentUserId()).thenReturn(java.util.Optional.of(RESOURCE));
    assertThat(authorizer.maySubscribe(LiveSyncTopic.parse("hangar:" + RESOURCE))).isTrue();
    assertThat(authorizer.maySubscribe(LiveSyncTopic.parse("blueprints:" + RESOURCE))).isTrue();

    when(authHelperService.currentUserId()).thenReturn(java.util.Optional.of(UUID.randomUUID()));
    assertThat(authorizer.maySubscribe(LiveSyncTopic.parse("hangar:" + RESOURCE))).isFalse();

    when(authHelperService.currentUserId()).thenReturn(java.util.Optional.empty());
    assertThat(authorizer.maySubscribe(LiveSyncTopic.parse("blueprints:" + RESOURCE))).isFalse();
  }

  @Test
  @DisplayName("the bank room is opened by the member-facing read, not the staff one")
  void theBankRoomUsesTheOrgUnitRead() {
    when(orgUnitBankAccessService.getViewableAccountDetail(RESOURCE)).thenReturn(null);

    assertThat(authorizer.maySubscribe(LiveSyncTopic.parse("bank:" + RESOURCE))).isTrue();
    verify(orgUnitBankAccessService).getViewableAccountDetail(RESOURCE);
  }

  @Test
  @DisplayName("a check that throws refuses the room rather than admitting on an exception")
  void aFailedCheckRefuses() {
    when(orgUnitBankAccessService.getViewableAccountDetail(RESOURCE))
        .thenThrow(new IllegalStateException("account not visible"));

    assertThat(authorizer.maySubscribe(LiveSyncTopic.parse("bank:" + RESOURCE))).isFalse();
    assertThat(
            meterRegistry
                .counter(
                    MetricNames.LIVESYNC_SUBSCRIBE,
                    MetricNames.TAG_TOPIC_CLASS,
                    "bank_account",
                    MetricNames.TAG_OUTCOME,
                    MetricNames.OUTCOME_DENIED,
                    MetricNames.TAG_REASON,
                    MetricNames.SUBSCRIBE_DENY_CHECK_FAILED)
                .count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("both verdicts are counted under the room's class")
  void verdictsAreCounted() {
    when(ownerScopeService.canSeeMission(RESOURCE)).thenReturn(false);

    authorizer.maySubscribe(LiveSyncTopic.parse("materialboard"));
    authorizer.maySubscribe(LiveSyncTopic.parse("mission:" + RESOURCE));

    assertThat(
            meterRegistry
                .counter(
                    MetricNames.LIVESYNC_SUBSCRIBE,
                    MetricNames.TAG_TOPIC_CLASS,
                    "materialboard",
                    MetricNames.TAG_OUTCOME,
                    MetricNames.OUTCOME_ALLOWED,
                    MetricNames.TAG_REASON,
                    MetricNames.REASON_NONE)
                .count())
        .isEqualTo(1.0);
    assertThat(
            meterRegistry
                .counter(
                    MetricNames.LIVESYNC_SUBSCRIBE,
                    MetricNames.TAG_TOPIC_CLASS,
                    "mission",
                    MetricNames.TAG_OUTCOME,
                    MetricNames.OUTCOME_DENIED,
                    MetricNames.TAG_REASON,
                    MetricNames.SUBSCRIBE_DENY_AUTHZ)
                .count())
        .isEqualTo(1.0);
  }
}

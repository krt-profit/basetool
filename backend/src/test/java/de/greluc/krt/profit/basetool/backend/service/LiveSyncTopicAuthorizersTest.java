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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.support.LiveSyncAuthorization;
import de.greluc.krt.profit.basetool.backend.support.LiveSyncTopic;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Each module's live-sync authorizer asks exactly its module's read gate (ADR-0143). */
@ExtendWith(MockitoExtension.class)
class LiveSyncTopicAuthorizersTest {

  private static final UUID RESOURCE = UUID.fromString("0b6c1f4e-2a7d-4c51-9e0a-6f3d2b8c9a11");

  @Mock private OwnerScopeService ownerScopeService;
  @Mock private OrgUnitBankAccessService orgUnitBankAccessService;

  @Test
  @DisplayName("the mission authorizer decides the Einsatz room by canSeeMission")
  void missionAuthorizerAsksTheMissionGate() {
    MissionLiveSyncTopicAuthorizer authorizer =
        new MissionLiveSyncTopicAuthorizer(ownerScopeService);
    when(ownerScopeService.canSeeMission(RESOURCE)).thenReturn(true, false);

    assertThat(authorizer.authorizations()).containsExactly(LiveSyncAuthorization.MISSION);
    assertThat(authorizer.mayJoin(LiveSyncTopic.parse("mission:" + RESOURCE))).isTrue();
    assertThat(authorizer.mayJoin(LiveSyncTopic.parse("mission:" + RESOURCE))).isFalse();
  }

  @Test
  @DisplayName("the operation authorizer decides the Operation room by canSeeOperation")
  void operationAuthorizerAsksTheOperationGate() {
    OperationLiveSyncTopicAuthorizer authorizer =
        new OperationLiveSyncTopicAuthorizer(ownerScopeService);
    when(ownerScopeService.canSeeOperation(RESOURCE)).thenReturn(true, false);

    assertThat(authorizer.authorizations()).containsExactly(LiveSyncAuthorization.OPERATION);
    assertThat(authorizer.mayJoin(LiveSyncTopic.parse("operation:" + RESOURCE))).isTrue();
    assertThat(authorizer.mayJoin(LiveSyncTopic.parse("operation:" + RESOURCE))).isFalse();
  }

  @Test
  @DisplayName("the job-order authorizer decides the Auftrag and the queue rooms by their gates")
  void jobOrderAuthorizerAsksTheJobOrderGates() {
    JobOrderLiveSyncTopicAuthorizer authorizer =
        new JobOrderLiveSyncTopicAuthorizer(ownerScopeService);
    when(ownerScopeService.canSeeJobOrder(RESOURCE)).thenReturn(false);
    when(ownerScopeService.canViewJobOrders()).thenReturn(true);

    assertThat(authorizer.authorizations())
        .containsExactlyInAnyOrder(
            LiveSyncAuthorization.JOB_ORDER, LiveSyncAuthorization.JOB_ORDER_QUEUE);
    assertThat(authorizer.mayJoin(LiveSyncTopic.parse("order:" + RESOURCE))).isFalse();
    assertThat(authorizer.mayJoin(LiveSyncTopic.parse("orders"))).isTrue();
    assertThatThrownBy(() -> authorizer.mayJoin(LiveSyncTopic.parse("mission:" + RESOURCE)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("the refinery authorizer decides the Raffinerie-Order room by canSeeRefineryOrder")
  void refineryAuthorizerAsksTheRefineryGate() {
    RefineryLiveSyncTopicAuthorizer authorizer =
        new RefineryLiveSyncTopicAuthorizer(ownerScopeService);
    when(ownerScopeService.canSeeRefineryOrder(RESOURCE)).thenReturn(true, false);

    assertThat(authorizer.authorizations()).containsExactly(LiveSyncAuthorization.REFINERY_ORDER);
    assertThat(authorizer.mayJoin(LiveSyncTopic.parse("refinery-order:" + RESOURCE))).isTrue();
    assertThat(authorizer.mayJoin(LiveSyncTopic.parse("refinery-order:" + RESOURCE))).isFalse();
  }

  @Test
  @DisplayName("the bank authorizer opens the account room only after the member-facing read")
  void bankAuthorizerPerformsTheOrgUnitRead() {
    OrgUnitBankLiveSyncTopicAuthorizer authorizer =
        new OrgUnitBankLiveSyncTopicAuthorizer(orgUnitBankAccessService);

    assertThat(authorizer.authorizations()).containsExactly(LiveSyncAuthorization.BANK_ACCOUNT);
    assertThat(authorizer.mayJoin(LiveSyncTopic.parse("bank:" + RESOURCE))).isTrue();
    verify(orgUnitBankAccessService).getViewableAccountDetail(RESOURCE);

    when(orgUnitBankAccessService.getViewableAccountDetail(RESOURCE))
        .thenThrow(new IllegalStateException("account not visible"));
    assertThatThrownBy(() -> authorizer.mayJoin(LiveSyncTopic.parse("bank:" + RESOURCE)))
        .isInstanceOf(IllegalStateException.class);
  }
}

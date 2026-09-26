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
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.AppExceptionKind;
import de.greluc.krt.profit.basetool.backend.exception.OwnerOrgUnitRequiredException;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies that the "pin, else choose" rejection of both stamping entry points carries its own
 * stable problem code (REQ-ORG-017, REQ-ORG-023), on which the frontend branches.
 */
@ExtendWith(MockitoExtension.class)
class OrgUnitStampingServiceOwnerRequiredTest {

  @Mock private RequestScopeResolver requestScopeResolver;
  @Mock private AccessGateService accessGateService;
  @Mock private AuthHelperService authHelper;
  @Mock private OrgUnitMembershipRepository orgUnitMembershipRepository;
  @Mock private OrgUnitRepository orgUnitRepository;

  private OrgUnitStampingService service;
  private User targetUser;

  @BeforeEach
  void setUp() {
    service =
        new OrgUnitStampingService(
            requestScopeResolver,
            accessGateService,
            authHelper,
            orgUnitMembershipRepository,
            orgUnitRepository);

    targetUser = new User();
    targetUser.setId(UUID.randomUUID());

    lenient()
        .when(orgUnitMembershipRepository.findAllByIdUserId(targetUser.getId()))
        .thenReturn(List.of(membership(UUID.randomUUID()), membership(UUID.randomUUID())));
  }

  /**
   * Builds a membership row of {@code targetUser} in one org unit.
   *
   * @param orgUnitId the org unit the row points at.
   * @return a membership whose composite id carries the target user and that org unit.
   */
  private OrgUnitMembership membership(UUID orgUnitId) {
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(targetUser.getId(), orgUnitId));
    return membership;
  }

  @Test
  void theSquadronPickerRefusesWithTheStableOwnerRequiredCode() {
    when(requestScopeResolver.readActiveSquadronFromHeader()).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.resolveSquadronForPickerOutput(targetUser, null))
        .isInstanceOf(OwnerOrgUnitRequiredException.class)
        .extracting(ex -> ((OwnerOrgUnitRequiredException) ex).code())
        .isEqualTo("OWNER_ORG_UNIT_REQUIRED");
  }

  @Test
  void theOrgUnitPickerRefusesWithTheStableOwnerRequiredCode() {
    when(requestScopeResolver.readActiveSquadronFromHeader()).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.resolveOrgUnitForPickerOutput(targetUser, null))
        .isInstanceOf(OwnerOrgUnitRequiredException.class)
        .extracting(ex -> ((OwnerOrgUnitRequiredException) ex).code())
        .isEqualTo("OWNER_ORG_UNIT_REQUIRED");
  }

  @Test
  void theRefusalStaysA400AndIsNotAConflictOrAPermissionFailure() {
    when(requestScopeResolver.readActiveSquadronFromHeader()).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.resolveOrgUnitForPickerOutput(targetUser, null))
        .isInstanceOf(OwnerOrgUnitRequiredException.class)
        .extracting(ex -> ((OwnerOrgUnitRequiredException) ex).status())
        .isEqualTo(AppExceptionKind.BAD_REQUEST.status());
  }

  @Test
  void anHonourablePinStillStampsWithoutRefusing() {
    UUID pinned = UUID.randomUUID();
    when(orgUnitMembershipRepository.findAllByIdUserId(targetUser.getId()))
        .thenReturn(List.of(membership(pinned), membership(UUID.randomUUID())));
    when(requestScopeResolver.readActiveSquadronFromHeader()).thenReturn(Optional.of(pinned));
    Squadron squadron = new Squadron();
    squadron.setId(pinned);
    when(orgUnitRepository.findById(pinned)).thenReturn(Optional.of(squadron));

    assertThat(service.resolveOrgUnitForPickerOutput(targetUser, null)).isSameAs(squadron);
  }
}

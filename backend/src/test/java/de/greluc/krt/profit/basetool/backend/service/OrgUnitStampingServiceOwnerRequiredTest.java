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
 * The "pin, else choose" rejection carries its own stable problem code (REQ-ORG-017, REQ-ORG-023).
 *
 * <p>Why a test for what looks like an exception type swap: the code is the frontend's only
 * branching point, and until 2026-09-16 there was none. Every picker surface fell through to
 * echoing the backend's own English {@code detail} — "User belongs to multiple org units;
 * owningOrgUnitId is required" — straight into a German toast, which is an i18n violation and tells
 * a member neither what is wrong nor what to do. If this rejection ever slides back under the
 * generic {@code BAD_REQUEST}, five surfaces silently regress to that at once and nothing else in
 * the build would notice.
 *
 * <p>Both stamping entry points are exercised. They are separate methods with separate membership
 * lookups that happen to share a tail, and the four-eyes version of this defect is fixing one of
 * them.
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

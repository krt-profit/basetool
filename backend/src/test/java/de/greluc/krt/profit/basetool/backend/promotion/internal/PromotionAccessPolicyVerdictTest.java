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

package de.greluc.krt.profit.basetool.backend.promotion.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.StaffelMembershipResolver;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.service.AccessGateService;
import de.greluc.krt.profit.basetool.backend.service.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.service.OrgUnitCascadeService;
import de.greluc.krt.profit.basetool.backend.service.OrgUnitStampingService;
import de.greluc.krt.profit.basetool.backend.service.OwnerScopeService;
import de.greluc.krt.profit.basetool.backend.service.RequestScopeResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.access.AccessDeniedException;

/**
 * Pins the verdicts of {@link PromotionAccessPolicy} over the scope fixture matrix of plan §5.4,
 * against the real {@link RequestScopeResolver} and {@link OwnerScopeService}.
 */
class PromotionAccessPolicyVerdictTest {

  private static final UUID CALLER = UUID.randomUUID();
  private static final UUID SQUADRON_ON = UUID.randomUUID();
  private static final UUID SQUADRON_OFF = UUID.randomUUID();
  private static final UUID SPECIAL_COMMAND = UUID.randomUUID();
  private static final UUID UNKNOWN = UUID.randomUUID();

  /**
   * One caller situation and the verdicts the gate gives it.
   *
   * @param name the case label
   * @param admin whether the caller holds ADMIN
   * @param authenticated whether the caller has a user id
   * @param pin the active org-unit header, or {@code null}
   * @param nameSortedStaffeln the caller's Staffel memberships in name order
   * @param featureEnabled the expected feature-flag verdict
   * @param readAccess the expected read verdict
   */
  record Case(
      String name,
      boolean admin,
      boolean authenticated,
      @Nullable UUID pin,
      List<UUID> nameSortedStaffeln,
      boolean featureEnabled,
      boolean readAccess) {

    @Override
    public String toString() {
      return name;
    }
  }

  static Stream<Case> matrix() {
    return Stream.of(
        new Case("admin unpinned", true, true, null, List.of(), true, true),
        new Case(
            "admin pinned to a flag-on Staffel", true, true, SQUADRON_ON, List.of(), true, true),
        new Case(
            "admin pinned to a flag-off Staffel", true, true, SQUADRON_OFF, List.of(), false, true),
        new Case(
            "admin pinned to a Spezialkommando",
            true,
            true,
            SPECIAL_COMMAND,
            List.of(),
            true,
            true),
        new Case("admin pinned to an unknown unit", true, true, UNKNOWN, List.of(), true, true),
        new Case(
            "admin who is also a member, unpinned",
            true,
            true,
            null,
            List.of(SQUADRON_OFF),
            true,
            true),
        new Case("anonymous caller", false, false, null, List.of(), true, false),
        new Case("member of no Staffel", false, true, null, List.of(), true, false),
        new Case(
            "member of a Spezialkommando or Bereich only, pinned to it",
            false,
            true,
            SPECIAL_COMMAND,
            List.of(),
            true,
            false),
        new Case(
            "member of one flag-on Staffel", false, true, null, List.of(SQUADRON_ON), true, true),
        new Case(
            "member of one flag-off Staffel",
            false,
            true,
            null,
            List.of(SQUADRON_OFF),
            false,
            true),
        new Case(
            "member of one Staffel pinned to it",
            false,
            true,
            SQUADRON_ON,
            List.of(SQUADRON_ON),
            true,
            true),
        new Case(
            "member of one Staffel pinned to a foreign one",
            false,
            true,
            SQUADRON_OFF,
            List.of(SQUADRON_ON),
            true,
            true),
        new Case(
            "member of two Staffeln, unpinned, flag-off first by name",
            false,
            true,
            null,
            List.of(SQUADRON_OFF, SQUADRON_ON),
            false,
            true),
        new Case(
            "member of two Staffeln, unpinned, flag-on first by name",
            false,
            true,
            null,
            List.of(SQUADRON_ON, SQUADRON_OFF),
            true,
            true),
        new Case(
            "member of two Staffeln pinned to the flag-off one",
            false,
            true,
            SQUADRON_OFF,
            List.of(SQUADRON_ON, SQUADRON_OFF),
            false,
            true),
        new Case(
            "member of two Staffeln pinned to the flag-on one",
            false,
            true,
            SQUADRON_ON,
            List.of(SQUADRON_OFF, SQUADRON_ON),
            true,
            true));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("matrix")
  void policyGivesTheRecordedVerdict(Case c) {
    AuthHelperService authHelper = mock(AuthHelperService.class);
    OrgUnitMembershipRepository memberships = mock(OrgUnitMembershipRepository.class);
    OrgUnitRepository orgUnits = mock(OrgUnitRepository.class);
    StaffelMembershipResolver staffelResolver = mock(StaffelMembershipResolver.class);
    HttpServletRequest request = mock(HttpServletRequest.class);

    when(authHelper.isAdmin()).thenReturn(c.admin());
    when(authHelper.isAuthenticated()).thenReturn(c.authenticated());
    when(authHelper.currentUserId())
        .thenReturn(c.authenticated() ? Optional.of(CALLER) : Optional.empty());
    when(request.getHeader(RequestScopeResolver.ACTIVE_ORG_UNIT_HEADER))
        .thenReturn(c.pin() == null ? null : c.pin().toString());
    List<OrgUnitMembership> rows =
        c.nameSortedStaffeln().stream().map(PromotionAccessPolicyVerdictTest::staffelRow).toList();
    when(memberships.findAllByIdUserIdAndKind(CALLER, OrgUnitKind.SQUADRON)).thenReturn(rows);
    when(staffelResolver.resolveNameSortedStaffelIds(anyList())).thenReturn(c.nameSortedStaffeln());
    Map<UUID, OrgUnit> world =
        Map.of(
            SQUADRON_ON, squadron(SQUADRON_ON, true),
            SQUADRON_OFF, squadron(SQUADRON_OFF, false),
            SPECIAL_COMMAND, specialCommand());
    when(orgUnits.findById(any(UUID.class)))
        .thenAnswer(inv -> Optional.ofNullable(world.get(inv.<UUID>getArgument(0))));

    RequestScopeResolver resolver =
        new RequestScopeResolver(
            authHelper,
            memberships,
            orgUnits,
            mock(OrgUnitCascadeService.class),
            staffelResolver,
            request);
    OwnerScopeService ownerScopeService =
        new OwnerScopeService(
            resolver, mock(AccessGateService.class), mock(OrgUnitStampingService.class));
    PromotionAccessPolicy policy = new PromotionAccessPolicy(ownerScopeService, authHelper);

    assertThat(policy.isFeatureEnabledForCurrentScope())
        .as("feature flag")
        .isEqualTo(c.featureEnabled());
    assertThat(policy.hasReadAccess()).as("read access").isEqualTo(c.readAccess());
    if (c.featureEnabled()) {
      assertThatCode(policy::assertFeatureEnabled).doesNotThrowAnyException();
    } else {
      assertThatThrownBy(policy::assertFeatureEnabled).isInstanceOf(AccessDeniedException.class);
    }
  }

  private static OrgUnitMembership staffelRow(UUID orgUnitId) {
    OrgUnitMembership row = new OrgUnitMembership();
    row.setId(new OrgUnitMembershipId(CALLER, orgUnitId));
    row.setKind(OrgUnitKind.SQUADRON);
    return row;
  }

  private static Squadron squadron(UUID id, boolean promotionEnabled) {
    Squadron squadron = new Squadron();
    squadron.setId(id);
    squadron.setPromotionEnabled(promotionEnabled);
    return squadron;
  }

  private static SpecialCommand specialCommand() {
    SpecialCommand command = new SpecialCommand();
    command.setId(SPECIAL_COMMAND);
    return command;
  }
}

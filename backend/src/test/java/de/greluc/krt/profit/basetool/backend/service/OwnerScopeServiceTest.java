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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.OwnerOrgUnitRequiredException;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.MembershipRole;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.Operation;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.RefineryOrder;
import de.greluc.krt.profit.basetool.backend.model.Ship;
import de.greluc.krt.profit.basetool.backend.model.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderHandoverRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderItemHandoverRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.OperationRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.RefineryOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.support.StaffelMembershipResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

/**
 * Mockito unit tests for {@link OwnerScopeService}. Inherits the test scenarios that previously
 * lived under {@code SquadronScopeServiceTest} before the R2.c rename — the implementation moved
 * from {@code SquadronScopeService} to {@code OwnerScopeService} but every behavioural invariant
 * stayed the same. Covers the org-unit-context resolution paths (admin via {@code
 * X-Active-Org-Unit-Id} request header, non-admin via persistent user record), the aggregate-
 * specific access checks for the five staffel-scoped roots, and the Mission cross-staffel-
 * visibility escape clause ({@code is_internal = false}).
 *
 * <p>The thin {@code SquadronScopeService} shim that still carries the legacy class name has its
 * own minimal smoke test ({@link SquadronScopeServiceTest}) verifying that every shim method
 * forwards to this service.
 */
@ExtendWith(MockitoExtension.class)
class OwnerScopeServiceTest {

  @Mock private AuthHelperService authHelper;
  @Mock private SquadronRepository squadronRepository;
  @Mock private MissionRepository missionRepository;
  @Mock private JobOrderRepository jobOrderRepository;
  @Mock private JobOrderHandoverRepository jobOrderHandoverRepository;
  @Mock private JobOrderItemHandoverRepository jobOrderItemHandoverRepository;
  @Mock private InventoryItemRepository inventoryItemRepository;
  @Mock private RefineryOrderRepository refineryOrderRepository;
  @Mock private OperationRepository operationRepository;
  @Mock private ShipRepository shipRepository;
  @Mock private OrgUnitMembershipRepository orgUnitMembershipRepository;

  @Mock
  private de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository orgUnitRepository;

  @Mock private OrgUnitCascadeService orgUnitCascadeService;

  @Mock private StaffelMembershipResolver staffelMembershipResolver;

  @Mock private HttpServletRequest request;

  private OwnerScopeService service;

  private static final UUID MEMBER_USER_ID = UUID.randomUUID();
  private static final UUID SQUADRON_A_ID = UUID.randomUUID();
  private static final UUID SQUADRON_B_ID = UUID.randomUUID();

  private Squadron squadronA;
  private Squadron squadronB;
  private User memberUserInA;

  @BeforeEach
  void setUp() {
    lenient().when(authHelper.isAuthenticated()).thenReturn(true);

    squadronA = new Squadron();
    squadronA.setId(SQUADRON_A_ID);
    squadronA.setShorthand("ALF");

    squadronB = new Squadron();
    squadronB.setId(SQUADRON_B_ID);
    squadronB.setShorthand("BRV");

    memberUserInA = new User();
    memberUserInA.setId(MEMBER_USER_ID);

    lenient()
        .when(
            orgUnitCascadeService.expandWithDescendants(
                org.mockito.ArgumentMatchers.anyCollection()))
        .thenAnswer(
            invocation -> {
              java.util.Collection<OrgUnitMembership> rows = invocation.getArgument(0);
              java.util.Set<UUID> ids = new java.util.LinkedHashSet<>();
              for (OrgUnitMembership row : rows) {
                ids.add(row.getId().getOrgUnitId());
              }
              return ids;
            });

    StaffelMembershipResolver realResolver =
        new StaffelMembershipResolver(squadronRepository, orgUnitRepository);
    lenient()
        .when(staffelMembershipResolver.resolveNameSortedStaffelIds(any()))
        .thenAnswer(
            invocation -> realResolver.resolveNameSortedStaffelIds(invocation.getArgument(0)));
    lenient().when(squadronRepository.existsById(any())).thenReturn(true);

    wireDelegates();
  }

  /**
   * Wires the L3-split (#922) collaborators into the {@link OwnerScopeService} facade under test.
   * Mockito does not inject one {@code @InjectMocks} target into another, so the facade's three
   * sub-services are built here as REAL instances fed with the same mocks the scenarios stub, and
   * the facade is then constructed from them. They used to be patched into an {@code @InjectMocks}
   * facade with {@code ReflectionTestUtils.setField}; its fields are {@code private final}, which
   * JEP 500 (JDK 26) warns about and a later release will refuse. A single {@link
   * RequestScopeResolver} instance is shared by the facade, the gates and the stamping service so
   * the request-scoped memoisation (backed by the shared {@code request} mock) collapses repeated
   * reads exactly as in production. Constructor-arg order matches each service's
   * {@code @RequiredArgsConstructor} field-declaration order.
   */
  private void wireDelegates() {
    RequestScopeResolver requestScopeResolver =
        new RequestScopeResolver(
            authHelper,
            orgUnitMembershipRepository,
            orgUnitRepository,
            orgUnitCascadeService,
            staffelMembershipResolver,
            request);
    AccessGateService accessGateService =
        new AccessGateService(
            requestScopeResolver,
            authHelper,
            missionRepository,
            jobOrderRepository,
            jobOrderHandoverRepository,
            jobOrderItemHandoverRepository,
            inventoryItemRepository,
            refineryOrderRepository,
            operationRepository,
            shipRepository,
            orgUnitMembershipRepository);
    OrgUnitStampingService orgUnitStampingService =
        new OrgUnitStampingService(
            requestScopeResolver,
            accessGateService,
            authHelper,
            orgUnitMembershipRepository,
            orgUnitRepository);
    service =
        new OwnerScopeService(requestScopeResolver, accessGateService, orgUnitStampingService);
  }

  /** Returns a Staffel membership row pointing the given user at the given Squadron. */
  private static OrgUnitMembership staffelMembership(UUID userId, UUID squadronId) {
    OrgUnitMembership m = new OrgUnitMembership();
    m.setId(new OrgUnitMembershipId(userId, squadronId));
    m.setKind(OrgUnitKind.SQUADRON);
    return m;
  }

  /** Returns an SK membership row pointing the given user at the given Special Command. */
  private static OrgUnitMembership skMembership(UUID userId, UUID skOrgUnitId) {
    OrgUnitMembership m = new OrgUnitMembership();
    m.setId(new OrgUnitMembershipId(userId, skOrgUnitId));
    m.setKind(OrgUnitKind.SPECIAL_COMMAND);
    return m;
  }

  /**
   * Returns a Bereich membership row (no leadership flags set) for the given user + Bereich; the
   * caller flips {@code isBereichsleiter}/-koordinator/-operator to make it an oversight seat (epic
   * #692 Phase 6).
   */
  private static OrgUnitMembership bereichMembershipRow(UUID userId, UUID bereichId) {
    OrgUnitMembership m = new OrgUnitMembership();
    m.setId(new OrgUnitMembershipId(userId, bereichId));
    m.setKind(OrgUnitKind.BEREICH);
    return m;
  }

  /** Returns an OL membership row with {@code is_ol_member} + the {@code OL_MEMBER} rank set. */
  private static OrgUnitMembership olMembershipRow(UUID userId, UUID olId) {
    OrgUnitMembership m = new OrgUnitMembership();
    m.setId(new OrgUnitMembershipId(userId, olId));
    m.setKind(OrgUnitKind.ORGANISATIONSLEITUNG);
    m.setRole(MembershipRole.OL_MEMBER);
    return m;
  }

  @Nested
  class CurrentSquadronIdTests {

    @Test
    void adminWithoutHeader_returnsEmpty_admin_seesAllSquadrons() {
      when(authHelper.isAdmin()).thenReturn(true);
      when(request.getHeader(OwnerScopeService.ACTIVE_ORG_UNIT_HEADER)).thenReturn(null);

      assertTrue(service.currentSquadronId().isEmpty());
    }

    @Test
    void adminWithActiveSquadronHeader_returnsThatSquadron() {
      when(authHelper.isAdmin()).thenReturn(true);
      when(request.getHeader(OwnerScopeService.ACTIVE_ORG_UNIT_HEADER))
          .thenReturn(SQUADRON_B_ID.toString());

      assertEquals(Optional.of(SQUADRON_B_ID), service.currentSquadronId());
    }

    @Test
    void adminWithBlankHeader_returnsEmpty() {
      when(authHelper.isAdmin()).thenReturn(true);
      when(request.getHeader(OwnerScopeService.ACTIVE_ORG_UNIT_HEADER)).thenReturn("");

      assertTrue(service.currentSquadronId().isEmpty());
    }

    @Test
    void adminWithMalformedHeader_returnsEmpty() {
      when(authHelper.isAdmin()).thenReturn(true);
      when(request.getHeader(OwnerScopeService.ACTIVE_ORG_UNIT_HEADER)).thenReturn("not-a-uuid");

      assertTrue(service.currentSquadronId().isEmpty());
    }

    @Test
    void nonAdmin_returnsPersistentUserSquadron() {
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserIdAndKind(
              MEMBER_USER_ID, OrgUnitKind.SQUADRON))
          .thenReturn(List.of(staffelMembership(MEMBER_USER_ID, SQUADRON_A_ID)));

      assertEquals(Optional.of(SQUADRON_A_ID), service.currentSquadronId());
    }

    @Test
    void nonAdmin_anonymousCaller_returnsEmpty() {
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.empty());

      assertTrue(service.currentSquadronId().isEmpty());
    }

    @Test
    void nonAdmin_userWithoutSquadron_returnsEmpty() {
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserIdAndKind(
              MEMBER_USER_ID, OrgUnitKind.SQUADRON))
          .thenReturn(List.of());

      assertTrue(service.currentSquadronId().isEmpty());
    }

    @Test
    void nonAdmin_twoStaffeln_noMatchingPin_returnsNameSortedPrimary() {
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserIdAndKind(
              MEMBER_USER_ID, OrgUnitKind.SQUADRON))
          .thenReturn(
              List.of(
                  staffelMembership(MEMBER_USER_ID, SQUADRON_B_ID),
                  staffelMembership(MEMBER_USER_ID, SQUADRON_A_ID)));
      squadronA.setName("Alpha");
      squadronB.setName("Bravo");
      when(orgUnitRepository.findAllById(any())).thenReturn(List.of(squadronB, squadronA));

      assertEquals(Optional.of(SQUADRON_A_ID), service.currentSquadronId());
    }

    @Test
    void nonAdmin_twoStaffeln_pinMatchesNonPrimary_returnsPinned() {
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserIdAndKind(
              MEMBER_USER_ID, OrgUnitKind.SQUADRON))
          .thenReturn(
              List.of(
                  staffelMembership(MEMBER_USER_ID, SQUADRON_A_ID),
                  staffelMembership(MEMBER_USER_ID, SQUADRON_B_ID)));
      when(request.getHeader(OwnerScopeService.ACTIVE_ORG_UNIT_HEADER))
          .thenReturn(SQUADRON_B_ID.toString());

      assertEquals(Optional.of(SQUADRON_B_ID), service.currentSquadronId());
    }
  }

  @Nested
  class CurrentSquadronTests {

    @Test
    void resolvesEntityFromCurrentSquadronId() {
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserIdAndKind(
              MEMBER_USER_ID, OrgUnitKind.SQUADRON))
          .thenReturn(List.of(staffelMembership(MEMBER_USER_ID, SQUADRON_A_ID)));
      when(orgUnitRepository.findById(SQUADRON_A_ID)).thenReturn(Optional.of(squadronA));

      assertEquals(Optional.of(squadronA), service.currentSquadron());
    }

    @Test
    void adminInAllSquadronsMode_returnsEmpty() {
      when(authHelper.isAdmin()).thenReturn(true);
      when(request.getHeader(OwnerScopeService.ACTIVE_ORG_UNIT_HEADER)).thenReturn(null);

      assertTrue(service.currentSquadron().isEmpty());
    }
  }

  @Nested
  class CanSeeSquadronTests {

    @Test
    void adminWithoutSelection_canSeeAnySquadron() {
      when(authHelper.isAdmin()).thenReturn(true);
      when(request.getHeader(OwnerScopeService.ACTIVE_ORG_UNIT_HEADER)).thenReturn(null);

      assertTrue(service.canSeeSquadron(SQUADRON_A_ID));
      assertTrue(service.canSeeSquadron(SQUADRON_B_ID));
    }

    @Test
    void adminWithSelection_seesOnlySelectedSquadron() {
      when(authHelper.isAdmin()).thenReturn(true);
      when(request.getHeader(OwnerScopeService.ACTIVE_ORG_UNIT_HEADER))
          .thenReturn(SQUADRON_A_ID.toString());

      assertTrue(service.canSeeSquadron(SQUADRON_A_ID));
      assertFalse(service.canSeeSquadron(SQUADRON_B_ID));
    }

    @Test
    void member_seesOnlyHomeSquadron() {
      lenient().when(authHelper.isAdmin()).thenReturn(false);
      lenient().when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      lenient()
          .when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
          .thenReturn(List.of(staffelMembership(MEMBER_USER_ID, SQUADRON_A_ID)));

      assertTrue(service.canSeeSquadron(SQUADRON_A_ID));
      assertFalse(service.canSeeSquadron(SQUADRON_B_ID));
    }

    @Test
    void member_seesOwnStaffelAndEverySkTheyBelongTo() {
      UUID skId = UUID.randomUUID();
      lenient().when(authHelper.isAdmin()).thenReturn(false);
      lenient().when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      lenient()
          .when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
          .thenReturn(
              List.of(
                  staffelMembership(MEMBER_USER_ID, SQUADRON_A_ID),
                  skMembership(MEMBER_USER_ID, skId)));

      assertTrue(service.canSeeSquadron(SQUADRON_A_ID), "own Staffel");
      assertTrue(service.canSeeSquadron(skId), "own SK — the regression this fixes");
      assertFalse(service.canSeeSquadron(SQUADRON_B_ID), "foreign Staffel stays hidden");
    }

    @Test
    void squadronlessSkMember_seesTheirSk() {
      UUID skId = UUID.randomUUID();
      lenient().when(authHelper.isAdmin()).thenReturn(false);
      lenient().when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      lenient()
          .when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
          .thenReturn(List.of(skMembership(MEMBER_USER_ID, skId)));

      assertTrue(service.canSeeSquadron(skId));
      assertFalse(service.canSeeSquadron(SQUADRON_A_ID));
    }

    @Test
    void nonAdminPinnedToOneMembership_seesOnlyThePin() {
      UUID skId = UUID.randomUUID();
      lenient().when(authHelper.isAdmin()).thenReturn(false);
      lenient().when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      lenient()
          .when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
          .thenReturn(
              List.of(
                  staffelMembership(MEMBER_USER_ID, SQUADRON_A_ID),
                  skMembership(MEMBER_USER_ID, skId)));
      lenient()
          .when(request.getHeader(OwnerScopeService.ACTIVE_ORG_UNIT_HEADER))
          .thenReturn(skId.toString());

      assertTrue(service.canSeeSquadron(skId), "pinned SK is visible");
      assertFalse(service.canSeeSquadron(SQUADRON_A_ID), "the unpinned Staffel is filtered out");
    }

    @Test
    void nonAdminForeignPin_collapsesToMembershipUnion() {
      lenient().when(authHelper.isAdmin()).thenReturn(false);
      lenient().when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      lenient()
          .when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
          .thenReturn(List.of(staffelMembership(MEMBER_USER_ID, SQUADRON_A_ID)));
      lenient()
          .when(request.getHeader(OwnerScopeService.ACTIVE_ORG_UNIT_HEADER))
          .thenReturn(SQUADRON_B_ID.toString());

      assertTrue(service.canSeeSquadron(SQUADRON_A_ID), "own Staffel still visible");
      assertFalse(service.canSeeSquadron(SQUADRON_B_ID), "foreign pin grants nothing");
    }

    @Test
    void anonymousCaller_seesNothing() {
      lenient().when(authHelper.isAdmin()).thenReturn(false);
      lenient().when(authHelper.currentUserId()).thenReturn(Optional.empty());

      assertFalse(service.canSeeSquadron(SQUADRON_A_ID));
      assertFalse(service.canSeeSquadron(SQUADRON_B_ID));
    }
  }

  @Nested
  class CanSeeJobOrderBlueprintOwnersTests {

    private JobOrder orderResponsibleTo(
        de.greluc.krt.profit.basetool.backend.model.OrgUnit responsible) {
      return JobOrder.builder().id(UUID.randomUUID()).responsibleOrgUnit(responsible).build();
    }

    @Test
    void member_ofResponsibleSquadron_canSee() {
      JobOrder order = orderResponsibleTo(squadronA);
      when(jobOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
      lenient().when(authHelper.isAdmin()).thenReturn(false);
      lenient().when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      lenient()
          .when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
          .thenReturn(List.of(staffelMembership(MEMBER_USER_ID, SQUADRON_A_ID)));

      assertTrue(service.canSeeJobOrderBlueprintOwners(order.getId()));
    }

    @Test
    void nonMember_ofResponsibleSquadron_cannotSee() {
      JobOrder order = orderResponsibleTo(squadronB);
      when(jobOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
      lenient().when(authHelper.isAdmin()).thenReturn(false);
      lenient().when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      lenient()
          .when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
          .thenReturn(List.of(staffelMembership(MEMBER_USER_ID, SQUADRON_A_ID)));

      assertFalse(service.canSeeJobOrderBlueprintOwners(order.getId()));
    }

    @Test
    void nonMember_ofResponsibleSk_cannotSee_eventThoughTheOrderItselfIsPublic() {
      UUID skId = UUID.randomUUID();
      SpecialCommand sk = new SpecialCommand();
      sk.setId(skId);
      JobOrder order = orderResponsibleTo(sk);
      when(jobOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
      lenient().when(authHelper.isAdmin()).thenReturn(false);
      lenient().when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      lenient()
          .when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
          .thenReturn(List.of(staffelMembership(MEMBER_USER_ID, SQUADRON_A_ID)));

      assertFalse(service.canSeeJobOrderBlueprintOwners(order.getId()));
    }

    @Test
    void member_ofResponsibleSk_canSee() {
      UUID skId = UUID.randomUUID();
      SpecialCommand sk = new SpecialCommand();
      sk.setId(skId);
      JobOrder order = orderResponsibleTo(sk);
      when(jobOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
      lenient().when(authHelper.isAdmin()).thenReturn(false);
      lenient().when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      lenient()
          .when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
          .thenReturn(List.of(skMembership(MEMBER_USER_ID, skId)));

      assertTrue(service.canSeeJobOrderBlueprintOwners(order.getId()));
    }

    @Test
    void adminWithoutPin_canSeeAnyOrdersCoverage() {
      JobOrder order = orderResponsibleTo(squadronB);
      when(jobOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
      when(authHelper.isAdmin()).thenReturn(true);
      when(request.getHeader(OwnerScopeService.ACTIVE_ORG_UNIT_HEADER)).thenReturn(null);

      assertTrue(service.canSeeJobOrderBlueprintOwners(order.getId()));
    }

    @Test
    void unknownOrder_returnsFalse() {
      UUID missing = UUID.randomUUID();
      when(jobOrderRepository.findById(missing)).thenReturn(Optional.empty());

      assertFalse(service.canSeeJobOrderBlueprintOwners(missing));
    }
  }

  @Nested
  class CanSeeJobOrderInventoryOwnersTests {

    private JobOrder orderResponsibleTo(
        de.greluc.krt.profit.basetool.backend.model.OrgUnit responsible) {
      return JobOrder.builder().id(UUID.randomUUID()).responsibleOrgUnit(responsible).build();
    }

    @Test
    void member_ofResponsibleSquadron_canSeeOwners() {
      JobOrder order = orderResponsibleTo(squadronA);
      when(jobOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
      lenient().when(authHelper.isAdmin()).thenReturn(false);
      lenient().when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      lenient()
          .when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
          .thenReturn(List.of(staffelMembership(MEMBER_USER_ID, SQUADRON_A_ID)));

      assertTrue(service.canSeeJobOrderInventoryOwners(order.getId()));
    }

    @Test
    void nonMember_ofResponsibleSquadron_cannotSeeOwners() {
      JobOrder order = orderResponsibleTo(squadronB);
      when(jobOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
      lenient().when(authHelper.isAdmin()).thenReturn(false);
      lenient().when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      lenient()
          .when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
          .thenReturn(List.of(staffelMembership(MEMBER_USER_ID, SQUADRON_A_ID)));

      assertFalse(service.canSeeJobOrderInventoryOwners(order.getId()));
    }

    @Test
    void requestingSideMember_ofPublicSkOrder_cannotSeeOwners() {
      UUID skId = UUID.randomUUID();
      SpecialCommand sk = new SpecialCommand();
      sk.setId(skId);
      JobOrder order = orderResponsibleTo(sk);
      when(jobOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
      lenient().when(authHelper.isAdmin()).thenReturn(false);
      lenient().when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      lenient()
          .when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
          .thenReturn(List.of(staffelMembership(MEMBER_USER_ID, SQUADRON_A_ID)));

      assertFalse(service.canSeeJobOrderInventoryOwners(order.getId()));
    }

    @Test
    void member_ofResponsibleSk_canSeeOwners() {
      UUID skId = UUID.randomUUID();
      SpecialCommand sk = new SpecialCommand();
      sk.setId(skId);
      JobOrder order = orderResponsibleTo(sk);
      when(jobOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
      lenient().when(authHelper.isAdmin()).thenReturn(false);
      lenient().when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      lenient()
          .when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
          .thenReturn(List.of(skMembership(MEMBER_USER_ID, skId)));

      assertTrue(service.canSeeJobOrderInventoryOwners(order.getId()));
    }

    @Test
    void adminWithoutPin_canSeeAnyOrdersOwners() {
      JobOrder order = orderResponsibleTo(squadronB);
      when(jobOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
      when(authHelper.isAdmin()).thenReturn(true);
      when(request.getHeader(OwnerScopeService.ACTIVE_ORG_UNIT_HEADER)).thenReturn(null);

      assertTrue(service.canSeeJobOrderInventoryOwners(order.getId()));
    }

    @Test
    void unknownOrder_returnsFalse() {
      UUID missing = UUID.randomUUID();
      when(jobOrderRepository.findById(missing)).thenReturn(Optional.empty());

      assertFalse(service.canSeeJobOrderInventoryOwners(missing));
    }
  }

  @Nested
  class CanSeeMissionTests {

    @Test
    void memberSeesOwnSquadronMission() {
      UUID missionId = UUID.randomUUID();
      Mission mission = newMission(missionId, squadronA, false);
      when(missionRepository.findByIdForAuthorization(missionId)).thenReturn(Optional.of(mission));
      stubMemberInSquadronA();

      assertTrue(service.canSeeMission(missionId));
    }

    @Test
    void memberSeesPublicMissionOfOtherSquadron_viaIsInternalEscape() {
      UUID missionId = UUID.randomUUID();
      Mission mission = newMission(missionId, squadronB, false);
      when(missionRepository.findByIdForAuthorization(missionId)).thenReturn(Optional.of(mission));
      stubMemberInSquadronA();

      assertTrue(service.canSeeMission(missionId));
    }

    @Test
    void memberRejectsInternalMissionOfOtherSquadron() {
      UUID missionId = UUID.randomUUID();
      Mission mission = newMission(missionId, squadronB, true);
      when(missionRepository.findByIdForAuthorization(missionId)).thenReturn(Optional.of(mission));
      stubMemberInSquadronA();

      assertFalse(service.canSeeMission(missionId));
    }

    @Test
    void ownerlessPublicMission_isVisibleToEveryone() {
      UUID missionId = UUID.randomUUID();
      Mission mission = newMission(missionId, null, false);
      when(missionRepository.findByIdForAuthorization(missionId)).thenReturn(Optional.of(mission));

      assertTrue(service.canSeeMission(missionId));
    }

    @Test
    void ownerlessInternalMission_isVisibleToMembersOrAbove() {
      UUID missionId = UUID.randomUUID();
      Mission mission = newMission(missionId, null, true);
      when(missionRepository.findByIdForAuthorization(missionId)).thenReturn(Optional.of(mission));
      when(authHelper.isMemberOrAbove()).thenReturn(true);

      assertTrue(service.canSeeMission(missionId));
    }

    @Test
    void ownerlessInternalMission_isHiddenFromOutsiders() {
      UUID missionId = UUID.randomUUID();
      Mission mission = newMission(missionId, null, true);
      when(missionRepository.findByIdForAuthorization(missionId)).thenReturn(Optional.of(mission));
      when(authHelper.isMemberOrAbove()).thenReturn(false);

      assertFalse(service.canSeeMission(missionId));
    }

    @Test
    void unknownMission_returnsFalse() {
      UUID missionId = UUID.randomUUID();
      when(missionRepository.findByIdForAuthorization(missionId)).thenReturn(Optional.empty());

      assertFalse(service.canSeeMission(missionId));
    }
  }

  @Nested
  class CanEditMissionTests {

    @Test
    void memberCannotEditPublicMissionOfOtherSquadron() {
      UUID missionId = UUID.randomUUID();
      Mission mission = newMission(missionId, squadronB, false);
      when(missionRepository.findByIdForAuthorization(missionId)).thenReturn(Optional.of(mission));
      stubMemberInSquadronA();

      assertFalse(service.canEditMission(missionId));
    }

    @Test
    void memberCanEditOwnSquadronMission() {
      UUID missionId = UUID.randomUUID();
      Mission mission = newMission(missionId, squadronA, true);
      when(missionRepository.findByIdForAuthorization(missionId)).thenReturn(Optional.of(mission));
      stubMemberInSquadronA();

      assertTrue(service.canEditMission(missionId));
    }

    @Test
    void ownerlessMission_passesPerRowEditCheck() {
      UUID missionId = UUID.randomUUID();
      Mission mission = newMission(missionId, null, false);
      when(missionRepository.findByIdForAuthorization(missionId)).thenReturn(Optional.of(mission));

      assertTrue(service.canEditMission(missionId));
    }

    @Test
    void unknownMission_returnsFalse() {
      UUID missionId = UUID.randomUUID();
      when(missionRepository.findByIdForAuthorization(missionId)).thenReturn(Optional.empty());

      assertFalse(service.canEditMission(missionId));
    }
  }

  @Nested
  class CanSeeJobOrderTests {

    @Test
    void skResponsibleOrder_isPublicToProfitEligibleViewer() {
      UUID orderId = UUID.randomUUID();
      when(jobOrderRepository.findById(orderId))
          .thenReturn(Optional.of(jobOrderResponsibleTo(orderId, newSpecialCommand())));
      stubMemberInSquadronA();

      assertTrue(service.canSeeJobOrder(orderId));
    }

    @Test
    void skResponsibleOrder_invisibleToNonProfitMember() {
      UUID orderId = UUID.randomUUID();
      when(jobOrderRepository.findById(orderId))
          .thenReturn(Optional.of(jobOrderResponsibleTo(orderId, newSpecialCommand())));
      stubNonProfitMember();

      assertFalse(service.canSeeJobOrder(orderId));
    }

    @Test
    void skResponsibleOrder_visibleToAdmin() {
      UUID orderId = UUID.randomUUID();
      when(jobOrderRepository.findById(orderId))
          .thenReturn(Optional.of(jobOrderResponsibleTo(orderId, newSpecialCommand())));
      when(authHelper.isAdmin()).thenReturn(true);

      assertTrue(service.canSeeJobOrder(orderId));
    }

    @Test
    void squadronResponsibleOrder_visibleToMemberOfThatSquadron() {
      UUID orderId = UUID.randomUUID();
      when(jobOrderRepository.findById(orderId))
          .thenReturn(Optional.of(jobOrderResponsibleTo(orderId, squadronA)));
      stubMemberInSquadronA();

      assertTrue(service.canSeeJobOrder(orderId));
    }

    @Test
    void squadronResponsibleOrder_invisibleToForeignSquadronMember() {
      UUID orderId = UUID.randomUUID();
      when(jobOrderRepository.findById(orderId))
          .thenReturn(Optional.of(jobOrderResponsibleTo(orderId, squadronB)));
      stubMemberInSquadronA();

      assertFalse(service.canSeeJobOrder(orderId));
    }

    @Test
    void squadronResponsibleOrder_visibleToAdminWithoutPin() {
      UUID orderId = UUID.randomUUID();
      when(jobOrderRepository.findById(orderId))
          .thenReturn(Optional.of(jobOrderResponsibleTo(orderId, squadronA)));
      when(authHelper.isAdmin()).thenReturn(true);
      when(request.getHeader(OwnerScopeService.ACTIVE_ORG_UNIT_HEADER)).thenReturn(null);

      assertTrue(service.canSeeJobOrder(orderId));
    }

    @Test
    void squadronResponsibleOrder_invisibleToAdminPinnedToOtherSquadron() {
      UUID orderId = UUID.randomUUID();
      when(jobOrderRepository.findById(orderId))
          .thenReturn(Optional.of(jobOrderResponsibleTo(orderId, squadronA)));
      when(authHelper.isAdmin()).thenReturn(true);
      when(request.getHeader(OwnerScopeService.ACTIVE_ORG_UNIT_HEADER))
          .thenReturn(SQUADRON_B_ID.toString());

      assertFalse(service.canSeeJobOrder(orderId));
    }

    @Test
    void unknownOrder_returnsFalse() {
      UUID orderId = UUID.randomUUID();
      when(jobOrderRepository.findById(orderId)).thenReturn(Optional.empty());

      assertFalse(service.canSeeJobOrder(orderId));
    }
  }

  /**
   * REQ-ORDERS-023: the requester escape — a member of an order's requesting org unit may read it
   * and (while it is still fully undelivered) edit it within limits, independent of the profit gate
   * that blocks the normal {@code canSeeJobOrder}/{@code canEditJobOrder} paths.
   */
  @Nested
  class RequesterEscapeGateTests {

    @Test
    void nonProfitRequester_canSeeOwnRequestedOrder() {
      UUID orderId = UUID.randomUUID();
      var order = jobOrderResponsibleTo(orderId, newSpecialCommand());
      order.setRequestingOrgUnit(squadronA);
      when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
      stubNonProfitMember();

      assertFalse(service.canSeeJobOrder(orderId));
      assertTrue(service.canSeeJobOrderAsRequester(orderId));
    }

    @Test
    void nonProfitMember_cannotSeeForeignRequestedOrder() {
      UUID orderId = UUID.randomUUID();
      var order = jobOrderResponsibleTo(orderId, newSpecialCommand());
      order.setRequestingOrgUnit(squadronB);
      when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
      stubNonProfitMember();

      assertFalse(service.canSeeJobOrderAsRequester(orderId));
    }

    @Test
    void nonProfitRequester_canEditOwnUndeliveredOrder() {
      UUID orderId = UUID.randomUUID();
      var order = jobOrderResponsibleTo(orderId, newSpecialCommand());
      order.setRequestingOrgUnit(squadronA);
      when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
      stubNonProfitMember();

      assertFalse(service.canEditJobOrder(orderId));
      assertTrue(service.canEditJobOrderAsRequester(orderId));
    }

    @Test
    void requester_cannotEditOwnOrder_onceDelivered() {
      UUID orderId = UUID.randomUUID();
      var order = jobOrderResponsibleTo(orderId, newSpecialCommand());
      order.setRequestingOrgUnit(squadronA);
      when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
      stubNonProfitMember();
      when(jobOrderHandoverRepository.existsByJobOrderId(orderId)).thenReturn(true);

      assertFalse(service.canEditJobOrderAsRequester(orderId));
    }

    @Test
    void nonProfitMember_cannotEditForeignRequestedOrder() {
      UUID orderId = UUID.randomUUID();
      var order = jobOrderResponsibleTo(orderId, newSpecialCommand());
      order.setRequestingOrgUnit(squadronB);
      when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
      stubNonProfitMember();

      assertFalse(service.canEditJobOrderAsRequester(orderId));
    }
  }

  @Nested
  class CanEditJobOrderTests {

    @Test
    void skResponsibleOrder_isOpenToTheRoleGate_forProfitEligibleCaller() {
      UUID orderId = UUID.randomUUID();
      when(jobOrderRepository.findById(orderId))
          .thenReturn(Optional.of(jobOrderResponsibleTo(orderId, newSpecialCommand())));
      stubMemberInSquadronA();

      assertTrue(service.canEditJobOrder(orderId));
    }

    @Test
    void skResponsibleOrder_notEditableByNonProfitMember() {
      UUID orderId = UUID.randomUUID();
      when(jobOrderRepository.findById(orderId))
          .thenReturn(Optional.of(jobOrderResponsibleTo(orderId, newSpecialCommand())));
      stubNonProfitMember();

      assertFalse(service.canEditJobOrder(orderId));
    }

    @Test
    void skResponsibleOrder_editableByAdmin() {
      UUID orderId = UUID.randomUUID();
      when(jobOrderRepository.findById(orderId))
          .thenReturn(Optional.of(jobOrderResponsibleTo(orderId, newSpecialCommand())));
      when(authHelper.isAdmin()).thenReturn(true);

      assertTrue(service.canEditJobOrder(orderId));
    }

    @Test
    void squadronResponsibleOrder_editableByMemberOfThatSquadron() {
      UUID orderId = UUID.randomUUID();
      when(jobOrderRepository.findById(orderId))
          .thenReturn(Optional.of(jobOrderResponsibleTo(orderId, squadronA)));
      stubMemberInSquadronA();

      assertTrue(service.canEditJobOrder(orderId));
    }

    @Test
    void squadronResponsibleOrder_notEditableByForeignSquadronMember() {
      UUID orderId = UUID.randomUUID();
      when(jobOrderRepository.findById(orderId))
          .thenReturn(Optional.of(jobOrderResponsibleTo(orderId, squadronB)));
      stubMemberInSquadronA();

      assertFalse(service.canEditJobOrder(orderId));
    }

    @Test
    void unknownOrder_returnsFalse() {
      UUID orderId = UUID.randomUUID();
      when(jobOrderRepository.findById(orderId)).thenReturn(Optional.empty());

      assertFalse(service.canEditJobOrder(orderId));
    }
  }

  @Nested
  class CanViewJobOrdersTests {

    @Test
    void admin_canAlwaysView() {
      when(authHelper.isAdmin()).thenReturn(true);

      assertTrue(service.canViewJobOrders());
    }

    @Test
    void memberOfProfitEligibleOrgUnit_canView() {
      stubMemberInSquadronA();

      assertTrue(service.canViewJobOrders());
    }

    @Test
    void memberOfOnlyNonProfitOrgUnit_cannotView() {
      stubNonProfitMember();

      assertFalse(service.canViewJobOrders());
    }

    @Test
    void callerWithNoMembership_cannotView() {
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID)).thenReturn(List.of());

      assertFalse(service.canViewJobOrders());
    }

    @Test
    void anonymousCaller_cannotView() {
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.empty());

      assertFalse(service.canViewJobOrders());
    }
  }

  @Nested
  class CanSeeInventoryItemTests {

    @Test
    void memberSeesOwnSquadronInventoryItem() {
      UUID itemId = UUID.randomUUID();
      InventoryItem item = new InventoryItem();
      item.setId(itemId);
      item.setOwningOrgUnit(squadronA);
      when(inventoryItemRepository.findById(itemId)).thenReturn(Optional.of(item));
      stubMemberInSquadronA();

      assertTrue(service.canSeeInventoryItem(itemId));
    }

    @Test
    void memberRejectsForeignSquadronInventoryItem() {
      UUID itemId = UUID.randomUUID();
      InventoryItem item = new InventoryItem();
      item.setId(itemId);
      item.setOwningOrgUnit(squadronB);
      when(inventoryItemRepository.findById(itemId)).thenReturn(Optional.of(item));
      stubMemberInSquadronA();

      assertFalse(service.canSeeInventoryItem(itemId));
    }

    @Test
    void unknownItem_returnsFalse() {
      UUID itemId = UUID.randomUUID();
      when(inventoryItemRepository.findById(itemId)).thenReturn(Optional.empty());

      assertFalse(service.canSeeInventoryItem(itemId));
    }
  }

  /**
   * REQ-ORG-011 owner-retains-access escape: the per-user owner of a personal aggregate (inventory
   * item, ship, refinery order) may always see and edit it, even when the row is still stamped to
   * an org unit the owner no longer belongs to (org-unit switch, or loss of the last membership). A
   * non-owner stays bound by the strict owning-org-unit scope. Mirrors the service-layer owner
   * check so the {@code @PreAuthorize} gate never denies a write the service would accept.
   */
  @Nested
  class PersonalAggregateOwnerRetainsAccessTests {

    private InventoryItem ownedItem(User owner, Squadron owningOrgUnit) {
      InventoryItem item = new InventoryItem();
      item.setId(UUID.randomUUID());
      item.setUser(owner);
      item.setOwningOrgUnit(owningOrgUnit);
      return item;
    }

    private Ship ownedShip(User owner, Squadron owningOrgUnit) {
      Ship ship = new Ship();
      ship.setId(UUID.randomUUID());
      ship.setOwner(owner);
      ship.setOwningOrgUnit(owningOrgUnit);
      return ship;
    }

    private RefineryOrder ownedRefineryOrder(User owner, Squadron owningOrgUnit) {
      RefineryOrder order = new RefineryOrder();
      order.setId(UUID.randomUUID());
      order.setOwner(owner);
      order.setOwningOrgUnit(owningOrgUnit);
      return order;
    }

    @Test
    void owner_retainsAccessToOwnItemStampedToForeignOrgUnit() {
      InventoryItem item = ownedItem(memberUserInA, squadronB);
      when(inventoryItemRepository.findById(item.getId())).thenReturn(Optional.of(item));
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));

      assertTrue(
          service.canSeeInventoryItem(item.getId()),
          "owner sees their own item even when it is stamped to a foreign org unit");
      assertTrue(
          service.canEditInventoryItem(item.getId()),
          "owner edits their own item even when it is stamped to a foreign org unit");
    }

    @Test
    void owner_retainsAccessAfterLosingAllMembershipsWhileItemStaysStamped() {
      InventoryItem item = ownedItem(memberUserInA, squadronA);
      when(inventoryItemRepository.findById(item.getId())).thenReturn(Optional.of(item));
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));

      assertTrue(service.canSeeInventoryItem(item.getId()), "owner sees their own stamped item");
      assertTrue(service.canEditInventoryItem(item.getId()), "owner edits their own stamped item");
    }

    @Test
    void nonOwnerOutsideScope_isStillDeniedEvenWhenTheItemHasAnOwner() {
      User otherOwner = new User();
      otherOwner.setId(UUID.randomUUID());
      InventoryItem item = ownedItem(otherOwner, squadronB);
      when(inventoryItemRepository.findById(item.getId())).thenReturn(Optional.of(item));
      stubMemberInSquadronA();

      assertFalse(
          service.canSeeInventoryItem(item.getId()),
          "a non-owner member of another org unit must not see the item");
      assertFalse(
          service.canEditInventoryItem(item.getId()),
          "a non-owner member of another org unit must not edit the item");
    }

    @Test
    void owner_retainsAccessToOwnShipStampedToForeignOrgUnit() {
      Ship ship = ownedShip(memberUserInA, squadronB);
      when(shipRepository.findById(ship.getId())).thenReturn(Optional.of(ship));
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));

      assertTrue(service.canSeeShip(ship.getId()), "owner sees their own foreign-org ship");
      assertTrue(service.canEditShip(ship.getId()), "owner edits their own foreign-org ship");
    }

    @Test
    void nonOwnerOutsideScope_isStillDeniedForShip() {
      User otherOwner = new User();
      otherOwner.setId(UUID.randomUUID());
      Ship ship = ownedShip(otherOwner, squadronB);
      when(shipRepository.findById(ship.getId())).thenReturn(Optional.of(ship));
      stubMemberInSquadronA();

      assertFalse(service.canSeeShip(ship.getId()), "a non-owner outside scope must not see it");
      assertFalse(service.canEditShip(ship.getId()), "a non-owner outside scope must not edit it");
    }

    @Test
    void owner_retainsAccessToOwnRefineryOrderStampedToForeignOrgUnit() {
      RefineryOrder order = ownedRefineryOrder(memberUserInA, squadronB);
      when(refineryOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));

      assertTrue(
          service.canSeeRefineryOrder(order.getId()), "owner sees their own foreign-org order");
      assertTrue(
          service.canEditRefineryOrder(order.getId()), "owner edits their own foreign-org order");
    }

    @Test
    void nonOwnerOutsideScope_isStillDeniedForRefineryOrder() {
      User otherOwner = new User();
      otherOwner.setId(UUID.randomUUID());
      RefineryOrder order = ownedRefineryOrder(otherOwner, squadronB);
      when(refineryOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
      stubMemberInSquadronA();

      assertFalse(
          service.canSeeRefineryOrder(order.getId()), "a non-owner outside scope must not see it");
      assertFalse(
          service.canEditRefineryOrder(order.getId()),
          "a non-owner outside scope must not edit it");
    }
  }

  @Nested
  class CanSeeRefineryOrderAndOperationTests {

    @Test
    void memberRejectsForeignSquadronRefineryOrder() {
      UUID orderId = UUID.randomUUID();
      RefineryOrder order = new RefineryOrder();
      order.setId(orderId);
      order.setOwningOrgUnit(squadronB);
      when(refineryOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
      stubMemberInSquadronA();

      assertFalse(service.canSeeRefineryOrder(orderId));
      assertFalse(service.canEditRefineryOrder(orderId));
    }

    @Test
    void memberAcceptsOwnSquadronRefineryOrder() {
      UUID orderId = UUID.randomUUID();
      RefineryOrder order = new RefineryOrder();
      order.setId(orderId);
      order.setOwningOrgUnit(squadronA);
      when(refineryOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
      stubMemberInSquadronA();

      assertTrue(service.canSeeRefineryOrder(orderId));
      assertTrue(service.canEditRefineryOrder(orderId));
    }

    @Test
    void memberRejectsForeignSquadronOperation() {
      UUID opId = UUID.randomUUID();
      Operation op = new Operation();
      op.setId(opId);
      op.setOwningOrgUnit(squadronB);
      when(operationRepository.findById(opId)).thenReturn(Optional.of(op));
      stubMemberInSquadronA();

      assertFalse(service.canSeeOperation(opId));
      assertFalse(service.canEditOperation(opId));
    }

    @Test
    void ownerlessOperation_isVisibleToMembersOrAbove() {
      UUID opId = UUID.randomUUID();
      Operation op = new Operation();
      op.setId(opId);
      op.setOwningOrgUnit(null);
      when(operationRepository.findById(opId)).thenReturn(Optional.of(op));
      when(authHelper.isMemberOrAbove()).thenReturn(true);

      assertTrue(service.canSeeOperation(opId));
    }

    @Test
    void ownerlessOperation_isHiddenFromGuestsAndAnonymous() {
      UUID opId = UUID.randomUUID();
      Operation op = new Operation();
      op.setId(opId);
      op.setOwningOrgUnit(null);
      when(operationRepository.findById(opId)).thenReturn(Optional.of(op));
      when(authHelper.isMemberOrAbove()).thenReturn(false);

      assertFalse(service.canSeeOperation(opId));
    }

    @Test
    void ownerlessOperation_passesPerRowEditCheck() {
      UUID opId = UUID.randomUUID();
      Operation op = new Operation();
      op.setId(opId);
      op.setOwningOrgUnit(null);
      when(operationRepository.findById(opId)).thenReturn(Optional.of(op));

      assertTrue(service.canEditOperation(opId));
    }

    @Test
    void participantSeesOperationOfForeignSquadron() {
      UUID opId = UUID.randomUUID();
      Operation op = new Operation();
      op.setId(opId);
      op.setOwningOrgUnit(squadronB);
      when(operationRepository.findById(opId)).thenReturn(Optional.of(op));
      stubMemberInSquadronA();
      when(operationRepository.existsParticipantUserInOperation(opId, MEMBER_USER_ID))
          .thenReturn(true);

      assertTrue(service.canSeeOperation(opId), "participant may view a foreign-Staffel operation");
      assertFalse(service.canEditOperation(opId), "participation grants view only, not edit");
    }

    @Test
    void adminInAllSquadronsMode_seesEveryAggregate() {
      UUID orderId = UUID.randomUUID();
      RefineryOrder order = new RefineryOrder();
      order.setId(orderId);
      order.setOwningOrgUnit(squadronB);
      when(refineryOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
      when(authHelper.isAdmin()).thenReturn(true);
      when(request.getHeader(OwnerScopeService.ACTIVE_ORG_UNIT_HEADER)).thenReturn(null);

      assertTrue(service.canSeeRefineryOrder(orderId));
      assertTrue(service.canEditRefineryOrder(orderId));
    }
  }

  @Nested
  class CanActOnUserRefineryOrdersTests {

    @Test
    void admin_canViewAndManageAnyUsersRefineryOrders() {
      UUID targetUserId = UUID.randomUUID();
      when(authHelper.isAdmin()).thenReturn(true);

      assertTrue(service.canViewUserRefineryOrders(targetUserId));
      assertTrue(service.canManageUserRefineryOrders(targetUserId));
    }

    @Test
    void self_canViewOwnRefineryOrders_withoutScopeCheck() {
      lenient().when(authHelper.isAdmin()).thenReturn(false);
      lenient().when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));

      assertTrue(service.canViewUserRefineryOrders(MEMBER_USER_ID));
      assertTrue(service.canManageUserRefineryOrders(MEMBER_USER_ID));
    }

    @Test
    void logisticianInTargetsSquadron_canViewAndManage() {
      UUID targetUserId = UUID.randomUUID();
      lenient().when(authHelper.isAdmin()).thenReturn(false);
      lenient().when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      lenient()
          .when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
          .thenReturn(List.of(staffelMembership(MEMBER_USER_ID, SQUADRON_A_ID)));
      lenient()
          .when(orgUnitMembershipRepository.findAllByIdUserId(targetUserId))
          .thenReturn(List.of(staffelMembership(targetUserId, SQUADRON_A_ID)));

      assertTrue(service.canViewUserRefineryOrders(targetUserId));
      assertTrue(service.canManageUserRefineryOrders(targetUserId));
    }

    @Test
    void logisticianOutsideTargetsScope_isDenied() {
      UUID targetUserId = UUID.randomUUID();
      lenient().when(authHelper.isAdmin()).thenReturn(false);
      lenient().when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      lenient()
          .when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
          .thenReturn(List.of(staffelMembership(MEMBER_USER_ID, SQUADRON_A_ID)));
      lenient()
          .when(orgUnitMembershipRepository.findAllByIdUserId(targetUserId))
          .thenReturn(List.of(staffelMembership(targetUserId, SQUADRON_B_ID)));

      assertFalse(service.canViewUserRefineryOrders(targetUserId));
      assertFalse(service.canManageUserRefineryOrders(targetUserId));
    }
  }

  /**
   * Null-owner gate behaviour for the three ownerless-personal-aggregate roots (ship, refinery
   * order, inventory item). A row with {@code owningOrgUnit == null} is reachable only by its own
   * owning user or by an admin in all-scopes mode — never by a foreign user or a pinned admin. See
   * {@code OwnerScopeService.canAccessOwnerlessPersonalRow}.
   */
  @Nested
  class OwnerlessPersonalAggregateGateTests {

    private Ship ownerlessShip(User owner) {
      Ship ship = new Ship();
      ship.setId(UUID.randomUUID());
      ship.setOwner(owner);
      ship.setOwningOrgUnit(null);
      return ship;
    }

    private RefineryOrder ownerlessRefineryOrder(User owner) {
      RefineryOrder order = new RefineryOrder();
      order.setId(UUID.randomUUID());
      order.setOwner(owner);
      order.setOwningOrgUnit(null);
      return order;
    }

    private InventoryItem ownerlessInventoryItem(User owner) {
      InventoryItem item = new InventoryItem();
      item.setId(UUID.randomUUID());
      item.setUser(owner);
      item.setOwningOrgUnit(null);
      return item;
    }

    @Test
    void owner_seesAndEditsOwnOwnerlessShip() {
      Ship ship = ownerlessShip(memberUserInA);
      when(shipRepository.findById(ship.getId())).thenReturn(Optional.of(ship));
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));

      assertTrue(service.canSeeShip(ship.getId()));
      assertTrue(service.canEditShip(ship.getId()));
    }

    @Test
    void adminInAllScopesMode_seesOwnerlessShip() {
      Ship ship = ownerlessShip(memberUserInA);
      when(shipRepository.findById(ship.getId())).thenReturn(Optional.of(ship));
      when(authHelper.isAdmin()).thenReturn(true);
      when(request.getHeader(OwnerScopeService.ACTIVE_ORG_UNIT_HEADER)).thenReturn(null);

      assertTrue(service.canSeeShip(ship.getId()));
      assertTrue(service.canEditShip(ship.getId()));
    }

    @Test
    void adminPinnedToSquadron_doesNotSeeOwnerlessShip() {
      Ship ship = ownerlessShip(memberUserInA);
      when(shipRepository.findById(ship.getId())).thenReturn(Optional.of(ship));
      when(authHelper.isAdmin()).thenReturn(true);
      when(request.getHeader(OwnerScopeService.ACTIVE_ORG_UNIT_HEADER))
          .thenReturn(SQUADRON_A_ID.toString());
      when(authHelper.currentUserId()).thenReturn(Optional.of(UUID.randomUUID()));

      assertFalse(service.canSeeShip(ship.getId()));
      assertFalse(service.canEditShip(ship.getId()));
    }

    @Test
    void owner_seesAndEditsOwnOwnerlessRefineryOrder() {
      RefineryOrder order = ownerlessRefineryOrder(memberUserInA);
      when(refineryOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));

      assertTrue(service.canSeeRefineryOrder(order.getId()));
      assertTrue(service.canEditRefineryOrder(order.getId()));
    }

    @Test
    void foreignUser_cannotSeeOwnerlessRefineryOrder() {
      RefineryOrder order = ownerlessRefineryOrder(memberUserInA);
      when(refineryOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(UUID.randomUUID()));

      assertFalse(service.canSeeRefineryOrder(order.getId()));
      assertFalse(service.canEditRefineryOrder(order.getId()));
    }

    @Test
    void owner_seesAndEditsOwnOwnerlessInventoryItem() {
      InventoryItem item = ownerlessInventoryItem(memberUserInA);
      when(inventoryItemRepository.findById(item.getId())).thenReturn(Optional.of(item));
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));

      assertTrue(service.canSeeInventoryItem(item.getId()));
      assertTrue(service.canEditInventoryItem(item.getId()));
    }

    @Test
    void foreignUser_cannotSeeOwnerlessInventoryItem() {
      InventoryItem item = ownerlessInventoryItem(memberUserInA);
      when(inventoryItemRepository.findById(item.getId())).thenReturn(Optional.of(item));
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(UUID.randomUUID()));

      assertFalse(service.canSeeInventoryItem(item.getId()));
      assertFalse(service.canEditInventoryItem(item.getId()));
    }
  }

  /**
   * Verifies the request-scoped memoisation on {@link OwnerScopeService#currentSquadronId()} and
   * {@link OwnerScopeService#currentSquadron()}. Without this, every controller call chain on a
   * non-admin request would re-hit the {@code org_unit_membership} lookup and {@code
   * orgUnitRepository.findById} once per scope query.
   */
  @Nested
  class RequestScopedCacheTests {

    private Map<String, Object> requestAttributes;

    @BeforeEach
    void backRequestAttributesWithMap() {
      requestAttributes = new HashMap<>();
      lenient()
          .when(request.getAttribute(anyString()))
          .thenAnswer(inv -> requestAttributes.get(inv.getArgument(0, String.class)));
      lenient()
          .doAnswer(
              inv -> {
                requestAttributes.put(inv.getArgument(0), inv.getArgument(1));
                return null;
              })
          .when(request)
          .setAttribute(anyString(), any());
    }

    @Test
    void currentSquadronId_nonAdmin_calledTwice_hitsMembershipRepoOnce() {
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserIdAndKind(
              MEMBER_USER_ID, OrgUnitKind.SQUADRON))
          .thenReturn(List.of(staffelMembership(MEMBER_USER_ID, SQUADRON_A_ID)));

      Optional<UUID> first = service.currentSquadronId();
      Optional<UUID> second = service.currentSquadronId();

      assertEquals(Optional.of(SQUADRON_A_ID), first);
      assertEquals(first, second);
      verify(orgUnitMembershipRepository, times(1))
          .findAllByIdUserIdAndKind(MEMBER_USER_ID, OrgUnitKind.SQUADRON);
    }

    @Test
    void currentSquadron_calledTwice_hitsSquadronRepoOnce() {
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserIdAndKind(
              MEMBER_USER_ID, OrgUnitKind.SQUADRON))
          .thenReturn(List.of(staffelMembership(MEMBER_USER_ID, SQUADRON_A_ID)));
      when(orgUnitRepository.findById(SQUADRON_A_ID)).thenReturn(Optional.of(squadronA));

      Optional<Squadron> first = service.currentSquadron();
      Optional<Squadron> second = service.currentSquadron();

      assertEquals(Optional.of(squadronA), first);
      assertEquals(first, second);
      verify(orgUnitRepository, times(1)).findById(SQUADRON_A_ID);
    }

    @Test
    void canSeeSquadron_calledTwice_hitsMembershipRepoOnce() {
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
          .thenReturn(List.of(staffelMembership(MEMBER_USER_ID, SQUADRON_A_ID)));

      assertTrue(service.canSeeSquadron(SQUADRON_A_ID));
      assertFalse(service.canSeeSquadron(SQUADRON_B_ID));

      verify(orgUnitMembershipRepository, times(1)).findAllByIdUserId(MEMBER_USER_ID);
    }

    @Test
    void blueprintGateAndOversightScopes_shareOneMembershipRead() {
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.hasReachableRole("ROLE_OFFICER")).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
          .thenReturn(List.of(staffelMembership(MEMBER_USER_ID, SQUADRON_A_ID)));
      when(request.getHeader(OwnerScopeService.ACTIVE_ORG_UNIT_HEADER)).thenReturn(null);

      service.canAccessBlueprintOverview();
      service.currentOversightScope();
      service.currentOwnLevelOversightScope();

      verify(orgUnitMembershipRepository, times(1)).findAllByIdUserId(MEMBER_USER_ID);
    }

    @Test
    void canViewJobOrders_calledTwice_runsProfitEligibilityCountOnce() {
      stubMemberInSquadronA();

      assertTrue(service.canViewJobOrders());
      assertTrue(service.canViewJobOrders());

      verify(orgUnitRepository, times(1)).countProfitEligibleByIdIn(any());
    }
  }

  @Test
  void resolveSquadronForPickerOutput_singleStaffelOnlyMembership_nullPicker_autoStamps() {
    Squadron homeStaffel = new Squadron();
    UUID homeStaffelId = UUID.randomUUID();
    homeStaffel.setId(homeStaffelId);
    User user = new User();
    user.setId(UUID.randomUUID());
    when(orgUnitMembershipRepository.findAllByIdUserId(user.getId()))
        .thenReturn(List.of(staffelMembership(user.getId(), homeStaffelId)));
    when(orgUnitRepository.findById(homeStaffelId)).thenReturn(Optional.of(homeStaffel));

    Squadron result = service.resolveSquadronForPickerOutput(user, null);

    assertSame(homeStaffel, result);
  }

  @Test
  void resolveSquadronForPickerOutput_noMembershipAtAll_throwsBadRequest() {
    User user = new User();
    user.setId(UUID.randomUUID());
    when(orgUnitMembershipRepository.findAllByIdUserId(user.getId())).thenReturn(List.of());

    BadRequestException ex =
        assertThrows(
            BadRequestException.class, () -> service.resolveSquadronForPickerOutput(user, null));
    assertTrue(ex.getMessage().toLowerCase().contains("no org-unit membership"), ex.getMessage());
    verify(orgUnitRepository, never()).findById(any());
  }

  @Test
  void resolveSquadronForPickerOutput_multipleMemberships_nullPicker_throwsOwnerRequired() {
    Squadron homeStaffel = new Squadron();
    UUID homeStaffelId = UUID.randomUUID();
    homeStaffel.setId(homeStaffelId);
    User user = new User();
    user.setId(UUID.randomUUID());
    when(orgUnitMembershipRepository.findAllByIdUserId(user.getId()))
        .thenReturn(
            List.of(
                staffelMembership(user.getId(), homeStaffelId),
                skMembership(user.getId(), UUID.randomUUID())));

    OwnerOrgUnitRequiredException ex =
        assertThrows(
            OwnerOrgUnitRequiredException.class,
            () -> service.resolveSquadronForPickerOutput(user, null));
    assertTrue(
        ex.getMessage().toLowerCase().contains("owningorgunitid is required"), ex.getMessage());
    assertEquals("OWNER_ORG_UNIT_REQUIRED", ex.code());
    verify(orgUnitRepository, never()).findById(any());
  }

  @Test
  void resolveSquadronForPickerOutput_multiMembership_nullPicker_withPin_stampsPinnedStaffel() {
    Squadron staffelA = new Squadron();
    UUID staffelAId = UUID.randomUUID();
    staffelA.setId(staffelAId);
    UUID staffelBId = UUID.randomUUID();
    User user = new User();
    user.setId(UUID.randomUUID());
    when(orgUnitMembershipRepository.findAllByIdUserId(user.getId()))
        .thenReturn(
            List.of(
                staffelMembership(user.getId(), staffelAId),
                staffelMembership(user.getId(), staffelBId)));
    when(request.getHeader(OwnerScopeService.ACTIVE_ORG_UNIT_HEADER))
        .thenReturn(staffelAId.toString());
    when(orgUnitRepository.findById(staffelAId)).thenReturn(Optional.of(staffelA));

    Squadron result = service.resolveSquadronForPickerOutput(user, null);

    assertSame(staffelA, result, "the pinned Staffel is stamped without an explicit pick");
  }

  @Test
  void resolveSquadronForPickerOutput_multiMembership_nullPicker_foreignPin_stillThrows() {
    UUID staffelAId = UUID.randomUUID();
    UUID staffelBId = UUID.randomUUID();
    User user = new User();
    user.setId(UUID.randomUUID());
    when(orgUnitMembershipRepository.findAllByIdUserId(user.getId()))
        .thenReturn(
            List.of(
                staffelMembership(user.getId(), staffelAId),
                staffelMembership(user.getId(), staffelBId)));
    when(request.getHeader(OwnerScopeService.ACTIVE_ORG_UNIT_HEADER))
        .thenReturn(UUID.randomUUID().toString());

    assertThrows(
        OwnerOrgUnitRequiredException.class,
        () -> service.resolveSquadronForPickerOutput(user, null));
    verify(orgUnitRepository, never()).findById(any());
  }

  @Test
  void resolveOrgUnitForPickerOutput_multiMembership_nullPicker_withPin_stampsPinned() {
    Squadron staffelA = new Squadron();
    UUID staffelAId = UUID.randomUUID();
    staffelA.setId(staffelAId);
    UUID staffelBId = UUID.randomUUID();
    User user = new User();
    user.setId(UUID.randomUUID());
    when(orgUnitMembershipRepository.findAllByIdUserId(user.getId()))
        .thenReturn(
            List.of(
                staffelMembership(user.getId(), staffelAId),
                staffelMembership(user.getId(), staffelBId)));
    when(request.getHeader(OwnerScopeService.ACTIVE_ORG_UNIT_HEADER))
        .thenReturn(staffelAId.toString());
    when(orgUnitRepository.findById(staffelAId)).thenReturn(Optional.of(staffelA));

    var result = service.resolveOrgUnitForPickerOutput(user, null);

    assertSame(staffelA, result);
  }

  @Test
  void resolveSquadronForPickerOutput_validStaffelPick_returnsPickedSquadron() {
    Squadron homeStaffel = new Squadron();
    UUID homeStaffelId = UUID.randomUUID();
    homeStaffel.setId(homeStaffelId);
    User user = new User();
    user.setId(UUID.randomUUID());
    when(orgUnitMembershipRepository.findAllByIdUserId(user.getId()))
        .thenReturn(List.of(staffelMembership(user.getId(), homeStaffelId)));
    when(orgUnitRepository.findById(homeStaffelId)).thenReturn(Optional.of(homeStaffel));

    Squadron result = service.resolveSquadronForPickerOutput(user, homeStaffelId);

    assertSame(homeStaffel, result, "the picked Staffel must be returned verbatim");
  }

  @Test
  void resolveSquadronForPickerOutput_validMultiMembershipStaffelPick_returnsPickedSquadron() {
    Squadron homeStaffel = new Squadron();
    UUID homeStaffelId = UUID.randomUUID();
    homeStaffel.setId(homeStaffelId);
    User user = new User();
    user.setId(UUID.randomUUID());

    when(orgUnitMembershipRepository.findAllByIdUserId(user.getId()))
        .thenReturn(
            List.of(
                staffelMembership(user.getId(), homeStaffelId),
                skMembership(user.getId(), UUID.randomUUID())));
    when(orgUnitRepository.findById(homeStaffelId)).thenReturn(Optional.of(homeStaffel));

    Squadron result = service.resolveSquadronForPickerOutput(user, homeStaffelId);

    assertSame(homeStaffel, result);
  }

  @Test
  void resolveSquadronForPickerOutput_foreignOrgUnitChoice_throwsBadRequest() {
    UUID homeStaffelId = UUID.randomUUID();
    User user = new User();
    user.setId(UUID.randomUUID());
    when(orgUnitMembershipRepository.findAllByIdUserId(user.getId()))
        .thenReturn(List.of(staffelMembership(user.getId(), homeStaffelId)));
    UUID foreignId = UUID.randomUUID();

    BadRequestException ex =
        assertThrows(
            BadRequestException.class,
            () -> service.resolveSquadronForPickerOutput(user, foreignId));
    assertTrue(ex.getMessage().toLowerCase().contains("not a membership"), ex.getMessage());
    verify(orgUnitRepository, never()).findById(any());
  }

  @Test
  void resolveSquadronForPickerOutput_pickedOrgUnitIsSpecialCommand_throwsBadRequest() {
    UUID homeStaffelId = UUID.randomUUID();
    User user = new User();
    user.setId(UUID.randomUUID());

    UUID skId = UUID.randomUUID();
    de.greluc.krt.profit.basetool.backend.model.SpecialCommand sk =
        new de.greluc.krt.profit.basetool.backend.model.SpecialCommand();
    sk.setId(skId);
    when(orgUnitMembershipRepository.findAllByIdUserId(user.getId()))
        .thenReturn(
            List.of(
                staffelMembership(user.getId(), homeStaffelId), skMembership(user.getId(), skId)));
    when(orgUnitRepository.findById(skId)).thenReturn(Optional.of(sk));

    BadRequestException ex =
        assertThrows(
            BadRequestException.class, () -> service.resolveSquadronForPickerOutput(user, skId));
    assertTrue(
        ex.getMessage().toLowerCase().contains("spezialkommando ownership"), ex.getMessage());
  }

  @Test
  void resolveOrgUnitForPickerOutput_singleStaffelOnlyMembership_nullPicker_returnsStaffel() {
    Squadron homeStaffel = new Squadron();
    UUID homeStaffelId = UUID.randomUUID();
    homeStaffel.setId(homeStaffelId);
    User user = new User();
    user.setId(UUID.randomUUID());
    when(orgUnitMembershipRepository.findAllByIdUserId(user.getId()))
        .thenReturn(List.of(staffelMembership(user.getId(), homeStaffelId)));
    when(orgUnitRepository.findById(homeStaffelId)).thenReturn(Optional.of(homeStaffel));

    de.greluc.krt.profit.basetool.backend.model.OrgUnit result =
        service.resolveOrgUnitForPickerOutput(user, null);

    assertSame(homeStaffel, result);
    verify(orgUnitRepository).findById(homeStaffelId);
  }

  @Test
  void resolveOrgUnitForPickerOutput_pickedSpecialCommand_isHonoured() {
    UUID homeStaffelId = UUID.randomUUID();
    User user = new User();
    user.setId(UUID.randomUUID());

    UUID skId = UUID.randomUUID();
    de.greluc.krt.profit.basetool.backend.model.SpecialCommand sk =
        new de.greluc.krt.profit.basetool.backend.model.SpecialCommand();
    sk.setId(skId);
    sk.setName("Alpha");

    when(orgUnitMembershipRepository.findAllByIdUserId(user.getId()))
        .thenReturn(
            List.of(
                staffelMembership(user.getId(), homeStaffelId), skMembership(user.getId(), skId)));
    when(orgUnitRepository.findById(skId)).thenReturn(Optional.of(sk));

    de.greluc.krt.profit.basetool.backend.model.OrgUnit result =
        service.resolveOrgUnitForPickerOutput(user, skId);

    assertSame(sk, result);
  }

  @Test
  void resolveOrgUnitForPickerOutput_foreignOrgUnitChoice_throwsBadRequest() {
    UUID homeStaffelId = UUID.randomUUID();
    User user = new User();
    user.setId(UUID.randomUUID());
    UUID foreignId = UUID.randomUUID();
    when(orgUnitMembershipRepository.findAllByIdUserId(user.getId()))
        .thenReturn(List.of(staffelMembership(user.getId(), homeStaffelId)));

    BadRequestException ex =
        assertThrows(
            BadRequestException.class,
            () -> service.resolveOrgUnitForPickerOutput(user, foreignId));
    assertTrue(ex.getMessage().toLowerCase().contains("editable scope"), ex.getMessage());
  }

  @Test
  void resolveOrgUnitForPickerOutput_noMembershipAtAll_throwsBadRequest() {
    User user = new User();
    user.setId(UUID.randomUUID());
    when(orgUnitMembershipRepository.findAllByIdUserId(user.getId())).thenReturn(List.of());

    BadRequestException ex =
        assertThrows(
            BadRequestException.class, () -> service.resolveOrgUnitForPickerOutput(user, null));
    assertTrue(ex.getMessage().toLowerCase().contains("no org-unit membership"), ex.getMessage());
  }

  @Test
  void resolveOrgUnitForPickerOutputNullable_noMembership_nullPicker_returnsNull() {
    User user = new User();
    user.setId(UUID.randomUUID());
    when(orgUnitMembershipRepository.findAllByIdUserId(user.getId())).thenReturn(List.of());

    assertNull(service.resolveOrgUnitForPickerOutputNullable(user, null));
  }

  @Test
  void resolveOrgUnitForPickerOutputNullable_noMembership_withPicker_throwsBadRequest() {
    User user = new User();
    user.setId(UUID.randomUUID());
    when(orgUnitMembershipRepository.findAllByIdUserId(user.getId())).thenReturn(List.of());

    BadRequestException ex =
        assertThrows(
            BadRequestException.class,
            () -> service.resolveOrgUnitForPickerOutputNullable(user, UUID.randomUUID()));
    assertTrue(ex.getMessage().toLowerCase().contains("not a membership"), ex.getMessage());
  }

  @Test
  void resolveOrgUnitForPickerOutputNullable_singleMembership_nullPicker_autoStamps() {
    Squadron homeStaffel = new Squadron();
    UUID homeStaffelId = UUID.randomUUID();
    homeStaffel.setId(homeStaffelId);
    User user = new User();
    user.setId(UUID.randomUUID());
    when(orgUnitMembershipRepository.findAllByIdUserId(user.getId()))
        .thenReturn(List.of(staffelMembership(user.getId(), homeStaffelId)));
    when(orgUnitRepository.findById(homeStaffelId)).thenReturn(Optional.of(homeStaffel));

    assertSame(homeStaffel, service.resolveOrgUnitForPickerOutputNullable(user, null));
  }

  @Test
  void resolveOrgUnitForPickerOutputNullable_foreignChoice_throwsBadRequest() {
    User user = new User();
    user.setId(UUID.randomUUID());
    UUID homeStaffelId = UUID.randomUUID();
    when(orgUnitMembershipRepository.findAllByIdUserId(user.getId()))
        .thenReturn(List.of(staffelMembership(user.getId(), homeStaffelId)));

    assertThrows(
        BadRequestException.class,
        () -> service.resolveOrgUnitForPickerOutputNullable(user, UUID.randomUUID()));
  }

  @Nested
  class HasRoleInOrgUnitTests {

    @Test
    void admin_alwaysReturnsTrue_evenWithoutAnyAuthorities() {
      UUID orgUnit = UUID.randomUUID();
      when(authHelper.isAdmin()).thenReturn(true);
      assertTrue(service.hasRoleInOrgUnit(orgUnit, "LOGISTICIAN"));
    }

    @Test
    void anonymousAuthentication_returnsFalse() {
      UUID orgUnit = UUID.randomUUID();
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.currentAuthentication()).thenReturn(Optional.empty());
      assertFalse(service.hasRoleInOrgUnit(orgUnit, "LOGISTICIAN"));
    }

    @Test
    void matchingContextualAuthority_returnsTrue() {
      UUID orgUnit = UUID.randomUUID();
      when(authHelper.isAdmin()).thenReturn(false);
      org.springframework.security.core.GrantedAuthority granted =
          new de.greluc.krt.profit.basetool.backend.support.OrgUnitContextualAuthority(
              "LOGISTICIAN", orgUnit);
      withAuthorities(java.util.List.of(granted));
      assertTrue(service.hasRoleInOrgUnit(orgUnit, "LOGISTICIAN"));
    }

    @Test
    void contextualAuthorityForDifferentOrgUnit_returnsFalse() {
      UUID orgUnitA = UUID.randomUUID();
      UUID orgUnitB = UUID.randomUUID();
      when(authHelper.isAdmin()).thenReturn(false);
      org.springframework.security.core.GrantedAuthority granted =
          new de.greluc.krt.profit.basetool.backend.support.OrgUnitContextualAuthority(
              "LOGISTICIAN", orgUnitA);
      withAuthorities(java.util.List.of(granted));
      assertFalse(service.hasRoleInOrgUnit(orgUnitB, "LOGISTICIAN"));
    }

    @Test
    void contextualAuthorityForDifferentRole_returnsFalse() {
      UUID orgUnit = UUID.randomUUID();
      when(authHelper.isAdmin()).thenReturn(false);
      org.springframework.security.core.GrantedAuthority granted =
          new de.greluc.krt.profit.basetool.backend.support.OrgUnitContextualAuthority(
              "MISSION_MANAGER", orgUnit);
      withAuthorities(java.util.List.of(granted));
      assertFalse(service.hasRoleInOrgUnit(orgUnit, "LOGISTICIAN"));
    }

    @Test
    void onlyFlatAuthorityNoContextual_returnsFalse() {
      UUID orgUnit = UUID.randomUUID();
      when(authHelper.isAdmin()).thenReturn(false);
      org.springframework.security.core.GrantedAuthority flat =
          new org.springframework.security.core.authority.SimpleGrantedAuthority(
              "ROLE_LOGISTICIAN");
      withAuthorities(java.util.List.of(flat));
      assertFalse(service.hasRoleInOrgUnit(orgUnit, "LOGISTICIAN"));
    }

    private void withAuthorities(
        java.util.List<? extends org.springframework.security.core.GrantedAuthority> auths) {
      org.springframework.security.core.Authentication authentication =
          new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
              "user", "n/a", auths);
      when(authHelper.currentAuthentication()).thenReturn(Optional.of(authentication));
    }
  }

  @Nested
  class CanAccessBlueprintOverviewTests {

    @Test
    void admin_canAccess() {
      when(authHelper.isAdmin()).thenReturn(true);

      assertTrue(service.canAccessBlueprintOverview());
    }

    @Test
    void officer_canAccess() {
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.hasReachableRole("ROLE_OFFICER")).thenReturn(true);

      assertTrue(service.canAccessBlueprintOverview());
    }

    @Test
    void skLead_canAccess() {
      UUID skId = UUID.randomUUID();
      OrgUnitMembership lead = skMembership(MEMBER_USER_ID, skId);
      lead.setRole(MembershipRole.SK_LEAD);
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.hasReachableRole("ROLE_OFFICER")).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
          .thenReturn(List.of(staffelMembership(MEMBER_USER_ID, SQUADRON_A_ID), lead));

      assertTrue(service.canAccessBlueprintOverview());
    }

    @Test
    void logisticianFlagWithoutOfficerOrLead_isDenied() {
      OrgUnitMembership logisticianStaffel = staffelMembership(MEMBER_USER_ID, SQUADRON_A_ID);
      logisticianStaffel.setLogistician(true);
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.hasReachableRole("ROLE_OFFICER")).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
          .thenReturn(List.of(logisticianStaffel));

      assertFalse(service.canAccessBlueprintOverview());
    }

    @Test
    void anonymous_isDenied() {
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.hasReachableRole("ROLE_OFFICER")).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.empty());

      assertFalse(service.canAccessBlueprintOverview());
    }

    @Test
    void bereichLeader_canAccess() {
      OrgUnitMembership bereichSeat = bereichMembershipRow(MEMBER_USER_ID, UUID.randomUUID());
      bereichSeat.setRole(MembershipRole.BEREICHSLEITER);
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.hasReachableRole("ROLE_OFFICER")).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
          .thenReturn(List.of(bereichSeat));

      assertTrue(service.canAccessBlueprintOverview());
    }

    @Test
    void olMember_canAccess() {
      OrgUnitMembership olSeat = olMembershipRow(MEMBER_USER_ID, UUID.randomUUID());
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.hasReachableRole("ROLE_OFFICER")).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
          .thenReturn(List.of(olSeat));

      assertTrue(service.canAccessBlueprintOverview());
    }

    @Test
    void flaglessBereichSeat_isDenied() {
      OrgUnitMembership flaglessBereich = bereichMembershipRow(MEMBER_USER_ID, UUID.randomUUID());
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.hasReachableRole("ROLE_OFFICER")).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
          .thenReturn(List.of(flaglessBereich));

      assertFalse(service.canAccessBlueprintOverview());
    }
  }

  @Nested
  class CurrentUserHasAreaOrOlOversightTests {

    @Test
    void admin_qualifies() {
      when(authHelper.isAdmin()).thenReturn(true);

      assertTrue(service.currentUserHasAreaOrOlOversight());
    }

    @Test
    void officerWithoutAreaOrOlSeat_doesNotQualify() {
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
          .thenReturn(List.of(staffelMembership(MEMBER_USER_ID, SQUADRON_A_ID)));

      assertFalse(service.currentUserHasAreaOrOlOversight());
    }

    @Test
    void skLead_doesNotQualify() {
      OrgUnitMembership lead = skMembership(MEMBER_USER_ID, UUID.randomUUID());
      lead.setRole(MembershipRole.SK_LEAD);
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
          .thenReturn(List.of(staffelMembership(MEMBER_USER_ID, SQUADRON_A_ID), lead));

      assertFalse(service.currentUserHasAreaOrOlOversight());
    }

    @Test
    void bereichsleiter_qualifies() {
      OrgUnitMembership seat = bereichMembershipRow(MEMBER_USER_ID, UUID.randomUUID());
      seat.setRole(MembershipRole.BEREICHSLEITER);
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID)).thenReturn(List.of(seat));

      assertTrue(service.currentUserHasAreaOrOlOversight());
    }

    @Test
    void bereichskoordinator_qualifies() {
      OrgUnitMembership seat = bereichMembershipRow(MEMBER_USER_ID, UUID.randomUUID());
      seat.setRole(MembershipRole.BEREICHSKOORDINATOR);
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID)).thenReturn(List.of(seat));

      assertTrue(service.currentUserHasAreaOrOlOversight());
    }

    @Test
    void olMember_qualifies() {
      OrgUnitMembership seat = olMembershipRow(MEMBER_USER_ID, UUID.randomUUID());
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID)).thenReturn(List.of(seat));

      assertTrue(service.currentUserHasAreaOrOlOversight());
    }

    @Test
    void flaglessBereichSeat_doesNotQualify() {
      OrgUnitMembership flaglessBereich = bereichMembershipRow(MEMBER_USER_ID, UUID.randomUUID());
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
          .thenReturn(List.of(flaglessBereich));

      assertFalse(service.currentUserHasAreaOrOlOversight());
    }

    @Test
    void anonymous_doesNotQualify() {
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.empty());

      assertFalse(service.currentUserHasAreaOrOlOversight());
    }
  }

  @Nested
  class CurrentOversightScopeTests {

    @Test
    void adminWithoutPin_allScope() {
      when(authHelper.isAdmin()).thenReturn(true);
      when(request.getHeader(OwnerScopeService.ACTIVE_ORG_UNIT_HEADER)).thenReturn(null);

      ScopePredicate scope = service.currentOversightScope();

      assertTrue(scope.adminAllScope());
      assertNull(scope.activeOrgUnitId());
      assertTrue(scope.memberOrgUnitIds().isEmpty());
    }

    @Test
    void adminWithPin_scopesToPinnedOrgUnit() {
      when(authHelper.isAdmin()).thenReturn(true);
      when(request.getHeader(OwnerScopeService.ACTIVE_ORG_UNIT_HEADER))
          .thenReturn(SQUADRON_B_ID.toString());

      ScopePredicate scope = service.currentOversightScope();

      assertFalse(scope.adminAllScope());
      assertEquals(SQUADRON_B_ID, scope.activeOrgUnitId());
      assertTrue(scope.memberOrgUnitIds().isEmpty());
    }

    @Test
    void officer_scopesToOwnStaffel() {
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.hasReachableRole("ROLE_OFFICER")).thenReturn(true);
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
          .thenReturn(List.of(staffelMembership(MEMBER_USER_ID, SQUADRON_A_ID)));
      when(request.getHeader(OwnerScopeService.ACTIVE_ORG_UNIT_HEADER)).thenReturn(null);

      ScopePredicate scope = service.currentOversightScope();

      assertFalse(scope.adminAllScope());
      assertNull(scope.activeOrgUnitId());
      assertEquals(Set.of(SQUADRON_A_ID), scope.memberOrgUnitIds());
    }

    @Test
    void skLeadOnly_scopesToLedSkNotOwnStaffel() {
      UUID skId = UUID.randomUUID();
      OrgUnitMembership lead = skMembership(MEMBER_USER_ID, skId);
      lead.setRole(MembershipRole.SK_LEAD);
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.hasReachableRole("ROLE_OFFICER")).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
          .thenReturn(List.of(staffelMembership(MEMBER_USER_ID, SQUADRON_A_ID), lead));
      when(request.getHeader(OwnerScopeService.ACTIVE_ORG_UNIT_HEADER)).thenReturn(null);

      ScopePredicate scope = service.currentOversightScope();

      assertEquals(Set.of(skId), scope.memberOrgUnitIds());
    }

    @Test
    void officerWhoAlsoLeadsSk_scopesToBoth() {
      UUID skId = UUID.randomUUID();
      OrgUnitMembership lead = skMembership(MEMBER_USER_ID, skId);
      lead.setRole(MembershipRole.SK_LEAD);
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.hasReachableRole("ROLE_OFFICER")).thenReturn(true);
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
          .thenReturn(List.of(staffelMembership(MEMBER_USER_ID, SQUADRON_A_ID), lead));
      when(request.getHeader(OwnerScopeService.ACTIVE_ORG_UNIT_HEADER)).thenReturn(null);

      ScopePredicate scope = service.currentOversightScope();

      assertEquals(Set.of(SQUADRON_A_ID, skId), scope.memberOrgUnitIds());
    }

    @Test
    void pinWithinOversight_isHonoured() {
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.hasReachableRole("ROLE_OFFICER")).thenReturn(true);
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
          .thenReturn(List.of(staffelMembership(MEMBER_USER_ID, SQUADRON_A_ID)));
      when(request.getHeader(OwnerScopeService.ACTIVE_ORG_UNIT_HEADER))
          .thenReturn(SQUADRON_A_ID.toString());

      ScopePredicate scope = service.currentOversightScope();

      assertEquals(SQUADRON_A_ID, scope.activeOrgUnitId());
      assertTrue(scope.memberOrgUnitIds().isEmpty());
    }

    @Test
    void pinOutsideOversight_isIgnored() {
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.hasReachableRole("ROLE_OFFICER")).thenReturn(true);
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
          .thenReturn(List.of(staffelMembership(MEMBER_USER_ID, SQUADRON_A_ID)));
      when(request.getHeader(OwnerScopeService.ACTIVE_ORG_UNIT_HEADER))
          .thenReturn(SQUADRON_B_ID.toString());

      ScopePredicate scope = service.currentOversightScope();

      assertNull(scope.activeOrgUnitId());
      assertEquals(Set.of(SQUADRON_A_ID), scope.memberOrgUnitIds());
    }

    @Test
    void plainMember_emptyOversight() {
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.hasReachableRole("ROLE_OFFICER")).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
          .thenReturn(List.of(staffelMembership(MEMBER_USER_ID, SQUADRON_A_ID)));
      when(request.getHeader(OwnerScopeService.ACTIVE_ORG_UNIT_HEADER)).thenReturn(null);

      ScopePredicate scope = service.currentOversightScope();

      assertFalse(scope.adminAllScope());
      assertNull(scope.activeOrgUnitId());
      assertTrue(scope.memberOrgUnitIds().isEmpty());
    }

    @Test
    void bereichLeader_viewScopeCascadesToBereichAndChildren() {
      UUID bereichId = UUID.randomUUID();
      UUID childStaffelId = UUID.randomUUID();
      OrgUnitMembership bereichSeat = bereichMembershipRow(MEMBER_USER_ID, bereichId);
      bereichSeat.setRole(MembershipRole.BEREICHSLEITER);
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.hasReachableRole("ROLE_OFFICER")).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
          .thenReturn(List.of(bereichSeat));
      when(orgUnitCascadeService.cascadedOfficerReach(any()))
          .thenReturn(Set.of(bereichId, childStaffelId));
      when(request.getHeader(OwnerScopeService.ACTIVE_ORG_UNIT_HEADER)).thenReturn(null);

      ScopePredicate scope = service.currentOversightScope();

      assertFalse(scope.adminAllScope());
      assertEquals(Set.of(bereichId, childStaffelId), scope.memberOrgUnitIds());
    }
  }

  /**
   * Epic #692 Phase 6 (REQ-BANK-022, owner decision Q4): the own-level (write) oversight scope is
   * deliberately NOT cascaded — it names only the caller's own-level leadership seats, so a
   * Bereichsleitung/OL may raise a bank booking request against their own AREA/CARTEL account but
   * not against the subordinate accounts they may merely view (those reach them through the
   * cascading {@link OwnerScopeService#currentOversightScope()} instead).
   */
  @Nested
  class CurrentOwnLevelOversightScopeTests {

    @Test
    void adminWithoutPin_allScope() {
      when(authHelper.isAdmin()).thenReturn(true);
      when(request.getHeader(OwnerScopeService.ACTIVE_ORG_UNIT_HEADER)).thenReturn(null);

      ScopePredicate scope = service.currentOwnLevelOversightScope();

      assertTrue(scope.adminAllScope());
    }

    @Test
    void officer_ownLevelIsTheirStaffel() {
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.hasReachableRole("ROLE_OFFICER")).thenReturn(true);
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
          .thenReturn(List.of(staffelMembership(MEMBER_USER_ID, SQUADRON_A_ID)));
      when(request.getHeader(OwnerScopeService.ACTIVE_ORG_UNIT_HEADER)).thenReturn(null);

      ScopePredicate scope = service.currentOwnLevelOversightScope();

      assertEquals(Set.of(SQUADRON_A_ID), scope.memberOrgUnitIds());
    }

    @Test
    void skLead_ownLevelIsLedSk() {
      UUID skId = UUID.randomUUID();
      OrgUnitMembership lead = skMembership(MEMBER_USER_ID, skId);
      lead.setRole(MembershipRole.SK_LEAD);
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.hasReachableRole("ROLE_OFFICER")).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
          .thenReturn(List.of(staffelMembership(MEMBER_USER_ID, SQUADRON_A_ID), lead));
      when(request.getHeader(OwnerScopeService.ACTIVE_ORG_UNIT_HEADER)).thenReturn(null);

      ScopePredicate scope = service.currentOwnLevelOversightScope();

      assertEquals(Set.of(skId), scope.memberOrgUnitIds());
    }

    @Test
    void bereichLeader_ownLevelIsBereichOnly_notChildrenAndNeverCascades() {
      UUID bereichId = UUID.randomUUID();
      OrgUnitMembership bereichSeat = bereichMembershipRow(MEMBER_USER_ID, bereichId);
      bereichSeat.setRole(MembershipRole.BEREICHSLEITER);
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.hasReachableRole("ROLE_OFFICER")).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
          .thenReturn(List.of(bereichSeat));
      when(request.getHeader(OwnerScopeService.ACTIVE_ORG_UNIT_HEADER)).thenReturn(null);

      ScopePredicate scope = service.currentOwnLevelOversightScope();

      assertEquals(Set.of(bereichId), scope.memberOrgUnitIds());
      verify(orgUnitCascadeService, never()).cascadedOfficerReach(any());
    }

    @Test
    void olMember_ownLevelIsOlSeatOnly() {
      UUID olId = UUID.randomUUID();
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.hasReachableRole("ROLE_OFFICER")).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
          .thenReturn(List.of(olMembershipRow(MEMBER_USER_ID, olId)));
      when(request.getHeader(OwnerScopeService.ACTIVE_ORG_UNIT_HEADER)).thenReturn(null);

      ScopePredicate scope = service.currentOwnLevelOversightScope();

      assertEquals(Set.of(olId), scope.memberOrgUnitIds());
    }

    @Test
    void plainMemberOrFlaglessBereichSeat_emptyOwnLevel() {
      OrgUnitMembership flaglessBereich = bereichMembershipRow(MEMBER_USER_ID, UUID.randomUUID());
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.hasReachableRole("ROLE_OFFICER")).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
          .thenReturn(List.of(staffelMembership(MEMBER_USER_ID, SQUADRON_A_ID), flaglessBereich));
      when(request.getHeader(OwnerScopeService.ACTIVE_ORG_UNIT_HEADER)).thenReturn(null);

      ScopePredicate scope = service.currentOwnLevelOversightScope();

      assertTrue(scope.memberOrgUnitIds().isEmpty());
    }
  }

  private Mission newMission(UUID id, Squadron owningSquadron, boolean isInternal) {
    Mission mission = new Mission();
    mission.setId(id);
    mission.setOwningOrgUnit(owningSquadron);
    mission.setIsInternal(isInternal);
    return mission;
  }

  /** Builds a {@link JobOrder} responsible to the given org unit (Squadron or SpecialCommand). */
  private static de.greluc.krt.profit.basetool.backend.model.JobOrder jobOrderResponsibleTo(
      UUID id, de.greluc.krt.profit.basetool.backend.model.OrgUnit responsible) {
    de.greluc.krt.profit.basetool.backend.model.JobOrder o =
        new de.greluc.krt.profit.basetool.backend.model.JobOrder();
    o.setId(id);
    o.setResponsibleOrgUnit(responsible);
    return o;
  }

  /** Builds a {@link SpecialCommand} with a random id, used as an SK-responsible org unit. */
  private static SpecialCommand newSpecialCommand() {
    SpecialCommand sc = new SpecialCommand();
    sc.setId(UUID.randomUUID());
    sc.setShorthand("SKX");
    return sc;
  }

  private void stubMemberInSquadronA() {
    lenient().when(authHelper.isAdmin()).thenReturn(false);
    lenient().when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
    lenient()
        .when(
            orgUnitMembershipRepository.findAllByIdUserIdAndKind(
                MEMBER_USER_ID, OrgUnitKind.SQUADRON))
        .thenReturn(List.of(staffelMembership(MEMBER_USER_ID, SQUADRON_A_ID)));
    lenient()
        .when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
        .thenReturn(List.of(staffelMembership(MEMBER_USER_ID, SQUADRON_A_ID)));
    lenient().when(orgUnitRepository.countProfitEligibleByIdIn(any())).thenReturn(1L);
  }

  /**
   * Stubs a non-admin caller whose single membership is a non-profit-eligible org unit — the viewer
   * gate {@link OwnerScopeService#canViewJobOrders()} must return {@code false} for such a caller.
   */
  private void stubNonProfitMember() {
    lenient().when(authHelper.isAdmin()).thenReturn(false);
    lenient().when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
    lenient()
        .when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
        .thenReturn(List.of(staffelMembership(MEMBER_USER_ID, SQUADRON_A_ID)));
    lenient().when(orgUnitRepository.countProfitEligibleByIdIn(any())).thenReturn(0L);
  }

  /**
   * Epic #692 / REQ-ORG-015: verifies that {@link OwnerScopeService} routes the cascade expansion
   * (delegated to {@link OrgUnitCascadeService}) into the scope predicate, so a Bereichsleitung /
   * OL member's per-row {@code canSee*}/{@code canEdit*} gates cover their subordinate units —
   * while never setting {@code adminAllScope} and never granting reach outside the cascaded set
   * (strict silo). The expansion math itself is covered by {@link OrgUnitCascadeServiceTest}; here
   * we stub the cascade output and assert OwnerScopeService consumes it correctly.
   */
  @Nested
  class CascadingScopeTests {

    private static final UUID BEREICH_A_ID = UUID.randomUUID();
    private static final UUID DESCENDANT_STAFFEL_ID = UUID.randomUUID();
    private static final UUID FOREIGN_STAFFEL_ID = UUID.randomUUID();

    private OrgUnitMembership bereichLeadMembership() {
      OrgUnitMembership m = new OrgUnitMembership();
      m.setId(new OrgUnitMembershipId(MEMBER_USER_ID, BEREICH_A_ID));
      m.setKind(OrgUnitKind.BEREICH);
      m.setRole(MembershipRole.BEREICHSLEITER);
      return m;
    }

    private void stubBereichLeaderWithCascade(Set<UUID> expandedReach) {
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
          .thenReturn(List.of(bereichLeadMembership()));
      when(orgUnitCascadeService.expandWithDescendants(any())).thenReturn(expandedReach);
    }

    @Test
    void bereichsleiter_canSeeAndEditDescendantUnit() {
      stubBereichLeaderWithCascade(Set.of(BEREICH_A_ID, DESCENDANT_STAFFEL_ID));

      assertTrue(service.canSeeSquadron(DESCENDANT_STAFFEL_ID));
      assertTrue(service.canEditSquadron(DESCENDANT_STAFFEL_ID));
      assertTrue(service.canSeeOrgUnit(BEREICH_A_ID));
    }

    @Test
    void strictSilo_bereichsleiterCannotSeeUnitOutsideTheirCascade() {
      stubBereichLeaderWithCascade(Set.of(BEREICH_A_ID, DESCENDANT_STAFFEL_ID));

      assertFalse(service.canSeeSquadron(FOREIGN_STAFFEL_ID));
      assertFalse(service.canEditSquadron(FOREIGN_STAFFEL_ID));
    }

    @Test
    void cascade_neverSetsAdminAllScope() {
      stubBereichLeaderWithCascade(Set.of(BEREICH_A_ID, DESCENDANT_STAFFEL_ID));

      ScopePredicate predicate = service.currentScopePredicate();

      assertFalse(predicate.adminAllScope());
      assertNull(predicate.activeOrgUnitId());
      assertTrue(predicate.memberOrgUnitIds().contains(DESCENDANT_STAFFEL_ID));
      assertFalse(predicate.memberOrgUnitIds().contains(FOREIGN_STAFFEL_ID));
    }

    @Test
    void olMember_seesEveryUnitInTheConcreteUnion_butStillNotAdminAllScope() {
      stubBereichLeaderWithCascade(Set.of(BEREICH_A_ID, DESCENDANT_STAFFEL_ID, FOREIGN_STAFFEL_ID));

      assertTrue(service.canSeeSquadron(FOREIGN_STAFFEL_ID));
      assertFalse(service.currentScopePredicate().adminAllScope());
    }

    @Test
    void leaderPinnedToReachableDescendant_narrowsScopeToThatUnit() {
      stubBereichLeaderWithCascade(Set.of(BEREICH_A_ID, DESCENDANT_STAFFEL_ID));
      when(request.getHeader(OwnerScopeService.ACTIVE_ORG_UNIT_HEADER))
          .thenReturn(DESCENDANT_STAFFEL_ID.toString());

      ScopePredicate predicate = service.currentScopePredicate();

      assertEquals(DESCENDANT_STAFFEL_ID, predicate.activeOrgUnitId());
      assertFalse(predicate.adminAllScope());
      assertTrue(service.canSeeSquadron(DESCENDANT_STAFFEL_ID));
      assertFalse(service.canSeeSquadron(BEREICH_A_ID));
    }
  }

  /**
   * Epic #692 / REQ-ORG-016 (Phase 4): the picker resolvers may stamp a {@code BEREICH} / {@code
   * ORGANISATIONSLEITUNG} as the owning org unit, and a leadership caller may stamp a subordinate
   * unit they oversee (create-on-behalf). Ordinary-member self-service stamping is unchanged
   * (covered by the existing {@code resolveOrgUnitForPickerOutput*} tests above).
   *
   * <p>Coverage here pins, in addition to the Bereich owner case: the {@code ORGANISATIONSLEITUNG}
   * arm of the resolution kind filter; both resolution legs of a create-on-behalf descendant pick
   * (Staffel and Spezialkommando); and — the genuine production divergence — a create-on-behalf
   * where the <b>caller differs from the target user</b> (inventory book-out/transfer, refinery
   * store), proving the validation gate keys {@code canEditOrgUnit} on the caller rather than the
   * target user.
   */
  @Nested
  class BereichOlOwnershipStampingTests {

    private static final UUID BEREICH_ID = UUID.randomUUID();
    private static final UUID DESCENDANT_STAFFEL_ID = UUID.randomUUID();
    private static final UUID OL_ID = UUID.randomUUID();

    private de.greluc.krt.profit.basetool.backend.model.Bereich newBereich() {
      de.greluc.krt.profit.basetool.backend.model.Bereich b =
          new de.greluc.krt.profit.basetool.backend.model.Bereich();
      b.setId(BEREICH_ID);
      b.setShorthand("PRF");
      return b;
    }

    private OrgUnitMembership bereichLeadMembership(UUID userId) {
      OrgUnitMembership m = new OrgUnitMembership();
      m.setId(new OrgUnitMembershipId(userId, BEREICH_ID));
      m.setKind(OrgUnitKind.BEREICH);
      m.setRole(MembershipRole.BEREICHSLEITER);
      return m;
    }

    private OrgUnitMembership olMembership(UUID userId) {
      OrgUnitMembership m = new OrgUnitMembership();
      m.setId(new OrgUnitMembershipId(userId, OL_ID));
      m.setKind(OrgUnitKind.ORGANISATIONSLEITUNG);
      return m;
    }

    @Test
    void leaderStampsOwnBereich_resolvesToBereichOrgUnit() {
      User leader = new User();
      leader.setId(UUID.randomUUID());
      de.greluc.krt.profit.basetool.backend.model.Bereich bereich = newBereich();
      when(orgUnitMembershipRepository.findAllByIdUserId(leader.getId()))
          .thenReturn(List.of(bereichLeadMembership(leader.getId())));
      when(orgUnitRepository.findById(BEREICH_ID)).thenReturn(Optional.of(bereich));

      assertSame(bereich, service.resolveOrgUnitForPickerOutput(leader, null));
      assertSame(bereich, service.resolveOrgUnitForPickerOutput(leader, BEREICH_ID));
    }

    @Test
    void leaderCreatesOnBehalfOfDescendant_passesViaCanEditOrgUnit() {
      User leader = new User();
      leader.setId(UUID.randomUUID());
      Squadron descendant = new Squadron();
      descendant.setId(DESCENDANT_STAFFEL_ID);
      descendant.setShorthand("DSC");

      when(orgUnitMembershipRepository.findAllByIdUserId(leader.getId()))
          .thenReturn(List.of(bereichLeadMembership(leader.getId())));
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(leader.getId()));
      when(orgUnitCascadeService.expandWithDescendants(any()))
          .thenReturn(Set.of(BEREICH_ID, DESCENDANT_STAFFEL_ID));
      when(orgUnitRepository.findById(DESCENDANT_STAFFEL_ID)).thenReturn(Optional.of(descendant));

      assertSame(descendant, service.resolveOrgUnitForPickerOutput(leader, DESCENDANT_STAFFEL_ID));
    }

    @Test
    void leaderCannotStampUnitOutsideTheirCascade_throws() {
      User leader = new User();
      leader.setId(UUID.randomUUID());
      UUID foreignStaffelId = UUID.randomUUID();

      when(orgUnitMembershipRepository.findAllByIdUserId(leader.getId()))
          .thenReturn(List.of(bereichLeadMembership(leader.getId())));
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(leader.getId()));
      when(orgUnitCascadeService.expandWithDescendants(any()))
          .thenReturn(Set.of(BEREICH_ID, DESCENDANT_STAFFEL_ID));

      BadRequestException ex =
          assertThrows(
              BadRequestException.class,
              () -> service.resolveOrgUnitForPickerOutput(leader, foreignStaffelId));
      assertTrue(ex.getMessage().toLowerCase().contains("editable scope"), ex.getMessage());
    }

    @Test
    void subordinateCannotSeeOrEditBereichOwnedScope_strictSilo() {
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
          .thenReturn(List.of(staffelMembership(MEMBER_USER_ID, DESCENDANT_STAFFEL_ID)));

      assertFalse(service.canSeeSquadron(BEREICH_ID));
      assertFalse(service.canEditSquadron(BEREICH_ID));
    }

    @Test
    void leaderStampsOwnOrganisationsleitung_resolvesToOlOrgUnit() {
      User olLeader = new User();
      olLeader.setId(UUID.randomUUID());
      de.greluc.krt.profit.basetool.backend.model.Organisationsleitung ol =
          new de.greluc.krt.profit.basetool.backend.model.Organisationsleitung();
      ol.setId(OL_ID);
      ol.setShorthand("OL");
      when(orgUnitMembershipRepository.findAllByIdUserId(olLeader.getId()))
          .thenReturn(List.of(olMembership(olLeader.getId())));
      when(orgUnitRepository.findById(OL_ID)).thenReturn(Optional.of(ol));

      assertSame(ol, service.resolveOrgUnitForPickerOutput(olLeader, null));
      assertSame(ol, service.resolveOrgUnitForPickerOutput(olLeader, OL_ID));
    }

    @Test
    void leaderCreatesOnBehalfOfDescendantSk_resolvesViaPolymorphicLoad() {
      User leader = new User();
      leader.setId(UUID.randomUUID());
      UUID descendantSkId = UUID.randomUUID();
      SpecialCommand descendantSk = new SpecialCommand();
      descendantSk.setId(descendantSkId);
      descendantSk.setShorthand("DSK");

      when(orgUnitMembershipRepository.findAllByIdUserId(leader.getId()))
          .thenReturn(List.of(bereichLeadMembership(leader.getId())));
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(leader.getId()));
      when(orgUnitCascadeService.expandWithDescendants(any()))
          .thenReturn(Set.of(BEREICH_ID, descendantSkId));
      when(orgUnitRepository.findById(descendantSkId)).thenReturn(Optional.of(descendantSk));

      assertSame(descendantSk, service.resolveOrgUnitForPickerOutput(leader, descendantSkId));
    }

    @Test
    void createOnBehalfForAnotherUser_keysGateOnCallerScopeNotTargetMemberships() {
      User leaderCaller = new User();
      leaderCaller.setId(UUID.randomUUID());
      User receiver = new User();
      receiver.setId(UUID.randomUUID());
      UUID receiverHomeStaffelId = UUID.randomUUID();
      Squadron descendant = new Squadron();
      descendant.setId(DESCENDANT_STAFFEL_ID);
      descendant.setShorthand("DSC");

      when(orgUnitMembershipRepository.findAllByIdUserId(receiver.getId()))
          .thenReturn(List.of(staffelMembership(receiver.getId(), receiverHomeStaffelId)));
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(leaderCaller.getId()));
      when(orgUnitMembershipRepository.findAllByIdUserId(leaderCaller.getId()))
          .thenReturn(List.of(bereichLeadMembership(leaderCaller.getId())));
      when(orgUnitCascadeService.expandWithDescendants(any()))
          .thenReturn(Set.of(BEREICH_ID, DESCENDANT_STAFFEL_ID));
      when(orgUnitRepository.findById(DESCENDANT_STAFFEL_ID)).thenReturn(Optional.of(descendant));

      assertSame(
          descendant, service.resolveOrgUnitForPickerOutput(receiver, DESCENDANT_STAFFEL_ID));
    }

    @Test
    void createOnBehalf_pickForeignToBothReceiverAndCaller_throws() {
      User leaderCaller = new User();
      leaderCaller.setId(UUID.randomUUID());
      User receiver = new User();
      receiver.setId(UUID.randomUUID());
      UUID receiverHomeStaffelId = UUID.randomUUID();
      UUID foreignToBothId = UUID.randomUUID();

      when(orgUnitMembershipRepository.findAllByIdUserId(receiver.getId()))
          .thenReturn(List.of(staffelMembership(receiver.getId(), receiverHomeStaffelId)));
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(leaderCaller.getId()));
      when(orgUnitMembershipRepository.findAllByIdUserId(leaderCaller.getId()))
          .thenReturn(List.of(bereichLeadMembership(leaderCaller.getId())));
      when(orgUnitCascadeService.expandWithDescendants(any()))
          .thenReturn(Set.of(BEREICH_ID, DESCENDANT_STAFFEL_ID));

      BadRequestException ex =
          assertThrows(
              BadRequestException.class,
              () -> service.resolveOrgUnitForPickerOutput(receiver, foreignToBothId));
      assertTrue(ex.getMessage().toLowerCase().contains("editable scope"), ex.getMessage());
    }
  }

  /**
   * REQ-HANGAR-003 / ADR-0048: the hangar unit-overview scope mirrors {@link
   * OwnerScopeService#currentScopePredicate()} for every caller except one owner-approved widening
   * — a non-pinned OL member is upgraded to {@code adminAllScope} so the Org-Einheitsübersicht
   * surfaces every ship, including ownerless personal ones. A pin still narrows it, and no other
   * caller class is affected.
   */
  @Nested
  class CurrentUnitOverviewScopeTests {

    private static final UUID OL_ID = UUID.randomUUID();

    @Test
    void olMemberWithoutPin_isUpgradedToAdminAllScope() {
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
          .thenReturn(List.of(olMembershipRow(MEMBER_USER_ID, OL_ID)));
      when(request.getHeader(OwnerScopeService.ACTIVE_ORG_UNIT_HEADER)).thenReturn(null);

      ScopePredicate scope = service.currentUnitOverviewScope();

      assertTrue(scope.adminAllScope(), "a non-pinned OL member sees every ship");
      assertNull(scope.activeOrgUnitId());
      assertTrue(scope.memberOrgUnitIds().isEmpty());
    }

    @Test
    void olMemberWithActivePin_respectsThePin() {
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
          .thenReturn(List.of(olMembershipRow(MEMBER_USER_ID, OL_ID)));
      when(request.getHeader(OwnerScopeService.ACTIVE_ORG_UNIT_HEADER))
          .thenReturn(OL_ID.toString());

      ScopePredicate scope = service.currentUnitOverviewScope();

      assertFalse(scope.adminAllScope());
      assertEquals(OL_ID, scope.activeOrgUnitId());
    }

    @Test
    void plainMemberWithoutPin_keepsTheirMembershipReach_notAdminAll() {
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
          .thenReturn(List.of(staffelMembership(MEMBER_USER_ID, SQUADRON_A_ID)));
      when(request.getHeader(OwnerScopeService.ACTIVE_ORG_UNIT_HEADER)).thenReturn(null);

      ScopePredicate scope = service.currentUnitOverviewScope();

      assertFalse(scope.adminAllScope(), "a plain member never gets the OL widening");
      assertNull(scope.activeOrgUnitId());
      assertTrue(scope.memberOrgUnitIds().contains(SQUADRON_A_ID));
    }

    @Test
    void admin_keepsAdminAllScope_unchanged() {
      when(authHelper.isAdmin()).thenReturn(true);
      when(request.getHeader(OwnerScopeService.ACTIVE_ORG_UNIT_HEADER)).thenReturn(null);

      ScopePredicate scope = service.currentUnitOverviewScope();

      assertTrue(scope.adminAllScope());
    }
  }

  /**
   * Tests for {@link OwnerScopeService#resolveReassignTargetOrgUnit(UUID)} — the assignable-target
   * gate of the mission owning-org-unit reassignment (REQ-ORG-018 / ADR-0050): an admin assigns
   * anywhere or to ownerless; a non-admin only to a direct membership / editable-scope unit, and to
   * ownerless only when membershipless.
   */
  @Nested
  class ResolveReassignTargetOrgUnit {

    @Test
    void admin_mayAssignToAnyExistingOrgUnit() {
      when(authHelper.isAdmin()).thenReturn(true);
      when(orgUnitRepository.findById(SQUADRON_A_ID)).thenReturn(Optional.of(squadronA));

      assertSame(squadronA, service.resolveReassignTargetOrgUnit(SQUADRON_A_ID));
    }

    @Test
    void admin_mayAssignOwnerless_returningNull() {
      when(authHelper.isAdmin()).thenReturn(true);

      assertNull(service.resolveReassignTargetOrgUnit(null));
    }

    @Test
    void admin_unknownTarget_throwsBadRequest() {
      UUID unknown = UUID.randomUUID();
      when(authHelper.isAdmin()).thenReturn(true);
      when(orgUnitRepository.findById(unknown)).thenReturn(Optional.empty());

      assertThrows(BadRequestException.class, () -> service.resolveReassignTargetOrgUnit(unknown));
    }

    @Test
    void nonAdmin_mayAssignToOwnMembership() {
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
          .thenReturn(List.of(staffelMembership(MEMBER_USER_ID, SQUADRON_A_ID)));
      when(orgUnitRepository.findById(SQUADRON_A_ID)).thenReturn(Optional.of(squadronA));

      assertSame(squadronA, service.resolveReassignTargetOrgUnit(SQUADRON_A_ID));
    }

    @Test
    void nonAdmin_targetOutsideScope_throwsAccessDenied() {
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
          .thenReturn(List.of(staffelMembership(MEMBER_USER_ID, SQUADRON_A_ID)));

      assertThrows(
          AccessDeniedException.class, () -> service.resolveReassignTargetOrgUnit(SQUADRON_B_ID));
    }

    @Test
    void nonAdminWithMembership_mayNotMakeOwnerless() {
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID))
          .thenReturn(List.of(staffelMembership(MEMBER_USER_ID, SQUADRON_A_ID)));

      assertThrows(AccessDeniedException.class, () -> service.resolveReassignTargetOrgUnit(null));
    }

    @Test
    void membershiplessCaller_mayMakeOwnerless_returningNull() {
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(MEMBER_USER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserId(MEMBER_USER_ID)).thenReturn(List.of());

      assertNull(service.resolveReassignTargetOrgUnit(null));
    }
  }
}

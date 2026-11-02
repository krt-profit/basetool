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

package de.greluc.krt.profit.basetool.backend.scope.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryAccessPolicy;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryAggregationService;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItem;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.inventory.internal.LagerFilter;
import de.greluc.krt.profit.basetool.backend.kernel.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionReferenceDto;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import de.greluc.krt.profit.basetool.backend.mission.internal.MissionAccessPolicy;
import de.greluc.krt.profit.basetool.backend.mission.internal.MissionService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Bereich;
import de.greluc.krt.profit.basetool.backend.orgunit.api.BereichRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.OrgUnitContextualAuthority;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryOrderListDto;
import de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryAccessPolicy;
import de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryOrder;
import de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryOrderQueryService;
import de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryOrderRepository;
import de.greluc.krt.profit.basetool.backend.refinery.web.RefineryOrderController;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Walks the granted-unit cascade of Logistician and Mission Manager through the aggregate access
 * checks over the real scope graph (decision 18, REQ-SEC-005).
 *
 * <p>The caller is a plain member of a Bereich, holds the flat role and a grant on the Bereich, and
 * holds no leadership seat, so only the grant cascade can reach the Staffel below it.
 */
@SpringBootTest
class GrantCascadeAggregateAccessIntegrationTest {

  @Autowired private MissionAccessPolicy missionAccessPolicy;
  @Autowired private InventoryAccessPolicy inventoryAccessPolicy;
  @Autowired private RefineryAccessPolicy refineryAccessPolicy;
  @Autowired private RefineryOrderController refineryOrderController;
  @Autowired private BereichRepository bereichRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrgUnitMembershipRepository orgUnitMembershipRepository;
  @Autowired private MissionRepository missionRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private InventoryItemRepository inventoryItemRepository;
  @Autowired private RefineryOrderRepository refineryOrderRepository;
  @Autowired private InventoryAggregationService inventoryAggregationService;
  @Autowired private RefineryOrderQueryService refineryOrderQueryService;
  @Autowired private MissionService missionService;
  @Autowired private TransactionTemplate transactionTemplate;

  private UUID bereichId;
  private UUID childId;
  private UUID siblingId;
  private UUID callerId;
  private UUID childMemberId;
  private UUID internalMissionId;
  private UUID inventoryRowId;
  private UUID refineryOrderId;

  @BeforeEach
  void seed() {
    transactionTemplate.executeWithoutResult(_ -> {
      String tag = UUID.randomUUID().toString().substring(0, 8);
      Bereich bereich = new Bereich();
      bereich.setName("Cascade-Bereich-" + tag);
      bereich.setShorthand("CB" + tag);
      bereich = bereichRepository.save(bereich);
      bereichId = bereich.getId();
      Squadron child = newSquadron("Cascade-Child-" + tag, "CC" + tag, bereich);
      childId = child.getId();
      siblingId = newSquadron("Cascade-Sibling-" + tag, "CS" + tag, bereich).getId();

      User caller = newUser("cascade-caller-" + tag);
      callerId = caller.getId();
      OrgUnitMembership membership = new OrgUnitMembership();
      membership.setId(new OrgUnitMembershipId(callerId, bereichId));
      membership.setUser(caller);
      membership.setKind(OrgUnitKind.BEREICH);
      membership.setJoinedAt(Instant.now());
      orgUnitMembershipRepository.save(membership);

      User childMember = newUser("cascade-member-" + tag);
      childMemberId = childMember.getId();
      OrgUnitMembership childMembership = new OrgUnitMembership();
      childMembership.setId(new OrgUnitMembershipId(childMemberId, childId));
      childMembership.setUser(childMember);
      childMembership.setKind(OrgUnitKind.SQUADRON);
      childMembership.setJoinedAt(Instant.now());
      orgUnitMembershipRepository.save(childMembership);
      Mission mission = new Mission();
      mission.setOwningOrgUnit(child);
      mission.setName("Cascade-Mission-" + tag);
      mission.setIsInternal(true);
      mission.setStatus("PLANNED");
      internalMissionId = missionRepository.save(mission).getId();

      Location location = new Location();
      location.setName("Cascade Depot " + tag);
      location = locationRepository.save(location);
      Material material = new Material();
      material.setName("Cascade Ore " + tag);
      material.setType(MaterialType.REFINED);
      material.setQuantityType(QuantityType.SCU);
      material = materialRepository.save(material);
      InventoryItem row = new InventoryItem();
      row.setUser(childMember);
      row.setOwningOrgUnit(child);
      row.setMaterial(material);
      row.setLocation(location);
      row.setQuality(500);
      row.setAmount(10.0);
      row.setPersonal(false);
      row.setStolen(false);
      inventoryRowId = inventoryItemRepository.save(row).getId();

      RefineryOrder order = new RefineryOrder();
      order.setOwner(childMember);
      order.setOwningOrgUnit(child);
      order.setLocation(location);
      order.setStartedAt(Instant.now());
      refineryOrderId = refineryOrderRepository.save(order).getId();
    });
  }

  @AfterEach
  void clearContexts() {
    SecurityContextHolder.clearContext();
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void aMissionManagerGrantOnTheBereichReadsAnInternalMissionOfAStaffelBelow() {
    assertThat(asCaller(missionGrant(bereichId), () -> sees(internalMissionId))).isTrue();
  }

  @Test
  void aMissionManagerGrantOnASiblingOrWithoutAGrantDoesNotReadIt() {
    assertThat(asCaller(missionGrant(siblingId), () -> sees(internalMissionId))).isFalse();
    assertThat(asCaller(null, () -> sees(internalMissionId))).isFalse();
  }

  @Test
  void aLogisticianGrantOnTheBereichEditsAnInventoryRowOfAStaffelBelow() {
    assertThat(
            asCaller(
                logisticianGrant(bereichId),
                () -> inventoryAccessPolicy.canEditInventoryItem(inventoryRowId)))
        .isTrue();
    assertThat(
            asCaller(
                logisticianGrant(bereichId),
                () -> inventoryAccessPolicy.canSeeInventoryItem(inventoryRowId)))
        .isTrue();
  }

  @Test
  void aLogisticianGrantOnASiblingOrOfTheWrongRoleDoesNotEditTheRow() {
    assertThat(
            asCaller(
                logisticianGrant(siblingId),
                () -> inventoryAccessPolicy.canEditInventoryItem(inventoryRowId)))
        .isFalse();
    assertThat(
            asCaller(
                missionGrant(bereichId),
                () -> inventoryAccessPolicy.canEditInventoryItem(inventoryRowId)))
        .isFalse();
  }

  @Test
  void aLogisticianGrantOnTheBereichReadsARefineryOrderOfAStaffelBelow() {
    assertThat(
            asCaller(
                logisticianGrant(bereichId),
                () -> refineryAccessPolicy.canSeeRefineryOrder(refineryOrderId)))
        .isTrue();
    assertThat(
            asCaller(
                logisticianGrant(siblingId),
                () -> refineryAccessPolicy.canSeeRefineryOrder(refineryOrderId)))
        .isFalse();
  }

  @Test
  void aLogisticianGrantOnTheBereichPassesTheOnBehalfGatesForAMemberOfAStaffelBelow() {
    assertThat(
            asCaller(
                logisticianGrant(bereichId),
                () -> refineryAccessPolicy.canViewUserRefineryOrders(childMemberId)))
        .isTrue();
    assertThat(
            asCaller(
                logisticianGrant(bereichId),
                () -> refineryAccessPolicy.canManageUserRefineryOrders(childMemberId)))
        .isTrue();
    assertThat(
            asCaller(
                logisticianGrant(bereichId),
                () -> inventoryAccessPolicy.canManageUserInventory(childMemberId)))
        .isTrue();
  }

  @Test
  void aSiblingGrantNoGrantOrAPinOutsideTheGrantFailsTheOnBehalfGates() {
    assertThat(
            asCaller(
                logisticianGrant(siblingId),
                () -> refineryAccessPolicy.canViewUserRefineryOrders(childMemberId)))
        .isFalse();
    assertThat(
            asCaller(null, () -> refineryAccessPolicy.canManageUserRefineryOrders(childMemberId)))
        .isFalse();
    assertThat(
            asCaller(
                logisticianGrant(bereichId),
                siblingId,
                () -> refineryAccessPolicy.canViewUserRefineryOrders(childMemberId)))
        .isFalse();
    assertThat(
            asCaller(
                missionGrant(bereichId),
                () -> inventoryAccessPolicy.canManageUserInventory(childMemberId)))
        .isFalse();
  }

  @Test
  void theUserOrdersEndpointListsAChildMembersOrdersForABereichLogisticianAndRefusesASibling() {
    assertThat(
            asCaller(
                    logisticianGrant(bereichId),
                    () -> refineryOrderController.getUserRefineryOrders(childMemberId, 0, 50, null))
                .content())
        .extracting(RefineryOrderListDto::id)
        .contains(refineryOrderId);
    assertThatThrownBy(() ->
            asCaller(
                logisticianGrant(siblingId),
                () -> refineryOrderController.getUserRefineryOrders(childMemberId, 0, 50, null)))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void aPinOutsideTheGrantedSetRefusesTheCascade() {
    assertThat(
            asCaller(
                logisticianGrant(bereichId),
                siblingId,
                () -> inventoryAccessPolicy.canEditInventoryItem(inventoryRowId)))
        .isFalse();
    assertThat(
            asCaller(
                logisticianGrant(bereichId),
                childId,
                () -> inventoryAccessPolicy.canEditInventoryItem(inventoryRowId)))
        .isTrue();
  }

  private List<UUID> inventoryListIds() {
    return inventoryAggregationService
        .getAllInventory(LagerFilter.NONE, PageRequest.of(0, 1000))
        .map(InventoryItemDto::id)
        .getContent();
  }

  private List<UUID> refineryListIds() {
    return refineryOrderQueryService
        .getAllRefineryOrders(null, false, null, PageRequest.of(0, 1000))
        .stream()
        .map(RefineryOrder::getId)
        .toList();
  }

  private List<UUID> missionPickerIds() {
    return missionService.findAllActiveReference().stream().map(MissionReferenceDto::id).toList();
  }

  @Test
  void aLogisticianGrantOnTheBereichListsTheInventoryRowsAndRefineryOrdersBelow() {
    assertThat(asCaller(logisticianGrant(bereichId), this::inventoryListIds))
        .contains(inventoryRowId);
    assertThat(asCaller(logisticianGrant(bereichId), this::refineryListIds))
        .contains(refineryOrderId);
  }

  @Test
  void aSiblingGrantOrTheWrongRoleListsNeitherRowNorOrder() {
    assertThat(asCaller(logisticianGrant(siblingId), this::inventoryListIds))
        .doesNotContain(inventoryRowId);
    assertThat(asCaller(missionGrant(bereichId), this::inventoryListIds))
        .doesNotContain(inventoryRowId);
    assertThat(asCaller(logisticianGrant(siblingId), this::refineryListIds))
        .doesNotContain(refineryOrderId);
    assertThat(asCaller(null, this::refineryListIds)).doesNotContain(refineryOrderId);
  }

  @Test
  void aPinNarrowsTheListsToTheGrantedPinnedUnit() {
    assertThat(asCaller(logisticianGrant(bereichId), siblingId, this::inventoryListIds))
        .doesNotContain(inventoryRowId);
    assertThat(asCaller(logisticianGrant(bereichId), childId, this::inventoryListIds))
        .contains(inventoryRowId);
    assertThat(asCaller(logisticianGrant(bereichId), siblingId, this::refineryListIds))
        .doesNotContain(refineryOrderId);
    assertThat(asCaller(logisticianGrant(bereichId), childId, this::refineryListIds))
        .contains(refineryOrderId);
  }

  @Test
  void aMissionManagerGrantOnTheBereichListsTheInternalMissionBelowAndASiblingGrantDoesNot() {
    assertThat(asCaller(missionGrant(bereichId), this::missionPickerIds))
        .contains(internalMissionId);
    assertThat(asCaller(missionGrant(siblingId), this::missionPickerIds))
        .doesNotContain(internalMissionId);
  }

  private boolean sees(UUID missionId) {
    return missionAccessPolicy.canSeeMission(missionId);
  }

  private static GrantedAuthority missionGrant(UUID unitId) {
    return new OrgUnitContextualAuthority(Roles.MISSION_MANAGER, unitId);
  }

  private static GrantedAuthority logisticianGrant(UUID unitId) {
    return new OrgUnitContextualAuthority(Roles.LOGISTICIAN, unitId);
  }

  private <T> T asCaller(GrantedAuthority grant, Supplier<T> check) {
    return asCaller(grant, null, check);
  }

  private <T> T asCaller(GrantedAuthority grant, UUID pin, Supplier<T> check) {
    List<GrantedAuthority> authorities = new java.util.ArrayList<>();
    authorities.add(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"));
    if (grant != null) {
      authorities.add(grant);
      authorities.add(new SimpleGrantedAuthority("ROLE_" + flatRole(grant)));
    }
    SecurityContextHolder.getContext()
        .setAuthentication(
            new JwtAuthenticationToken(
                Jwt.withTokenValue("grant-cascade")
                    .header("alg", "none")
                    .subject(callerId.toString())
                    .build(),
                authorities));
    MockHttpServletRequest request = new MockHttpServletRequest();
    if (pin != null) {
      request.addHeader(RequestScopeResolver.ACTIVE_ORG_UNIT_HEADER, pin.toString());
    }
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    return transactionTemplate.execute(_ -> check.get());
  }

  private static String flatRole(GrantedAuthority grant) {
    return ((OrgUnitContextualAuthority) grant).roleName();
  }

  private Squadron newSquadron(String name, String shorthand, Bereich parent) {
    Squadron s = new Squadron();
    s.setName(name);
    s.setShorthand(shorthand);
    s.setProfitEligible(true);
    s.setParent(parent);
    return squadronRepository.save(s);
  }

  private User newUser(String username) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username);
    return userRepository.save(user);
  }
}

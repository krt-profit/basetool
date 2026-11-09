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

package de.greluc.krt.profit.basetool.backend.controller;

import static de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyFixtures.persistedProfitArea;
import static de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyFixtures.withinProfit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.bank.internal.BankAccount;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankAccountRepository;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankAccountType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.CityRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryAllocations;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItem;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.kernel.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.support.TestPlaces;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/**
 * Walks the linked-stock reads of a private job order over HTTP as an external assignee who may not
 * read the internal mission that part of the stock is earmarked to (REQ-ORDERS-047, REQ-INV-027).
 *
 * <p>The order, the stock and the internal mission belong to unit A; the assignee is a plain member
 * of unit B.
 */
@SpringBootTest
class JobOrderLinkedStockMissionAllocationIntegrationTest {

  @Autowired private OrgUnitRepository orgUnitRepository;

  private static final String ORDERS = "/api/v1/orders/";
  private static final String MISSION_NAME = "Secret-Op-";
  private static final double ALLOCATED = 4.0;

  @Autowired private WebApplicationContext context;

  @Autowired private CityRepository cityRepository;

  @Autowired private JobOrderRepository jobOrderRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrgUnitMembershipRepository orgUnitMembershipRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private MissionRepository missionRepository;
  @Autowired private InventoryItemRepository inventoryItemRepository;
  @Autowired private BankAccountRepository bankAccounts;
  @Autowired private EntityManagerFactory entityManagerFactory;
  @Autowired private TransactionTemplate transactionTemplate;

  private MockMvc mockMvc;
  private UUID orderId;
  private UUID requesterUnitId;
  private UUID linkedInventoryId;
  private UUID materialId;
  private UUID missionId;
  private UUID assigneeId;
  private UUID unitAMemberId;
  private UUID adminId;
  private String tag;

  @BeforeEach
  void seed() throws Exception {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    adminId = UUID.randomUUID();
    transactionTemplate.executeWithoutResult(_ -> {
      tag = UUID.randomUUID().toString().substring(0, 8);
      Squadron unitA = newSquadron("Alloc-A-" + tag, "AA" + tag);
      Squadron unitB = newSquadron("Alloc-B-" + tag, "AB" + tag);
      requesterUnitId = unitB.getId();
      User assignee = newUser("alloc-assignee-" + tag, UUID.randomUUID());
      assigneeId = assignee.getId();
      joinUnit(assignee, unitB);
      User unitAMember = newUser("alloc-amember-" + tag, UUID.randomUUID());
      unitAMemberId = unitAMember.getId();
      joinUnit(unitAMember, unitA);
      newUser("alloc-admin-" + tag, adminId);

      Material material = new Material();
      material.setName("Alloc Ore " + tag);
      material.setType(MaterialType.REFINED);
      material.setQuantityType(QuantityType.SCU);
      materialId = materialRepository.save(material).getId();

      Mission mission = new Mission();
      mission.setOwningOrgUnit(unitA);
      mission.setName(MISSION_NAME + tag);
      mission.setIsInternal(true);
      mission.setPlannedStartTime(Instant.parse("2030-01-02T03:04:05Z"));
      missionId = missionRepository.save(mission).getId();

      orderId =
          jobOrderRepository
              .save(
                  JobOrder.builder()
                      .responsibleOrgUnit(unitA)
                      .requestingOrgUnit(unitA)
                      .handle("alloc-test")
                      .status(JobOrderStatus.OPEN)
                      .build())
              .getId();

      InventoryItem row = new InventoryItem();
      row.setUser(unitAMember);
      Location location = new Location();
      location.setName("Alloc Depot " + tag);
      row.setLocation(locationRepository.save(TestPlaces.onOwnCity(location, cityRepository)));
      row.setMaterial(material);
      row.setQuality(500);
      row.setAmount(10.0);
      row.setPersonal(false);
      row.setStolen(false);
      InventoryAllocations.addJobOrder(row, orderId, ALLOCATED, false);
      InventoryAllocations.addMission(row, missionId, ALLOCATED);
      linkedInventoryId = inventoryItemRepository.save(row).getId();
    });
    as(post(ORDERS + orderId + "/assignees/" + assigneeId), adminId, "ROLE_ADMIN")
        .andExpect(status().isOk());
  }

  @Test
  void anAssigneeOfAnotherUnitSeesTheLinkedRowButNotTheInternalMissionBehindItsAllocation()
      throws Exception {
    as(
            get(ORDERS + orderId + "/materials/" + materialId + "/inventory"),
            assigneeId,
            "ROLE_KRT_MEMBER")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].missionAllocations.length()").value(1))
        .andExpect(jsonPath("$[0].missionAllocations[0].amount").value(ALLOCATED))
        .andExpect(jsonPath("$[0].missionAllocations[0].missionName").doesNotExist())
        .andExpect(jsonPath("$[0].missionAllocations[0].missionPlannedStartTime").doesNotExist());
    as(get("/api/v1/missions/" + missionId), assigneeId, "ROLE_KRT_MEMBER")
        .andExpect(status().isForbidden());
  }

  @Test
  void aConcreteRequesterReadKeepsMissionInventoryAndBankAuthorityIndependent() throws Exception {
    UUID[] privateIds = new UUID[2];
    transactionTemplate.executeWithoutResult(_ -> {
      Squadron requesterUnit = squadronRepository.findById(requesterUnitId).orElseThrow();
      requesterUnit.setParent(null);
      squadronRepository.saveAndFlush(requesterUnit);
      JobOrder order = jobOrderRepository.findById(orderId).orElseThrow();
      order.setRequestingOrgUnit(requesterUnit);
      jobOrderRepository.saveAndFlush(order);
      InventoryItem linked = inventoryItemRepository.findById(linkedInventoryId).orElseThrow();
      InventoryItem unrelated = new InventoryItem();
      unrelated.setUser(linked.getUser());
      unrelated.setMaterial(linked.getMaterial());
      unrelated.setLocation(linked.getLocation());
      unrelated.setOwningOrgUnit(order.getResponsibleOrgUnit());
      unrelated.setQuality(501);
      unrelated.setAmount(1.0);
      privateIds[0] = inventoryItemRepository.saveAndFlush(unrelated).getId();
      BankAccount account = new BankAccount();
      account.setAccountNo("KB-" + tag);
      account.setName("Private account " + tag);
      account.setType(BankAccountType.ORG_UNIT);
      account.setOrgUnit(order.getResponsibleOrgUnit());
      privateIds[1] = bankAccounts.saveAndFlush(account).getId();
    });
    as(delete(ORDERS + orderId + "/assignees/" + assigneeId), adminId, "ROLE_ADMIN")
        .andExpect(status().isOk());

    as(get("/api/v1/me/capabilities"), assigneeId, "ROLE_KRT_MEMBER")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.canViewJobOrders").value(false))
        .andExpect(jsonPath("$.canViewOrderWorkspace").value(true))
        .andExpect(jsonPath("$.canViewBankStaff").value(false));
    as(get(ORDERS + orderId), assigneeId, "ROLE_KRT_MEMBER")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assignees.length()").value(0))
        .andExpect(jsonPath("$.canEdit").value(false));
    as(
            get(ORDERS + orderId + "/materials/" + materialId + "/inventory"),
            assigneeId,
            "ROLE_KRT_MEMBER")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].missionAllocations[0].amount").value(ALLOCATED))
        .andExpect(jsonPath("$[0].missionAllocations[0].missionName").doesNotExist());
    as(get("/api/v1/missions/" + missionId), assigneeId, "ROLE_KRT_MEMBER")
        .andExpect(status().isForbidden());
    as(get("/api/v1/inventory/" + privateIds[0] + "/pool-targets"), assigneeId, "ROLE_KRT_MEMBER")
        .andExpect(status().isForbidden());
    as(get(ORDERS + orderId + "/material-collection"), assigneeId, "ROLE_KRT_MEMBER")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].ownerName").exists())
        .andExpect(jsonPath("$[0].canEdit").value(false));
    as(get("/api/v1/bank/accounts/" + privateIds[1]), assigneeId, "ROLE_KRT_MEMBER")
        .andExpect(status().isForbidden());
  }

  @Test
  void collectionRowCapabilitiesRemainStatementBoundedForARealReader() throws Exception {
    appendCollectionRows(1, 2);
    var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    statistics.setStatisticsEnabled(true);
    statistics.clear();
    as(get(ORDERS + orderId + "/material-collection"), assigneeId, "ROLE_KRT_MEMBER")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].canEdit").value(false));
    long few = statistics.getPrepareStatementCount();

    appendCollectionRows(3, 24);
    statistics.clear();
    as(get(ORDERS + orderId + "/material-collection"), assigneeId, "ROLE_KRT_MEMBER")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(25))
        .andExpect(jsonPath("$[0].canEdit").value(false));
    assertThat(statistics.getPrepareStatementCount()).isEqualTo(few);
    as(get(ORDERS + orderId + "/material-collection"), adminId, "ROLE_ADMIN")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].canEdit").value(true));
  }

  /**
   * Adds distinct foreign stock rows to the collection without assigning the reader stock rights.
   */
  private void appendCollectionRows(int first, int last) {
    transactionTemplate.executeWithoutResult(_ -> {
      JobOrder order = jobOrderRepository.findById(orderId).orElseThrow();
      InventoryItem source = inventoryItemRepository.findById(linkedInventoryId).orElseThrow();
      for (int index = first; index <= last; index++) {
        InventoryItem row = new InventoryItem();
        row.setUser(source.getUser());
        row.setMaterial(source.getMaterial());
        row.setLocation(source.getLocation());
        row.setOwningOrgUnit(order.getResponsibleOrgUnit());
        row.setQuality(500 + index);
        row.setAmount(1.0);
        InventoryAllocations.addJobOrder(row, orderId, 1.0, false);
        inventoryItemRepository.save(row);
      }
    });
  }

  @Test
  void theOrphanedLinkedStockHidesTheInternalMissionToo() throws Exception {
    as(get(ORDERS + orderId + "/allocations/orphaned"), assigneeId, "ROLE_KRT_MEMBER")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].missionAllocations[0].amount").value(ALLOCATED))
        .andExpect(jsonPath("$[0].missionAllocations[0].missionName").doesNotExist())
        .andExpect(jsonPath("$[0].missionAllocations[0].missionPlannedStartTime").doesNotExist());
  }

  @Test
  void aViewerWhoMaySeeTheMissionKeepsItsNameAndStart() throws Exception {
    as(
            get(ORDERS + orderId + "/materials/" + materialId + "/inventory"),
            unitAMemberId,
            "ROLE_KRT_MEMBER")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].missionAllocations[0].missionName").value(MISSION_NAME + tag))
        .andExpect(jsonPath("$[0].missionAllocations[0].missionPlannedStartTime").exists());
  }

  private ResultActions as(MockHttpServletRequestBuilder request, UUID userId, String role) {
    try {
      return mockMvc.perform(
          request
              .with(
                  jwt()
                      .jwt(token -> token.subject(userId.toString()))
                      .authorities(new SimpleGrantedAuthority(role)))
              .accept(MediaType.APPLICATION_JSON));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private Squadron newSquadron(String name, String shorthand) {
    Squadron s = new Squadron();
    s.setName(name);
    s.setShorthand(shorthand);
    withinProfit(s, persistedProfitArea(orgUnitRepository));
    return squadronRepository.save(s);
  }

  private User newUser(String username, UUID id) {
    User user = new User();
    user.setId(id);
    user.setUsername(username);
    return userRepository.save(user);
  }

  private void joinUnit(User user, Squadron unit) {
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(user.getId(), unit.getId()));
    membership.setUser(user);
    membership.setKind(OrgUnitKind.SQUADRON);
    membership.setJoinedAt(Instant.now());
    orgUnitMembershipRepository.save(membership);
  }
}

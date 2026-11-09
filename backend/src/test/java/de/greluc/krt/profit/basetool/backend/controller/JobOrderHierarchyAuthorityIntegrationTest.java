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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.blueprint.api.MemberBlueprintSharings;
import de.greluc.krt.profit.basetool.backend.blueprint.api.PersonalBlueprint;
import de.greluc.krt.profit.basetool.backend.blueprint.api.PersonalBlueprintRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Blueprint;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTierRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderAssignee;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderHandover;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderHandoverItem;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderType;
import de.greluc.krt.profit.basetool.backend.joborder.api.MaterialClaim;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderHandoverRepository;
import de.greluc.krt.profit.basetool.backend.joborder.internal.MaterialClaimRepository;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Department;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgHierarchyService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.OrgUnitContextualAuthority;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/** Verifies current creation authority and recovery through the public order HTTP API. */
@SpringBootTest
@Transactional
class JobOrderHierarchyAuthorityIntegrationTest {

  @Autowired private WebApplicationContext context;
  @Autowired private OrgHierarchyService hierarchy;
  @Autowired private OrgUnitRepository units;
  @Autowired private SquadronRepository squadrons;
  @Autowired private UserRepository users;
  @Autowired private OrgUnitMembershipRepository memberships;
  @Autowired private JobOrderRepository orders;
  @Autowired private MaterialClaimRepository claims;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private JobOrderHandoverRepository handovers;
  @Autowired private QualityTierRepository tiers;
  @Autowired private MaterialRepository materials;
  @Autowired private GameItemRepository gameItems;
  @Autowired private BlueprintRepository blueprints;
  @Autowired private PersonalBlueprintRepository personalBlueprints;
  @Autowired private MemberBlueprintSharings sharings;

  private MockMvc mvc;
  private OrgUnit profit;
  private Squadron responsible;
  private Squadron requester;
  private UUID caller;
  private UUID material;
  private UUID gameItemId;
  private UUID blueprintId;

  @BeforeEach
  void seed() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    String tag = UUID.randomUUID().toString();
    List<UUID> roots = units.findActiveProfitRootIds();
    profit =
        roots.isEmpty()
            ? hierarchy.createBereich("Profit " + tag, "P" + tag, null, null, Department.PROFIT)
            : units.findById(roots.getFirst()).orElseThrow();
    responsible = squadron("Responsible " + tag, profit);
    requester = squadron("Requester " + tag, null);
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("hierarchy-" + tag);
    caller = users.saveAndFlush(user).getId();
    join(user, responsible);
    join(user, requester);
    Material row = new Material();
    row.setName("Hierarchy material " + tag);
    row.setType(MaterialType.RAW);
    material = materials.saveAndFlush(row).getId();
    GameItem gameItem = new GameItem();
    gameItem.setName("Hierarchy item " + tag);
    gameItem = gameItems.saveAndFlush(gameItem);
    gameItemId = gameItem.getId();
    Blueprint blueprint = new Blueprint();
    blueprint.setOutputItem(gameItem);
    blueprint.setOutputName(gameItem.getName());
    blueprintId = blueprints.saveAndFlush(blueprint).getId();
  }

  @Test
  void requesterReadsTheSameCompleteOrderInQueueNumberDetailAndLinkedReadsWithoutEnrollment()
      throws Exception {
    memberships.deleteById(new OrgUnitMembershipId(caller, responsible.getId()));
    memberships.flush();
    JobOrder order =
        JobOrder.builder()
            .responsibleOrgUnit(responsible)
            .requestingOrgUnit(requester)
            .handle("Shared requester workspace")
            .build();
    order.addMaterial(
        JobOrderMaterial.builder()
            .material(materials.findById(material).orElseThrow())
            .qualityTier(tiers.findByMinQuality(0).orElseThrow())
            .amount(12.0)
            .build());
    order = orders.saveAndFlush(order);

    as(get("/api/v1/orders").param("size", "1"), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].id").value(order.getId().toString()))
        .andExpect(jsonPath("$.content[0].materials[0].currentStock").value(0.0));
    as(get("/api/v1/orders/requested"), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].materials[0].currentStock").value(0.0));
    as(get("/api/v1/orders/by-number").param("number", order.getDisplayId().toString()), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(order.getId().toString()));
    as(get("/api/v1/orders/" + order.getId()), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.redacted").doesNotExist())
        .andExpect(jsonPath("$.materials[0].currentStock").value(0.0))
        .andExpect(jsonPath("$.canEdit").value(false))
        .andExpect(jsonPath("$.canManageAssignees").value(false));
    as(get("/api/v1/orders/" + order.getId() + "/claims"), null).andExpect(status().isOk());
    as(get("/api/v1/orders/" + order.getId() + "/allocations/orphaned"), null)
        .andExpect(status().isOk());
    as(post("/api/v1/orders/" + order.getId() + "/assignees/" + caller), null)
        .andExpect(status().isForbidden());
  }

  @Test
  void unitWorkCapabilityAdmitsOrdinaryUnitReadersWithoutConferringWrites() throws Exception {
    JobOrder order =
        orders.saveAndFlush(
            JobOrder.builder()
                .responsibleOrgUnit(responsible)
                .requestingOrgUnit(requester)
                .handle("Unit work capabilities")
                .build());
    as(get("/api/v1/me/capabilities"), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.canViewOrderWorkspace").value(true))
        .andExpect(jsonPath("$.canViewOrderUnitWork").value(true))
        .andExpect(jsonPath("$.isLogisticianOrAbove").value(false))
        .andExpect(jsonPath("$.canCreateJobOrders").value(false));
    as(get("/api/v1/orders").param("toProcess", "true"), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].canEdit").value(false));

    memberships.deleteById(new OrgUnitMembershipId(caller, responsible.getId()));
    memberships.flush();
    as(get("/api/v1/me/capabilities"), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.canViewOrderWorkspace").value(true))
        .andExpect(jsonPath("$.canViewOrderUnitWork").value(false));
    as(get("/api/v1/orders").param("toProcess", "true"), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));

    as(get("/api/v1/me/capabilities"), responsible.getId())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.canViewJobOrders").value(false))
        .andExpect(jsonPath("$.canViewOrderWorkspace").value(true))
        .andExpect(jsonPath("$.canViewOrderUnitWork").value(true));
    as(get("/api/v1/orders").param("toProcess", "true"), responsible.getId())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].id").value(order.getId().toString()));
  }

  @Test
  void sharedReferenceLookupKeepsPriorityAndNewestNumberOrdering() throws Exception {
    memberships.deleteById(new OrgUnitMembershipId(caller, responsible.getId()));
    memberships.flush();
    JobOrder oldest =
        orders.saveAndFlush(
            JobOrder.builder()
                .responsibleOrgUnit(responsible)
                .requestingOrgUnit(requester)
                .priority(2)
                .handle("Older reference")
                .build());
    JobOrder first =
        orders.saveAndFlush(
            JobOrder.builder()
                .responsibleOrgUnit(responsible)
                .requestingOrgUnit(requester)
                .priority(1)
                .handle("Priority reference")
                .build());
    JobOrder newest =
        orders.saveAndFlush(
            JobOrder.builder()
                .responsibleOrgUnit(responsible)
                .requestingOrgUnit(requester)
                .priority(2)
                .handle("Newer reference")
                .build());
    JobOrder withoutPriority =
        orders.saveAndFlush(
            JobOrder.builder()
                .responsibleOrgUnit(responsible)
                .requestingOrgUnit(requester)
                .handle("Unprioritized reference")
                .build());

    as(get("/api/v1/orders/lookup"), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(4))
        .andExpect(jsonPath("$[0].id").value(first.getId().toString()))
        .andExpect(jsonPath("$[1].id").value(newest.getId().toString()))
        .andExpect(jsonPath("$[2].id").value(oldest.getId().toString()))
        .andExpect(jsonPath("$[3].id").value(withoutPriority.getId().toString()));
  }

  @Test
  void directProfitAreaProducerPanelIncludesItsMembersChildrenAndExplicitGlobalSharersOnly()
      throws Exception {
    User areaOwner = users.findById(caller).orElseThrow();
    join(areaOwner, profit);
    blueprintOwner("Squadron producer", responsible, false);
    blueprintOwner("Command producer", processingUnit(), false);
    User globalOwner = blueprintOwner("Global producer", requester, true);
    blueprintOwner("Private outsider", requester, false);
    Squadron inactiveChild = squadron("Inactive producer " + UUID.randomUUID(), profit);
    blueprintOwner("Inactive child producer", inactiveChild, false);
    inactiveChild.setActive(false);
    squadrons.saveAndFlush(inactiveChild);
    personalBlueprints.saveAndFlush(
        PersonalBlueprint.builder()
            .ownerUserId(caller)
            .productKey("area-producer")
            .productName(gameItems.findById(gameItemId).orElseThrow().getName())
            .build());
    JobOrder order =
        JobOrder.builder()
            .responsibleOrgUnit(profit)
            .requestingOrgUnit(requester)
            .type(JobOrderType.ITEM)
            .build();
    order.addItem(
        JobOrderItem.builder()
            .blueprint(blueprints.findById(blueprintId).orElseThrow())
            .gameItem(gameItems.findById(gameItemId).orElseThrow())
            .amount(2)
            .build());
    order = orders.saveAndFlush(order);

    as(get("/api/v1/orders/" + order.getId() + "/item-blueprint-owners"), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requiredBlueprints[0].ownerCount").value(5))
        .andExpect(jsonPath("$.owners.length()").value(5));
    caller = globalOwner.getId();
    as(get("/api/v1/orders/" + order.getId()), null).andExpect(status().isOk());
    as(get("/api/v1/orders/" + order.getId() + "/item-blueprint-owners"), null)
        .andExpect(status().isForbidden());
  }

  private User blueprintOwner(String name, OrgUnit unit, boolean global) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(name + UUID.randomUUID());
    user = users.saveAndFlush(user);
    join(user, unit);
    personalBlueprints.saveAndFlush(
        PersonalBlueprint.builder()
            .ownerUserId(user.getId())
            .productKey("producer")
            .productName(gameItems.findById(gameItemId).orElseThrow().getName())
            .build());
    if (global) {
      sharings.update(user.getId(), true, 0L);
    }
    return user;
  }

  @Test
  void kindFilterNarrowsEveryWorkspaceScopeAndItsDatabasePageCount() throws Exception {
    JobOrder item = null;
    for (JobOrderType kind : JobOrderType.values()) {
      JobOrder order =
          JobOrder.builder()
              .type(kind)
              .responsibleOrgUnit(responsible)
              .requestingOrgUnit(requester)
              .build();
      order.addAssignee(
          JobOrderAssignee.builder().user(users.findById(caller).orElseThrow()).build());
      order = orders.saveAndFlush(order);
      if (kind == JobOrderType.ITEM) {
        item = order;
      }
    }
    for (String endpoint :
        List.of(
            "/api/v1/orders",
            "/api/v1/orders/requested",
            "/api/v1/orders?toProcess=true",
            "/api/v1/orders?assignedToMe=true")) {
      as(get(endpoint).param("type", "ITEM").param("size", "1"), null)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.totalElements").value(1))
          .andExpect(jsonPath("$.content[0].type").value("ITEM"))
          .andExpect(jsonPath("$.content[0].id").value(item.getId().toString()));
    }
    as(
            get("/api/v1/orders")
                .param("type", "ITEM")
                .param("squadronId", UUID.randomUUID().toString()),
            null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  void requesterReadsTheSameOrderInDemandLookupCollectionAndPersistedReports() throws Exception {
    memberships.deleteById(new OrgUnitMembershipId(caller, responsible.getId()));
    memberships.flush();
    JobOrder order =
        JobOrder.builder()
            .responsibleOrgUnit(responsible)
            .requestingOrgUnit(requester)
            .handle("Shared read representations")
            .build();
    order.addMaterial(
        JobOrderMaterial.builder()
            .material(materials.findById(material).orElseThrow())
            .qualityTier(tiers.findByMinQuality(0).orElseThrow())
            .amount(12.0)
            .build());
    JobOrderHandover handover =
        JobOrderHandover.builder()
            .handoverTime(Instant.now())
            .recipientHandle("Shared recipient")
            .build();
    handover.addItem(
        JobOrderHandoverItem.builder()
            .material(materials.findById(material).orElseThrow())
            .amount(2.0)
            .quality(650)
            .locationName("Shared hangar")
            .build());
    handover.setJobOrder(order);
    order.getHandovers().add(handover);
    order = orders.saveAndFlush(order);

    as(get("/api/v1/orders/material-demand"), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.groups.length()").value(1))
        .andExpect(jsonPath("$.groups[0].orgUnit.id").value(responsible.getId().toString()));
    as(get("/api/v1/orders/lookup"), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(order.getId().toString()));
    as(get("/api/v1/orders/" + order.getId() + "/materials/" + material + "/inventory"), null)
        .andExpect(status().isOk());
    as(get("/api/v1/orders/" + order.getId() + "/materials/" + material + "/attribution"), null)
        .andExpect(status().isOk());
    as(get("/api/v1/orders/" + order.getId() + "/material-collection"), null)
        .andExpect(status().isOk());
    as(get("/api/v1/orders/" + order.getId() + "/handovers/" + handover.getId() + "/report"), null)
        .andExpect(status().isOk());
  }

  @Test
  void contextualRequesterWriterWithoutMembershipGetsTheConcreteWorkspaceCapability()
      throws Exception {
    memberships.deleteById(new OrgUnitMembershipId(caller, responsible.getId()));
    memberships.deleteById(new OrgUnitMembershipId(caller, requester.getId()));
    memberships.flush();
    JobOrder order =
        orders.saveAndFlush(
            JobOrder.builder()
                .responsibleOrgUnit(responsible)
                .requestingOrgUnit(requester)
                .build());

    as(get("/api/v1/me/capabilities"), requester.getId())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.canViewJobOrders").value(false))
        .andExpect(jsonPath("$.canViewOwnJobOrders").value(true));
    as(get("/api/v1/orders/requested"), requester.getId())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].id").value(order.getId().toString()));
    as(get("/api/v1/orders"), requester.getId())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].id").value(order.getId().toString()));
  }

  @Test
  void observedFormerResponsibleUnitRetainsOnlyConcreteReadThroughCurrentMembership()
      throws Exception {
    memberships.deleteById(new OrgUnitMembershipId(caller, requester.getId()));
    memberships.flush();
    Squadron customer = squadron("Historical customer " + UUID.randomUUID(), null);
    Squadron target = squadron("Historical target " + UUID.randomUUID(), profit);
    JobOrder order =
        orders.saveAndFlush(
            JobOrder.builder()
                .responsibleOrgUnit(responsible)
                .requestingOrgUnit(customer)
                .originalProfitOrgUnitId(profit.getId())
                .build());

    as(
            patch("/api/v1/orders/" + order.getId() + "/responsible-org-unit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"responsibleOrgUnitId\":\"" + target.getId() + "\"}"),
            responsible.getId())
        .andExpect(status().isOk());
    hierarchy.setParent(responsible.getId(), null, responsible.getVersion());
    JobOrder unrelated =
        orders.saveAndFlush(
            JobOrder.builder().responsibleOrgUnit(target).requestingOrgUnit(customer).build());

    as(get("/api/v1/orders").param("size", "1"), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].id").value(order.getId().toString()));
    as(get("/api/v1/orders/by-number").param("number", order.getDisplayId().toString()), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(order.getId().toString()));
    as(get("/api/v1/orders/" + order.getId()), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.canEdit").value(false))
        .andExpect(jsonPath("$.canManageAssignees").value(false))
        .andExpect(jsonPath("$.canTransferResponsibility").value(false));
    as(get("/api/v1/orders/" + unrelated.getId()), null).andExpect(status().isForbidden());
    as(post("/api/v1/orders/" + order.getId() + "/assignees/" + caller), null)
        .andExpect(status().isForbidden());
    memberships.deleteById(new OrgUnitMembershipId(caller, responsible.getId()));
    memberships.flush();
    as(get("/api/v1/orders/" + order.getId()), null).andExpect(status().isForbidden());
    as(get("/api/v1/orders"), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  void observedFormerRequesterRetainsAllReadWithoutCurrentRequesterWritesOrMineScope()
      throws Exception {
    memberships.deleteById(new OrgUnitMembershipId(caller, responsible.getId()));
    memberships.flush();
    Squadron newRequester = squadron("New customer " + UUID.randomUUID(), null);
    JobOrder order =
        orders.saveAndFlush(
            JobOrder.builder()
                .responsibleOrgUnit(responsible)
                .requestingOrgUnit(requester)
                .originalProfitOrgUnitId(profit.getId())
                .build());
    String payload =
        createBody().replace(requester.getId().toString(), newRequester.getId().toString());
    payload =
        payload.substring(0, payload.length() - 1) + ",\"version\":" + order.getVersion() + "}";

    as(
            put("/api/v1/orders/" + order.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload),
            responsible.getId())
        .andExpect(status().isOk());

    as(get("/api/v1/orders/" + order.getId()), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.canEdit").value(false))
        .andExpect(jsonPath("$.canEditAsRequester").value(false));
    as(get("/api/v1/orders"), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1));
    as(get("/api/v1/orders/requested"), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  void requesterAndPersonalAssignmentReadDoNotBecomeOwnProcessingUnits() throws Exception {
    memberships.deleteById(new OrgUnitMembershipId(caller, responsible.getId()));
    memberships.flush();
    JobOrder order =
        orders.saveAndFlush(
            JobOrder.builder()
                .responsibleOrgUnit(responsible)
                .requestingOrgUnit(requester)
                .build());
    as(get("/api/v1/orders"), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1));
    as(
            get("/api/v1/orders")
                .param("toProcess", "true")
                .param("orgUnitId", responsible.getId().toString()),
            null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
    as(get("/api/v1/orders").param("assignedToMe", "true"), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
    order.addAssignee(
        JobOrderAssignee.builder().user(users.findById(caller).orElseThrow()).build());
    orders.saveAndFlush(order);
    as(get("/api/v1/orders").param("assignedToMe", "true"), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1));
    as(
            get("/api/v1/orders")
                .param("toProcess", "true")
                .param("orgUnitId", responsible.getId().toString()),
            null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  void requestingWriterCanCommissionWorkWithoutWritingTheResponsibleUnit() throws Exception {
    as(
            post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(createBody()),
            requester.getId())
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.responsibilityInvalid").value(false))
        .andExpect(jsonPath("$.canEdit").value(false))
        .andExpect(jsonPath("$.canEditAsRequester").value(true))
        .andExpect(jsonPath("$.originalProfitBoundaryUnknown").value(false));
  }

  @Test
  void responsibleWriterCanCommissionForAnOutsideRequester() throws Exception {
    as(
            post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(createBody()),
            responsible.getId())
        .andExpect(status().isCreated());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void eitherCurrentUnitWriterCanCreateItemOrders(boolean requestingWriter) throws Exception {
    as(
            post("/api/v1/orders/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createItemBody()),
            requestingWriter ? requester.getId() : responsible.getId())
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.type").value("ITEM"));
  }

  @Test
  void mereMembershipAndAGrantInAnotherContextCannotCreate() throws Exception {
    as(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(createBody()), null)
        .andExpect(status().isForbidden());
    as(
            post("/api/v1/orders/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createItemBody()),
            null)
        .andExpect(status().isForbidden());
    as(
            post("/api/v1/orders/items")
                .header("X-Active-Org-Unit-Id", requester.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createItemBody()),
            responsible.getId())
        .andExpect(status().isForbidden());
    as(
            post("/api/v1/orders")
                .header("X-Active-Org-Unit-Id", requester.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody()),
            responsible.getId())
        .andExpect(status().isForbidden());
  }

  @ParameterizedTest
  @EnumSource(JobOrderStatus.class)
  void invalidResponsibilityInEveryStatusAllowsConcreteReadingAndImmediateValidTransferOnly(
      JobOrderStatus state) throws Exception {
    JobOrder order =
        orders.saveAndFlush(
            JobOrder.builder()
                .responsibleOrgUnit(responsible)
                .requestingOrgUnit(requester)
                .status(state)
                .handle("Recoverable")
                .build());
    hierarchy.setParent(responsible.getId(), null, responsible.getVersion());
    String path = "/api/v1/orders/" + order.getId();

    as(get(path), responsible.getId())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.responsibilityInvalid").value(true))
        .andExpect(jsonPath("$.canTransferResponsibility").value(true));
    JobOrder foreign =
        orders.saveAndFlush(
            JobOrder.builder()
                .responsibleOrgUnit(profit)
                .requestingOrgUnit(profit)
                .handle("Unrelated")
                .build());
    as(get("/api/v1/me/capabilities"), responsible.getId())
        .andExpect(jsonPath("$.canViewJobOrders").value(false));
    as(get("/api/v1/orders/" + foreign.getId()), responsible.getId())
        .andExpect(status().isForbidden());
    as(
            get("/api/v1/orders/by-number").param("number", foreign.getDisplayId().toString()),
            responsible.getId())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
    as(
            get("/api/v1/orders/by-number").param("number", order.getDisplayId().toString()),
            responsible.getId())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id == '" + order.getId() + "')]").isNotEmpty());
    as(get("/api/v1/orders").param("size", "1000"), responsible.getId())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[?(@.id == '" + order.getId() + "')]").isNotEmpty());
    as(
            put(path + "/status")
                .header("Accept-Language", "de")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"IN_PROGRESS\",\"version\":" + order.getVersion() + "}"),
            responsible.getId())
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.detail")
                .value(
                    "Die verantwortliche Einheit gehört nicht mehr zur gültigen Profit-Hierarchie."
                        + " Dieser Auftrag kann nur gelesen oder an eine gültige Einheit"
                        + " umgeschrieben werden."));
    as(
            put(path + "/status")
                .header("Accept-Language", "en")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"IN_PROGRESS\",\"version\":" + order.getVersion() + "}"),
            responsible.getId())
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.detail")
                .value(
                    "The responsible unit is outside the valid Profit hierarchy. This order can"
                        + " only be read or transferred to an eligible unit."));
    as(
            patch(path + "/responsible-org-unit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"responsibleOrgUnitId\":\"" + profit.getId() + "\"}"),
            responsible.getId())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.responsibilityInvalid").value(false))
        .andExpect(jsonPath("$.originalProfitBoundaryUnknown").value(true))
        .andExpect(jsonPath("$.status").value(state.name()))
        .andExpect(jsonPath("$.requestingOrgUnit.id").value(requester.getId().toString()));
  }

  @Test
  void requesterEditCapabilityRequiresItsCurrentWriterAndNeverGrantsProcessing() throws Exception {
    memberships.deleteById(new OrgUnitMembershipId(caller, responsible.getId()));
    memberships.flush();
    var quality = tiers.findByCode("GOOD").orElseThrow();
    JobOrder order =
        JobOrder.builder()
            .responsibleOrgUnit(responsible)
            .requestingOrgUnit(requester)
            .handle("Requester capability")
            .build();
    order.addMaterial(
        JobOrderMaterial.builder()
            .material(materials.getReferenceById(material))
            .qualityTier(quality)
            .amount(10.0)
            .build());
    order = orders.saveAndFlush(order);
    String endpoint = "/api/v1/orders/" + order.getId();

    as(get(endpoint), requester.getId())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.redacted").doesNotExist())
        .andExpect(jsonPath("$.canEdit").value(false))
        .andExpect(jsonPath("$.canEditAsRequester").value(true));
    as(get(endpoint), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.redacted").doesNotExist())
        .andExpect(jsonPath("$.canEdit").value(false))
        .andExpect(jsonPath("$.canEditAsRequester").value(false));
    JobOrderHandover delivery =
        JobOrderHandover.builder()
            .jobOrder(order)
            .handoverTime(Instant.now())
            .recipientHandle("Partial delivery")
            .build();
    delivery.addItem(
        JobOrderHandoverItem.builder()
            .material(materials.getReferenceById(material))
            .quality(quality.getMinQuality())
            .amount(1.0)
            .build());
    handovers.saveAndFlush(delivery);

    as(get(endpoint), requester.getId())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.canEdit").value(false))
        .andExpect(jsonPath("$.canEditAsRequester").value(false));
  }

  @Test
  void claimingOptionsPreserveOwnSquadronWritesWithoutGrantingProcessingOrForeignClaims()
      throws Exception {
    SpecialCommand processing = processingUnit();
    Squadron foreign = squadron("Foreign claimant " + UUID.randomUUID(), profit);
    var quality = tiers.findByCode("GOOD").orElseThrow();
    JobOrder order =
        JobOrder.builder()
            .responsibleOrgUnit(processing)
            .requestingOrgUnit(requester)
            .handle("Claim options fixture")
            .build();
    order.addMaterial(
        JobOrderMaterial.builder()
            .material(materials.getReferenceById(material))
            .qualityTier(quality)
            .amount(10.0)
            .build());
    order = orders.saveAndFlush(order);
    MaterialClaim ownClaim =
        claims.saveAndFlush(
            MaterialClaim.builder()
                .jobOrder(order)
                .material(materials.getReferenceById(material))
                .qualityTier(quality)
                .claimingOrgUnit(responsible)
                .amount(2.0)
                .build());
    MaterialClaim foreignClaim =
        claims.saveAndFlush(
            MaterialClaim.builder()
                .jobOrder(order)
                .material(materials.getReferenceById(material))
                .qualityTier(quality)
                .claimingOrgUnit(foreign)
                .amount(3.0)
                .build());
    String path = "/api/v1/orders/" + order.getId();
    String options = path + "/claims/claiming-options";

    as(get(path), responsible.getId())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.canEdit").value(false));
    as(get(options), responsible.getId())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].orgUnitId").value(responsible.getId().toString()))
        .andExpect(jsonPath("$[0].kind").value("SQUADRON"));
    as(get(options), null).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
    as(get(options).header("X-Active-Org-Unit-Id", requester.getId()), responsible.getId())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
    as(delete(path + "/claims/" + foreignClaim.getId()), responsible.getId())
        .andExpect(status().isForbidden());
    as(delete(path + "/claims/" + ownClaim.getId()), null).andExpect(status().isForbidden());
    as(delete(path + "/claims/" + ownClaim.getId()), responsible.getId())
        .andExpect(status().isNoContent());
    claims.flush();
    assertThat(
            jdbc.queryForObject(
                "SELECT ended_at IS NOT NULL FROM material_claim WHERE id = ?",
                Boolean.class,
                ownClaim.getId()))
        .isTrue();
    assertThat(claims.findById(foreignClaim.getId())).isPresent();

    as(get(options).header("X-Active-Org-Unit-Id", requester.getId()), processing.getId())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.orgUnitId == '" + responsible.getId() + "')]").isNotEmpty())
        .andExpect(jsonPath("$[?(@.orgUnitId == '" + foreign.getId() + "')]").isNotEmpty());
    JobOrder privateOrder =
        orders.saveAndFlush(
            JobOrder.builder()
                .responsibleOrgUnit(foreign)
                .requestingOrgUnit(foreign)
                .handle("Foreign readable order")
                .build());
    as(
            get("/api/v1/orders/" + privateOrder.getId() + "/claims/claiming-options"),
            responsible.getId())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"COMPLETED", "REJECTED", "NON_SK", "INVALID"})
  void claimingOptionsNeverOfferWritesForFrozenOrInvalidOrders(String block) throws Exception {
    SpecialCommand processing = processingUnit();
    JobOrder order =
        orders.saveAndFlush(
            JobOrder.builder()
                .responsibleOrgUnit(processing)
                .requestingOrgUnit(requester)
                .handle("Blocked options")
                .build());
    switch (block) {
      case "COMPLETED", "REJECTED" -> order.setStatus(JobOrderStatus.valueOf(block));
      case "NON_SK" -> order.setResponsibleOrgUnit(responsible);
      case "INVALID" -> hierarchy.setParent(processing.getId(), null, processing.getVersion());
      default -> throw new IllegalArgumentException(block);
    }
    orders.flush();

    as(get("/api/v1/orders/" + order.getId() + "/claims/claiming-options"), responsible.getId())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
  }

  @ParameterizedTest
  @CsvSource({"MATERIAL,true", "MATERIAL,false", "ITEM,true", "ITEM,false"})
  void explicitAdminDeleteHandlesArchivedPromisesOnlyWithValidResponsibility(
      JobOrderType kind, boolean validResponsibility) throws Exception {
    String tag = UUID.randomUUID().toString();
    SpecialCommand processing = new SpecialCommand();
    processing.setName("Delete processing " + tag);
    processing.setShorthand("DP" + tag);
    processing.setParent(profit);
    processing = units.saveAndFlush(processing);
    JobOrder order =
        orders.saveAndFlush(
            JobOrder.builder()
                .type(kind)
                .responsibleOrgUnit(processing)
                .requestingOrgUnit(requester)
                .status(JobOrderStatus.COMPLETED)
                .handle("Delete fixture")
                .build());
    MaterialClaim archived =
        claims.saveAndFlush(
            MaterialClaim.builder()
                .jobOrder(order)
                .material(materials.getReferenceById(material))
                .qualityTier(tiers.findByCode("GOOD").orElseThrow())
                .claimingOrgUnit(responsible)
                .amount(3.0)
                .claimedByUser(users.getReferenceById(caller))
                .endedAt(Instant.now())
                .build());
    if (!validResponsibility) {
      hierarchy.setParent(processing.getId(), null, processing.getVersion());
    }
    var history = jdbc.queryForMap("SELECT * FROM material_claim WHERE id = ?", archived.getId());

    mvc.perform(
            delete("/api/v1/orders/" + order.getId())
                .with(
                    jwt()
                        .jwt(token -> token.subject(caller.toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(validResponsibility ? status().isNoContent() : status().isBadRequest());

    orders.flush();
    assertThat(orders.existsById(order.getId())).isEqualTo(!validResponsibility);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE subject_id = ? AND event_type ="
                    + " 'JOB_ORDER_DELETED'",
                Integer.class,
                order.getId()))
        .isEqualTo(validResponsibility ? 1 : 0);
    if (validResponsibility) {
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM material_claim WHERE id = ?",
                  Integer.class,
                  archived.getId()))
          .isZero();
    } else {
      assertThat(jdbc.queryForMap("SELECT * FROM material_claim WHERE id = ?", archived.getId()))
          .containsExactlyInAnyOrderEntriesOf(history);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void coveringWriterCannotCommissionAfterRequesterMovesOutsideItsReach(boolean itemOrder)
      throws Exception {
    String tag = UUID.randomUUID().toString();
    OrgUnit grantingArea =
        hierarchy.createBereich("Grant area " + tag, "GA" + tag, null, null, null);
    OrgUnit otherArea = hierarchy.createBereich("Other area " + tag, "OA" + tag, null, null, null);
    hierarchy.setParent(requester.getId(), grantingArea.getId(), requester.getVersion());
    String endpoint = itemOrder ? "/api/v1/orders/items" : "/api/v1/orders";
    String payload = itemOrder ? createItemBody() : createBody();

    as(
            post(endpoint).contentType(MediaType.APPLICATION_JSON).content(payload),
            grantingArea.getId())
        .andExpect(status().isCreated());

    hierarchy.setParent(requester.getId(), otherArea.getId(), requester.getVersion());

    as(
            post(endpoint).contentType(MediaType.APPLICATION_JSON).content(payload),
            grantingArea.getId())
        .andExpect(status().isForbidden());
  }

  @Test
  void aValidCurrentHierarchyDoesNotRequireALegacyOrderToTransferFirst() throws Exception {
    JobOrder order =
        orders.saveAndFlush(
            JobOrder.builder()
                .responsibleOrgUnit(responsible)
                .requestingOrgUnit(requester)
                .handle("Unknown past")
                .build());

    as(
            put("/api/v1/orders/" + order.getId() + "/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"IN_PROGRESS\",\"version\":" + order.getVersion() + "}"),
            responsible.getId())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.originalProfitBoundaryUnknown").value(true));
  }

  private SpecialCommand processingUnit() {
    String tag = UUID.randomUUID().toString();
    SpecialCommand unit = new SpecialCommand();
    unit.setName("Claim processing " + tag);
    unit.setShorthand("CP" + tag);
    unit.setParent(profit);
    return units.saveAndFlush(unit);
  }

  private Squadron squadron(String name, OrgUnit parent) {
    Squadron row = new Squadron();
    row.setName(name);
    row.setShorthand(name);
    row.setParent(parent);
    return squadrons.saveAndFlush(row);
  }

  private void join(User user, OrgUnit unit) {
    OrgUnitMembership row = new OrgUnitMembership();
    row.setId(new OrgUnitMembershipId(user.getId(), unit.getId()));
    row.setUser(user);
    row.setKind(unit.getKind());
    row.setJoinedAt(Instant.now());
    memberships.saveAndFlush(row);
  }

  private String createItemBody() {
    return "{\"responsibleOrgUnitId\":\""
        + responsible.getId()
        + "\",\"requestingOrgUnitId\":\""
        + requester.getId()
        + "\",\"items\":[{\"gameItemId\":\""
        + gameItemId
        + "\",\"blueprintId\":\""
        + blueprintId
        + "\",\"amount\":1,\"materials\":[]}]}";
  }

  private String createBody() {
    return "{\"responsibleOrgUnitId\":\""
        + responsible.getId()
        + "\",\"requestingOrgUnitId\":\""
        + requester.getId()
        + "\",\"handle\":\"Hierarchy test\",\"materials\":[{\"materialId\":\""
        + material
        + "\",\"amount\":1.0}]}";
  }

  private ResultActions as(MockHttpServletRequestBuilder request, UUID grant) throws Exception {
    List<GrantedAuthority> authorities =
        grant == null
            ? List.of(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"))
            : List.of(
                new SimpleGrantedAuthority("ROLE_KRT_MEMBER"),
                new SimpleGrantedAuthority("ROLE_LOGISTICIAN"),
                new OrgUnitContextualAuthority(Roles.LOGISTICIAN, grant));
    return mvc.perform(
        request
            .with(jwt().jwt(token -> token.subject(caller.toString())).authorities(authorities))
            .accept(MediaType.APPLICATION_JSON));
  }
}

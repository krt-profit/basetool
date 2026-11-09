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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderHandover;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderHandoverItem;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderHandoverRepository;
import de.greluc.krt.profit.basetool.backend.kernel.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Department;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgHierarchyService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.OrgUnitContextualAuthority;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
  @Autowired private JobOrderHandoverRepository handovers;
  @Autowired private QualityTierRepository tiers;
  @Autowired private MaterialRepository materials;
  @Autowired private GameItemRepository gameItems;
  @Autowired private BlueprintRepository blueprints;

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
        .andExpect(jsonPath("$.redacted").value(true))
        .andExpect(jsonPath("$.canEdit").value(false))
        .andExpect(jsonPath("$.canEditAsRequester").value(true));
    as(get(endpoint), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.redacted").value(true))
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

  private Squadron squadron(String name, OrgUnit parent) {
    Squadron row = new Squadron();
    row.setName(name);
    row.setShorthand(name);
    row.setParent(parent);
    return squadrons.saveAndFlush(row);
  }

  private void join(User user, Squadron unit) {
    OrgUnitMembership row = new OrgUnitMembership();
    row.setId(new OrgUnitMembershipId(user.getId(), unit.getId()));
    row.setUser(user);
    row.setKind(OrgUnitKind.SQUADRON);
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

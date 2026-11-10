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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTierRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderParticipation;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderType;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderParticipationRepository;
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
import de.greluc.krt.profit.basetool.backend.platform.api.OrgUnitContextualAuthority;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

/** Verifies shared participation readership and current unit work through the public HTTP API. */
@SpringBootTest
@Transactional
class JobOrderSupportReadIntegrationTest {
  @Autowired private WebApplicationContext context;
  @Autowired private OrgHierarchyService hierarchy;
  @Autowired private OrgUnitRepository units;
  @Autowired private UserRepository users;
  @Autowired private OrgUnitMembershipRepository memberships;
  @Autowired private JobOrderRepository orders;
  @Autowired private JobOrderParticipationRepository participations;
  @Autowired private MaterialRepository materials;
  @Autowired private QualityTierRepository tiers;

  private MockMvc mvc;
  private OrgUnit profit;
  private OrgUnit responsible;
  private Squadron support;
  private OrgUnit requester;
  private User caller;
  private Material material;

  @BeforeEach
  void seed() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    String tag = UUID.randomUUID().toString();
    List<UUID> roots = units.findActiveProfitRootIds();
    profit =
        roots.isEmpty()
            ? hierarchy.createBereich(
                "Support profit " + tag, "SP" + tag, null, null, Department.PROFIT)
            : units.findById(roots.getFirst()).orElseThrow();
    SpecialCommand command = new SpecialCommand();
    command.setName("Support processor " + tag);
    command.setShorthand("SProc" + tag);
    command.setParent(profit);
    responsible = units.saveAndFlush(command);
    support = new Squadron();
    support.setName("Supporting squadron " + tag);
    support.setShorthand("SS" + tag);
    support.setParent(profit);
    support = units.saveAndFlush(support);
    Squadron customer = new Squadron();
    customer.setName("Support customer " + tag);
    customer.setShorthand("SC" + tag);
    requester = units.saveAndFlush(customer);
    caller = new User();
    caller.setId(UUID.randomUUID());
    caller.setUsername("support-reader-" + tag);
    caller = users.saveAndFlush(caller);
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(caller.getId(), support.getId()));
    membership.setUser(caller);
    membership.setKind(support.getKind());
    membership.setJoinedAt(Instant.now());
    memberships.saveAndFlush(membership);
    material = new Material();
    material.setName("Support material " + tag);
    material.setType(MaterialType.RAW);
    material = materials.saveAndFlush(material);
  }

  @Test
  void activeSupportWithNoOpenPromiseRemainsUnitWorkAndCountsBeforePagination() throws Exception {
    JobOrder first = supported(JobOrderType.MATERIAL, true);
    JobOrder second = supported(JobOrderType.ITEM, true);
    supported(JobOrderType.MATERIAL, false);
    order(JobOrderType.MATERIAL);

    as(get("/api/v1/orders").param("toProcess", "true").param("size", "1"), false, false)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].canEdit").value(false));
    as(get("/api/v1/orders").param("toProcess", "true").param("type", "ITEM"), false, false)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].id").value(second.getId().toString()));
    as(
            get("/api/v1/orders")
                .param("toProcess", "true")
                .param("orgUnitId", requester.getId().toString()),
            false,
            false)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
    as(
            get("/api/v1/orders")
                .param("size", "1")
                .header("X-Active-Org-Unit-Id", support.getId()),
            true,
            false)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(3));
    as(get("/api/v1/orders/" + first.getId() + "/claims"), false, false)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].claims").isEmpty());
    as(get("/api/v1/me/capabilities"), false, false)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.canViewOrderUnitWork").value(true));
  }

  @Test
  void endedSupportRetainsCompleteConcreteReadThroughCurrentMembershipOnly() throws Exception {
    JobOrder order = supported(JobOrderType.MATERIAL, false);
    JobOrder itemOrder = supported(JobOrderType.ITEM, false);
    JobOrder unrelated = order(JobOrderType.MATERIAL);
    hierarchy.setParent(support.getId(), null, support.getVersion());
    String path = "/api/v1/orders/" + order.getId();

    as(get("/api/v1/orders").param("size", "1"), false, false)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2));
    as(get("/api/v1/orders").param("page", "1").param("size", "1"), false, false)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.totalElements").value(2));
    as(get("/api/v1/orders").param("type", "ITEM"), false, false)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].id").value(itemOrder.getId().toString()));
    as(get(path), false, false)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.materials[0].currentStock").value(0.0))
        .andExpect(jsonPath("$.canEdit").value(false))
        .andExpect(jsonPath("$.canEditAsRequester").value(false))
        .andExpect(jsonPath("$.canManageAssignees").value(false));
    as(
            get("/api/v1/orders/by-number").param("number", order.getDisplayId().toString()),
            false,
            false)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(order.getId().toString()));
    as(get("/api/v1/orders/lookup"), false, false)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id == '" + order.getId() + "')]").isNotEmpty())
        .andExpect(jsonPath("$[?(@.id == '" + unrelated.getId() + "')]").isEmpty());
    as(get("/api/v1/orders/material-demand"), false, false)
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.groups[0].materials[0].orders[0].jobOrderId")
                .value(order.getId().toString()));
    for (String suffix : List.of("/claims", "/material-collection", "/allocations/orphaned")) {
      as(get(path + suffix), false, false).andExpect(status().isOk());
    }
    as(get(path + "/item-blueprint-owners"), false, false).andExpect(status().isForbidden());
    as(get("/api/v1/orders/" + unrelated.getId()), false, false).andExpect(status().isForbidden());
    as(post(path + "/assignees/" + caller.getId()), false, false).andExpect(status().isForbidden());
    as(post(path + "/assignees/" + UUID.randomUUID()), false, false)
        .andExpect(status().isForbidden());
    as(get("/api/v1/orders").param("toProcess", "true"), false, false)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
    as(get("/api/v1/orders/requested"), false, false)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
    as(get("/api/v1/me/capabilities"), false, false)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.canViewJobOrders").value(false))
        .andExpect(jsonPath("$.canViewOrderWorkspace").value(true))
        .andExpect(jsonPath("$.canViewOrderUnitWork").value(false));

    memberships.deleteById(new OrgUnitMembershipId(caller.getId(), support.getId()));
    memberships.flush();
    as(get(path), false, false).andExpect(status().isForbidden());
    as(get("/api/v1/orders"), false, false)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  void currentContextualSupportGrantOffersUnitWorkWithoutBecomingProcessingAuthority()
      throws Exception {
    JobOrder order = supported(JobOrderType.MATERIAL, true);
    JobOrder unrelated = order(JobOrderType.MATERIAL);
    memberships.deleteById(new OrgUnitMembershipId(caller.getId(), support.getId()));
    memberships.flush();
    as(get("/api/v1/me/capabilities"), false, true)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.canViewJobOrders").value(false))
        .andExpect(jsonPath("$.canViewOrderWorkspace").value(true))
        .andExpect(jsonPath("$.canViewOrderUnitWork").value(true));
    as(get("/api/v1/orders").param("toProcess", "true"), false, true)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].id").value(order.getId().toString()))
        .andExpect(jsonPath("$.content[0].canEdit").value(false));
    as(get("/api/v1/orders/" + order.getId()), false, true).andExpect(status().isOk());
    as(get("/api/v1/orders/" + unrelated.getId()), false, true).andExpect(status().isForbidden());
    as(post("/api/v1/orders/" + order.getId() + "/assignees/" + caller.getId()), false, true)
        .andExpect(status().isForbidden());
  }

  private JobOrder supported(JobOrderType type, boolean active) {
    JobOrder order = order(type);
    JobOrderParticipation participation = new JobOrderParticipation();
    participation.setJobOrder(order);
    participation.setClaimingOrgUnit(support);
    participation.setActive(active);
    participations.saveAndFlush(participation);
    return order;
  }

  private JobOrder order(JobOrderType type) {
    JobOrder order =
        JobOrder.builder()
            .responsibleOrgUnit(responsible)
            .requestingOrgUnit(requester)
            .type(type)
            .handle("Support read witness")
            .build();
    if (type == JobOrderType.MATERIAL) {
      order.addMaterial(
          JobOrderMaterial.builder()
              .material(material)
              .qualityTier(tiers.findByMinQuality(0).orElseThrow())
              .amount(12.0)
              .build());
    }
    return orders.saveAndFlush(order);
  }

  private ResultActions as(MockHttpServletRequestBuilder request, boolean admin, boolean grant)
      throws Exception {
    List<GrantedAuthority> authorities =
        admin
            ? List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
            : grant
                ? List.of(
                    new SimpleGrantedAuthority("ROLE_KRT_MEMBER"),
                    new SimpleGrantedAuthority("ROLE_LOGISTICIAN"),
                    new OrgUnitContextualAuthority(Roles.LOGISTICIAN, support.getId()))
                : List.of(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"));
    return mvc.perform(
        request
            .with(
                jwt()
                    .jwt(token -> token.subject(caller.getId().toString()))
                    .authorities(authorities))
            .accept(MediaType.APPLICATION_JSON));
  }
}

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.kernel.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.OrgUnitContextualAuthority;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/**
 * Walks the job-order assignee grant over HTTP across two units (REQ-ORDERS-047, ADR-0301).
 *
 * <p>Unit A, profit-eligible, processes the order; unit B is not profit-eligible. The external
 * assignee and their unit-mate are plain members of B, so only the grant can open the order.
 */
@SpringBootTest
class JobOrderAssigneeGrantIntegrationTest {

  private static final String ORDERS = "/api/v1/orders/";

  @Autowired private WebApplicationContext context;
  @Autowired private JobOrderRepository jobOrderRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrgUnitMembershipRepository orgUnitMembershipRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DataSource dataSource;

  private MockMvc mockMvc;
  private UUID orderId;
  private UUID unitAId;
  private UUID unitBId;
  private UUID assigneeId;
  private UUID unitMateId;
  private UUID adminId;
  private UUID unitAMemberId;
  private String tag;

  @BeforeEach
  void seed() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    adminId = UUID.randomUUID();
    transactionTemplate.executeWithoutResult(_ -> {
      tag = UUID.randomUUID().toString().substring(0, 8);
      Squadron unitA = newSquadron("Grant-A-" + tag, "GA" + tag, true);
      Squadron unitB = newSquadron("Grant-B-" + tag, "GB" + tag, false);
      unitAId = unitA.getId();
      unitBId = unitB.getId();
      User assignee = newUser("grant-assignee-" + tag);
      User unitMate = newUser("grant-mate-" + tag);
      newUser("grant-admin-" + tag, adminId);
      assigneeId = assignee.getId();
      unitMateId = unitMate.getId();
      joinUnit(assignee, unitB);
      joinUnit(unitMate, unitB);
      User unitAMember = newUser("grant-zmember-" + tag);
      unitAMemberId = unitAMember.getId();
      joinUnit(unitAMember, unitA);
      User pending = newUser("grant-pending-" + tag);
      pending.setApprovalStatus(ApprovalStatus.PENDING);
      userRepository.save(pending);
      orderId =
          jobOrderRepository
              .save(
                  JobOrder.builder()
                      .responsibleOrgUnit(unitA)
                      .requestingOrgUnit(unitA)
                      .handle("grant-test")
                      .status(JobOrderStatus.OPEN)
                      .build())
              .getId();
    });
  }

  @Test
  void anExternalAssigneeReadsTheOrderInFullAndAUnitMateDoesNot() throws Exception {
    assignAsAdmin();

    as(get(ORDERS + orderId), assigneeId, "ROLE_KRT_MEMBER")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.redacted").value(false))
        .andExpect(jsonPath("$.canEdit").value(false));
    as(get(ORDERS + orderId), unitMateId, "ROLE_KRT_MEMBER").andExpect(status().isForbidden());
    assertThat(lastAuditEventType()).isEqualTo(AuditEventType.JOB_ORDER_EXTERNAL_ASSIGNEE_ADDED);
  }

  @Test
  void theGrantOpensNoOtherWriteNorAnotherUsersEntry() throws Exception {
    assignAsAdmin();

    as(
            put(ORDERS + orderId + "/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"IN_PROGRESS\",\"version\":" + orderVersion() + "}"),
            assigneeId,
            "ROLE_LOGISTICIAN")
        .andExpect(status().isForbidden());
    as(post(ORDERS + orderId + "/assignees/" + unitMateId), assigneeId, "ROLE_LOGISTICIAN")
        .andExpect(status().isForbidden());
  }

  @Test
  void noteEditAndSelfRemovalLeaveTheOrderVersionAloneAndRemovalEndsTheGrant() throws Exception {
    assignAsAdmin();
    long versionBefore = orderVersion();

    as(
            put(ORDERS + orderId + "/assignees/" + assigneeId + "/note")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"Freitag\"}"),
            assigneeId,
            "ROLE_KRT_MEMBER")
        .andExpect(status().isOk());
    as(delete(ORDERS + orderId + "/assignees/" + assigneeId), assigneeId, "ROLE_KRT_MEMBER")
        .andExpect(status().isOk());

    assertThat(orderVersion()).isEqualTo(versionBefore);
    assertThat(lastAuditEventType()).isEqualTo(AuditEventType.JOB_ORDER_EXTERNAL_ASSIGNEE_REMOVED);
    as(get(ORDERS + orderId), assigneeId, "ROLE_KRT_MEMBER").andExpect(status().isForbidden());
  }

  @Test
  void theGrantSurvivesAStatusChangeAndLeavingTheUnit() throws Exception {
    assignAsAdmin();
    as(
            put(ORDERS + orderId + "/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"COMPLETED\",\"version\":" + orderVersion() + "}"),
            adminId,
            "ROLE_ADMIN")
        .andExpect(status().isOk());
    transactionTemplate.executeWithoutResult(
        _ -> orgUnitMembershipRepository.deleteById(new OrgUnitMembershipId(assigneeId, unitBId)));

    as(get(ORDERS + orderId), assigneeId, "ROLE_KRT_MEMBER").andExpect(status().isOk());
  }

  @Test
  void thePickerListsTheProcessingUnitsMembersFirstAndMarksEveryOtherHitWithItsUnit()
      throws Exception {
    as(get(ORDERS + orderId + "/assignee-candidates").param("query", tag), adminId, "ROLE_ADMIN")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(4))
        .andExpect(jsonPath("$[0].id").value(unitAMemberId.toString()))
        .andExpect(jsonPath("$[0].member").value(true))
        .andExpect(jsonPath("$[0].unitShorthand").doesNotExist())
        .andExpect(jsonPath("$[?(@.id == '" + assigneeId + "')].member").value(false))
        .andExpect(jsonPath("$[?(@.id == '" + assigneeId + "')].unitShorthand").value("GB" + tag))
        .andExpect(jsonPath("$[?(@.effectiveName == 'grant-pending-" + tag + "')]").isEmpty());
  }

  @Test
  void aGrantOnlyViewerMayNotSearchCandidates() throws Exception {
    assignAsAdmin();

    as(get(ORDERS + orderId + "/assignee-candidates"), assigneeId, "ROLE_LOGISTICIAN")
        .andExpect(status().isForbidden());
  }

  private void assignAsAdmin() throws Exception {
    as(post(ORDERS + orderId + "/assignees/" + assigneeId), adminId, "ROLE_ADMIN")
        .andExpect(status().isOk());
  }

  @Test
  void aLogisticianOfAnotherUnitWhoIsAMemberOfTheOrdersUnitMayNotAssignOthers() throws Exception {
    GrantedAuthority logisticianOfB = new OrgUnitContextualAuthority(Roles.LOGISTICIAN, unitBId);

    as(post(ORDERS + orderId + "/assignees/" + unitMateId), unitAMemberId, logisticianOfB)
        .andExpect(status().isForbidden());
    as(get(ORDERS + orderId + "/assignee-candidates"), unitAMemberId, logisticianOfB)
        .andExpect(status().isForbidden());
    as(post(ORDERS + orderId + "/assignees/" + unitAMemberId), unitAMemberId, logisticianOfB)
        .andExpect(status().isOk());
  }

  @Test
  void aLogisticianOfTheOrdersUnitAssignsOthers() throws Exception {
    as(
            post(ORDERS + orderId + "/assignees/" + unitMateId),
            unitAMemberId,
            new OrgUnitContextualAuthority(Roles.LOGISTICIAN, unitAId))
        .andExpect(status().isOk());
  }

  private ResultActions as(MockHttpServletRequestBuilder request, UUID userId, String role)
      throws Exception {
    return as(request, userId, new SimpleGrantedAuthority(role));
  }

  private ResultActions as(
      MockHttpServletRequestBuilder request, UUID userId, GrantedAuthority contextual)
      throws Exception {
    List<GrantedAuthority> authorities = new ArrayList<>();
    authorities.add(contextual);
    if (contextual instanceof OrgUnitContextualAuthority) {
      authorities.add(new SimpleGrantedAuthority("ROLE_" + Roles.LOGISTICIAN));
    }
    return mockMvc.perform(
        request
            .with(jwt().jwt(token -> token.subject(userId.toString())).authorities(authorities))
            .accept(MediaType.APPLICATION_JSON));
  }

  private long orderVersion() {
    return jobOrderRepository.findById(orderId).orElseThrow().getVersion();
  }

  private AuditEventType lastAuditEventType() {
    return AuditEventType.valueOf(
        new JdbcTemplate(dataSource)
            .queryForObject(
                "SELECT event_type FROM audit_event WHERE subject_id = ? ORDER BY occurred_at DESC"
                    + " LIMIT 1",
                String.class,
                orderId));
  }

  private Squadron newSquadron(String name, String shorthand, boolean profitEligible) {
    Squadron s = new Squadron();
    s.setName(name);
    s.setShorthand(shorthand);
    s.setProfitEligible(profitEligible);
    return squadronRepository.save(s);
  }

  private User newUser(String username) {
    return newUser(username, UUID.randomUUID());
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

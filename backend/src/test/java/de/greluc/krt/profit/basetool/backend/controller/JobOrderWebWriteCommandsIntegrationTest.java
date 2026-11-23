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
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgHierarchyService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyFixtures;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.AsyncConfig;
import de.greluc.krt.profit.basetool.backend.platform.api.OrgUnitContextualAuthority;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * Characterises the web's job-order status, target and information commands over HTTP against
 * PostgreSQL: who writes under the contextual authorities and the pin, the order of the refusals,
 * each part's own version, and the audit event and notices with the actor left out (REQ-ORDERS-053,
 * REQ-ORDERS-054, REQ-ORDERS-055, REQ-ORDERS-063, REQ-ORDERS-066, ADR-0408).
 */
@SpringBootTest
class JobOrderWebWriteCommandsIntegrationTest {

  private static final String PIN_HEADER = "X-Active-Org-Unit-Id";

  @Autowired private WebApplicationContext context;
  @Autowired private OrgUnitRepository units;
  @Autowired private SquadronRepository squadrons;
  @Autowired private OrgHierarchyService hierarchy;
  @Autowired private UserRepository users;
  @Autowired private RoleRepository roles;
  @Autowired private OrgUnitMembershipRepository memberships;
  @Autowired private JobOrderRepository orders;
  @Autowired private TransactionTemplate transactions;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private JsonMapper json;

  /** The after-commit notice work; cleanup waits for it, since it writes rows cleanup deletes. */
  @Autowired
  @Qualifier(AsyncConfig.NOTIFICATION_EXECUTOR)
  private ThreadPoolTaskExecutor noticeWork;

  private MockMvc mvc;
  private UUID responsible;
  private UUID requesting;
  private UUID elsewhere;
  private UUID responsibleWriter;
  private UUID requestingWriter;
  private UUID reader;
  private UUID administrator;
  private final List<UUID> createdOrders = new ArrayList<>();

  @BeforeEach
  void seed() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    transactions.executeWithoutResult(_ -> {
      String tag = UUID.randomUUID().toString();
      OrgUnit profit = ProfitHierarchyFixtures.persistedProfitArea(units);
      responsible = squadron("Write responsible " + tag, profit).getId();
      requesting = squadron("Write requesting " + tag, null).getId();
      elsewhere = squadron("Write elsewhere " + tag, null).getId();
      responsibleWriter = account("write-responsible-" + tag);
      requestingWriter = account("write-requesting-" + tag);
      reader = account("write-reader-" + tag);
      administrator = account("write-admin-" + tag);
      seat(responsibleWriter, responsible, true);
      seat(requestingWriter, requesting, true);
      seat(requestingWriter, elsewhere, false);
      seat(reader, requesting, false);
    });
  }

  @AfterEach
  void clean() {
    await()
        .atMost(Duration.ofSeconds(20))
        .until(() ->
            noticeWork.getActiveCount() == 0
                && noticeWork.getThreadPoolExecutor().getQueue().isEmpty());
    transactions.executeWithoutResult(_ -> {
      for (UUID id : createdOrders) {
        jdbc.update("DELETE FROM notification WHERE entity_id = ?", id);
        jdbc.update("DELETE FROM audit_event WHERE subject_id = ?", id);
        orders.deleteById(id);
      }
      orders.flush();
      List<UUID> accounts = List.of(responsibleWriter, requestingWriter, reader, administrator);
      jdbc.update(
          "DELETE FROM notification WHERE recipient_user_id = ANY(?)",
          (Object) accounts.toArray(UUID[]::new));
      for (UUID account : accounts) {
        for (UUID unit : List.of(responsible, requesting, elsewhere)) {
          memberships
              .findById(new OrgUnitMembershipId(account, unit))
              .ifPresent(memberships::delete);
        }
      }
      memberships.flush();
      users.deleteAllById(accounts);
      units.deleteAllById(List.of(responsible, requesting, elsewhere));
    });
  }

  @Test
  void theRequestingWriterCancelsAndReopensACancellationButSetsNoOtherStatus() throws Exception {
    UUID order = createOrder();

    changeStatus(order, requestingWriter(), "IN_PROGRESS", 0).andExpect(status().isForbidden());
    changeStatus(order, requestingWriter(), "REJECTED", 0).andExpect(status().isForbidden());
    changeStatus(order, requestingWriter(), "CANCELLED", 0)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"))
        .andExpect(jsonPath("$.lifecycleVersion").value(1));
    changeStatus(order, requestingWriter(), "OPEN", 1)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("OPEN"));
    changeStatus(order, responsibleWriter(), "IN_PROGRESS", 2).andExpect(status().isOk());

    changeStatus(order, requestingWriter(), "OPEN", 3).andExpect(status().isForbidden());
  }

  @Test
  void theResponsibleWriterSetsEveryStatusOfAnAuecOrderButCompletion() throws Exception {
    UUID order = createOrder();

    changeStatus(order, responsibleWriter(), "IN_PROGRESS", 0).andExpect(status().isOk());
    changeStatus(order, responsibleWriter(), "OPEN", 1).andExpect(status().isOk());
    changeStatus(order, responsibleWriter(), "COMPLETED", 2)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").exists());
    changeStatus(order, responsibleWriter(), "REJECTED", 2)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"));
  }

  @Test
  void aMemberWhoOnlyReadsTheOrderChangesNoPart() throws Exception {
    UUID order = createOrder();

    changeStatus(order, member(reader), "CANCELLED", 0).andExpect(status().isForbidden());
    changeTarget(order, member(reader), "12000000", 0).andExpect(status().isForbidden());
    changeInformation(order, member(reader), "Other contact", "Note", 0, 0)
        .andExpect(status().isForbidden());

    assertThat(auditEvents(order)).containsExactly("JOB_ORDER_CREATED");
  }

  @Test
  void aPinOnAnotherUnitTakesTheWriteAway() throws Exception {
    UUID order = createOrder();
    RequestPostProcessor pinnedElsewhere = pinned(requestingWriter(), elsewhere);

    changeStatus(order, pinnedElsewhere, "CANCELLED", 0).andExpect(status().isForbidden());
    changeTarget(order, pinnedElsewhere, "12000000", 0).andExpect(status().isForbidden());
    changeInformation(order, pinnedElsewhere, "Other contact", "Note", 0, 0)
        .andExpect(status().isForbidden());

    changeTarget(order, pinned(requestingWriter(), requesting), "12000000", 0)
        .andExpect(status().isOk());
  }

  @Test
  void anAdministratorWritesEveryPartUnpinnedButNoneWhilePinnedElsewhere() throws Exception {
    UUID order = createOrder();
    RequestPostProcessor pinnedElsewhere = pinned(administrator(), elsewhere);

    changeStatus(order, pinnedElsewhere, "CANCELLED", 0).andExpect(status().isForbidden());
    changeTarget(order, pinnedElsewhere, "12000000", 0).andExpect(status().isForbidden());
    changeInformation(order, pinnedElsewhere, "Other contact", "Note", 0, 0)
        .andExpect(status().isForbidden());

    changeTarget(order, administrator(), "12000000", 0).andExpect(status().isOk());
    changeInformation(order, administrator(), "Other contact", "Note", 0, 0)
        .andExpect(status().isOk());
    changeStatus(order, administrator(), "CANCELLED", 0).andExpect(status().isOk());
  }

  @Test
  void invalidResponsibilityRefusesEveryPartBeforeTheWriterIsChecked() throws Exception {
    UUID order = createOrder();
    transactions.executeWithoutResult(_ -> {
      OrgUnit unit = units.findById(responsible).orElseThrow();
      hierarchy.setParent(responsible, null, unit.getVersion());
    });

    for (RequestPostProcessor caller : List.of(responsibleWriter(), member(reader))) {
      changeStatus(order, caller, "CANCELLED", 0)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.detail").exists());
      changeTarget(order, caller, "12000000", 0).andExpect(status().isBadRequest());
      changeInformation(order, caller, "Other contact", "Note", 0, 0)
          .andExpect(status().isBadRequest());
    }
  }

  @Test
  void eachPartChecksOnlyItsOwnVersion() throws Exception {
    UUID order = createOrder();

    changeStatus(order, responsibleWriter(), "IN_PROGRESS", 1).andExpect(status().isConflict());
    changeTarget(order, responsibleWriter(), "12000000", 1).andExpect(status().isConflict());
    changeInformation(order, responsibleWriter(), "Other contact", "Note", 1, 0)
        .andExpect(status().isConflict());
    changeInformation(order, responsibleWriter(), "Other contact", "Note", 0, 1)
        .andExpect(status().isConflict());

    changeStatus(order, responsibleWriter(), "IN_PROGRESS", 0).andExpect(status().isOk());
    changeTarget(order, responsibleWriter(), "12000000", 0)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targetVersion").value(1));
    changeInformation(order, responsibleWriter(), "Other contact", "Note", 0, 0)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.informationVersion").value(1));
    changeStatus(order, responsibleWriter(), "OPEN", 1).andExpect(status().isOk());
  }

  @Test
  void aCancelledOrderRefusesTargetAndInformationWhileARejectedOneTakesBoth() throws Exception {
    UUID cancelled = createOrder();
    changeStatus(cancelled, requestingWriter(), "CANCELLED", 0).andExpect(status().isOk());
    UUID rejected = createOrder();
    changeStatus(rejected, responsibleWriter(), "REJECTED", 0).andExpect(status().isOk());

    changeTarget(cancelled, requestingWriter(), "12000000", 0).andExpect(status().isConflict());
    changeInformation(cancelled, requestingWriter(), "Other contact", "Note", 0, 0)
        .andExpect(status().isConflict());

    changeTarget(rejected, requestingWriter(), "12000000", 0)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.target").value("12000000"));
    changeInformation(rejected, requestingWriter(), "Other contact", "Note", 0, 0)
        .andExpect(status().isOk());
  }

  @Test
  void aCancellationIsAuditedAndAnnouncedToTheOtherSideButNotToTheActor() throws Exception {
    UUID order = createOrder();

    changeStatus(order, requestingWriter(), "CANCELLED", 0).andExpect(status().isOk());

    assertThat(audit(order, "JOB_ORDER_STATUS_CHANGED"))
        .containsExactly(new AuditRow(requestingWriter, "from=OPEN to=CANCELLED"));
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> assertThat(notices(order, responsibleWriter)).contains("JOB_ORDER_FINISHED"));
    assertThat(notices(order, requestingWriter)).doesNotContain("JOB_ORDER_FINISHED");
  }

  @Test
  void aTargetChangeIsRecordedAndAnnouncedFromTheSideTheActorWrites() throws Exception {
    UUID order = createOrder();

    changeTarget(order, requestingWriter(), "10000000", 0)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targetVersion").value(0));
    changeTarget(order, requestingWriter(), "12000000", 0).andExpect(status().isOk());
    changeTarget(order, responsibleWriter(), "11000000", 1).andExpect(status().isOk());

    assertThat(audit(order, "JOB_ORDER_MONEY_TARGET_CHANGED"))
        .containsExactly(
            new AuditRow(requestingWriter, "previousTarget=10000000 target=12000000"),
            new AuditRow(responsibleWriter, "previousTarget=12000000 target=11000000"));
    assertThat(
            jdbc.queryForList(
                "SELECT actor_id FROM job_order_money_target_change WHERE order_id = ?"
                    + " ORDER BY recorded_at",
                UUID.class,
                order))
        .containsExactly(requestingWriter, responsibleWriter);
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> {
          assertThat(notices(order, responsibleWriter)).contains("JOB_ORDER_UPDATED_BY_REQUESTER");
          assertThat(notices(order, requestingWriter)).contains("JOB_ORDER_UPDATED_BY_RESPONSIBLE");
        });
    assertThat(notices(order, requestingWriter)).doesNotContain("JOB_ORDER_UPDATED_BY_REQUESTER");
    assertThat(notices(order, responsibleWriter))
        .doesNotContain("JOB_ORDER_UPDATED_BY_RESPONSIBLE");
  }

  @Test
  void anInformationChangeIsAuditedWithoutItsTextAndAnnouncedFromTheActorsSide() throws Exception {
    UUID order = createOrder();

    changeInformation(order, requestingWriter(), "Money contact", null, 0, 0)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.informationVersion").value(0));
    changeInformation(order, requestingWriter(), "Money contact", "Deliver to Area18", 0, 0)
        .andExpect(status().isOk());

    assertThat(audit(order, "JOB_ORDER_UPDATED"))
        .containsExactly(new AuditRow(requestingWriter, "information=true"));
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() ->
            assertThat(notices(order, responsibleWriter))
                .contains("JOB_ORDER_UPDATED_BY_REQUESTER"));
    assertThat(notices(order, requestingWriter)).doesNotContain("JOB_ORDER_UPDATED_BY_REQUESTER");
  }

  /** One recorded audit event of an order: who acted and the details. */
  private record AuditRow(UUID actor, String details) {}

  private List<AuditRow> audit(UUID order, String type) {
    return jdbc.query(
        "SELECT actor_user_id, details FROM audit_event WHERE subject_id = ? AND event_type = ?"
            + " ORDER BY occurred_at",
        (row, _) -> new AuditRow(row.getObject(1, UUID.class), row.getString(2)),
        order,
        type);
  }

  private List<String> auditEvents(UUID order) {
    return jdbc.queryForList(
        "SELECT event_type FROM audit_event WHERE subject_id = ? ORDER BY occurred_at",
        String.class,
        order);
  }

  private List<String> notices(UUID order, UUID recipient) {
    return jdbc.queryForList(
        "SELECT type FROM notification WHERE entity_id = ? AND recipient_user_id = ?",
        String.class,
        order,
        recipient);
  }

  private ResultActions changeStatus(
      UUID order, RequestPostProcessor caller, String target, long lifecycleVersion)
      throws Exception {
    return mvc.perform(
        put("/api/v1/orders/{id}/status", order)
            .with(caller)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                json.writeValueAsString(Map.of("status", target, "version", lifecycleVersion))));
  }

  private ResultActions changeTarget(
      UUID order, RequestPostProcessor caller, String amount, long targetVersion) throws Exception {
    return mvc.perform(
        patch("/api/v1/orders/{id}/money/target", order)
            .with(caller)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("target", amount, "version", targetVersion))));
  }

  private ResultActions changeInformation(
      UUID order,
      RequestPostProcessor caller,
      String handle,
      @Nullable String comment,
      long version,
      long commentVersion)
      throws Exception {
    Map<String, Object> body = new HashMap<>();
    body.put("handle", handle);
    body.put("comment", comment);
    body.put("version", version);
    body.put("commentVersion", commentVersion);
    return mvc.perform(
        patch("/api/v1/orders/{id}/money/information", order)
            .with(caller)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body)));
  }

  private UUID createOrder() throws Exception {
    MockHttpServletRequestBuilder request =
        post("/api/v1/orders/auec")
            .with(requestingWriter())
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                json.writeValueAsString(
                    Map.of(
                        "responsibleOrgUnitId",
                        responsible,
                        "requestingOrgUnitId",
                        requesting,
                        "handle",
                        "Money contact",
                        "target",
                        "10000000")));
    String created =
        mvc.perform(request)
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID order = UUID.fromString(json.readTree(created).get("id").asString());
    createdOrders.add(order);
    return order;
  }

  private RequestPostProcessor responsibleWriter() {
    return writer(responsibleWriter, responsible);
  }

  private RequestPostProcessor requestingWriter() {
    return writer(requestingWriter, requesting);
  }

  private static RequestPostProcessor writer(UUID user, UUID unit) {
    return jwt()
        .jwt(token -> token.subject(user.toString()))
        .authorities(
            new SimpleGrantedAuthority("ROLE_KRT_MEMBER"),
            new SimpleGrantedAuthority("ROLE_LOGISTICIAN"),
            new OrgUnitContextualAuthority(Roles.LOGISTICIAN, unit));
  }

  private RequestPostProcessor administrator() {
    return jwt()
        .jwt(token -> token.subject(administrator.toString()))
        .authorities(
            new SimpleGrantedAuthority("ROLE_KRT_MEMBER"),
            new SimpleGrantedAuthority("ROLE_ADMIN"));
  }

  private static RequestPostProcessor member(UUID user) {
    return jwt()
        .jwt(token -> token.subject(user.toString()))
        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"));
  }

  private static RequestPostProcessor pinned(RequestPostProcessor caller, UUID unit) {
    return request -> {
      request.addHeader(PIN_HEADER, unit.toString());
      return caller.postProcessRequest(request);
    };
  }

  private UUID account(String name) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(name);
    user.getRoles().add(roles.findByCode(Roles.KRT_MEMBER).orElseThrow());
    return users.saveAndFlush(user).getId();
  }

  private void seat(UUID user, UUID unit, boolean logistician) {
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(user, unit));
    membership.setUser(users.findById(user).orElseThrow());
    membership.setKind(units.findById(unit).orElseThrow().getKind());
    membership.setLogistician(logistician);
    membership.setJoinedAt(Instant.now());
    memberships.saveAndFlush(membership);
  }

  private Squadron squadron(String name, @Nullable OrgUnit parent) {
    Squadron unit = new Squadron();
    unit.setName(name);
    unit.setShorthand(name);
    unit.setParent(parent);
    return squadrons.saveAndFlush(unit);
  }
}

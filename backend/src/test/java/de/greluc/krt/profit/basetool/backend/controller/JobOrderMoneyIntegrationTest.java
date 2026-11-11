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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyFixtures;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.OrgUnitContextualAuthority;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/** Exercises durable order money through authenticated HTTP against real PostgreSQL. */
@SpringBootTest
class JobOrderMoneyIntegrationTest {

  @Autowired private WebApplicationContext context;
  @Autowired private OrgUnitRepository units;
  @Autowired private SquadronRepository squadrons;
  @Autowired private UserRepository users;
  @Autowired private OrgUnitMembershipRepository memberships;
  @Autowired private JobOrderRepository orders;
  @Autowired private MaterialRepository materials;
  @Autowired private TransactionTemplate transactions;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private JsonMapper json;

  private MockMvc mvc;
  private UUID responsible;
  private UUID profitRoot;
  private UUID requester;
  private UUID writer;
  private UUID reader;
  private UUID unitless;
  private final List<UUID> createdOrders = new ArrayList<>();
  private final List<UUID> createdMaterials = new ArrayList<>();

  @BeforeEach
  void seed() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    transactions.executeWithoutResult(_ -> {
      String tag = UUID.randomUUID().toString();
      OrgUnit profit = ProfitHierarchyFixtures.persistedProfitArea(units);
      profitRoot = profit.getId();
      responsible = squadron("Money responsible " + tag, profit).getId();
      requester = squadron("Money requester " + tag, null).getId();
      writer = member("money-writer-" + tag);
      reader = member("money-reader-" + tag);
      unitless = account("money-linked-" + tag).getId();
    });
  }

  @AfterEach
  void clean() {
    transactions.executeWithoutResult(_ -> {
      for (UUID id : createdOrders) {
        jdbc.update("DELETE FROM job_order_money_operation WHERE order_id = ?", id);
        jdbc.update("DELETE FROM notification WHERE entity_id = ?", id);
        jdbc.update("DELETE FROM audit_event WHERE subject_id = ?", id);
        orders.deleteById(id);
      }
      orders.flush();
      materials.deleteAllById(createdMaterials);
      memberships.deleteById(new OrgUnitMembershipId(writer, requester));
      memberships.deleteById(new OrgUnitMembershipId(reader, requester));
      memberships.flush();
      users.deleteAllById(List.of(writer, reader, unitless));
      units.deleteAllById(List.of(responsible, requester));
    });
  }

  @Test
  void readerCreatesOwnLinkedHolderInOneExactBankFreeProvision() throws Exception {
    UUID order = createOrder();
    UUID operation = UUID.randomUUID();
    provision(
            order,
            reader(),
            Map.of(
                "operationId",
                operation,
                "amount",
                "9007199254740993",
                "bankHandling",
                false,
                "holderVersion",
                0))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.operationId").value(operation.toString()))
        .andExpect(jsonPath("$.amount").value("9007199254740993"));
    mvc.perform(get("/api/v1/orders/{id}/money", order).with(reader()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.target").value("10000000"))
        .andExpect(jsonPath("$.provided").value("9007199254740993"))
        .andExpect(jsonPath("$.holdings").value("9007199254740993"))
        .andExpect(jsonPath("$.netHandedOver").value("0"))
        .andExpect(jsonPath("$.remaining").value("10000000"))
        .andExpect(jsonPath("$.holders.length()").value(1))
        .andExpect(jsonPath("$.holders[0].userId").value(reader.toString()));
    mvc.perform(get("/api/v1/orders/{id}", order).with(reader()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
        .andExpect(jsonPath("$.assignees").isEmpty());
    mvc.perform(
            get("/api/v1/orders/{id}/money/operations/{operation}", order, operation)
                .with(reader()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.amount").value("9007199254740993"));
  }

  @Test
  void onlyResponsibleWriterCanRecordNamedAccountlessProvision() throws Exception {
    UUID order = createOrder();
    UUID operation = UUID.randomUUID();
    Map<String, ?> terms =
        Map.of(
            "operationId",
            operation,
            "amount",
            "2400000.00",
            "bankHandling",
            false,
            "holderVersion",
            0,
            "holderName",
            "  Accountless Keeper  ");

    provision(order, reader(), terms).andExpect(status().isForbidden());
    provision(order, writer(), terms)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.holderName").value("Accountless Keeper"))
        .andExpect(jsonPath("$.amount").value("2400000"));
    mvc.perform(get("/api/v1/orders/{id}/money", order).with(reader()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.holdings").value("2400000"))
        .andExpect(jsonPath("$.holders.length()").value(1))
        .andExpect(jsonPath("$.holders[0].userId").doesNotExist())
        .andExpect(jsonPath("$.holders[0].displayName").value("Accountless Keeper"));
  }

  @Test
  void explicitlyLinkedUnitlessHolderCanReadOnlyTheirOrderAcrossWorkspace() throws Exception {
    UUID order = createOrder();
    UUID otherOrder = createOrder();
    String number =
        json.readTree(
                mvc.perform(get("/api/v1/orders/{id}", order).with(writer()))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("displayId")
            .asString();
    UUID operation = UUID.randomUUID();
    RequestPostProcessor linkedAccount = signedInMember(unitless);
    mvc.perform(get("/api/v1/orders/{id}", order).with(linkedAccount))
        .andExpect(status().isForbidden());

    provision(
            order,
            writer(),
            Map.of(
                "operationId",
                operation,
                "amount",
                "15",
                "bankHandling",
                false,
                "holderVersion",
                0,
                "holderUserId",
                unitless))
        .andExpect(status().isOk());

    mvc.perform(get("/api/v1/me/capabilities").with(linkedAccount))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.canViewOrderWorkspace").value(true))
        .andExpect(jsonPath("$.canViewJobOrders").value(false))
        .andExpect(jsonPath("$.canViewOrderUnitWork").value(false));
    mvc.perform(get("/api/v1/orders").param("type", "AUEC").with(linkedAccount))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].id").value(order.toString()));
    mvc.perform(get("/api/v1/orders/by-number").param("number", number).with(linkedAccount))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(order.toString()));
    mvc.perform(get("/api/v1/orders/{id}", order).with(linkedAccount))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assignees").isEmpty());
    mvc.perform(get("/api/v1/orders/{id}/money", order).with(linkedAccount))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.canProvideSelf").value(true))
        .andExpect(jsonPath("$.canProvideOnBehalf").value(false))
        .andExpect(jsonPath("$.history[0].operationId").value(operation.toString()));
    mvc.perform(get("/api/v1/orders/{id}", otherOrder).with(linkedAccount))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/v1/orders/{id}/money/operations/{operation}", otherOrder, operation)
                .with(linkedAccount))
        .andExpect(status().isForbidden());
  }

  @Test
  void moneyOrdersCannotAcquireMaterialLinesOrManualCompletion() throws Exception {
    UUID order = createOrder();
    Material material = new Material();
    material.setName("Money kind fixture " + UUID.randomUUID());
    material.setType(MaterialType.RAW);
    UUID materialId = materials.saveAndFlush(material).getId();
    createdMaterials.add(materialId);
    mvc.perform(
            put("/api/v1/orders/{id}", order)
                .with(writer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "version",
                            0,
                            "handle",
                            "Money contact",
                            "materials",
                            List.of(Map.of("materialId", materialId, "amount", 1.0))))))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.detail").value("Material lines can only be edited on a material order."));
    mvc.perform(
            post("/api/v1/orders/{id}/handovers", order)
                .with(writer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "handoverTime",
                            Instant.now().toString(),
                            "recipientHandle",
                            "Fixture recipient",
                            "items",
                            List.of(Map.of("inventoryItemId", UUID.randomUUID(), "amount", 1.0))))))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.detail")
                .value("Material can only be handed over against a material order."));
    mvc.perform(
            put("/api/v1/orders/{id}/status", order)
                .with(writer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"COMPLETED\",\"version\":0}"))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.detail").value("An aUEC order completes through an effective handover."));
    mvc.perform(get("/api/v1/orders/{id}", order).with(writer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.materials").isEmpty())
        .andExpect(jsonPath("$.status").value("OPEN"));
  }

  @Test
  void moneyOrdersStayInTheWorkspaceAndOutOfTheInventoryPicker() throws Exception {
    UUID order = createOrder();
    mvc.perform(get("/api/v1/orders").with(writer()).param("type", "AUEC"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[?(@.id == '" + order + "')]").isNotEmpty());
    mvc.perform(get("/api/v1/orders/lookup").with(writer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id == '" + order + "')]").isEmpty());
  }

  @Test
  void onlyTheAffectedLinkedHolderReceivesOneNoticeForTheDurableProvision() throws Exception {
    UUID order = createOrder();
    UUID operation = UUID.randomUUID();
    var terms =
        Map.of(
            "operationId",
            operation,
            "amount",
            "55",
            "bankHandling",
            false,
            "holderVersion",
            0,
            "holderUserId",
            unitless);
    provision(order, writer(), terms).andExpect(status().isOk());
    provision(order, writer(), terms).andExpect(status().isOk());

    await()
        .atMost(Duration.ofSeconds(6))
        .untilAsserted(
            () -> assertThat(provisionNotices(signedInMember(unitless), order)).isEqualTo(1));
    assertThat(provisionNotices(writer(), order)).isZero();
    assertThat(provisionNotices(reader(), order)).isZero();
  }

  @Test
  void additionalNotificationSelectorsCannotExposeAProvisionToUnrelatedAccounts() throws Exception {
    UUID order = createOrder();
    UUID selector = UUID.randomUUID();
    assertThat(
            jdbc.update(
                """
                INSERT INTO notification_rule_selector (id, rule_id, kind, user_id)
                SELECT ?, id, 'SPECIFIC_USER', ? FROM notification_rule
                WHERE event_type = 'JOB_ORDER_MONEY_PROVISIONED'
                """,
                selector,
                reader))
        .isEqualTo(1);
    try {
      provision(
              order,
              writer(),
              Map.of(
                  "operationId",
                  UUID.randomUUID(),
                  "amount",
                  "18",
                  "bankHandling",
                  false,
                  "holderVersion",
                  0,
                  "holderUserId",
                  unitless))
          .andExpect(status().isOk());
      await()
          .atMost(Duration.ofSeconds(6))
          .untilAsserted(
              () -> assertThat(provisionNotices(signedInMember(unitless), order)).isEqualTo(1));
      assertThat(provisionNotices(reader(), order)).isZero();
    } finally {
      jdbc.update("DELETE FROM notification_rule_selector WHERE id = ?", selector);
    }
  }

  private int provisionNotices(RequestPostProcessor user, UUID order) {
    MvcResult result = perform(get("/api/v1/notifications").with(user));
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    int count = 0;
    for (var notice : json.readTree(result.getResponse().getContentAsByteArray()).path("content")) {
      if ("JOB_ORDER_MONEY_PROVISIONED".equals(notice.path("type").asString())
          && order.toString().equals(notice.path("entityId").asString())) {
        count++;
      }
    }
    return count;
  }

  @Test
  void eachFreshProvisionRequiresAnExplicitBankFreeChoice() throws Exception {
    UUID order = createOrder();
    UUID operation = UUID.randomUUID();
    provision(order, reader(), Map.of("operationId", operation, "amount", "9", "holderVersion", 0))
        .andExpect(status().isBadRequest());
    provision(
            order,
            reader(),
            Map.of(
                "operationId", operation, "amount", "9", "holderVersion", 0, "bankHandling", true))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/orders/{id}/money", order).with(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.canDelete").value(true))
        .andExpect(jsonPath("$.history").isEmpty());
    provision(order, reader(), provisionTerms(operation, "9", 0))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.bankHandling").value(false));
  }

  private UUID createOrder() throws Exception {
    String created =
        mvc.perform(
                post("/api/v1/orders/auec")
                    .with(writer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "responsibleOrgUnitId",
                                responsible,
                                "requestingOrgUnitId",
                                requester,
                                "handle",
                                "Money contact",
                                "target",
                                "10000000"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.type").value("AUEC"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID order = UUID.fromString(json.readTree(created).get("id").asString());
    createdOrders.add(order);
    return order;
  }

  private ResultActions provision(UUID order, RequestPostProcessor user, Map<String, ?> terms)
      throws Exception {
    return mvc.perform(provisionRequest(order, user, terms));
  }

  private MockHttpServletRequestBuilder provisionRequest(
      UUID order, RequestPostProcessor user, Map<String, ?> terms) {
    return post("/api/v1/orders/{id}/money/provisions", order)
        .with(user)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(terms));
  }

  @Test
  @Timeout(60)
  void firstDurableBookingWinsAgainstWaitingAdminDeletion() throws Exception {
    UUID order = createOrder();
    UUID operation = UUID.randomUUID();
    Map<String, ?> terms = provisionTerms(operation, "12", 0);

    RaceResults race =
        serializeBehind(provisionRequest(order, reader(), terms), 200, deleteOrder(order));
    assertThat(race.waiter().getResponse().getStatus()).isEqualTo(409);
    assertThat(
            json.readTree(race.waiter().getResponse().getContentAsString())
                .path("detail")
                .asString())
        .isEqualTo("The order cannot be deleted after a monetary operation has been submitted.");
    mvc.perform(get("/api/v1/orders/{id}/money", order).with(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.canDelete").value(false))
        .andExpect(jsonPath("$.history.length()").value(1));
    assertOneProvisionAudit(order);
  }

  @Test
  @Timeout(60)
  void eligibleDeletionWinsAgainstWaitingFirstSubmission() throws Exception {
    UUID order = createOrder();
    UUID operation = UUID.randomUUID();
    mvc.perform(get("/api/v1/orders/{id}/money", order).with(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.canDelete").value(true));
    provision(order, reader(), provisionTerms(operation, "0.5", 0))
        .andExpect(status().isBadRequest());

    RaceResults race =
        serializeBehind(
            deleteOrder(order),
            204,
            provisionRequest(order, reader(), provisionTerms(operation, "12", 0)));
    assertThat(race.waiter().getResponse().getStatus()).isEqualTo(404);
    mvc.perform(
            get("/api/v1/orders/{id}/money/operations/{operation}", order, operation)
                .with(reader()))
        .andExpect(status().isNotFound());
    mvc.perform(provisionAudit())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  @Timeout(60)
  void duplicateSubmissionsAndStaleReplayReturnOneExactDurableResult() throws Exception {
    UUID order = createOrder();
    UUID operation = UUID.randomUUID();
    Map<String, ?> terms = provisionTerms(operation, "9007199254740993", 0);

    RaceResults race =
        serializeBehind(
            provisionRequest(order, reader(), terms),
            200,
            provisionRequest(order, reader(), terms));
    assertThat(race.waiter().getResponse().getStatus()).isEqualTo(200);
    String original = race.first().getResponse().getContentAsString();
    assertThat(json.readTree(race.waiter().getResponse().getContentAsString()))
        .isEqualTo(json.readTree(original));
    assertOneProvisionAudit(order);
    mvc.perform(get("/api/v1/orders/{id}/money", order).with(reader()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targetVersion").value(0))
        .andExpect(jsonPath("$.holders.length()").value(1))
        .andExpect(jsonPath("$.holders[0].version").value(1))
        .andExpect(jsonPath("$.history.length()").value(1));

    provision(order, reader(), provisionTerms(UUID.randomUUID(), "7", 1))
        .andExpect(status().isOk());
    String replay =
        provision(order, reader(), provisionTerms(operation, "9007199254740993.00", 0))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(json.readTree(replay)).isEqualTo(json.readTree(original));
    provision(order, reader(), provisionTerms(operation, "9007199254740992", 0))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.detail").value("This operation was submitted with different terms."));
    provision(order, writer(), terms).andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/orders/{id}/money", order).with(reader()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targetVersion").value(0))
        .andExpect(jsonPath("$.provided").value("9007199254741000"))
        .andExpect(jsonPath("$.history.length()").value(2));
    mvc.perform(provisionAudit())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2));
  }

  @Test
  @Timeout(60)
  void waitingOnBehalfProvisionRechecksTheCurrentResponsibleWriter() throws Exception {
    UUID order = createOrder();
    UUID operation = UUID.randomUUID();
    MockHttpServletRequestBuilder transfer =
        patch("/api/v1/orders/{id}/responsible-org-unit", order)
            .with(writer())
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                json.writeValueAsString(Map.of("responsibleOrgUnitId", profitRoot, "version", 0)));
    var terms =
        Map.of(
            "operationId",
            operation,
            "amount",
            "16",
            "bankHandling",
            false,
            "holderVersion",
            0,
            "holderName",
            "Unchanged money");

    RaceResults race = serializeBehind(transfer, 200, provisionRequest(order, writer(), terms));
    assertThat(race.waiter().getResponse().getStatus()).isEqualTo(403);
    mvc.perform(get("/api/v1/orders/{id}/money", order).with(reader()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.holders").isEmpty())
        .andExpect(jsonPath("$.history").isEmpty());
  }

  @Test
  void failedActivityAuditRollsBackTheHolderTheOutcomeAndTheLifecycle() throws Exception {
    UUID order = createOrder();
    UUID operation = UUID.randomUUID();
    String constraint = "audit_order_money_" + order.toString().replace("-", "");
    jdbc.execute(
        "ALTER TABLE audit_event ADD CONSTRAINT "
            + constraint
            + " CHECK (subject_id IS DISTINCT FROM '"
            + order
            + "'::uuid"
            + " OR event_type <> 'JOB_ORDER_MONEY_PROVIDED') NOT VALID");
    try {
      provision(order, reader(), provisionTerms(operation, "27", 0))
          .andExpect(status().isConflict());
    } finally {
      jdbc.execute("ALTER TABLE audit_event DROP CONSTRAINT " + constraint);
    }
    mvc.perform(get("/api/v1/orders/{id}/money", order).with(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.holdings").value("0"))
        .andExpect(jsonPath("$.canDelete").value(true))
        .andExpect(jsonPath("$.holders").isEmpty())
        .andExpect(jsonPath("$.history").isEmpty());
    mvc.perform(
            get("/api/v1/orders/{id}/money/operations/{operation}", order, operation)
                .with(reader()))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/orders/{id}", order).with(reader()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("OPEN"));
    mvc.perform(provisionAudit())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  private RaceResults serializeBehind(
      MockHttpServletRequestBuilder first, int expected, MockHttpServletRequestBuilder waiting)
      throws Exception {
    try (var worker = Executors.newSingleThreadExecutor()) {
      var waiterPid = new AtomicInteger();
      RunningRace pending =
          Objects.requireNonNull(
              transactions.execute(_ -> {
                MvcResult result = perform(first);
                assertThat(result.getResponse().getStatus()).isEqualTo(expected);
                int blocker = currentPid();
                var future =
                    worker.submit(() ->
                        transactions.execute(transaction -> {
                          waiterPid.set(currentPid());
                          MvcResult response = perform(waiting);
                          if (response.getResponse().getStatus() >= 400) {
                            transaction.setRollbackOnly();
                          }
                          return response;
                        }));
                await()
                    .atMost(Duration.ofSeconds(10))
                    .until(() ->
                        waiterPid.get() != 0
                            && Boolean.TRUE.equals(
                                jdbc.queryForObject(
                                    "SELECT ? = ANY(pg_blocking_pids(?))",
                                    Boolean.class,
                                    blocker,
                                    waiterPid.get())));
                return new RunningRace(result, future);
              }));
      return new RaceResults(pending.first(), pending.waiter().get(15, TimeUnit.SECONDS));
    }
  }

  private MvcResult perform(MockHttpServletRequestBuilder request) {
    try {
      return mvc.perform(request).andReturn();
    } catch (Exception exception) {
      throw new IllegalStateException("Public money request failed", exception);
    }
  }

  private int currentPid() {
    return Objects.requireNonNull(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
  }

  private MockHttpServletRequestBuilder deleteOrder(UUID order) {
    return delete("/api/v1/orders/{id}", order).with(admin());
  }

  private void assertOneProvisionAudit(UUID order) throws Exception {
    mvc.perform(provisionAudit())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].subjectId").value(order.toString()));
  }

  private MockHttpServletRequestBuilder provisionAudit() {
    return get("/api/v1/audit/JOB_ORDER")
        .with(admin())
        .param("eventType", "JOB_ORDER_MONEY_PROVIDED")
        .param("actorUserId", reader.toString());
  }

  private RequestPostProcessor admin() {
    return jwt()
        .jwt(token -> token.subject(writer.toString()))
        .authorities(
            new SimpleGrantedAuthority("ROLE_KRT_MEMBER"),
            new SimpleGrantedAuthority("ROLE_ADMIN"));
  }

  private static Map<String, ?> provisionTerms(UUID operation, String amount, long version) {
    return Map.of(
        "operationId",
        operation,
        "amount",
        amount,
        "bankHandling",
        false,
        "holderVersion",
        version);
  }

  /** A transaction holding the first response while its contender waits on PostgreSQL. */
  private record RunningRace(MvcResult first, Future<MvcResult> waiter) {}

  /** The two observed HTTP outcomes after the waiting transaction can finish. */
  private record RaceResults(MvcResult first, MvcResult waiter) {}

  private RequestPostProcessor writer() {
    return jwt()
        .jwt(token -> token.subject(writer.toString()))
        .authorities(
            new SimpleGrantedAuthority("ROLE_KRT_MEMBER"),
            new SimpleGrantedAuthority("ROLE_LOGISTICIAN"),
            new OrgUnitContextualAuthority(Roles.LOGISTICIAN, responsible));
  }

  private RequestPostProcessor reader() {
    return signedInMember(reader);
  }

  private static RequestPostProcessor signedInMember(UUID id) {
    return jwt()
        .jwt(token -> token.subject(id.toString()))
        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"));
  }

  private UUID member(String name) {
    User user = account(name);
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(user.getId(), requester));
    membership.setUser(user);
    membership.setKind(units.findById(requester).orElseThrow().getKind());
    membership.setJoinedAt(Instant.now());
    memberships.saveAndFlush(membership);
    return user.getId();
  }

  private User account(String name) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(name);
    return users.saveAndFlush(user);
  }

  private Squadron squadron(String name, OrgUnit parent) {
    Squadron unit = new Squadron();
    unit.setName(name);
    unit.setShorthand(name);
    unit.setParent(parent);
    return squadrons.saveAndFlush(unit);
  }
}

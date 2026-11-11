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
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationType;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
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
import tools.jackson.databind.JsonNode;
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
        jdbc.update("DELETE FROM job_order_money_commitment_change WHERE order_id = ?", id);
        jdbc.update("DELETE FROM job_order_money_operation WHERE order_id = ?", id);
        jdbc.update("DELETE FROM notification WHERE entity_id = ?", id);
        jdbc.update("DELETE FROM audit_event WHERE subject_id = ?", id);
        orders.deleteById(id);
      }
      orders.flush();
      materials.deleteAllById(createdMaterials);
      memberships.deleteById(new OrgUnitMembershipId(writer, requester));
      memberships.deleteById(new OrgUnitMembershipId(reader, requester));
      memberships.deleteById(new OrgUnitMembershipId(reader, responsible));
      memberships.deleteById(new OrgUnitMembershipId(unitless, responsible));
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

  @ParameterizedTest
  @NullSource
  @EnumSource(
      value = NotificationType.class,
      names = {"JOB_ORDER_MONEY_COMMITMENT_CHANGED", "JOB_ORDER_MONEY_PROVISIONED"})
  void attributedOnBehalfProvisionGroupsWriterAndHolderNotices(@Nullable NotificationType muted)
      throws Exception {
    UUID order = createOrder();
    String commitment = openResponsibleCommitment(order, "6000000");
    var holder = signedInMember(reader);
    addCommitmentWriters();
    if (muted != null) {
      setNoticePreference(holder, muted, true);
    }
    try {
      var terms =
          Map.of(
              "operationId",
              UUID.randomUUID(),
              "amount",
              "4000000",
              "holderVersion",
              0,
              "bankHandling",
              false,
              "holderUserId",
              reader,
              "commitmentId",
              commitment);
      provision(order, writer(), terms).andExpect(status().isOk());
      provision(order, writer(), terms).andExpect(status().isOk());

      NotificationType expected =
          muted == NotificationType.JOB_ORDER_MONEY_COMMITMENT_CHANGED
              ? NotificationType.JOB_ORDER_MONEY_PROVISIONED
              : NotificationType.JOB_ORDER_MONEY_COMMITMENT_CHANGED;
      assertOneGroupedMoneyNotice(order, holder, expected);
    } finally {
      if (muted != null) {
        setNoticePreference(holder, muted, false);
      }
    }
  }

  @Test
  void attributedCollectionTopUpGroupsNoticeAndCreditsOnlyNewMoney() throws Exception {
    withFeeRate(
        "0",
        () -> {
          UUID order = createOrder();
          String source = providedHolder(order, writer(), "2000000");
          String commitment = openResponsibleCommitment(order, "6000000");
          addCommitmentWriters();
          var terms =
              movementTerms(
                  "COLLECTION",
                  source,
                  1,
                  "5000000",
                  "0",
                  Map.of(
                      "destinationUserId",
                      reader,
                      "destinationVersion",
                      0,
                      "provision",
                      "3000000",
                      "commitmentId",
                      commitment));
          submitMovement(order, writer(), terms);
          submitMovement(order, writer(), terms);
          mvc.perform(get("/api/v1/orders/{id}/participations", order).with(writer()))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.moneyCommitments[0].outstanding").value("3000000"))
              .andExpect(jsonPath("$.moneyCommitments[0].changes.length()").value(2));
          assertMoney(order, "5000000", "5000000", "0", "10000000", "IN_PROGRESS");
          assertOneGroupedMoneyNotice(
              order, signedInMember(reader), NotificationType.JOB_ORDER_MONEY_COMMITMENT_CHANGED);
        });
  }

  @Test
  @Timeout(60)
  void completingHandoverCreditsCurrentCommitmentAfterWaitingOnTheQueue() throws Exception {
    withFeeRate(
        "0",
        () -> {
          UUID first = createOrder();
          UUID second = createOrder();
          boolean firstSortsEarlier = first.toString().compareTo(second.toString()) < 0;
          UUID blockedRow = firstSortsEarlier ? first : second;
          UUID order = firstSortsEarlier ? second : first;
          String source = providedHolder(order, writer(), "4000000");
          String commitment = openResponsibleCommitment(order, "9000000");
          String recipient = designateRecipient(order, writer(), Map.of("name", "Requester"));
          var payment = movementTerms(
              "HANDOVER", source, 1, "10000000", "0",
              Map.of("recipientId", recipient, "targetVersion", 0,
                  "provision", "6000000", "commitmentId", commitment));
          var revision = put("/api/v1/orders/{id}/participations/{unitId}/money-commitment", order, responsible)
              .with(writer())
              .contentType(MediaType.APPLICATION_JSON)
              .content(json.writeValueAsString(
                  Map.of("amount", "8000000", "commitmentId", commitment, "version", 0)));

          try (var workers = Executors.newFixedThreadPool(2)) {
            var waiterPid = new AtomicInteger();
            Future<MvcResult> waiting =
                Objects.requireNonNull(
                    transactions.execute(_ -> {
                      jdbc.queryForObject(
                          "SELECT id FROM job_order WHERE id = ? FOR NO KEY UPDATE", UUID.class, blockedRow);
                      int blocker = currentPid();
                      Future<MvcResult> request =
                          workers.submit(() -> transactions.execute(_ -> {
                            waiterPid.set(currentPid());
                            return perform(movementRequest(order, writer(), payment, false));
                          }));
                      await().atMost(Duration.ofSeconds(10)).until(() ->
                          waiterPid.get() != 0 && Boolean.TRUE.equals(jdbc.queryForObject(
                              "SELECT ? = ANY(pg_blocking_pids(?))", Boolean.class, blocker, waiterPid.get())));
                      try {
                        assertThat(workers.submit(() -> perform(revision)).get(8, TimeUnit.SECONDS)
                            .getResponse().getStatus()).isEqualTo(204);
                      } catch (Exception failure) {
                        throw new IllegalStateException("Commitment revision failed while handover waited.", failure);
                      }
                      return request;
                    }));
            assertThat(waiting.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
          }

          submitMovement(order, writer(), payment);
          mvc.perform(get("/api/v1/orders/{id}/participations", order).with(writer()))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.moneyCommitments[0].outstanding").value("0"))
              .andExpect(jsonPath("$.moneyCommitments[0].changes.length()").value(4))
              .andExpect(jsonPath("$.moneyCommitments[0].changes[2].reason").value("CREDITED"))
              .andExpect(jsonPath("$.moneyCommitments[0].changes[2].before").value("8000000"))
              .andExpect(jsonPath("$.moneyCommitments[0].changes[3].reason").value("ORDER_ENDED"))
              .andExpect(jsonPath("$.moneyCommitments[0].changes[3].before").value("2000000"));
          assertMoney(order, "10000000", "0", "10000000", "0", "COMPLETED");
          assertAuditCount(order, "JOB_ORDER_MONEY_MOVED", 1);
        });
  }

  private String openResponsibleCommitment(UUID order, String amount) throws Exception {
    mvc.perform(
            put("/api/v1/orders/{id}/participations/{unitId}/money-commitment", order, responsible)
                .with(writer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("amount", amount))))
        .andExpect(status().isNoContent());
    MvcResult result =
        mvc.perform(get("/api/v1/orders/{id}/participations", order).with(writer()))
            .andExpect(status().isOk())
            .andReturn();
    return json.readTree(result.getResponse().getContentAsByteArray())
        .path("moneyCommitments")
        .get(0)
        .path("id")
        .asString();
  }

  private void addCommitmentWriters() {
    transactions.executeWithoutResult(_ -> {
      for (UUID userId : List.of(reader, unitless)) {
        var membership = new OrgUnitMembership();
        membership.setId(new OrgUnitMembershipId(userId, responsible));
        membership.setUser(users.findById(userId).orElseThrow());
        membership.setKind(units.findById(responsible).orElseThrow().getKind());
        membership.setJoinedAt(Instant.now());
        membership.setLogistician(true);
        memberships.save(membership);
      }
      memberships.flush();
    });
  }

  private void setNoticePreference(
      RequestPostProcessor recipient, NotificationType type, boolean muted) throws Exception {
    mvc.perform(
            put("/api/v1/notifications/preferences/{type}", type)
                .with(recipient)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("muted", muted))))
        .andExpect(status().isOk());
  }

  private void assertOneGroupedMoneyNotice(
      UUID order, RequestPostProcessor holder, NotificationType expected) {
    await()
        .atMost(Duration.ofSeconds(6))
        .untilAsserted(() -> {
          assertThat(moneyActivityNotices(signedInMember(unitless), order))
              .containsExactly(NotificationType.JOB_ORDER_MONEY_COMMITMENT_CHANGED);
          assertThat(moneyActivityNotices(holder, order)).containsExactly(expected);
        });
  }

  private int provisionNotices(RequestPostProcessor user, UUID order) {
    return (int)
        moneyActivityNotices(user, order).stream()
            .filter(type -> type == NotificationType.JOB_ORDER_MONEY_PROVISIONED)
            .count();
  }

  private List<NotificationType> moneyActivityNotices(RequestPostProcessor user, UUID order) {
    MvcResult result = perform(get("/api/v1/notifications").with(user));
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    List<NotificationType> types = new ArrayList<>();
    for (var notice : json.readTree(result.getResponse().getContentAsByteArray()).path("content")) {
      if (!order.toString().equals(notice.path("entityId").asString())) {
        continue;
      }
      NotificationType type = NotificationType.valueOf(notice.path("type").asString());
      switch (type) {
        case JOB_ORDER_MONEY_PROVISIONED,
            JOB_ORDER_MONEY_MOVED,
            JOB_ORDER_MONEY_COMMITMENT_CHANGED ->
            types.add(type);
        default -> {}
      }
    }
    return List.copyOf(types);
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

  @Test
  void collectsTenMillionAndCompletesOnlyOnTheLastRequesterHandover() throws Exception {
    String rateKey = "operation.transfer_fee_rate";
    String originalRate =
        jdbc.queryForObject(
            "SELECT setting_value FROM system_setting WHERE setting_key = ?",
            String.class,
            rateKey);
    jdbc.update("UPDATE system_setting SET setting_value = '0' WHERE setting_key = ?", rateKey);
    try {
      UUID order = createOrder();
      assertMoney(order, "0", "0", "0", "10000000", "OPEN");
      String holderA = providedHolder(order, reader(), "4000000");
      assertMoney(order, "4000000", "4000000", "0", "10000000", "IN_PROGRESS");
      assertHeld(order, holderA, "4000000");
      String holderB = providedHolder(order, writer(), "6000000");
      assertMoney(order, "10000000", "10000000", "0", "10000000", "IN_PROGRESS");
      assertHeld(order, holderA, "4000000");
      assertHeld(order, holderB, "6000000");

      String holderC =
          move(
                  order,
                  "COLLECTION",
                  holderA,
                  1,
                  "4000000",
                  Map.of("destinationName", "Collection C", "destinationVersion", 0))
              .get("destinationHolderId")
              .asString();
      assertMoney(order, "10000000", "10000000", "0", "10000000", "IN_PROGRESS");
      assertHeld(order, holderA, "0");
      assertHeld(order, holderB, "6000000");
      assertHeld(order, holderC, "4000000");

      move(
          order,
          "COLLECTION",
          holderB,
          1,
          "6000000",
          Map.of("destinationHolderId", holderC, "destinationVersion", 1));
      assertMoney(order, "10000000", "10000000", "0", "10000000", "IN_PROGRESS");
      assertHeld(order, holderA, "0");
      assertHeld(order, holderB, "0");
      assertHeld(order, holderC, "10000000");

      var designated =
          mvc.perform(
                  post("/api/v1/orders/{id}/money/recipients", order)
                      .with(writer())
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(json.writeValueAsString(Map.of("name", "Requester R"))))
              .andExpect(status().isCreated())
              .andReturn();
      String recipient =
          json.readTree(designated.getResponse().getContentAsString()).get("id").asString();
      move(
          order,
          "HANDOVER",
          holderC,
          2,
          "3000000",
          Map.of("recipientId", recipient, "targetVersion", 0));
      assertMoney(order, "10000000", "7000000", "3000000", "7000000", "IN_PROGRESS");
      assertHeld(order, holderC, "7000000");

      move(
          order,
          "HANDOVER",
          holderC,
          3,
          "7000000",
          Map.of("recipientId", recipient, "targetVersion", 0));
      assertMoney(order, "10000000", "0", "10000000", "0", "COMPLETED");
      assertHeld(order, holderA, "0");
      assertHeld(order, holderB, "0");
      assertHeld(order, holderC, "0");
      mvc.perform(get("/api/v1/orders/{id}/money", order).with(reader()))
          .andExpect(jsonPath("$.holders.length()").value(3))
          .andExpect(jsonPath("$.history.length()").value(6))
          .andExpect(jsonPath("$.fees").value("0"));
    } finally {
      jdbc.update(
          "UPDATE system_setting SET setting_value = ? WHERE setting_key = ?",
          originalRate,
          rateKey);
    }
  }

  @Test
  void fourFeeFundedTransfersReconcileExactly() throws Exception {
    withFeeRate(
        "0.005",
        () -> {
          UUID order = createOrder();
          String a = providedHolder(order, reader(), "4000000");
          String b = providedHolder(order, writer(), "6000000");
          Map<String, ?> first =
              movementTerms(
                  "COLLECTION",
                  a,
                  1,
                  "4000000",
                  "0.005",
                  Map.of(
                      "destinationName",
                      "Collecting account",
                      "destinationVersion",
                      0,
                      "provision",
                      "20000"));
          String c = submitMovement(order, reader(), first).get("destinationHolderId").asString();
          submitMovement(
              order,
              writer(),
              movementTerms(
                  "COLLECTION",
                  b,
                  1,
                  "6000000",
                  "0.005",
                  Map.of("destinationHolderId", c, "destinationVersion", 1, "provision", "30000")));
          assertMoney(order, "10050000", "10000000", "0", "10000000", "IN_PROGRESS");
          String recipient = designateRecipient(order, writer(), Map.of("name", "Net requester"));
          Map<String, ?> third =
              movementTerms(
                  "HANDOVER",
                  c,
                  2,
                  "3000000",
                  "0.005",
                  Map.of("recipientId", recipient, "targetVersion", 0, "provision", "15000"));
          JsonNode paidThird = submitMovement(order, writer(), third);
          assertThat(paidThird.path("fee").asString()).isEqualTo("15000");
          assertMoney(order, "10065000", "7000000", "3000000", "7000000", "IN_PROGRESS");
          submitMovement(
              order,
              writer(),
              movementTerms(
                  "HANDOVER",
                  c,
                  3,
                  "7000000",
                  "0.005",
                  Map.of("recipientId", recipient, "targetVersion", 0, "provision", "35000")));
          assertMoney(order, "10100000", "0", "10000000", "0", "COMPLETED");
          mvc.perform(get("/api/v1/orders/{id}/money", order).with(reader()))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.fees").value("100000"))
              .andExpect(jsonPath("$.history.length()").value(6));
          withFeeRate(
              "0.01",
              () -> assertThat(submitMovement(order, writer(), third)).isEqualTo(paidThird));
          assertAuditCount(order, "JOB_ORDER_MONEY_MOVED", 4);
          assertAuditCount(order, "JOB_ORDER_COMPLETED", 1);
        });
  }

  @Test
  void onlyExplicitAuthorisedProvisionCanFillTheDeficitInTheSameOperation() throws Exception {
    withFeeRate(
        "0",
        () -> {
          UUID order = createOrder();
          String source = providedHolder(order, reader(), "2000000");
          Map<String, ?> unbacked =
              movementTerms(
                  "COLLECTION",
                  source,
                  1,
                  "5000000",
                  "0",
                  Map.of("destinationUserId", writer, "destinationVersion", 0));
          mvc.perform(movementRequest(order, reader(), unbacked, true))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.shortfall").value("3000000"));
          mvc.perform(movementRequest(order, reader(), unbacked, false))
              .andExpect(status().isConflict())
              .andExpect(jsonPath("$.code").value("MONEY_PROVISION_REFUSED"));
          assertHeld(order, source, "2000000");
          mvc.perform(get("/api/v1/orders/{id}/money", order).with(reader()))
              .andExpect(jsonPath("$.holders.length()").value(1))
              .andExpect(jsonPath("$.history.length()").value(1));

          Map<String, ?> covered =
              movementTerms(
                  "COLLECTION",
                  source,
                  1,
                  "5000000",
                  "0",
                  Map.of(
                      "destinationName",
                      "New holder",
                      "destinationVersion",
                      0,
                      "provision",
                      "3000000"));
          JsonNode booked = submitMovement(order, reader(), covered);
          assertThat(booked.path("provision").asString()).isEqualTo("3000000");
          assertHeld(order, source, "0");
          assertHeld(order, booked.path("destinationHolderId").asString(), "5000000");
          assertMoney(order, "5000000", "5000000", "0", "10000000", "IN_PROGRESS");
        });
  }

  @Test
  void linkedRequesterRecipientCanRecordAnIncomingPaymentButNotInventForeignFunds()
      throws Exception {
    UUID order = createOrder();
    UUID unrelated = createOrder();
    var payee = signedInMember(unitless);
    mvc.perform(get("/api/v1/orders/{id}/money", order).with(payee))
        .andExpect(status().isForbidden());
    String recipient = designateRecipient(order, writer(), Map.of("userId", unitless));
    mvc.perform(get("/api/v1/orders/{id}/money", order).with(payee))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.canProvideSelf").value(true))
        .andExpect(jsonPath("$.canDesignateRecipient").value(false));
    mvc.perform(get("/api/v1/orders/{id}/money", unrelated).with(payee))
        .andExpect(status().isForbidden());
    String source = providedHolder(order, reader(), "5");
    Map<String, ?> overdraw =
        movementTerms(
            "HANDOVER",
            source,
            1,
            "6",
            "0.005",
            Map.of("recipientId", recipient, "targetVersion", 0, "provision", "1"));
    mvc.perform(movementRequest(order, payee, overdraw, false)).andExpect(status().isForbidden());
    JsonNode paid =
        submitMovement(
            order,
            payee,
            movementTerms(
                "HANDOVER",
                source,
                1,
                "5",
                "0.005",
                Map.of("recipientId", recipient, "targetVersion", 0)));
    assertThat(paid.path("recipientId").asString()).isEqualTo(recipient);

    mvc.perform(
            delete("/api/v1/orders/{id}/money/recipients/{recipient}", order, recipient)
                .with(writer()))
        .andExpect(status().isNoContent());
    String replacement =
        designateRecipient(order, requesterWriter(), Map.of("name", "Replacement"));
    assertThat(replacement).isNotEqualTo(recipient);
    mvc.perform(get("/api/v1/orders/{id}/money", order).with(payee))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.netHandedOver").value("5"))
        .andExpect(jsonPath("$.history[1].kind").value("HANDOVER"))
        .andExpect(jsonPath("$.history[1].recipientId").value(recipient));
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(moneyMoveNotices(reader(), order)).isEqualTo(1));
    assertThat(moneyMoveNotices(payee, order)).isZero();
  }

  @Test
  void sameHolderPurposeHandoverRequiresExplicitPurposeAndKeepsOverpaymentSeparate()
      throws Exception {
    UUID order = createOrder();
    String source = providedHolder(order, reader(), "12000000");
    String recipient = designateRecipient(order, writer(), Map.of("holderId", source));
    Map<String, ?> invalid =
        movementTerms(
            "HANDOVER",
            source,
            1,
            "11000000",
            "0.005",
            Map.of("recipientId", recipient, "targetVersion", 0));
    mvc.perform(movementRequest(order, reader(), invalid, true)).andExpect(status().isBadRequest());
    JsonNode delivered =
        submitMovement(
            order,
            reader(),
            movementTerms(
                "PURPOSE_HANDOVER",
                source,
                1,
                "11000000",
                "0",
                Map.of("recipientId", recipient, "targetVersion", 0)));
    assertThat(delivered.path("fee").asString()).isEqualTo("0");
    assertThat(delivered.path("provision").asString()).isEqualTo("0");
    assertMoney(order, "12000000", "1000000", "11000000", "0", "COMPLETED");

    mvc.perform(targetRequest(order, "12000000", 0))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.remaining").value("1000000"))
        .andExpect(jsonPath("$.informationVersion").value(0));
    assertMoney(order, "12000000", "1000000", "11000000", "1000000", "IN_PROGRESS");
    mvc.perform(
            patch("/api/v1/orders/{id}/money/information", order)
                .with(writer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("handle", "New contact", "comment", "Terms", "version", 0))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targetVersion").value(1))
        .andExpect(jsonPath("$.informationVersion").value(1));
    mvc.perform(targetRequest(order, "8000000", 1))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.overpaid").value("3000000"))
        .andExpect(jsonPath("$.informationVersion").value(1))
        .andExpect(jsonPath("$.targetChanges.length()").value(2))
        .andExpect(jsonPath("$.targetChanges[0].previousTarget").value("10000000"))
        .andExpect(jsonPath("$.targetChanges[1].target").value("8000000"));
    assertMoney(order, "12000000", "1000000", "11000000", "0", "COMPLETED");
    mvc.perform(get("/api/v1/orders/{id}", order).with(reader()))
        .andExpect(jsonPath("$.handle").value("New contact"));
    mvc.perform(
            put("/api/v1/orders/{id}/status", order)
                .with(writer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"OPEN\",\"version\":4}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @Timeout(60)
  void postgresSerializesCompetingDebitsAndKeepsAcceptedReplayExact() throws Exception {
    UUID order = createOrder();
    String source = providedHolder(order, reader(), "7");
    Map<String, ?> winning =
        movementTerms(
            "COLLECTION",
            source,
            1,
            "6",
            "0.005",
            Map.of("destinationName", "First collection", "destinationVersion", 0));
    Map<String, ?> losing =
        movementTerms(
            "COLLECTION",
            source,
            1,
            "6",
            "0.005",
            Map.of("destinationName", "Second collection", "destinationVersion", 0));
    RaceResults race =
        serializeBehind(
            movementRequest(order, reader(), winning, false),
            200,
            movementRequest(order, writer(), losing, false));
    assertThat(race.waiter().getResponse().getStatus()).isEqualTo(409);
    mvc.perform(
            get(
                    "/api/v1/orders/{id}/money/operations/{operation}",
                    order,
                    losing.get("operationId"))
                .with(writer()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("MONEY_PROVISION_REFUSED"));
    JsonNode original = json.readTree(race.first().getResponse().getContentAsByteArray());
    assertThat(submitMovement(order, reader(), winning)).isEqualTo(original);
    assertHeld(order, source, "1");
    assertHeld(order, original.path("destinationHolderId").asString(), "6");
    mvc.perform(get("/api/v1/orders/{id}/money", order).with(reader()))
        .andExpect(jsonPath("$.history.length()").value(2))
        .andExpect(jsonPath("$.holders.length()").value(2));
    assertAuditCount(order, "JOB_ORDER_MONEY_MOVED", 1);
  }

  @Test
  @Timeout(60)
  void postgresTargetChangeAndCancellationDefeatStaleHandoverWithoutAnyDebit() throws Exception {
    withFeeRate(
        "0",
        () -> {
          UUID order = createOrder();
          String source = providedHolder(order, reader(), "10000000");
          String recipient = designateRecipient(order, writer(), Map.of("name", "Requester"));
          Map<String, ?> stale =
              movementTerms(
                  "HANDOVER",
                  source,
                  1,
                  "10000000",
                  "0",
                  Map.of("recipientId", recipient, "targetVersion", 0));
          RaceResults targetRace =
              serializeBehind(
                  targetRequest(order, "12000000", 0),
                  200,
                  movementRequest(order, reader(), stale, false));
          assertThat(targetRace.waiter().getResponse().getStatus()).isEqualTo(409);
          assertMoney(order, "10000000", "10000000", "0", "12000000", "IN_PROGRESS");
          Map<String, ?> correct =
              movementTerms(
                  "HANDOVER",
                  source,
                  1,
                  "10000000",
                  "0",
                  Map.of("recipientId", recipient, "targetVersion", 1));
          var cancel =
              put("/api/v1/orders/{id}/status", order)
                  .with(writer())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"status\":\"CANCELLED\",\"version\":1}");
          RaceResults cancelRace =
              serializeBehind(cancel, 200, movementRequest(order, reader(), correct, false));
          assertThat(cancelRace.waiter().getResponse().getStatus()).isEqualTo(409);
          assertMoney(order, "10000000", "10000000", "0", "12000000", "CANCELLED");
          assertHeld(order, source, "10000000");
          assertAuditCount(order, "JOB_ORDER_MONEY_MOVED", 0);
          assertAuditCount(order, "JOB_ORDER_MONEY_TARGET_CHANGED", 1);
        });
  }

  @Test
  void failedMovementAuditRollsBackDebitDeliveryAndAutoCompletionTogether() throws Exception {
    withFeeRate(
        "0",
        () -> {
          UUID order = createOrder();
          String source = providedHolder(order, reader(), "10000000");
          String recipient = designateRecipient(order, writer(), Map.of("name", "Requester"));
          Map<String, ?> terms =
              movementTerms(
                  "HANDOVER",
                  source,
                  1,
                  "10000000",
                  "0",
                  Map.of("recipientId", recipient, "targetVersion", 0));
          String constraint = "audit_move_" + order.toString().replace("-", "");
          jdbc.execute(
              "ALTER TABLE audit_event ADD CONSTRAINT "
                  + constraint
                  + " CHECK (subject_id IS DISTINCT FROM '"
                  + order
                  + "'::uuid OR event_type <> 'JOB_ORDER_MONEY_MOVED') NOT VALID");
          try {
            mvc.perform(movementRequest(order, reader(), terms, false))
                .andExpect(status().isConflict());
          } finally {
            jdbc.execute("ALTER TABLE audit_event DROP CONSTRAINT " + constraint);
          }
          assertMoney(order, "10000000", "10000000", "0", "10000000", "IN_PROGRESS");
          assertAuditCount(order, "JOB_ORDER_COMPLETED", 0);
          mvc.perform(
                  get(
                          "/api/v1/orders/{id}/money/operations/{operation}",
                          order,
                          terms.get("operationId"))
                      .with(reader()))
              .andExpect(status().isNotFound());
          submitMovement(order, reader(), terms);
          assertMoney(order, "10000000", "0", "10000000", "0", "COMPLETED");
          assertAuditCount(order, "JOB_ORDER_COMPLETED", 1);
          assertAuditCount(order, "JOB_ORDER_MONEY_MOVED", 1);
        });
  }

  @Test
  @Timeout(60)
  void postgresIdenticalMovementReplayAndChangedFeeBasisPreserveOneEffect() throws Exception {
    UUID order = createOrder();
    String source = providedHolder(order, reader(), "3000000");
    Map<String, ?> same =
        movementTerms(
            "COLLECTION",
            source,
            1,
            "1000000",
            "0.005",
            Map.of("destinationName", "Collecting account", "destinationVersion", 0));
    RaceResults race =
        serializeBehind(
            movementRequest(order, reader(), same, false),
            200,
            movementRequest(order, reader(), same, false));
    assertThat(race.waiter().getResponse().getStatus()).isEqualTo(200);
    JsonNode original = json.readTree(race.first().getResponse().getContentAsByteArray());
    assertThat(json.readTree(race.waiter().getResponse().getContentAsByteArray()))
        .isEqualTo(original);
    assertThat(original.path("fee").asString()).isEqualTo("5000");
    String collected = original.path("destinationHolderId").asString();
    Map<String, ?> newAttempt =
        movementTerms(
            "COLLECTION",
            source,
            2,
            "1000000",
            "0.005",
            Map.of("destinationHolderId", collected, "destinationVersion", 1));
    mvc.perform(movementRequest(order, reader(), newAttempt, true))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fee").value("5000"));
    withFeeRate(
        "0.01",
        () -> {
          mvc.perform(movementRequest(order, reader(), newAttempt, false))
              .andExpect(status().isConflict())
              .andExpect(jsonPath("$.code").value("MONEY_PROVISION_REFUSED"));
          assertThat(submitMovement(order, reader(), same)).isEqualTo(original);
        });
    assertHeld(order, source, "1995000");
    assertHeld(order, collected, "1000000");
    assertAuditCount(order, "JOB_ORDER_MONEY_MOVED", 1);
  }

  @Test
  void movementReplayRequiresCurrentAuthorityButReadersMayRecoverAnOutcome() throws Exception {
    UUID order = createOrder();
    String source = providedHolder(order, reader(), "10");
    Map<String, ?> original =
        movementTerms(
            "COLLECTION",
            source,
            1,
            "5",
            "0.005",
            Map.of("destinationName", "Receiving account", "destinationVersion", 0));
    JsonNode accepted = submitMovement(order, writer(), original);
    mvc.perform(
            patch("/api/v1/orders/{id}/responsible-org-unit", order)
                .with(writer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("responsibleOrgUnitId", profitRoot, "version", 0))))
        .andExpect(status().isOk());
    mvc.perform(movementRequest(order, writer(), original, false))
        .andExpect(status().isForbidden());
    mvc.perform(
            get(
                    "/api/v1/orders/{id}/money/operations/{operation}",
                    order,
                    accepted.path("operationId").asString())
                .with(writer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.amount").value("5"));
    assertHeld(order, source, "5");
    assertAuditCount(order, "JOB_ORDER_MONEY_MOVED", 1);
  }

  @Test
  @Timeout(60)
  void unauthorizedCompletionCandidateDoesNotWaitForAnUnrelatedQueueRow() throws Exception {
    UUID order = createOrder();
    UUID unrelated = createOrder();
    String source = providedHolder(order, writer(), "11000000");
    String recipient = designateRecipient(order, writer(), Map.of("name", "Requester"));
    Map<String, ?> terms =
        movementTerms(
            "HANDOVER",
            source,
            1,
            "10000000",
            "0.005",
            Map.of("recipientId", recipient, "targetVersion", 0));
    try (var worker = Executors.newSingleThreadExecutor()) {
      transactions.executeWithoutResult(_ -> {
        jdbc.queryForObject(
            "SELECT id FROM job_order WHERE id = ? FOR NO KEY UPDATE", UUID.class, unrelated);
        try {
          MvcResult forbidden =
              worker
                  .submit(() -> perform(movementRequest(order, reader(), terms, false)))
                  .get(8, TimeUnit.SECONDS);
          assertThat(forbidden.getResponse().getStatus()).isEqualTo(403);
        } catch (Exception failure) {
          throw new IllegalStateException(
              "An unauthorized movement must not wait on the queue.", failure);
        }
      });
    }
    assertMoney(order, "11000000", "11000000", "0", "10000000", "IN_PROGRESS");
  }

  @Test
  @Timeout(60)
  void oppositeCollectionsAcquireTheSameAccountOrderAndRecheckBothHolders() throws Exception {
    UUID order = createOrder();
    String a = providedHolder(order, reader(), "10");
    String b = providedHolder(order, writer(), "10");
    Map<String, ?> forward =
        movementTerms(
            "COLLECTION",
            a,
            1,
            "3",
            "0.005",
            Map.of("destinationHolderId", b, "destinationVersion", 1));
    Map<String, ?> reverse =
        movementTerms(
            "COLLECTION",
            b,
            1,
            "3",
            "0.005",
            Map.of("destinationHolderId", a, "destinationVersion", 1));
    RaceResults race =
        serializeBehind(
            movementRequest(order, reader(), forward, false),
            200,
            movementRequest(order, writer(), reverse, false));
    assertThat(race.waiter().getResponse().getStatus()).isEqualTo(409);
    assertHeld(order, a, "7");
    assertHeld(order, b, "13");
    assertMoney(order, "20", "20", "0", "10000000", "IN_PROGRESS");
    assertAuditCount(order, "JOB_ORDER_MONEY_MOVED", 1);
  }

  @Test
  void aLowerFeeCannotLeaveAnExplicitlyFundedAttemptUnknownOrLaterBookIt() throws Exception {
    UUID order = createOrder();
    String source = providedHolder(order, reader(), "2000000");
    Map<String, ?> terms =
        movementTerms(
            "COLLECTION",
            source,
            1,
            "2000000",
            "0.005",
            Map.of(
                "destinationName",
                "Receiving account",
                "destinationVersion",
                0,
                "provision",
                "10000"));
    mvc.perform(movementRequest(order, reader(), terms, true))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fee").value("10000"))
        .andExpect(jsonPath("$.shortfall").value("0"));
    withFeeRate(
        "0",
        () ->
            mvc.perform(movementRequest(order, reader(), terms, false))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MONEY_PROVISION_REFUSED")));
    mvc.perform(
            get("/api/v1/orders/{id}/money/operations/{operation}", order, terms.get("operationId"))
                .with(reader()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("MONEY_PROVISION_REFUSED"));
    mvc.perform(movementRequest(order, reader(), terms, false)).andExpect(status().isConflict());
    assertHeld(order, source, "2000000");
    assertAuditCount(order, "JOB_ORDER_MONEY_MOVED", 0);
  }

  @Test
  @Timeout(60)
  void terminalHandoverWaitsForButIsNotStaledByIndependentContactEdit() throws Exception {
    withFeeRate(
        "0",
        () -> {
          UUID order = createOrder();
          String source = providedHolder(order, reader(), "10000000");
          String recipient = designateRecipient(order, writer(), Map.of("name", "Requester"));
          Map<String, ?> payment =
              movementTerms(
                  "HANDOVER",
                  source,
                  1,
                  "10000000",
                  "0",
                  Map.of("recipientId", recipient, "targetVersion", 0));
          var information =
              patch("/api/v1/orders/{id}/money/information", order)
                  .with(writer())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      json.writeValueAsString(
                          Map.of(
                              "handle",
                              "Updated contact",
                              "comment",
                              "Independent",
                              "version",
                              0)));
          RaceResults race =
              serializeBehind(information, 200, movementRequest(order, reader(), payment, false));
          assertThat(race.waiter().getResponse().getStatus()).isEqualTo(200);
          assertMoney(order, "10000000", "0", "10000000", "0", "COMPLETED");
          mvc.perform(get("/api/v1/orders/{id}/money", order).with(reader()))
              .andExpect(jsonPath("$.targetVersion").value(0))
              .andExpect(jsonPath("$.informationVersion").value(1));
          mvc.perform(get("/api/v1/orders/{id}", order).with(reader()))
              .andExpect(jsonPath("$.handle").value("Updated contact"));
        });
  }

  @Test
  void retiringAQuotedPayeeLeavesARecoverableNoEffectDecision() throws Exception {
    UUID order = createOrder();
    String source = providedHolder(order, reader(), "10");
    String recipient = designateRecipient(order, writer(), Map.of("name", "Requester"));
    Map<String, ?> terms =
        movementTerms(
            "HANDOVER",
            source,
            1,
            "5",
            "0.005",
            Map.of("recipientId", recipient, "targetVersion", 0));
    mvc.perform(movementRequest(order, reader(), terms, true)).andExpect(status().isOk());
    mvc.perform(
            delete("/api/v1/orders/{id}/money/recipients/{recipient}", order, recipient)
                .with(writer()))
        .andExpect(status().isNoContent());
    mvc.perform(movementRequest(order, reader(), terms, false))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("MONEY_PROVISION_REFUSED"));
    mvc.perform(
            get("/api/v1/orders/{id}/money/operations/{operation}", order, terms.get("operationId"))
                .with(reader()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("MONEY_PROVISION_REFUSED"));
    assertHeld(order, source, "10");
    assertAuditCount(order, "JOB_ORDER_MONEY_MOVED", 0);
  }

  private String designateRecipient(UUID order, RequestPostProcessor caller, Map<String, ?> terms)
      throws Exception {
    MvcResult result =
        mvc.perform(
                post("/api/v1/orders/{id}/money/recipients", order)
                    .with(caller)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(terms)))
            .andExpect(status().isCreated())
            .andReturn();
    return json.readTree(result.getResponse().getContentAsByteArray()).path("id").asString();
  }

  private JsonNode submitMovement(UUID order, RequestPostProcessor caller, Map<String, ?> terms)
      throws Exception {
    MvcResult result =
        mvc.perform(movementRequest(order, caller, terms, false))
            .andExpect(status().isOk())
            .andReturn();
    return json.readTree(result.getResponse().getContentAsByteArray());
  }

  private static Map<String, ?> movementTerms(
      String kind,
      String source,
      int sourceVersion,
      String amount,
      String rate,
      Map<String, ?> destination) {
    var body = new HashMap<String, Object>(destination);
    body.putAll(
        Map.of(
            "operationId",
            UUID.randomUUID(),
            "kind",
            kind,
            "sourceHolderId",
            source,
            "sourceVersion",
            sourceVersion,
            "amount",
            amount,
            "rate",
            rate,
            "bankHandling",
            false));
    return body;
  }

  private MockHttpServletRequestBuilder movementRequest(
      UUID order, RequestPostProcessor caller, Map<String, ?> terms, boolean preview) {
    String path = "/api/v1/orders/{id}/money/movements" + (preview ? "/preview" : "");
    return post(path, order)
        .with(caller)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(terms));
  }

  private MockHttpServletRequestBuilder targetRequest(UUID order, String amount, long version) {
    return patch("/api/v1/orders/{id}/money/target", order)
        .with(writer())
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(Map.of("target", amount, "version", version)));
  }

  private void assertAuditCount(UUID order, String event, int count) {
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE subject_id = ? AND event_type = ?",
                Integer.class,
                order,
                event))
        .isEqualTo(count);
  }

  private int moneyMoveNotices(RequestPostProcessor user, UUID order) {
    MvcResult result = perform(get("/api/v1/notifications").with(user));
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    int count = 0;
    for (var notice : json.readTree(result.getResponse().getContentAsByteArray()).path("content")) {
      if ("JOB_ORDER_MONEY_MOVED".equals(notice.path("type").asString())
          && order.toString().equals(notice.path("entityId").asString())) {
        count++;
      }
    }
    return count;
  }

  private void withFeeRate(String rate, CheckedScenario scenario) throws Exception {
    String key = "operation.transfer_fee_rate";
    String original =
        jdbc.queryForObject(
            "SELECT setting_value FROM system_setting WHERE setting_key = ?", String.class, key);
    jdbc.update("UPDATE system_setting SET setting_value = ? WHERE setting_key = ?", rate, key);
    try {
      scenario.run();
    } finally {
      jdbc.update(
          "UPDATE system_setting SET setting_value = ? WHERE setting_key = ?", original, key);
    }
  }

  @FunctionalInterface
  private interface CheckedScenario {
    void run() throws Exception;
  }

  private String providedHolder(UUID order, RequestPostProcessor caller, String amount)
      throws Exception {
    var result =
        provision(order, caller, provisionTerms(UUID.randomUUID(), amount, 0))
            .andExpect(status().isOk())
            .andReturn();
    return json.readTree(result.getResponse().getContentAsString()).get("holderId").asString();
  }

  private JsonNode move(
      UUID order,
      String kind,
      String sourceHolder,
      int sourceVersion,
      String amount,
      Map<String, ?> destination)
      throws Exception {
    var body = new HashMap<String, Object>(destination);
    body.putAll(
        Map.of(
            "operationId",
            UUID.randomUUID(),
            "kind",
            kind,
            "sourceHolderId",
            sourceHolder,
            "sourceVersion",
            sourceVersion,
            "amount",
            amount,
            "rate",
            "0",
            "bankHandling",
            false));
    var result =
        mvc.perform(
                post("/api/v1/orders/{id}/money/movements", order)
                    .with(writer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn();
    return json.readTree(result.getResponse().getContentAsString());
  }

  private void assertMoney(
      UUID order,
      String provided,
      String held,
      String handedOver,
      String remaining,
      String statusValue)
      throws Exception {
    mvc.perform(get("/api/v1/orders/{id}/money", order).with(reader()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.provided").value(provided))
        .andExpect(jsonPath("$.holdings").value(held))
        .andExpect(jsonPath("$.netHandedOver").value(handedOver))
        .andExpect(jsonPath("$.remaining").value(remaining));
    mvc.perform(get("/api/v1/orders/{id}", order).with(reader()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value(statusValue));
  }

  private void assertHeld(UUID order, String holderId, String amount) throws Exception {
    var result =
        mvc.perform(get("/api/v1/orders/{id}/money", order).with(reader()))
            .andExpect(status().isOk())
            .andReturn();
    var amounts = new ArrayList<String>();
    for (JsonNode holder :
        json.readTree(result.getResponse().getContentAsByteArray()).path("holders")) {
      if (holderId.equals(holder.path("id").asString())) {
        amounts.add(holder.path("amount").asString());
      }
    }
    assertThat(amounts).containsExactly(amount);
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
  void aRefusedOperationCanNeverBeAcceptedWithUpdatedTerms() throws Exception {
    UUID order = createOrder();
    UUID missing = UUID.randomUUID();
    String refusedCode = "MONEY_PROVISION_REFUSED";
    var outcome =
        get("/api/v1/orders/{id}/money/operations/{operation}", order, missing).with(reader());
    mvc.perform(outcome).andExpect(status().isNotFound());

    provision(order, reader(), provisionTerms(UUID.randomUUID(), "7", 0))
        .andExpect(status().isOk());
    provision(order, reader(), provisionTerms(missing, "19", 0))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(refusedCode));
    mvc.perform(outcome)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(refusedCode));
    provision(order, reader(), provisionTerms(missing, "19", 1))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(refusedCode));

    provision(order, reader(), provisionTerms(UUID.randomUUID(), "19", 1))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/orders/{id}/money", order).with(reader()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.provided").value("26"))
        .andExpect(jsonPath("$.history.length()").value(2));
    mvc.perform(provisionAudit())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2));
  }

  @Test
  void refusalWhileAnOrderIsClosedSurvivesReopening() throws Exception {
    UUID order = createOrder();
    UUID operation = UUID.randomUUID();
    Map<String, ?> terms = provisionTerms(operation, "23", 0);
    mvc.perform(
            put("/api/v1/orders/{id}/status", order)
                .with(writer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"CANCELLED\",\"version\":0}"))
        .andExpect(status().isOk());
    provision(order, reader(), terms)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("MONEY_PROVISION_REFUSED"));

    mvc.perform(
            put("/api/v1/orders/{id}/status", order)
                .with(writer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"OPEN\",\"version\":1}"))
        .andExpect(status().isOk());
    mvc.perform(
            get("/api/v1/orders/{id}/money/operations/{operation}", order, operation)
                .with(reader()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("MONEY_PROVISION_REFUSED"));
    provision(order, reader(), terms)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("MONEY_PROVISION_REFUSED"));
    mvc.perform(get("/api/v1/orders/{id}/money", order).with(reader()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.history").isEmpty());
    provision(order, reader(), provisionTerms(UUID.randomUUID(), "23", 0))
        .andExpect(status().isOk());
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
    UUID refused = UUID.randomUUID();
    provision(order, reader(), provisionTerms(refused, "12", 1))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("MONEY_PROVISION_REFUSED"));
    mvc.perform(get("/api/v1/orders/{id}/money", order).with(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.canDelete").value(true))
        .andExpect(jsonPath("$.history").isEmpty());

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
  void distinctConcurrentProvisionsCreditOnlyTheOutstandingCommitment() throws Exception {
    UUID order = createOrder();
    mvc.perform(
            put("/api/v1/orders/{id}/participations/{unitId}/money-commitment", order, responsible)
                .with(writer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":\"6000000\"}"))
        .andExpect(status().isNoContent());
    var support = get("/api/v1/orders/{id}/participations", order).with(writer());
    String before =
        mvc.perform(support)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.moneyCommitments[0].outstanding").value("6000000"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String commitmentId =
        json.readTree(before).path("moneyCommitments").get(0).path("id").asString();
    Map<String, Object> first =
        Map.of(
            "operationId",
            UUID.randomUUID(),
            "amount",
            "4000000",
            "holderVersion",
            0,
            "bankHandling",
            false,
            "commitmentId",
            commitmentId);
    Map<String, Object> second =
        Map.of(
            "operationId",
            UUID.randomUUID(),
            "amount",
            "4000000",
            "holderVersion",
            0,
            "bankHandling",
            false,
            "commitmentId",
            commitmentId);

    RaceResults race =
        serializeBehind(
            provisionRequest(order, writer(), first),
            200,
            provisionRequest(order, writerFor(reader), second));
    assertThat(race.waiter().getResponse().getStatus()).isEqualTo(200);

    provision(order, writer(), first).andExpect(status().isOk());
    mvc.perform(support)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.moneyCommitments[0].outstanding").value("0"))
        .andExpect(jsonPath("$.moneyCommitments[0].changes.length()").value(3))
        .andExpect(jsonPath("$.moneyCommitments[0].changes[1].after").value("2000000"))
        .andExpect(jsonPath("$.moneyCommitments[0].changes[2].before").value("2000000"))
        .andExpect(jsonPath("$.moneyCommitments[0].changes[2].reason").value("FULFILLED"));
    mvc.perform(get("/api/v1/orders/{id}/money", order).with(writer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.provided").value("8000000"))
        .andExpect(jsonPath("$.holders.length()").value(2))
        .andExpect(jsonPath("$.history.length()").value(2));
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
                          if (response.getResponse().getStatus() >= 400
                              && !durableMoneyRefusal(response)) {
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

  private boolean durableMoneyRefusal(MvcResult response) {
    return response.getResponse().getStatus() == 409
        && "MONEY_PROVISION_REFUSED"
            .equals(
                json.readTree(response.getResponse().getContentAsByteArray())
                    .path("code")
                    .asString());
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
    return writerFor(writer);
  }

  private RequestPostProcessor writerFor(UUID user) {
    return jwt()
        .jwt(token -> token.subject(user.toString()))
        .authorities(
            new SimpleGrantedAuthority("ROLE_KRT_MEMBER"),
            new SimpleGrantedAuthority("ROLE_LOGISTICIAN"),
            new OrgUnitContextualAuthority(Roles.LOGISTICIAN, responsible));
  }

  private RequestPostProcessor requesterWriter() {
    return jwt()
        .jwt(token -> token.subject(writer.toString()))
        .authorities(
            new SimpleGrantedAuthority("ROLE_KRT_MEMBER"),
            new SimpleGrantedAuthority("ROLE_LOGISTICIAN"),
            new OrgUnitContextualAuthority(Roles.LOGISTICIAN, requester));
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

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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Blueprint;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderType;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.UnexpectedRollbackException;
import tools.jackson.databind.JsonNode;

/**
 * Checks final fulfillment when distinct item lines are delivered by concurrent public requests.
 */
@SpringBootTest
@WithMockUser(roles = "ADMIN")
class JobOrderItemCompletionRaceIntegrationTest extends MaterialContributionTestSupport {
  @Autowired private GameItemRepository gameItems;
  @Autowired private BlueprintRepository blueprints;
  @Autowired private DataSource dataSource;

  @Test
  @Timeout(60)
  void concurrentLastLineHandoversCompleteExactlyOnceWithoutLosingEitherDelivery()
      throws Exception {
    RaceFixture fixture = seedFinishedLines();
    String gate = "item_delivery_gate_" + UUID.randomUUID().toString().replace("-", "");
    String firstTable = gate + "_a";
    String secondTable = gate + "_b";
    String firstPayload = payload(fixture.firstLine());
    String secondPayload = payload(fixture.secondLine());
    try {
      createGates(gate, firstTable, secondTable, fixture);
      AtomicInteger firstPid = new AtomicInteger();
      AtomicInteger secondPid = new AtomicInteger();
      MvcResult firstResult;
      MvcResult secondResult;
      try (var workers = Executors.newFixedThreadPool(2);
          Connection control = dataSource.getConnection()) {
        control.setAutoCommit(false);
        int controlPid;
        try (Statement sql = control.createStatement()) {
          sql.execute(
              "LOCK TABLE " + firstTable + ", " + secondTable + " IN ACCESS EXCLUSIVE MODE");
          try (ResultSet row = sql.executeQuery("SELECT pg_backend_pid()")) {
            assertThat(row.next()).isTrue();
            controlPid = row.getInt(1);
          }
        }
        var first = workers.submit(() -> deliver(fixture, firstPayload, firstPid));
        await()
            .atMost(Duration.ofSeconds(10))
            .until(() -> blockedByEither(firstPid.get(), controlPid, controlPid));
        var second = workers.submit(() -> deliver(fixture, secondPayload, secondPid));
        await()
            .atMost(Duration.ofSeconds(10))
            .until(() -> blockedByEither(secondPid.get(), controlPid, firstPid.get()));
        assertThat(firstPid.get()).isNotEqualTo(secondPid.get());
        control.commit();
        firstResult = first.get(15, TimeUnit.SECONDS);
        secondResult = second.get(15, TimeUnit.SECONDS);
      }
      assertCreatedOrRetrySamePayload(fixture, firstPayload, firstResult);
      assertCreatedOrRetrySamePayload(fixture, secondPayload, secondResult);
      JsonNode current =
          json.readTree(
              mvc()
                  .perform(
                      get("/api/v1/orders/{id}", fixture.base().orderId())
                          .with(
                              jwt()
                                  .jwt(token -> token.subject(fixture.actor().toString()))
                                  .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                  .andExpect(status().isOk())
                  .andReturn()
                  .getResponse()
                  .getContentAsString());
      assertThat(current.path("items")).hasSize(2);
      for (JsonNode line : current.path("items")) {
        assertThat(line.path("amount").intValue()).isEqualTo(1);
        assertThat(line.path("manufacturedAmount").intValue()).isEqualTo(1);
        assertThat(line.path("deliveredAmount").intValue()).isEqualTo(1);
      }
      assertThat(current.path("itemHandovers")).hasSize(2);
      assertThat(current.path("status").asString()).isEqualTo("COMPLETED");
    } finally {
      try {
        jdbc.execute("DROP TRIGGER IF EXISTS " + gate + " ON job_order_item");
        jdbc.execute("DROP FUNCTION IF EXISTS " + gate + "()");
        jdbc.execute("DROP TABLE IF EXISTS " + firstTable + ", " + secondTable);
      } finally {
        cleanup(fixture);
      }
    }
  }

  private String payload(UUID line) {
    return json.writeValueAsString(
        new JobOrderItemHandoverCreateDto(
            Instant.now(),
            "Completion race recipient",
            List.of(new JobOrderItemHandoverEntryCreateDto(line, 1))));
  }

  private MvcResult deliver(RaceFixture fixture, String body, AtomicInteger pid) {
    AtomicReference<MvcResult> response = new AtomicReference<>();
    try {
      return Objects.requireNonNull(
          transactionTemplate.execute(_ -> {
            pid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
            try {
              MvcResult result =
                  mvc()
                      .perform(
                          post("/api/v1/orders/{id}/item-handovers", fixture.base().orderId())
                              .with(
                                  jwt()
                                      .jwt(token -> token.subject(fixture.actor().toString()))
                                      .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                              .contentType(MediaType.APPLICATION_JSON)
                              .content(body))
                      .andReturn();
              response.set(result);
              return result;
            } catch (Exception failure) {
              throw new IllegalStateException("Public item handover failed", failure);
            }
          }));
    } catch (UnexpectedRollbackException failure) {
      MvcResult result = response.get();
      if (result != null && result.getResponse().getStatus() == 409) {
        return result;
      }
      throw failure;
    }
  }

  private void assertCreatedOrRetrySamePayload(RaceFixture fixture, String body, MvcResult result)
      throws Exception {
    assertThat(result.getResponse().getStatus())
        .as(result.getResponse().getContentAsString())
        .isIn(201, 409);
    if (result.getResponse().getStatus() == 409) {
      var retry = deliver(fixture, body, new AtomicInteger());
      assertThat(retry.getResponse().getStatus())
          .as(retry.getResponse().getContentAsString())
          .isEqualTo(201);
    }
  }

  private boolean blockedByEither(int waiter, int first, int second) {
    return jdbc.queryForObject(
            "SELECT count(*) FROM pg_stat_activity WHERE pid = ?"
                + " AND wait_event_type = 'Lock' AND (? = ANY(pg_blocking_pids(pid))"
                + " OR ? = ANY(pg_blocking_pids(pid)))",
            Integer.class,
            waiter,
            first,
            second)
        > 0;
  }

  private void createGates(
      String gate, String firstTable, String secondTable, RaceFixture fixture) {
    jdbc.execute("CREATE TABLE " + firstTable + " (id integer)");
    jdbc.execute("CREATE TABLE " + secondTable + " (id integer)");
    jdbc.execute(
        """
        CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $gate$
        BEGIN
          IF NEW.job_order_id = '%s'::uuid AND NEW.delivered_amount > OLD.delivered_amount THEN
            IF NEW.id = '%s'::uuid THEN
              LOCK TABLE %s IN ACCESS SHARE MODE;
            ELSIF NEW.id = '%s'::uuid THEN
              LOCK TABLE %s IN ACCESS SHARE MODE;
            END IF;
          END IF;
          RETURN NEW;
        END;
        $gate$
        """
            .formatted(
                gate,
                fixture.base().orderId(),
                fixture.firstLine(),
                firstTable,
                fixture.secondLine(),
                secondTable));
    jdbc.execute(
        "CREATE TRIGGER "
            + gate
            + " BEFORE UPDATE ON job_order_item"
            + " FOR EACH ROW EXECUTE FUNCTION "
            + gate
            + "()");
  }

  private RaceFixture seedFinishedLines() {
    Fixture base = seed();
    return Objects.requireNonNull(
        transactionTemplate.execute(_ -> {
          User actor = new User();
          actor.setId(UUID.randomUUID());
          actor.setUsername("item-completion-" + UUID.randomUUID());
          actor.setApprovalStatus(ApprovalStatus.ACTIVE);
          actor.setInKeycloak(true);
          actor = users.saveAndFlush(actor);
          var order = jobOrderRepository.findById(base.orderId()).orElseThrow();
          order.setType(JobOrderType.ITEM);
          order.getMaterials().clear();
          List<UUID> itemIds = new ArrayList<>();
          List<UUID> blueprintIds = new ArrayList<>();
          for (int index = 0; index < 2; index++) {
            GameItem item = new GameItem();
            item.setName("Completion output " + UUID.randomUUID());
            item = gameItems.saveAndFlush(item);
            itemIds.add(item.getId());
            Blueprint blueprint = new Blueprint();
            blueprint.setOutputItem(item);
            blueprint.setOutputName(item.getName());
            blueprint = blueprints.saveAndFlush(blueprint);
            blueprintIds.add(blueprint.getId());
            order
                .getItems()
                .add(
                    JobOrderItem.builder()
                        .jobOrder(order)
                        .gameItem(item)
                        .blueprint(blueprint)
                        .amount(1)
                        .manufacturedAmount(1)
                        .build());
          }
          jobOrderRepository.saveAndFlush(order);
          var lines = order.getItems().stream().map(JobOrderItem::getId).sorted().toList();
          return new RaceFixture(
              base,
              actor.getId(),
              order.getResponsibleOrgUnit().getId(),
              lines.getFirst(),
              lines.getLast(),
              List.copyOf(itemIds),
              List.copyOf(blueprintIds));
        }));
  }

  private void cleanup(RaceFixture fixture) {
    transactionTemplate.executeWithoutResult(_ -> {
      UUID order = fixture.base().orderId();
      jdbc.update("DELETE FROM notification WHERE entity_id = ?", order);
      jdbc.update("DELETE FROM audit_event WHERE subject_id = ?", order);
      jdbc.update("DELETE FROM job_order_item_handover WHERE job_order_id = ?", order);
      jdbc.update("DELETE FROM job_order WHERE id = ?", order);
      blueprints.deleteAllById(fixture.blueprints());
      blueprints.flush();
      gameItems.deleteAllById(fixture.items());
      materialRepository.deleteById(fixture.base().materialId());
      users.deleteById(fixture.actor());
      orgUnitRepository.deleteAllById(
          List.of(
              fixture.responsible(), fixture.base().squadronAId(), fixture.base().squadronBId()));
    });
  }

  /**
   * The independently persisted order, two completed production lines and their synthetic actor.
   */
  private record RaceFixture(
      Fixture base,
      UUID actor,
      UUID responsible,
      UUID firstLine,
      UUID secondLine,
      List<UUID> items,
      List<UUID> blueprints) {}
}

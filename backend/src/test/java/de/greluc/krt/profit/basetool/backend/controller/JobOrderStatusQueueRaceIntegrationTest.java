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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTierRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyFixtures;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SpecialCommand;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Observes independent public terminal writes under database-controlled queue contention. */
@SpringBootTest
class JobOrderStatusQueueRaceIntegrationTest {
  private static final String ORDERS = "/api/v1/orders";

  @Autowired private WebApplicationContext context;
  @Autowired private TransactionTemplate transactions;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper json;
  @Autowired private UserRepository users;
  @Autowired private RoleRepository roles;
  @Autowired private OrgUnitRepository units;
  @Autowired private MaterialRepository materials;
  @Autowired private QualityTierRepository tiers;
  @Autowired private JobOrderRepository orders;
  private MockMvc mvc;

  @BeforeEach
  void publicHttp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  @Timeout(60)
  void independentTerminalWritesSerializeWithoutCyclingOverQueueRows() throws Exception {
    Fixture fixture = Objects.requireNonNull(transactions.execute(_ -> seed()));
    String gate = "status_gate_" + UUID.randomUUID().toString().replace("-", "");
    String firstTable = gate + "_a";
    String secondTable = gate + "_b";
    try {
      mvc.perform(
              authenticated(
                  put(ORDERS + "/{id}/priority", fixture.second())
                      .param("priority", Integer.toString(Integer.MAX_VALUE)),
                  fixture))
          .andExpect(status().isOk());
      mvc.perform(
              authenticated(
                  put(ORDERS + "/{id}/priority", fixture.second()).param("priority", "1"), fixture))
          .andExpect(status().isOk());
      mvc.perform(
              authenticated(
                  put(ORDERS + "/{id}/priority", fixture.first())
                      .param("priority", Integer.toString(Integer.MAX_VALUE)),
                  fixture))
          .andExpect(status().isOk());
      long firstVersion = readOrder(fixture, fixture.first()).path("version").longValue();
      long secondVersion = readOrder(fixture, fixture.second()).path("version").longValue();
      createGates(gate, firstTable, secondTable, fixture);
      AtomicInteger firstPid = new AtomicInteger();
      AtomicInteger secondPid = new AtomicInteger();
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
        var first =
            workers.submit(() -> complete(fixture, fixture.first(), firstVersion, firstPid));
        await()
            .atMost(Duration.ofSeconds(10))
            .until(() -> blockedByEither(firstPid.get(), controlPid, controlPid));
        var second =
            workers.submit(() -> complete(fixture, fixture.second(), secondVersion, secondPid));
        await()
            .atMost(Duration.ofSeconds(10))
            .until(() -> blockedByEither(secondPid.get(), controlPid, firstPid.get()));
        control.commit();
        assertThat(first.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        assertThat(second.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
      }
      assertThat(readOrder(fixture, fixture.first()).path("status").asText())
          .isEqualTo("COMPLETED");
      assertThat(readOrder(fixture, fixture.second()).path("status").asText())
          .isEqualTo("COMPLETED");
    } finally {
      try {
        jdbc.execute("DROP TRIGGER IF EXISTS " + gate + " ON job_order");
        jdbc.execute("DROP FUNCTION IF EXISTS " + gate + "()");
        jdbc.execute("DROP TABLE IF EXISTS " + firstTable + ", " + secondTable);
      } finally {
        transactions.executeWithoutResult(_ -> {
          jdbc.update(
              "DELETE FROM notification WHERE entity_id IN (?, ?)",
              fixture.first(),
              fixture.second());
          jdbc.update(
              "DELETE FROM audit_event WHERE subject_id IN (?, ?)",
              fixture.first(),
              fixture.second());
          orders.deleteAllById(List.of(fixture.first(), fixture.second()));
          orders.flush();
          units.deleteById(fixture.unit());
          units.flush();
          materials.deleteById(fixture.material());
          users.deleteById(fixture.actor());
        });
      }
    }
  }

  private void createGates(
      @NotNull String gate,
      @NotNull String firstTable,
      @NotNull String secondTable,
      @NotNull Fixture fixture) {
    jdbc.execute("CREATE TABLE " + firstTable + " (id integer)");
    jdbc.execute("CREATE TABLE " + secondTable + " (id integer)");
    jdbc.execute(
        """
        CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $gate$
        BEGIN
          IF NEW.status = 'COMPLETED' AND OLD.status IS DISTINCT FROM NEW.status THEN
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
            .formatted(gate, fixture.first(), firstTable, fixture.second(), secondTable));
    jdbc.execute(
        "CREATE TRIGGER "
            + gate
            + " BEFORE UPDATE ON job_order"
            + " FOR EACH ROW EXECUTE FUNCTION "
            + gate
            + "()");
  }

  private @NotNull MvcResult complete(
      @NotNull Fixture fixture, UUID order, long version, @NotNull AtomicInteger pid) {
    return Objects.requireNonNull(
        transactions.execute(_ -> {
          pid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
          try {
            return mvc.perform(
                    authenticated(put(ORDERS + "/{id}/status", order), fixture)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            json.writeValueAsString(
                                Map.of("status", "COMPLETED", "version", version))))
                .andReturn();
          } catch (Exception failure) {
            throw new IllegalStateException("Public status request failed", failure);
          }
        }));
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

  private @NotNull JsonNode readOrder(@NotNull Fixture fixture, UUID id) throws Exception {
    MvcResult response =
        mvc.perform(authenticated(get(ORDERS + "/{id}", id), fixture))
            .andExpect(status().isOk())
            .andReturn();
    return Objects.requireNonNull(json.readTree(response.getResponse().getContentAsString()));
  }

  private @NotNull MockHttpServletRequestBuilder authenticated(
      @NotNull MockHttpServletRequestBuilder request, @NotNull Fixture fixture) {
    return request.with(
        jwt()
            .jwt(token -> token.subject(fixture.actor().toString()))
            .authorities(
                new SimpleGrantedAuthority("ROLE_ADMIN"),
                new SimpleGrantedAuthority("ROLE_KRT_MEMBER")));
  }

  private @NotNull Fixture seed() {
    String tag = UUID.randomUUID().toString();
    User actor = new User();
    actor.setId(UUID.randomUUID());
    actor.setUsername("status-race-" + tag);
    actor.setApprovalStatus(ApprovalStatus.ACTIVE);
    actor.setInKeycloak(true);
    actor.setRoles(
        new HashSet<>(
            Set.of(
                roles.findByCode(Roles.ADMIN).orElseThrow(),
                roles.findByCode(Roles.KRT_MEMBER).orElseThrow())));
    actor = users.saveAndFlush(actor);
    SpecialCommand unit = new SpecialCommand();
    unit.setName("Status race " + tag);
    unit.setShorthand("SQ" + tag);
    unit.setParent(ProfitHierarchyFixtures.persistedProfitArea(units));
    unit = units.saveAndFlush(unit);
    Material material = new Material();
    material.setName("Status race material " + tag);
    material.setType(MaterialType.RAW);
    material = materials.saveAndFlush(material);
    JobOrder first = order(unit, material, 2);
    JobOrder second = order(unit, material, 1);
    return new Fixture(
        first.getId(), second.getId(), actor.getId(), unit.getId(), material.getId());
  }

  private @NotNull JobOrder order(
      @NotNull SpecialCommand unit, @NotNull Material material, int priority) {
    JobOrder order =
        JobOrder.builder()
            .responsibleOrgUnit(unit)
            .requestingOrgUnit(unit)
            .handle("Queue race")
            .priority(priority)
            .build();
    order.addMaterial(
        JobOrderMaterial.builder()
            .material(material)
            .qualityTier(tiers.findByCode("NONE").orElseThrow())
            .amount(10.0)
            .build());
    return orders.saveAndFlush(order);
  }

  /** Synthetic identities owned by this queue-contention scenario. */
  private record Fixture(UUID first, UUID second, UUID actor, UUID unit, UUID material) {}
}

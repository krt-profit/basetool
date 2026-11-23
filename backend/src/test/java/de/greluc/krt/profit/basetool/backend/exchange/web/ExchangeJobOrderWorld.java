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

package de.greluc.krt.profit.basetool.backend.exchange.web;

import static de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyFixtures.persistedProfitArea;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import de.greluc.krt.profit.basetool.backend.exchange.api.ExchangeMetricNames;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeCapability;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClient;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClientRepository;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClientStatus;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeSettings;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeSettingsRepository;
import de.greluc.krt.profit.basetool.backend.exchange.internal.KnownExchangeClients;
import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.Role;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.ActingMemberHeader;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A committed world for the job-order writes on an existing order through the relay: a registered
 * client with both job-order capabilities, both job-order switches on, a Profit unit and a
 * requesting unit with a direct Logistician, relayed requests through the real filter chain, and
 * the probes the route tests share. {@link #close()} removes everything it made, the orders of the
 * requesting unit included, and restores the switches.
 */
final class ExchangeJobOrderWorld {

  /** The job-order routes on the backend. */
  static final String PATH = "/api/v1/exchange/job-orders";

  /** The job-order write capability. */
  static final String WRITE = "exchange.joborders.write";

  /** The job-order read capability. */
  static final String READ = "exchange.joborders.read";

  private static final String GATEWAY = "55555555-5555-5555-5555-555555555555";
  private static final String INSTALLATION = "Kx9_" + "w".repeat(39);

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;
  private final UserRepository userRepository;
  private final RoleRepository roleRepository;
  private final ExchangeClientRepository clientRepository;
  private final KnownExchangeClients knownClients;
  private final ExchangeSettingsRepository settingsRepository;
  private final OrgUnitRepository orgUnitRepository;
  private final List<UUID> orgUnits = new ArrayList<>();
  private final List<UUID> users = new ArrayList<>();

  /** The relay's view of the backend, through the real filter chain. */
  @Getter private final MockMvc mockMvc;

  /** The registry client, an id of the longest length the registry admits, 63 characters. */
  @Getter private final String client;

  /** The Profit area. */
  @Getter private final UUID profit;

  /** An eligible Profit unit below the area, the responsible unit of the world's orders. */
  @Getter private final UUID responsible;

  /** A unit outside the Profit hierarchy, the requesting unit of the world's orders. */
  @Getter private final UUID requesting;

  /** A direct Logistician of the requesting unit by the Logistician flag. */
  @Getter private final UUID logistician;

  private final boolean wasEnabled;
  private final boolean wasJobOrdersEnabled;
  private final boolean wasJobOrderWritesEnabled;

  /**
   * Seeds the world and switches the exchange and both job-order switches on.
   *
   * @param context the application context the beans come from
   */
  ExchangeJobOrderWorld(@NotNull WebApplicationContext context) {
    jdbc = context.getBean(JdbcTemplate.class);
    objectMapper = context.getBean(ObjectMapper.class);
    meterRegistry = context.getBean(MeterRegistry.class);
    userRepository = context.getBean(UserRepository.class);
    roleRepository = context.getBean(RoleRepository.class);
    clientRepository = context.getBean(ExchangeClientRepository.class);
    knownClients = context.getBean(KnownExchangeClients.class);
    settingsRepository = context.getBean(ExchangeSettingsRepository.class);
    orgUnitRepository = context.getBean(OrgUnitRepository.class);
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy.class))
            .build();
    profit = persistedProfitArea(orgUnitRepository).getId();
    responsible = squadron("Write responsible", profit);
    requesting = squadron("Write requesting", null);
    logistician = user("write-logistician", false);
    seat(logistician, requesting, "MEMBER", true);
    client = "jw-" + UUID.randomUUID().toString().substring(0, 8) + "-" + "w".repeat(51);
    ExchangeClient registered = new ExchangeClient();
    registered.setClientId(client);
    registered.setDisplayName("VerseKit");
    registered.setStatus(ExchangeClientStatus.ACTIVE);
    registered.setCapabilities(
        EnumSet.of(
            ExchangeCapability.CONNECT,
            ExchangeCapability.JOB_ORDERS_READ,
            ExchangeCapability.JOB_ORDERS_WRITE));
    clientRepository.saveAndFlush(registered);
    knownClients.invalidate();
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    wasEnabled = settings.isEnabled();
    wasJobOrdersEnabled = settings.isJobOrdersEnabled();
    wasJobOrderWritesEnabled = settings.isJobOrderWritesEnabled();
    switches(true, true, true);
  }

  /** Removes the orders of the requesting unit with their rows, the client, members and units. */
  void close() {
    List<UUID> orders =
        jdbc.queryForList(
            "SELECT id FROM job_order WHERE created_by_client_id = ? OR requesting_org_unit_id = ?",
            UUID.class,
            client,
            requesting);
    orders.forEach(id -> {
      jdbc.update("DELETE FROM job_order_money_operation WHERE order_id = ?", id);
      jdbc.update("DELETE FROM job_order_money_holder WHERE order_id = ?", id);
      jdbc.update("DELETE FROM notification WHERE entity_id = ?", id);
      jdbc.update("DELETE FROM audit_event WHERE subject_id = ?", id);
      jdbc.update("DELETE FROM job_order WHERE id = ?", id);
    });
    jdbc.update("DELETE FROM exchange_client WHERE client_id = ?", client);
    users.forEach(id -> {
      jdbc.update("DELETE FROM notification WHERE recipient_user_id = ?", id);
      jdbc.update("DELETE FROM org_unit_membership WHERE user_id = ?", id);
      jdbc.update("DELETE FROM user_roles WHERE user_id = ?", id);
      jdbc.update("DELETE FROM app_user WHERE id = ?", id);
    });
    orgUnits.reversed().forEach(id -> jdbc.update("DELETE FROM org_unit WHERE id = ?", id));
    switches(wasEnabled, wasJobOrdersEnabled, wasJobOrderWritesEnabled);
  }

  /**
   * Sets the global exchange switch and both job-order switches.
   *
   * @param enabled the global switch
   * @param jobOrders the job-order family switch
   * @param writes the job-order writes switch
   */
  void switches(boolean enabled, boolean jobOrders, boolean writes) {
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    settings.setEnabled(enabled);
    settings.setJobOrdersEnabled(jobOrders);
    settings.setJobOrderWritesEnabled(writes);
    settingsRepository.saveAndFlush(settings);
  }

  /**
   * Creates an aUEC order through the client's create route, between the world's units.
   *
   * @param member the creating member, a writer of the requesting unit
   * @param target the target in whole aUEC
   * @return the new order's id
   * @throws Exception if the request fails
   */
  @NotNull
  UUID createAuec(@NotNull UUID member, long target) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("requestingUnitId", requesting);
    body.put("responsibleUnitId", responsible);
    body.put("contact", "Write contact");
    body.put("target", target);
    MockHttpServletResponse response =
        mockMvc
            .perform(
                relayed(post(PATH), member, WRITE)
                    .header(ActingMemberHeader.EXCHANGE_IDEMPOTENCY_KEY_HEADER, key())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
    return UUID.fromString(
        objectMapper.readTree(response.getContentAsString()).get("id").asString());
  }

  /**
   * Inserts an open order of a stock kind between the world's units.
   *
   * @param type {@code MATERIAL} or {@code ITEM}
   * @return the new order's id
   */
  @NotNull
  UUID stockOrder(@NotNull String type) {
    UUID order = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO job_order (id, priority, created_at, updated_at, version, status,"
            + " requesting_org_unit_id, responsible_org_unit_id, type)"
            + " VALUES (?, 2, now(), now(), 0, 'OPEN', ?, ?, ?)",
        order,
        requesting,
        responsible,
        type);
    return order;
  }

  /**
   * Adds the relay's identity and headers to a request to the backend.
   *
   * @param request the request
   * @param member the acting member
   * @param capabilities the relayed capabilities, comma-separated
   * @return the relayed request
   */
  @NotNull
  MockHttpServletRequestBuilder relayed(
      @NotNull MockHttpServletRequestBuilder request,
      @NotNull UUID member,
      @NotNull String capabilities) {
    return request
        .with(jwt().jwt(t -> t.subject(GATEWAY).claim("azp", "test-ingest-gateway")))
        .header(ActingMemberHeader.ON_BEHALF_OF_HEADER, member.toString())
        .header(ActingMemberHeader.EXCHANGE_CLIENT_HEADER, client)
        .header(ActingMemberHeader.EXCHANGE_CAPABILITIES_HEADER, capabilities)
        .header(ActingMemberHeader.EXCHANGE_INSTALLATION_HEADER, INSTALLATION);
  }

  /**
   * Creates an active account holding {@code KRT Member}.
   *
   * @param username the name's prefix
   * @param admin whether the account also holds the administrator role
   * @return the account's id
   */
  @NotNull
  UUID user(@NotNull String username, boolean admin) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username + "-" + UUID.randomUUID());
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    Set<Role> roles = new HashSet<>();
    roles.add(roleRepository.findByCode(Roles.KRT_MEMBER).orElseThrow());
    if (admin) {
      roles.add(roleRepository.findByCode(Roles.ADMIN).orElseThrow());
    }
    user.setRoles(roles);
    UUID id = userRepository.saveAndFlush(user).getId();
    users.add(id);
    return id;
  }

  /**
   * Seats a member in a unit.
   *
   * @param member the member
   * @param unit the unit
   * @param role the membership role
   * @param logisticianFlag the Logistician flag
   */
  void seat(
      @NotNull UUID member, @NotNull UUID unit, @NotNull String role, boolean logisticianFlag) {
    jdbc.update(
        "INSERT INTO org_unit_membership (user_id, org_unit_id, role, is_logistician)"
            + " VALUES (?, ?, ?, ?)",
        member,
        unit,
        role,
        logisticianFlag);
  }

  /**
   * Creates an active squadron.
   *
   * @param name the name's prefix
   * @param parent the parent unit, or {@code null} for none
   * @return the unit's id
   */
  @NotNull
  UUID squadron(@NotNull String name, @Nullable UUID parent) {
    return orgUnit("SQUADRON", name, parent);
  }

  /**
   * Creates an active unit.
   *
   * @param kind the unit kind
   * @param name the name's prefix
   * @param parent the parent unit, or {@code null} for none
   * @return the unit's id
   */
  @NotNull
  UUID orgUnit(@NotNull String kind, @NotNull String name, @Nullable UUID parent) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO org_unit (id, kind, name, shorthand, active, parent_org_unit_id)"
            + " VALUES (?, ?, ?, ?, TRUE, ?)",
        id,
        kind,
        name + " " + id,
        "W" + id.toString().substring(0, 7),
        parent);
    orgUnits.add(id);
    return id;
  }

  /**
   * Returns a fresh idempotency key.
   *
   * @return a key of the gateway's shape
   */
  static @NotNull String key() {
    return "write-" + UUID.randomUUID();
  }

  /**
   * Reads an order's stored lifecycle status.
   *
   * @param order the order
   * @return its status
   */
  @NotNull
  String status(@NotNull UUID order) {
    return jdbc.queryForObject("SELECT status FROM job_order WHERE id = ?", String.class, order);
  }

  /**
   * Moves an order to a terminal status in the database, leaving the queue and moving its lifecycle
   * version on, as a closing in the Basetool does.
   *
   * @param order the order
   * @param status the terminal status
   */
  void closeOrder(@NotNull UUID order, @NotNull String status) {
    jdbc.update(
        "UPDATE job_order SET status = ?, priority = NULL, closed_at = now(),"
            + " lifecycle_version = lifecycle_version + 1 WHERE id = ?",
        status,
        order);
  }

  /** Moves the responsible unit out of the Profit hierarchy, which invalidates its orders. */
  void leaveTheProfitHierarchy() {
    jdbc.update("UPDATE org_unit SET parent_org_unit_id = NULL WHERE id = ?", responsible);
  }

  /**
   * Asserts that a relayed write was refused with the status and the code.
   *
   * @param response the backend's answer
   * @param status the expected status
   * @param code the expected problem code
   * @return the problem
   * @throws Exception if the answer cannot be read
   */
  @NotNull
  JsonNode assertRefused(
      @NotNull MockHttpServletResponse response, int status, @NotNull String code)
      throws Exception {
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
    JsonNode problem = objectMapper.readTree(response.getContentAsString());
    assertThat(problem.get("code").asString()).isEqualTo(code);
    return problem;
  }

  /**
   * The counter of the world's client's job-order writes under an outcome.
   *
   * @param outcome {@code applied} or {@code rejected}
   * @return the counter
   */
  @NotNull
  Counter writes(@NotNull String outcome) {
    return meterRegistry.counter(
        ExchangeMetricNames.EXCHANGE_WRITES,
        MetricNames.TAG_CLIENT_ID,
        client,
        ExchangeMetricNames.TAG_RESOURCE,
        "job_order",
        MetricNames.TAG_OUTCOME,
        outcome);
  }

  /**
   * The counter of the backend's job-order refusals under a reason.
   *
   * @param reason the refusal's reason label
   * @return the counter
   */
  @NotNull
  Counter refusals(@NotNull String reason) {
    return meterRegistry.counter(
        ExchangeMetricNames.EXCHANGE_JOB_ORDER_REFUSED, MetricNames.TAG_REASON, reason);
  }

  /**
   * Waits for a latch a racing test thread releases, failing after 30 seconds.
   *
   * @param latch the latch
   */
  static void awaitQuietly(@NotNull CountDownLatch latch) {
    try {
      assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}

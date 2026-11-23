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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTierRepository;
import de.greluc.krt.profit.basetool.backend.exchange.ExchangePublishedSchemas;
import de.greluc.krt.profit.basetool.backend.exchange.api.ExchangeMetricNames;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeCapability;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClient;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClientRepository;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClientStatus;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeSettings;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeSettingsRepository;
import de.greluc.krt.profit.basetool.backend.exchange.internal.JobOrderFingerprintRecomputation;
import de.greluc.krt.profit.basetool.backend.exchange.internal.JobOrderFingerprintRuns;
import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.ActingMemberHeader;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The change feed of the member's job-order set under {@code exchange.joborders.read}, with the
 * fingerprint worker run as the scheduled task would (REQ-OXCH-003, REQ-XCH-013): a key of the set
 * is an item, any other key a tombstone {@code { key, removedAt }}, whether the relation was lost,
 * the membership ended or the order was deleted.
 */
@SpringBootTest
class ExchangeJobOrderFeedTest {

  private static final String PATH = "/api/v1/exchange/job-orders";
  private static final String GATEWAY = "55555555-5555-5555-5555-555555555555";
  private static final String KEY = "Kx9_" + "f".repeat(39);
  private static final String READ = "exchange.joborders.read";
  private static final String PAGE_SCHEMA = "page.schema.json#/$defs/jobOrderPage";

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private ExchangeClientRepository clientRepository;
  @Autowired private ExchangeSettingsRepository settingsRepository;
  @Autowired private JobOrderRepository jobOrderRepository;
  @Autowired private OrgUnitRepository orgUnitRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private QualityTierRepository qualityTierRepository;
  @Autowired private JobOrderFingerprintRecomputation recomputation;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private MeterRegistry meterRegistry;

  private final List<UUID> orders = new ArrayList<>();
  private final List<UUID> orgUnits = new ArrayList<>();
  private final List<UUID> users = new ArrayList<>();

  private MockMvc mockMvc;
  private UUID member;
  private UUID own;
  private UUID other;
  private UUID foreign;
  private UUID material;
  private String client;
  private boolean wasEnabled;
  private boolean wasJobOrdersEnabled;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy.class))
            .build();
    member = user("feed-member");
    own = orgUnit("Own");
    other = orgUnit("Other");
    foreign = orgUnit("Foreign");
    jdbc.update(
        "INSERT INTO org_unit_membership (user_id, org_unit_id, role, is_logistician)"
            + " VALUES (?, ?, 'MEMBER', false)",
        member,
        own);
    material = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO material (id, name, type, quantity_type, is_manual_raw_material,"
            + " is_job_order, is_visible, source_systems)"
            + " VALUES (?, ?, 'REFINED', 'SCU', false, false, true, 'UEX_ONLY')",
        material,
        "Feed material " + material);
    client = "vk-" + UUID.randomUUID().toString().substring(0, 8);
    ExchangeClient registered = new ExchangeClient();
    registered.setClientId(client);
    registered.setDisplayName("VerseKit");
    registered.setStatus(ExchangeClientStatus.ACTIVE);
    registered.setCapabilities(
        EnumSet.of(ExchangeCapability.CONNECT, ExchangeCapability.JOB_ORDERS_READ));
    clientRepository.saveAndFlush(registered);
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    wasEnabled = settings.isEnabled();
    wasJobOrdersEnabled = settings.isJobOrdersEnabled();
    settings.setEnabled(true);
    settings.setJobOrdersEnabled(true);
    settingsRepository.saveAndFlush(settings);
  }

  @AfterEach
  void tearDown() {
    orders.forEach(id -> {
      jdbc.update("DELETE FROM job_order_material WHERE job_order_id = ?", id);
      jdbc.update("DELETE FROM job_order WHERE id = ?", id);
    });
    jdbc.update("DELETE FROM exchange_client WHERE client_id = ?", client);
    users.forEach(id -> {
      jdbc.update("DELETE FROM org_unit_membership WHERE user_id = ?", id);
      jdbc.update("DELETE FROM user_roles WHERE user_id = ?", id);
      jdbc.update("DELETE FROM app_user WHERE id = ?", id);
    });
    orgUnits.forEach(id -> jdbc.update("DELETE FROM org_unit WHERE id = ?", id));
    jdbc.update("DELETE FROM material WHERE id = ?", material);
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    settings.setEnabled(wasEnabled);
    settings.setJobOrdersEnabled(wasJobOrdersEnabled);
    settingsRepository.saveAndFlush(settings);
  }

  @Test
  void aChangedOrderReachesTheFeedAsAnItemAndThePageCountsOneFeedRead() throws Exception {
    UUID order = order(other, own);
    drain(order);
    String cursor = snapshotCursor();
    jdbc.update("UPDATE job_order SET comment = 'Now to Area18' WHERE id = ?", order);
    drain(order);
    Counter feedReads =
        meterRegistry.counter(
            ExchangeMetricNames.EXCHANGE_JOB_ORDER_READS,
            MetricNames.TAG_CLIENT_ID,
            client,
            ExchangeMetricNames.TAG_ROUTE,
            "feed");
    double before = feedReads.count();

    JsonNode page = feed(cursor);

    assertThat(ids(page.get("items"))).containsExactly(order.toString());
    assertThat(page.get("items").get(0).get("comment").asString()).isEqualTo("Now to Area18");
    assertThat(page.get("removed")).isEmpty();
    assertThat(page.get("hasMore").asBoolean()).isFalse();
    assertThat(page.get("nextCursor").asString()).startsWith("f1.").isNotEqualTo(cursor);
    assertThat(feedReads.count()).isEqualTo(before + 1);
    assertThat(feed(page.get("nextCursor").asString()).get("items")).isEmpty();
  }

  @Test
  void aLostRelationAndAHardDeleteLookAlike() throws Exception {
    UUID moved = order(other, own);
    UUID deleted = order(other, own);
    drain(moved, deleted);
    String cursor = snapshotCursor();
    Instant start =
        Objects.requireNonNull(jdbc.queryForObject("SELECT now()", Timestamp.class)).toInstant();
    jdbc.update("UPDATE job_order SET requesting_org_unit_id = ? WHERE id = ?", foreign, moved);
    drain(moved);
    jdbc.update("DELETE FROM job_order_material WHERE job_order_id = ?", deleted);
    jdbc.update("DELETE FROM job_order WHERE id = ?", deleted);

    JsonNode page = feed(cursor);

    assertThat(page.get("items")).isEmpty();
    assertThat(keys(page.get("removed")))
        .containsExactlyInAnyOrder(moved.toString(), deleted.toString());
    for (JsonNode removed : page.get("removed")) {
      assertThat(removed.propertyNames()).containsExactlyInAnyOrder("key", "removedAt");
      assertThat(Instant.parse(removed.get("removedAt").asString())).isAfterOrEqualTo(start);
    }
  }

  @Test
  void aMembershipEndResolvesToGoneAndTheUnitsLaterEntriesStayUnread() throws Exception {
    UUID order = order(other, own);
    drain(order);
    String cursor = snapshotCursor();

    jdbc.update(
        "DELETE FROM org_unit_membership WHERE user_id = ? AND org_unit_id = ?", member, own);
    JsonNode left = feed(cursor);

    assertThat(left.get("items")).isEmpty();
    assertThat(keys(left.get("removed"))).containsExactly(order.toString());

    jdbc.update("UPDATE job_order SET comment = 'after the member left' WHERE id = ?", order);
    drain(order);
    JsonNode later = feed(left.get("nextCursor").asString());

    assertThat(later.get("items")).isEmpty();
    assertThat(later.get("removed")).isEmpty();
  }

  @Test
  void anOrderThatLeftTheUnitBeforeTheMemberDidIsGoneThoughTheClientSyncsOnlyAfterwards()
      throws Exception {
    UUID order = order(other, own);
    drain(order);
    String cursor = snapshotCursor();
    jdbc.update("UPDATE job_order SET requesting_org_unit_id = ? WHERE id = ?", foreign, order);
    drain(order);

    jdbc.update(
        "DELETE FROM org_unit_membership WHERE user_id = ? AND org_unit_id = ?", member, own);
    JsonNode page = feed(cursor);

    assertThat(page.get("items")).isEmpty();
    assertThat(keys(page.get("removed"))).containsExactly(order.toString());
  }

  @Test
  void aReopenedOrderReturnsAsAnItem() throws Exception {
    UUID order = order(other, own);
    jdbc.update(
        "UPDATE job_order SET status = 'CANCELLED', priority = NULL,"
            + " closed_at = now() - INTERVAL '30 days' WHERE id = ?",
        order);
    drain(order);
    String cursor = snapshotCursor();
    assertThat(snapshotIds()).doesNotContain(order.toString());

    jdbc.update(
        "UPDATE job_order SET status = 'OPEN', priority = 1, closed_at = NULL,"
            + " lifecycle_version = lifecycle_version + 1 WHERE id = ?",
        order);
    drain(order);
    JsonNode page = feed(cursor);

    assertThat(ids(page.get("items"))).containsExactly(order.toString());
    assertThat(page.get("items").get(0).get("status").asString()).isEqualTo("OPEN");
  }

  @Test
  void aClientsOwnWriteReturnsAsAnItem() throws Exception {
    UUID order = order(other, own);
    drain(order);
    String cursor = snapshotCursor();

    transactionTemplate.executeWithoutResult(_ -> {
      jdbc.queryForObject(
          "SELECT set_config('basetool.change_source', ?, true)",
          String.class,
          "client|" + client + "|" + KEY);
      jdbc.update("UPDATE job_order SET comment = 'written by the client' WHERE id = ?", order);
    });
    drain(order);
    JsonNode page = feed(cursor);

    assertThat(ids(page.get("items"))).containsExactly(order.toString());
    assertThat(page.get("items").get(0).get("comment").asString())
        .isEqualTo("written by the client");
  }

  @Test
  void aReaderNeverPassesAnAnnouncementThatCommitsLate() throws Exception {
    UUID order = order(other, own);
    drain(order);
    String cursor = snapshotCursor();

    try (Connection racing = dataSource.getConnection()) {
      racing.setAutoCommit(false);
      try (PreparedStatement worker =
          racing.prepareStatement(
              "UPDATE exchange_job_order_fingerprint SET fingerprint = ?, changed_at = now()"
                  + " WHERE job_order_id = ?")) {
        worker.setBytes(1, new byte[32]);
        worker.setObject(2, order);
        worker.executeUpdate();
      }
      JsonNode whileRunning = feed(cursor);

      assertThat(whileRunning.get("items")).isEmpty();
      racing.commit();
      JsonNode afterCommit = feed(whileRunning.get("nextCursor").asString());

      assertThat(ids(afterCommit.get("items"))).containsExactly(order.toString());
    }
  }

  @Test
  void theFamilySwitchClosesTheFeed() throws Exception {
    String cursor = snapshotCursor();
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    settings.setJobOrdersEnabled(false);
    settingsRepository.saveAndFlush(settings);

    mockMvc
        .perform(relayed(get(PATH).param("cursor", cursor)))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("JOB_ORDER_EXCHANGE_DISABLED"));
  }

  private void drain(UUID... fixtureOrders) {
    JobOrderFingerprintRuns.drain(jdbc, recomputation, fixtureOrders);
  }

  private String snapshotCursor() throws Exception {
    String cursor = null;
    boolean more = true;
    while (more) {
      MockHttpServletRequestBuilder request = get(PATH);
      if (cursor != null) {
        request.param("cursor", cursor);
      }
      JsonNode page = page(request);
      more = page.get("hasMore").asBoolean();
      cursor = page.get("nextCursor").asString();
    }
    return Objects.requireNonNull(cursor);
  }

  private List<String> snapshotIds() throws Exception {
    return ids(page(get(PATH)).get("items"));
  }

  private JsonNode feed(String cursor) throws Exception {
    return page(get(PATH).param("cursor", cursor));
  }

  private JsonNode page(MockHttpServletRequestBuilder request) throws Exception {
    String body =
        mockMvc
            .perform(relayed(request))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    ExchangePublishedSchemas.assertMatches(PAGE_SCHEMA, body, "a job-order page");
    return objectMapper.readTree(body);
  }

  private static List<String> ids(JsonNode items) {
    List<String> ids = new ArrayList<>();
    items.forEach(item -> ids.add(item.get("id").asString()));
    return ids;
  }

  private static List<String> keys(JsonNode removed) {
    List<String> keys = new ArrayList<>();
    removed.forEach(entry -> keys.add(entry.get("key").asString()));
    return keys;
  }

  private @NotNull MockHttpServletRequestBuilder relayed(
      @NotNull MockHttpServletRequestBuilder request) {
    return request
        .with(jwt().jwt(t -> t.subject(GATEWAY).claim("azp", "test-ingest-gateway")))
        .header(ActingMemberHeader.ON_BEHALF_OF_HEADER, member.toString())
        .header(ActingMemberHeader.EXCHANGE_CLIENT_HEADER, client)
        .header(ActingMemberHeader.EXCHANGE_CAPABILITIES_HEADER, READ)
        .header(ActingMemberHeader.EXCHANGE_INSTALLATION_HEADER, KEY);
  }

  private @NotNull UUID user(@NotNull String username) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username + "-" + UUID.randomUUID());
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    user.setRoles(new HashSet<>(Set.of(roleRepository.findByCode(Roles.KRT_MEMBER).orElseThrow())));
    UUID id = userRepository.saveAndFlush(user).getId();
    users.add(id);
    return id;
  }

  private @NotNull UUID orgUnit(@NotNull String name) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO org_unit (id, kind, name, shorthand, active, parent_org_unit_id)"
            + " VALUES (?, 'SQUADRON', ?, ?, TRUE, ?)",
        id,
        name + " " + id,
        "U" + id.toString().substring(0, 6),
        persistedProfitArea(orgUnitRepository).getId());
    orgUnits.add(id);
    return id;
  }

  private @NotNull UUID order(@NotNull UUID responsible, @NotNull UUID requesting) {
    UUID id =
        transactionTemplate.execute(_ -> {
          JobOrder order =
              JobOrder.builder()
                  .responsibleOrgUnit(orgUnitRepository.getReferenceById(responsible))
                  .requestingOrgUnit(orgUnitRepository.getReferenceById(requesting))
                  .handle("feed-requester")
                  .priority(1)
                  .status(JobOrderStatus.OPEN)
                  .build();
          order.addMaterial(
              JobOrderMaterial.builder()
                  .material(materialRepository.getReferenceById(material))
                  .qualityTier(qualityTierRepository.findByCode("NONE").orElseThrow())
                  .amount(4.0)
                  .build());
          return jobOrderRepository.save(order).getId();
        });
    orders.add(Objects.requireNonNull(id));
    return id;
  }
}

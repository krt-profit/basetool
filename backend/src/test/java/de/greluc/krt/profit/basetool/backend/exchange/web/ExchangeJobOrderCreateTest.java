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
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import de.greluc.krt.profit.basetool.backend.exchange.ExchangePublishedSchemas;
import de.greluc.krt.profit.basetool.backend.exchange.api.ExchangeMetricNames;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeCapability;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClient;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClientRepository;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClientStatus;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeSettings;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeSettingsRepository;
import de.greluc.krt.profit.basetool.backend.exchange.internal.JobOrderFingerprintRecomputation;
import de.greluc.krt.profit.basetool.backend.exchange.internal.KnownExchangeClients;
import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.Role;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import de.greluc.krt.profit.basetool.backend.livesync.api.LivesyncMetricNames;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.ActingMemberHeader;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import de.greluc.krt.profit.basetool.backend.platform.api.OrgUnitContextualAuthority;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The create options and the create of aUEC orders under {@code exchange.joborders.write}: the pair
 * a member may create for, the one refusal of any other pair, the stored idempotency key, the audit
 * type of a client's create, its notice and queue frame, the order landing in the member's read
 * set, and the open-order and daily create caps with the limits in the create options
 * (REQ-OXCH-001, REQ-OXCH-004, REQ-OXCH-005, REQ-OXCH-009).
 */
@SpringBootTest
class ExchangeJobOrderCreateTest {

  private static final String PATH = "/api/v1/exchange/job-orders";
  private static final String OPTIONS = PATH + "/create-options";
  private static final String GATEWAY = "55555555-5555-5555-5555-555555555555";
  private static final String INSTALLATION = "Kx9_" + "w".repeat(39);
  private static final String WRITE = "exchange.joborders.write";
  private static final String READ = "exchange.joborders.read";
  private static final String CREATED_SCHEMA = "job-order-write.schema.json#/$defs/created";
  private static final String OPTIONS_SCHEMA = "job-order-create-options.schema.json";

  /** The default open-order cap of REQ-OXCH-009. */
  private static final int OPEN_ORDER_CAP = 20;

  /** The default daily create cap of REQ-OXCH-009. */
  private static final int DAILY_CREATE_CAP = 20;

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private ExchangeClientRepository clientRepository;
  @Autowired private KnownExchangeClients knownClients;
  @Autowired private ExchangeSettingsRepository settingsRepository;
  @Autowired private OrgUnitRepository orgUnitRepository;
  @Autowired private JobOrderFingerprintRecomputation fingerprints;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private MeterRegistry meterRegistry;

  private final List<UUID> orgUnits = new ArrayList<>();
  private final List<UUID> users = new ArrayList<>();

  private MockMvc mockMvc;
  private UUID profit;
  private UUID responsible;
  private UUID requesting;
  private UUID logistician;

  /** A registry client id of the longest length the registry admits, 63 characters. */
  private String client;

  private boolean wasEnabled;
  private boolean wasJobOrdersEnabled;
  private boolean wasJobOrderWritesEnabled;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy.class))
            .build();
    profit = persistedProfitArea(orgUnitRepository).getId();
    responsible = squadron("Create responsible", profit);
    requesting = squadron("Create requesting", null);
    logistician = user("create-logistician", false);
    seat(logistician, requesting, "MEMBER", true);
    client = "jo-" + UUID.randomUUID().toString().substring(0, 8) + "-" + "c".repeat(51);
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
    settings.setEnabled(true);
    settings.setJobOrdersEnabled(true);
    settings.setJobOrderWritesEnabled(true);
    settingsRepository.saveAndFlush(settings);
  }

  @AfterEach
  void tearDown() {
    List<UUID> created =
        jdbc.queryForList(
            "SELECT id FROM job_order WHERE created_by_client_id = ? OR requesting_org_unit_id = ?",
            UUID.class,
            client,
            requesting);
    created.forEach(id -> {
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
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    settings.setEnabled(wasEnabled);
    settings.setJobOrdersEnabled(wasJobOrdersEnabled);
    settings.setJobOrderWritesEnabled(wasJobOrderWritesEnabled);
    settingsRepository.saveAndFlush(settings);
  }

  @Test
  void aLogisticianOfTheRequestingUnitCreatesAnAuecOrderForAnEligibleProfitUnit() throws Exception {
    MockHttpServletResponse response =
        create(logistician, body(requesting, responsible, 250_000L), key());

    assertThat(response.getStatus()).isEqualTo(201);
    String content = response.getContentAsString();
    ExchangePublishedSchemas.assertMatches(CREATED_SCHEMA, content, "a created order");
    JsonNode created = objectMapper.readTree(content);
    UUID id = UUID.fromString(created.get("id").asString());
    assertThat(created.propertyNames())
        .containsExactlyInAnyOrder("id", "status", "versions", "target");
    assertThat(created.get("status").asString()).isEqualTo("OPEN");
    assertThat(created.get("target").asLong()).isEqualTo(250_000L);
    assertThat(created.get("versions").get("lifecycle").asLong()).isZero();
    assertThat(created.get("versions").get("target").asLong()).isZero();
    assertThat(created.get("versions").get("comment").asLong()).isZero();
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT type, requesting_org_unit_id, responsible_org_unit_id, handle, comment,"
                + " created_by_client_id, created_by_member_id FROM job_order WHERE id = ?",
            id);
    assertThat(row)
        .containsEntry("type", "AUEC")
        .containsEntry("requesting_org_unit_id", requesting)
        .containsEntry("responsible_org_unit_id", responsible)
        .containsEntry("handle", "Create contact")
        .containsEntry("comment", "Deliver to Area18")
        .containsEntry("created_by_client_id", client)
        .containsEntry("created_by_member_id", logistician);
  }

  @Test
  void anUnknownRequestingUnitIsNotOffered() throws Exception {
    assertNotOffered(logistician, body(UUID.randomUUID(), responsible, 1L));
  }

  @Test
  void anUnknownResponsibleUnitIsNotOffered() throws Exception {
    assertNotOffered(logistician, body(requesting, UUID.randomUUID(), 1L));
  }

  @Test
  void aRequestingUnitTheMemberOnlyBelongsToIsNotOffered() throws Exception {
    UUID plain = user("create-plain-member", false);
    seat(plain, requesting, "MEMBER", false);

    assertNotOffered(plain, body(requesting, responsible, 1L));
  }

  @Test
  void aResponsibleUnitOutsideTheEligibleProfitUnitsIsNotOffered() throws Exception {
    UUID outside = squadron("Create outside", null);

    assertNotOffered(logistician, body(requesting, outside, 1L));
  }

  @Test
  void aWriterOfTheResponsibleSideIsNotOfferedAPairForAnotherRequester() throws Exception {
    UUID profitLogistician = user("create-profit-logistician", false);
    seat(profitLogistician, responsible, "MEMBER", true);

    assertNotOffered(profitLogistician, body(requesting, responsible, 1L));
  }

  @Test
  void leadershipReachWithoutMembershipIsNotOffered() throws Exception {
    UUID area = orgUnit("BEREICH", "Create area", null);
    UUID below = squadron("Create below area", area);
    UUID leader = user("create-area-leader", false);
    seat(leader, area, "BEREICHSLEITER", false);

    assertNotOffered(leader, body(below, responsible, 1L));
  }

  @Test
  void anAdministratorIsAPlainMemberThroughAClient() throws Exception {
    UUID admin = user("create-admin", true);

    assertNotOffered(admin, body(requesting, responsible, 1L));
  }

  @Test
  void aRetryUnderTheSameKeyAnswersTheFirstOrderWithItsStoredTermsAfterTheCache() throws Exception {
    String key = key();
    UUID first = createdId(create(logistician, body(requesting, responsible, 250_000L), key));

    MockHttpServletResponse retry =
        create(logistician, body(requesting, responsible, 999_999L), key);

    assertThat(retry.getStatus()).isEqualTo(201);
    JsonNode answer = objectMapper.readTree(retry.getContentAsString());
    assertThat(answer.get("id").asString()).isEqualTo(first.toString());
    assertThat(answer.get("target").asLong()).isEqualTo(250_000L);
    assertThat(createdOrders()).containsExactly(first);
    assertThat(clientAudits()).hasSize(1);
  }

  @Test
  void aRetryAfterALostAnswerCarriesTheOrdersCurrentState() throws Exception {
    String key = key();
    UUID first = createdId(create(logistician, body(requesting, responsible, 250_000L), key));
    close(List.of(first), "CANCELLED");

    MockHttpServletResponse retry =
        create(logistician, body(requesting, responsible, 250_000L), key);

    assertThat(retry.getStatus()).isEqualTo(201);
    JsonNode answer = objectMapper.readTree(retry.getContentAsString());
    assertThat(answer.get("id").asString()).isEqualTo(first.toString());
    assertThat(answer.get("status").asString()).isEqualTo("CANCELLED");
    assertThat(answer.get("versions").get("lifecycle").asLong()).isEqualTo(1L);
    assertThat(createdOrders()).containsExactly(first);
  }

  @Test
  void anotherKeyCreatesAnotherOrder() throws Exception {
    UUID first = createdId(create(logistician, body(requesting, responsible, 1L), key()));
    UUID second = createdId(create(logistician, body(requesting, responsible, 1L), key()));

    assertThat(second).isNotEqualTo(first);
    assertThat(createdOrders()).containsExactlyInAnyOrder(first, second);
  }

  @Test
  void twoRacingCreatesUnderOneKeyInsertOneOrder() throws Exception {
    String key = key();

    Set<UUID> ids = new HashSet<>();
    for (MockHttpServletResponse response : racing(List.of(key, key))) {
      ids.add(createdId(response));
    }

    assertThat(ids).hasSize(1);
    assertThat(createdOrders()).hasSize(1);
    assertThat(clientAudits()).hasSize(1);
  }

  @Test
  void aClientsCreateRecordsItsOwnAuditTypeWithTheClient() throws Exception {
    UUID id = createdId(create(logistician, body(requesting, responsible, 250_000L), key()));

    List<Map<String, Object>> audits =
        jdbc.queryForList(
            "SELECT event_type, client_id, actor_user_id, details FROM audit_event"
                + " WHERE subject_id = ?",
            id);
    assertThat(audits).hasSize(1);
    Map<String, Object> audit = audits.getFirst();
    assertThat(audit)
        .containsEntry("event_type", "JOB_ORDER_CREATED_VIA_CLIENT")
        .containsEntry("client_id", client)
        .containsEntry("actor_user_id", logistician);
    assertThat((String) audit.get("details"))
        .contains("requestingOrgUnit=" + requesting + "(SQUADRON)")
        .contains("responsibleOrgUnit=" + responsible + "(SQUADRON)")
        .contains("target=250000")
        .doesNotContain("Create contact")
        .doesNotContain("Area18");
  }

  @Test
  void aRefusedCreateRecordsNothing() throws Exception {
    assertNotOffered(logistician, body(requesting, squadron("Create refused", null), 1L));

    assertThat(clientAudits()).isEmpty();
  }

  @Test
  void aClientsCreateNotifiesTheUnitWritersButTheMemberAndRefreshesTheQueue() throws Exception {
    UUID colleague = user("create-colleague", false);
    seat(colleague, requesting, "MEMBER", true);
    Counter queueFrames =
        meterRegistry.counter(
            LivesyncMetricNames.LIVESYNC_PUBLISH_ACCEPTED,
            LivesyncMetricNames.TAG_TOPIC_CLASS,
            LiveSyncTopicClass.ORDERS_QUEUE.metricLabel());
    double framesBefore = queueFrames.count();

    UUID id = createdId(create(logistician, body(requesting, responsible, 1L), key()));

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(createdNotices(id, colleague)).isEqualTo(1));
    assertThat(createdNotices(id, logistician)).isZero();
    assertThat(queueFrames.count()).isGreaterThan(framesBefore);
  }

  @Test
  void theCreatedOrderLandsInTheMembersReadSet() throws Exception {
    UUID id = createdId(create(logistician, body(requesting, responsible, 1L), key()));
    fingerprints.recomputeMarked();

    JsonNode page =
        objectMapper.readTree(
            mockMvc
                .perform(relayed(get(PATH), logistician, READ))
                .andReturn()
                .getResponse()
                .getContentAsString());

    JsonNode item = null;
    for (JsonNode candidate : page.get("items")) {
      if (candidate.get("id").asString().equals(id.toString())) {
        item = candidate;
      }
    }
    assertThat(item).as("the created order in the member's snapshot").isNotNull();
    assertThat(item.get("relations").get(0).asString()).isEqualTo("requesting");
    assertThat(item.get("kind").asString()).isEqualTo("AUEC");
  }

  @Test
  void aCreateCountsOneAppliedWriteAndARefusalOneRejectedWriteAndItsReason() throws Exception {
    Counter applied = writes(client, "applied");
    Counter rejected = writes(client, "rejected");
    Counter notOffered =
        meterRegistry.counter(
            ExchangeMetricNames.EXCHANGE_JOB_ORDER_REFUSED,
            MetricNames.TAG_REASON,
            "units_not_offered");
    double appliedBefore = applied.count();
    double rejectedBefore = rejected.count();
    double notOfferedBefore = notOffered.count();

    create(logistician, body(requesting, responsible, 1L), key());
    create(logistician, body(UUID.randomUUID(), responsible, 1L), key());

    assertThat(applied.count()).isEqualTo(appliedBefore + 1);
    assertThat(rejected.count()).isEqualTo(rejectedBefore + 1);
    assertThat(notOffered.count()).isEqualTo(notOfferedBefore + 1);
  }

  @Test
  void theCreateOptionsOfferTheMembersLogisticianUnitsAndTheEligibleProfitUnits() throws Exception {
    MockHttpServletResponse response =
        mockMvc.perform(relayed(get(OPTIONS), logistician, WRITE)).andReturn().getResponse();

    assertThat(response.getStatus()).isEqualTo(200);
    String content = response.getContentAsString();
    ExchangePublishedSchemas.assertMatches(OPTIONS_SCHEMA, content, "the create options");
    JsonNode options = objectMapper.readTree(content);
    assertThat(ids(options.get("requestingUnits"))).containsExactly(requesting.toString());
    assertThat(ids(options.get("responsibleUnits")))
        .contains(responsible.toString(), profit.toString())
        .doesNotContain(requesting.toString());
    assertThat(options.get("suggestedResponsible").asString()).isEqualTo(profit.toString());
    assertThat(options.get("writesEnabled").asBoolean()).isTrue();
    JsonNode unit = options.get("requestingUnits").get(0);
    assertThat(unit.propertyNames()).containsExactlyInAnyOrder("id", "name", "shorthand", "kind");
    assertThat(content).doesNotContain(logistician.toString());
  }

  @Test
  void aMemberWithoutAWriterUnitIsOfferedNoCreate() throws Exception {
    UUID plain = user("create-options-plain", false);
    seat(plain, requesting, "MEMBER", false);

    JsonNode options =
        objectMapper.readTree(
            mockMvc
                .perform(relayed(get(OPTIONS), plain, WRITE))
                .andReturn()
                .getResponse()
                .getContentAsString());

    assertThat(options.get("requestingUnits")).isEmpty();
    assertThat(options.get("responsibleUnits")).isEmpty();
    assertThat(options.get("suggestedResponsible").isNull()).isTrue();
  }

  @Test
  void theCreateOptionsTellWhenTheWritesSwitchIsOffAndCountOneRead() throws Exception {
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    settings.setJobOrderWritesEnabled(false);
    settingsRepository.saveAndFlush(settings);
    Counter reads =
        meterRegistry.counter(
            ExchangeMetricNames.EXCHANGE_JOB_ORDER_READS,
            MetricNames.TAG_CLIENT_ID,
            client,
            ExchangeMetricNames.TAG_ROUTE,
            "create_options");
    double before = reads.count();

    JsonNode options =
        objectMapper.readTree(
            mockMvc
                .perform(relayed(get(OPTIONS), logistician, WRITE))
                .andReturn()
                .getResponse()
                .getContentAsString());

    assertThat(options.get("writesEnabled").asBoolean()).isFalse();
    assertThat(reads.count()).isEqualTo(before + 1);
  }

  @Test
  void theWritesSwitchClosesTheCreate() throws Exception {
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    settings.setJobOrderWritesEnabled(false);
    settingsRepository.saveAndFlush(settings);

    MockHttpServletResponse response =
        create(logistician, body(requesting, responsible, 1L), key());

    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(objectMapper.readTree(response.getContentAsString()).get("code").asString())
        .isEqualTo("JOB_ORDER_WRITES_DISABLED");
    assertThat(createdOrders()).isEmpty();
  }

  @Test
  void theReadCapabilityDoesNotOpenTheCreate() throws Exception {
    MockHttpServletResponse response =
        mockMvc
            .perform(
                relayed(post(PATH), logistician, READ)
                    .header(ActingMemberHeader.EXCHANGE_IDEMPOTENCY_KEY_HEADER, key())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(requesting, responsible, 1L)))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(createdOrders()).isEmpty();
  }

  @Test
  void aCreateAtTheOpenOrderCapIsRefusedAndCountedAndCreatesNothing() throws Exception {
    createdAt(createOrders(OPEN_ORDER_CAP), Instant.now().minus(Duration.ofDays(1)));
    Counter capRefusals = refusals("open_order_cap");
    Counter rejected = writes(client, "rejected");
    double capRefusalsBefore = capRefusals.count();
    double rejectedBefore = rejected.count();

    MockHttpServletResponse response =
        create(logistician, body(requesting, responsible, 1L), key());

    assertCapRefusal(response, "JOB_ORDER_OPEN_ORDER_CAP");
    assertThat(createdOrders()).hasSize(OPEN_ORDER_CAP);
    assertThat(clientAudits()).hasSize(OPEN_ORDER_CAP);
    assertThat(capRefusals.count()).isEqualTo(capRefusalsBefore + 1);
    assertThat(rejected.count()).isEqualTo(rejectedBefore + 1);
  }

  @Test
  void cancelledOrdersStillCountForTheDailyCreateCap() throws Exception {
    close(createOrders(DAILY_CREATE_CAP), "CANCELLED");
    Counter capRefusals = refusals("daily_create_cap");
    double capRefusalsBefore = capRefusals.count();

    MockHttpServletResponse response =
        create(logistician, body(requesting, responsible, 1L), key());

    assertCapRefusal(response, "JOB_ORDER_DAILY_CREATE_CAP");
    assertThat(createdOrders()).hasSize(DAILY_CREATE_CAP);
    assertThat(capRefusals.count()).isEqualTo(capRefusalsBefore + 1);
  }

  @Test
  void atBothCapsTheOpenOrderCapAnswers() throws Exception {
    createOrders(OPEN_ORDER_CAP);

    assertCapRefusal(
        create(logistician, body(requesting, responsible, 1L), key()), "JOB_ORDER_OPEN_ORDER_CAP");
  }

  @Test
  void anInProgressOrderHoldsAnOpenSlotAndAnyClosingFreesIt() throws Exception {
    List<UUID> orders = createOrders(OPEN_ORDER_CAP);
    createdAt(orders, Instant.now().minus(Duration.ofDays(1)));
    jdbc.update("UPDATE job_order SET status = 'IN_PROGRESS' WHERE id = ?", orders.getFirst());
    assertCapRefusal(
        create(logistician, body(requesting, responsible, 1L), key()), "JOB_ORDER_OPEN_ORDER_CAP");

    close(List.of(orders.getFirst()), "COMPLETED");

    createdId(create(logistician, body(requesting, responsible, 1L), key()));
    assertThat(createdOrders()).hasSize(OPEN_ORDER_CAP + 1);
  }

  @Test
  void aKnownKeyAnswersItsOrderWhateverTheCaps() throws Exception {
    String key = key();
    UUID first = createdId(create(logistician, body(requesting, responsible, 250_000L), key));
    createOrders(OPEN_ORDER_CAP - 1);

    MockHttpServletResponse retry = create(logistician, body(requesting, responsible, 1L), key);

    assertThat(createdId(retry)).isEqualTo(first);
    assertThat(objectMapper.readTree(retry.getContentAsString()).get("target").asLong())
        .isEqualTo(250_000L);
    assertThat(createdOrders()).hasSize(OPEN_ORDER_CAP);
  }

  @Test
  void twoCreatesRacingForTheLastOpenSlotInsertOneOrder() throws Exception {
    createdAt(createOrders(OPEN_ORDER_CAP - 1), Instant.now().minus(Duration.ofDays(1)));

    assertOneWinsTheRace(racing(List.of(key(), key())), "JOB_ORDER_OPEN_ORDER_CAP");
    assertThat(createdOrders()).hasSize(OPEN_ORDER_CAP);
  }

  @Test
  void twoCreatesRacingForTheLastDailySlotInsertOneOrder() throws Exception {
    close(createOrders(DAILY_CREATE_CAP - 1), "CANCELLED");

    assertOneWinsTheRace(racing(List.of(key(), key())), "JOB_ORDER_DAILY_CREATE_CAP");
    assertThat(createdOrders()).hasSize(DAILY_CREATE_CAP);
  }

  @Test
  void ordersCreatedInTheWebNeitherCountNorAreRefused() throws Exception {
    createOrders(OPEN_ORDER_CAP - 1);
    webCreate();
    webCreate();

    createdId(create(logistician, body(requesting, responsible, 1L), key()));

    webCreate();
    assertThat(createdOrders()).hasSize(OPEN_ORDER_CAP);
  }

  @Test
  void theDailyCreateCapCountsFromUtcMidnight() throws Exception {
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    Instant midnight = today.atStartOfDay(ZoneOffset.UTC).toInstant();
    List<UUID> orders = createOrders(DAILY_CREATE_CAP);
    close(orders, "CANCELLED");
    createdAt(orders, midnight.minus(1, ChronoUnit.MICROS));

    createdId(create(logistician, body(requesting, responsible, 1L), key()));
    createdAt(orders.subList(0, DAILY_CREATE_CAP - 1), midnight);
    MockHttpServletResponse response =
        create(logistician, body(requesting, responsible, 1L), key());

    assumeThat(LocalDate.now(ZoneOffset.UTC)).as("the UTC day did not change").isEqualTo(today);
    assertCapRefusal(response, "JOB_ORDER_DAILY_CREATE_CAP");
  }

  @Test
  void theCreateOptionsCarryTheMembersOwnFiguresAgainstBothCaps() throws Exception {
    List<UUID> orders = createOrders(3);
    close(orders.subList(0, 1), "CANCELLED");
    createdAt(orders.subList(1, 2), Instant.now().minus(Duration.ofDays(1)));
    webCreate();
    UUID colleague = user("create-options-colleague", false);
    seat(colleague, requesting, "MEMBER", true);
    createdId(create(colleague, body(requesting, responsible, 1L), key()));

    MockHttpServletResponse response =
        mockMvc.perform(relayed(get(OPTIONS), logistician, WRITE)).andReturn().getResponse();

    ExchangePublishedSchemas.assertMatches(
        OPTIONS_SCHEMA, response.getContentAsString(), "the create options");
    JsonNode limits = objectMapper.readTree(response.getContentAsString()).get("limits");
    assertThat(limits.propertyNames())
        .containsExactlyInAnyOrder("openOrderCap", "openOrders", "dailyCreateCap", "createdToday");
    assertThat(limits.get("openOrderCap").asInt()).isEqualTo(OPEN_ORDER_CAP);
    assertThat(limits.get("openOrders").asInt()).isEqualTo(2);
    assertThat(limits.get("dailyCreateCap").asInt()).isEqualTo(DAILY_CREATE_CAP);
    assertThat(limits.get("createdToday").asInt()).isEqualTo(2);
  }

  private void assertOneWinsTheRace(
      @NotNull List<MockHttpServletResponse> answers, @NotNull String code) throws Exception {
    assertThat(answers)
        .extracting(MockHttpServletResponse::getStatus)
        .containsExactlyInAnyOrder(201, 409);
    for (MockHttpServletResponse answer : answers) {
      if (answer.getStatus() == 409) {
        assertCapRefusal(answer, code);
      }
    }
  }

  private @NotNull List<MockHttpServletResponse> racing(@NotNull List<String> keys)
      throws Exception {
    String body = body(requesting, responsible, 250_000L);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<MockHttpServletResponse>> calls = new ArrayList<>();
    List<MockHttpServletResponse> answers = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(keys.size())) {
      for (String key : keys) {
        calls.add(
            pool.submit(() -> {
              start.await();
              return create(logistician, body, key);
            }));
      }
      start.countDown();
      for (Future<MockHttpServletResponse> call : calls) {
        answers.add(call.get());
      }
    }
    return answers;
  }

  private void webCreate() throws Exception {
    MockHttpServletResponse response =
        mockMvc
            .perform(
                post("/api/v1/orders/auec")
                    .with(
                        jwt()
                            .jwt(t -> t.subject(logistician.toString()))
                            .authorities(
                                new SimpleGrantedAuthority(Roles.authority(Roles.KRT_MEMBER)),
                                new SimpleGrantedAuthority(Roles.authority(Roles.LOGISTICIAN)),
                                new OrgUnitContextualAuthority(Roles.LOGISTICIAN, requesting)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            Map.of(
                                "responsibleOrgUnitId",
                                responsible,
                                "requestingOrgUnitId",
                                requesting,
                                "handle",
                                "Web contact",
                                "target",
                                "1"))))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
  }

  private void assertCapRefusal(@NotNull MockHttpServletResponse response, @NotNull String code)
      throws Exception {
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(409);
    assertThat(objectMapper.readTree(response.getContentAsString()).get("code").asString())
        .isEqualTo(code);
  }

  private @NotNull List<UUID> createOrders(int count) throws Exception {
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      ids.add(createdId(create(logistician, body(requesting, responsible, 1L), key())));
    }
    return ids;
  }

  private void close(@NotNull List<UUID> orders, @NotNull String status) {
    orders.forEach(id ->
        jdbc.update(
            "UPDATE job_order SET status = ?, priority = NULL, closed_at = now(),"
                + " lifecycle_version = lifecycle_version + 1 WHERE id = ?",
            status,
            id));
  }

  private void createdAt(@NotNull List<UUID> orders, @NotNull Instant instant) {
    orders.forEach(id ->
        jdbc.update(
            "UPDATE job_order SET created_at = ? WHERE id = ?", Timestamp.from(instant), id));
  }

  private @NotNull Counter refusals(@NotNull String reason) {
    return meterRegistry.counter(
        ExchangeMetricNames.EXCHANGE_JOB_ORDER_REFUSED, MetricNames.TAG_REASON, reason);
  }

  private void assertNotOffered(@NotNull UUID member, @NotNull String body) throws Exception {
    MockHttpServletResponse response = create(member, body, key());

    assertThat(response.getStatus()).isEqualTo(400);
    JsonNode problem = objectMapper.readTree(response.getContentAsString());
    assertThat(problem.get("code").asString()).isEqualTo("JOB_ORDER_UNITS_NOT_OFFERED");
    assertThat(response.getContentAsString())
        .doesNotContain(requesting.toString())
        .doesNotContain(responsible.toString());
    assertThat(createdOrders()).isEmpty();
  }

  private @NotNull MockHttpServletResponse create(
      @NotNull UUID member, @NotNull String body, @NotNull String idempotencyKey) throws Exception {
    return mockMvc
        .perform(
            relayed(post(PATH), member, WRITE)
                .header(ActingMemberHeader.EXCHANGE_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andReturn()
        .getResponse();
  }

  private @NotNull UUID createdId(@NotNull MockHttpServletResponse response) throws Exception {
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
    return UUID.fromString(
        objectMapper.readTree(response.getContentAsString()).get("id").asString());
  }

  private @NotNull String body(
      @NotNull UUID requestingUnit, @NotNull UUID responsibleUnit, long target) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("requestingUnitId", requestingUnit);
    body.put("responsibleUnitId", responsibleUnit);
    body.put("contact", "Create contact");
    body.put("comment", "Deliver to Area18");
    body.put("target", target);
    return objectMapper.writeValueAsString(body);
  }

  private static @NotNull String key() {
    return "create-" + UUID.randomUUID();
  }

  private @NotNull List<UUID> createdOrders() {
    return jdbc.queryForList(
        "SELECT id FROM job_order WHERE created_by_client_id = ?", UUID.class, client);
  }

  private @NotNull List<String> clientAudits() {
    return jdbc.queryForList(
        "SELECT event_type FROM audit_event WHERE client_id = ?"
            + " AND event_type = 'JOB_ORDER_CREATED_VIA_CLIENT'",
        String.class,
        client);
  }

  private int createdNotices(@NotNull UUID order, @NotNull UUID recipient) {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM notification WHERE entity_id = ? AND recipient_user_id = ?"
                + " AND type = 'JOB_ORDER_CREATED'",
            Integer.class,
            order,
            recipient);
    return count == null ? 0 : count;
  }

  private @NotNull Counter writes(@NotNull String clientId, @NotNull String outcome) {
    return meterRegistry.counter(
        ExchangeMetricNames.EXCHANGE_WRITES,
        MetricNames.TAG_CLIENT_ID,
        clientId,
        ExchangeMetricNames.TAG_RESOURCE,
        "job_order",
        MetricNames.TAG_OUTCOME,
        outcome);
  }

  private static @NotNull List<String> ids(@NotNull JsonNode units) {
    List<String> ids = new ArrayList<>();
    units.forEach(unit -> ids.add(unit.get("id").asString()));
    return ids;
  }

  private @NotNull MockHttpServletRequestBuilder relayed(
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

  private @NotNull UUID user(@NotNull String username, boolean admin) {
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

  private void seat(
      @NotNull UUID member, @NotNull UUID unit, @NotNull String role, boolean logisticianFlag) {
    jdbc.update(
        "INSERT INTO org_unit_membership (user_id, org_unit_id, role, is_logistician)"
            + " VALUES (?, ?, ?, ?)",
        member,
        unit,
        role,
        logisticianFlag);
  }

  private @NotNull UUID squadron(@NotNull String name, @Nullable UUID parent) {
    return orgUnit("SQUADRON", name, parent);
  }

  private @NotNull UUID orgUnit(@NotNull String kind, @NotNull String name, @Nullable UUID parent) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO org_unit (id, kind, name, shorthand, active, parent_org_unit_id)"
            + " VALUES (?, ?, ?, ?, TRUE, ?)",
        id,
        kind,
        name + " " + id,
        "C" + id.toString().substring(0, 7),
        parent);
    orgUnits.add(id);
    return id;
  }
}

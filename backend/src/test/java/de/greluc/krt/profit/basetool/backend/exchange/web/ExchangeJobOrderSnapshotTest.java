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
import static org.hamcrest.Matchers.startsWith;
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
import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderType;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.ActingMemberHeader;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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
 * The snapshot of the member's job-order set under {@code exchange.joborders.read}: the read set,
 * the window, the carried fields per kind and nothing else, withheld names and the family switch
 * (REQ-OXCH-001, REQ-OXCH-002, REQ-OXCH-003).
 */
@SpringBootTest
class ExchangeJobOrderSnapshotTest {

  private static final String PATH = "/api/v1/exchange/job-orders";
  private static final String GATEWAY = "55555555-5555-5555-5555-555555555555";
  private static final String KEY = "Kx9_" + "j".repeat(39);
  private static final String READ = "exchange.joborders.read";
  private static final String PAGE_SCHEMA = "page.schema.json#/$defs/jobOrderPage";
  private static final Instant CHANGED_AT = Instant.parse("2026-11-20T08:15:30Z");

  /** Every property a MATERIAL order may carry; the AUEC set swaps its kind fields. */
  private static final Set<String> HEADER =
      Set.of(
          "id",
          "number",
          "kind",
          "status",
          "priority",
          "requestingUnit",
          "responsibleUnit",
          "supportingUnits",
          "createdAt",
          "updatedAt",
          "relations",
          "comment",
          "responsibilityInvalid",
          "link",
          "assignees",
          "versions");

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private ExchangeClientRepository clientRepository;
  @Autowired private ExchangeSettingsRepository settingsRepository;
  @Autowired private JobOrderRepository jobOrderRepository;
  @Autowired private OrgUnitRepository orgUnitRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private QualityTierRepository qualityTierRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private MeterRegistry meterRegistry;

  private final List<UUID> orders = new ArrayList<>();
  private final List<UUID> orgUnits = new ArrayList<>();
  private final List<UUID> materials = new ArrayList<>();
  private final List<UUID> users = new ArrayList<>();

  private MockMvc mockMvc;
  private UUID member;
  private UUID own;
  private UUID other;
  private String client;
  private boolean wasEnabled;
  private boolean wasJobOrdersEnabled;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy.class))
            .build();
    member = user("snapshot-member");
    own = orgUnit("Own");
    other = orgUnit("Other");
    jdbc.update(
        "INSERT INTO org_unit_membership (user_id, org_unit_id, role, is_logistician)"
            + " VALUES (?, ?, 'MEMBER', false)",
        member,
        own);
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
      jdbc.update("DELETE FROM job_order_money_holder WHERE order_id = ?", id);
      jdbc.update("DELETE FROM job_order_money_target WHERE order_id = ?", id);
      jdbc.update("DELETE FROM job_order_assignees WHERE job_order_id = ?", id);
      jdbc.update("DELETE FROM job_order_participation WHERE job_order_id = ?", id);
      jdbc.update("DELETE FROM job_order_material WHERE job_order_id = ?", id);
      jdbc.update("DELETE FROM job_order WHERE id = ?", id);
    });
    jdbc.update("DELETE FROM exchange_client WHERE client_id = ?", client);
    users.forEach(id -> {
      jdbc.update("DELETE FROM exchange_name_withholding WHERE user_id = ?", id);
      jdbc.update("DELETE FROM org_unit_membership WHERE user_id = ?", id);
      jdbc.update("DELETE FROM user_roles WHERE user_id = ?", id);
      jdbc.update("DELETE FROM app_user WHERE id = ?", id);
    });
    orgUnits.forEach(id -> jdbc.update("DELETE FROM org_unit WHERE id = ?", id));
    materials.forEach(id -> jdbc.update("DELETE FROM material WHERE id = ?", id));
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    settings.setEnabled(wasEnabled);
    settings.setJobOrdersEnabled(wasJobOrdersEnabled);
    settingsRepository.saveAndFlush(settings);
  }

  @Test
  void aMaterialOrderOfTheMembersUnitCarriesTheHeaderTheMembersPartAndItsLines() throws Exception {
    UUID material = material("Agricium");
    UUID order = materialOrder(other, own, material, 12.5, "Bring it to Lorville");
    fingerprint(order, false);
    UUID colleague = user("snapshot-colleague");
    assignee(order, colleague, "colleague's secret");
    assignee(order, member, "my own note");
    Integer number =
        jdbc.queryForObject("SELECT display_id FROM job_order WHERE id = ?", Integer.class, order);

    String body =
        mockMvc
            .perform(relayed(get(PATH), READ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.removed.length()").value(0))
            .andExpect(jsonPath("$.hasMore").value(false))
            .andExpect(jsonPath("$.items[0].id").value(order.toString()))
            .andExpect(jsonPath("$.items[0].number").value(number))
            .andExpect(jsonPath("$.items[0].kind").value("MATERIAL"))
            .andExpect(jsonPath("$.items[0].status").value("OPEN"))
            .andExpect(jsonPath("$.items[0].requestingUnit.id").value(own.toString()))
            .andExpect(jsonPath("$.items[0].requestingUnit.name").value("Own " + own))
            .andExpect(jsonPath("$.items[0].requestingUnit.shorthand").exists())
            .andExpect(jsonPath("$.items[0].responsibleUnit.id").value(other.toString()))
            .andExpect(jsonPath("$.items[0].supportingUnits.length()").value(0))
            .andExpect(jsonPath("$.items[0].updatedAt").value("2026-11-20T08:15:30Z"))
            .andExpect(jsonPath("$.items[0].relations[0]").value("requesting"))
            .andExpect(jsonPath("$.items[0].relations.length()").value(2))
            .andExpect(jsonPath("$.items[0].relations[1]").value("assignee"))
            .andExpect(jsonPath("$.items[0].comment").value("Bring it to Lorville"))
            .andExpect(jsonPath("$.items[0].contact").value("snapshot-requester"))
            .andExpect(jsonPath("$.items[0].responsibilityInvalid").value(false))
            .andExpect(
                jsonPath("$.items[0].link").value("https://profit-base.online/orders/" + order))
            .andExpect(jsonPath("$.items[0].versions.lifecycle").value(0))
            .andExpect(jsonPath("$.items[0].versions.comment").value(0))
            .andExpect(jsonPath("$.items[0].versions.target").isEmpty())
            .andExpect(jsonPath("$.items[0].materials[0].material.bt").value(material.toString()))
            .andExpect(jsonPath("$.items[0].materials[0].minQuality").value(0))
            .andExpect(jsonPath("$.items[0].materials[0].unit").value("SCU"))
            .andExpect(jsonPath("$.items[0].materials[0].requested").value(12.5))
            .andExpect(jsonPath("$.items[0].materials[0].handedOver").value(0))
            .andExpect(jsonPath("$.items[0].materials[0].remaining").value(12.5))
            .andExpect(jsonPath("$.items[0].materials[0].open").value(12.5))
            .andExpect(jsonPath("$.items[0].assignees.length()").value(2))
            .andExpect(jsonPath("$.nextCursor").value(startsWith("f1.")))
            .andReturn()
            .getResponse()
            .getContentAsString();

    ExchangePublishedSchemas.assertMatches(PAGE_SCHEMA, body, "a material order");
    JsonNode item = objectMapper.readTree(body).get("items").get(0);
    Set<String> expected = new HashSet<>(HEADER);
    expected.addAll(Set.of("contact", "materials"));
    assertThat(item.propertyNames()).containsExactlyInAnyOrderElementsOf(expected);
    JsonNode mine = assigneeNamed(item, "snapshot-member");
    assertThat(mine.get("own").asBoolean()).isTrue();
    assertThat(mine.get("note").asString()).isEqualTo("my own note");
    assertThat(mine.get("ref").asString()).isNotBlank();
    JsonNode theirs = assigneeNamed(item, "snapshot-colleague");
    assertThat(theirs.get("own").asBoolean()).isFalse();
    assertThat(theirs.get("note").isNull()).isTrue();
    assertThat(body)
        .doesNotContain("colleague's secret")
        .doesNotContain(colleague.toString())
        .doesNotContain(member.toString());
  }

  @Test
  void anAuecOrderCarriesItsProgressButNoContactHolderOrAmountPerPerson() throws Exception {
    UUID order = auecOrder(own, other, 100);
    fingerprint(order, false);
    UUID holder = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO job_order_money_holder (id, order_id, accountless_name, revision)"
            + " VALUES (?, ?, 'Secret Holder', 1)",
        holder,
        order);

    String body =
        mockMvc
            .perform(relayed(get(PATH), READ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].kind").value("AUEC"))
            .andExpect(jsonPath("$.items[0].relations[0]").value("responsible"))
            .andExpect(jsonPath("$.items[0].target").value(100))
            .andExpect(jsonPath("$.items[0].netHandedOver").value(0))
            .andExpect(jsonPath("$.items[0].remaining").value(100))
            .andExpect(jsonPath("$.items[0].overpaid").value(0))
            .andExpect(jsonPath("$.items[0].settlementOpen").value(false))
            .andExpect(jsonPath("$.items[0].versions.target").value(0))
            .andReturn()
            .getResponse()
            .getContentAsString();

    ExchangePublishedSchemas.assertMatches(PAGE_SCHEMA, body, "an aUEC order");
    JsonNode item = objectMapper.readTree(body).get("items").get(0);
    Set<String> expected = new HashSet<>(HEADER);
    expected.addAll(Set.of("target", "netHandedOver", "remaining", "overpaid", "settlementOpen"));
    assertThat(item.propertyNames()).containsExactlyInAnyOrderElementsOf(expected);
    assertThat(body)
        .doesNotContain("private auec contact")
        .doesNotContain("Secret Holder")
        .doesNotContain(holder.toString());
  }

  @Test
  void theWindowKeepsActiveOrdersRecentlyClosedOnesAndOpenSettlementsOnly() throws Exception {
    Instant now = Instant.now();
    UUID active = materialOrder(other, own, material("Window active"), 1.0, null);
    UUID closedInside = materialOrder(other, own, material("Window inside"), 1.0, null);
    close(closedInside, now.minus(Duration.ofDays(7)).plus(Duration.ofHours(1)));
    UUID closedOutside = materialOrder(other, own, material("Window outside"), 1.0, null);
    close(closedOutside, now.minus(Duration.ofDays(7)).minus(Duration.ofHours(1)));
    UUID settling = auecOrder(other, own, 50);
    close(settling, now.minus(Duration.ofDays(30)));
    UUID settled = auecOrder(other, own, 50);
    close(settled, now.minus(Duration.ofDays(30)));
    fingerprint(active, false);
    fingerprint(closedInside, false);
    fingerprint(closedOutside, false);
    fingerprint(settling, true);
    fingerprint(settled, false);

    assertThat(snapshotIds())
        .containsExactlyInAnyOrder(active.toString(), closedInside.toString(), settling.toString());
  }

  @Test
  void anOrderWithoutARelationOrWithoutAFingerprintStaysOut() throws Exception {
    UUID foreign = orgUnit("Foreign");
    UUID unrelated = materialOrder(other, foreign, material("Unrelated"), 1.0, null);
    fingerprint(unrelated, false);
    UUID unprinted = materialOrder(other, own, material("Unprinted"), 1.0, null);

    assertThat(snapshotIds()).doesNotContain(unrelated.toString(), unprinted.toString());
  }

  @Test
  void anAssigneeWhoWithholdsTheirNameIsLeftOut() throws Exception {
    UUID order = materialOrder(other, orgUnit("Foreign"), material("Withheld"), 1.0, null);
    fingerprint(order, false);
    UUID withholding = user("snapshot-withholding");
    jdbc.update(
        "INSERT INTO exchange_name_withholding (user_id, withhold_name) VALUES (?, true)",
        withholding);
    assignee(order, withholding, "withheld note");
    assignee(order, member, null);
    jdbc.update(
        "INSERT INTO exchange_name_withholding (user_id, withhold_name) VALUES (?, true)", member);

    String body =
        mockMvc
            .perform(relayed(get(PATH), READ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].relations[0]").value("assignee"))
            .andExpect(jsonPath("$.items[0].assignees.length()").value(0))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain("snapshot-withholding").doesNotContain("withheld note");
  }

  @Test
  void theSnapshotPagesByIdAndEndsWithTheFeedCursor() throws Exception {
    List<String> seeded = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      UUID order = materialOrder(other, own, material("Page " + i), 1.0, null);
      fingerprint(order, false);
      seeded.add(order.toString());
    }
    List<String> delivered = new ArrayList<>();
    String cursor = null;
    boolean more = true;
    while (more) {
      MockHttpServletRequestBuilder request = get(PATH).param("limit", "1");
      if (cursor != null) {
        request.param("cursor", cursor);
      }
      JsonNode page =
          objectMapper.readTree(
              mockMvc
                  .perform(relayed(request, READ))
                  .andExpect(status().isOk())
                  .andReturn()
                  .getResponse()
                  .getContentAsString());
      page.get("items").forEach(item -> delivered.add(item.get("id").asString()));
      more = page.get("hasMore").asBoolean();
      cursor = page.get("nextCursor").asString();
    }

    assertThat(delivered).containsExactlyElementsOf(seeded.stream().sorted().toList());
    assertThat(cursor).startsWith("f1.");
    mockMvc
        .perform(relayed(get(PATH).param("cursor", cursor), READ))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0))
        .andExpect(jsonPath("$.removed.length()").value(0))
        .andExpect(jsonPath("$.nextCursor").value(startsWith("f1.")));
  }

  @Test
  void aSnapshotPageCountsOneSnapshotRead() throws Exception {
    Counter reads =
        meterRegistry.counter(
            ExchangeMetricNames.EXCHANGE_JOB_ORDER_READS,
            MetricNames.TAG_CLIENT_ID,
            client,
            ExchangeMetricNames.TAG_ROUTE,
            "snapshot");
    double before = reads.count();

    mockMvc.perform(relayed(get(PATH), READ)).andExpect(status().isOk());

    assertThat(reads.count()).isEqualTo(before + 1);
  }

  @Test
  void theFamilySwitchClosesTheRoute() throws Exception {
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    settings.setJobOrdersEnabled(false);
    settingsRepository.saveAndFlush(settings);

    mockMvc
        .perform(relayed(get(PATH), READ))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("JOB_ORDER_EXCHANGE_DISABLED"));
  }

  @Test
  void anotherCapabilityDoesNotOpenTheRoute() throws Exception {
    mockMvc.perform(relayed(get(PATH), "exchange.demand.read")).andExpect(status().isForbidden());
  }

  private List<String> snapshotIds() throws Exception {
    JsonNode page =
        objectMapper.readTree(
            mockMvc
                .perform(relayed(get(PATH), READ))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    ExchangePublishedSchemas.assertMatches(PAGE_SCHEMA, page.toString(), "a snapshot page");
    List<String> ids = new ArrayList<>();
    page.get("items").forEach(item -> ids.add(item.get("id").asString()));
    return ids;
  }

  private static JsonNode assigneeNamed(JsonNode item, String prefix) {
    for (JsonNode assignee : item.get("assignees")) {
      if (assignee.get("name").asString().startsWith(prefix)) {
        return assignee;
      }
    }
    throw new AssertionError("no assignee named " + prefix);
  }

  private @NotNull MockHttpServletRequestBuilder relayed(
      @NotNull MockHttpServletRequestBuilder request, @NotNull String capabilities) {
    return request
        .with(jwt().jwt(t -> t.subject(GATEWAY).claim("azp", "test-ingest-gateway")))
        .header(ActingMemberHeader.ON_BEHALF_OF_HEADER, member.toString())
        .header(ActingMemberHeader.EXCHANGE_CLIENT_HEADER, client)
        .header(ActingMemberHeader.EXCHANGE_CAPABILITIES_HEADER, capabilities)
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

  private @NotNull UUID material(@NotNull String name) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO material (id, name, type, quantity_type, is_manual_raw_material,"
            + " is_job_order, is_visible, source_systems)"
            + " VALUES (?, ?, 'REFINED', 'SCU', false, false, true, 'UEX_ONLY')",
        id,
        name + " " + id);
    materials.add(id);
    return id;
  }

  private @NotNull UUID materialOrder(
      @NotNull UUID responsible,
      @NotNull UUID requesting,
      @NotNull UUID material,
      double amount,
      @Nullable String comment) {
    UUID id =
        transactionTemplate.execute(_ -> {
          JobOrder order =
              JobOrder.builder()
                  .responsibleOrgUnit(orgUnitRepository.getReferenceById(responsible))
                  .requestingOrgUnit(orgUnitRepository.getReferenceById(requesting))
                  .handle("snapshot-requester")
                  .comment(comment)
                  .status(JobOrderStatus.OPEN)
                  .build();
          order.addMaterial(
              JobOrderMaterial.builder()
                  .material(materialRepository.getReferenceById(material))
                  .qualityTier(qualityTierRepository.findByCode("NONE").orElseThrow())
                  .amount(amount)
                  .build());
          return jobOrderRepository.save(order).getId();
        });
    orders.add(Objects.requireNonNull(id));
    return id;
  }

  private @NotNull UUID auecOrder(@NotNull UUID responsible, @NotNull UUID requesting, int target) {
    UUID id =
        transactionTemplate.execute(_ -> {
          JobOrder order =
              jobOrderRepository.saveAndFlush(
                  JobOrder.builder()
                      .responsibleOrgUnit(orgUnitRepository.getReferenceById(responsible))
                      .requestingOrgUnit(orgUnitRepository.getReferenceById(requesting))
                      .type(JobOrderType.AUEC)
                      .handle("private auec contact")
                      .status(JobOrderStatus.OPEN)
                      .build());
          jdbc.update(
              "INSERT INTO job_order_money_target (order_id, amount, version) VALUES (?, ?, 0)",
              order.getId(),
              target);
          return order.getId();
        });
    orders.add(Objects.requireNonNull(id));
    return id;
  }

  private void close(@NotNull UUID order, @NotNull Instant closedAt) {
    jdbc.update(
        "UPDATE job_order SET status = 'COMPLETED', priority = NULL, closed_at = ? WHERE id = ?",
        Timestamp.from(closedAt.truncatedTo(ChronoUnit.MICROS)),
        order);
  }

  private void fingerprint(@NotNull UUID order, boolean settlementOpen) {
    jdbc.update(
        "INSERT INTO exchange_job_order_fingerprint"
            + " (job_order_id, fingerprint, changed_at, settlement_open) VALUES (?, ?, ?, ?)",
        order,
        new byte[32],
        Timestamp.from(CHANGED_AT),
        settlementOpen);
  }

  private void assignee(@NotNull UUID order, @NotNull UUID user, @Nullable String note) {
    jdbc.update(
        "INSERT INTO job_order_assignees (id, job_order_id, user_id, note, version)"
            + " VALUES (?, ?, ?, ?, 0)",
        UUID.randomUUID(),
        order,
        user,
        note);
  }
}

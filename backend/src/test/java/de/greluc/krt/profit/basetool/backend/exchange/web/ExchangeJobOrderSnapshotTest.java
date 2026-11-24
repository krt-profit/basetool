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

import static de.greluc.krt.profit.basetool.backend.exchange.web.ExchangeJobOrderWorld.PATH;
import static de.greluc.krt.profit.basetool.backend.exchange.web.ExchangeJobOrderWorld.READ;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.exchange.ExchangePublishedSchemas;
import de.greluc.krt.profit.basetool.backend.exchange.api.ExchangeMetricNames;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private MeterRegistry meterRegistry;

  private ExchangeJobOrderWorld world;
  private MockMvc mockMvc;
  private UUID member;
  private UUID own;
  private UUID other;

  @BeforeEach
  void setUp() {
    world = new ExchangeJobOrderWorld(context);
    mockMvc = world.getMockMvc();
    member = world.user("snapshot-member", false);
    own = orgUnit("Own");
    other = orgUnit("Other");
    world.seat(member, own, "MEMBER", false);
  }

  @AfterEach
  void tearDown() {
    world.close();
  }

  @Test
  void aMaterialOrderOfTheMembersUnitCarriesTheHeaderTheMembersPartAndItsLines() throws Exception {
    UUID material = world.material("Agricium");
    UUID order = materialOrder(other, own, material, 12.5, "Bring it to Lorville");
    fingerprint(order, false);
    UUID colleague = world.user("snapshot-colleague", false);
    world.assignee(order, colleague, "colleague's secret");
    world.assignee(order, member, "my own note");
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
    UUID active = materialOrder(other, own, world.material("Window active"), 1.0, null);
    UUID closedInside = materialOrder(other, own, world.material("Window inside"), 1.0, null);
    close(closedInside, now.minus(Duration.ofDays(7)).plus(Duration.ofHours(1)));
    UUID closedOutside = materialOrder(other, own, world.material("Window outside"), 1.0, null);
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
    UUID unrelated = materialOrder(other, foreign, world.material("Unrelated"), 1.0, null);
    fingerprint(unrelated, false);
    UUID unprinted = materialOrder(other, own, world.material("Unprinted"), 1.0, null);

    assertThat(snapshotIds()).doesNotContain(unrelated.toString(), unprinted.toString());
  }

  @Test
  void anAssigneeWhoWithholdsTheirNameIsLeftOut() throws Exception {
    UUID order = materialOrder(other, orgUnit("Foreign"), world.material("Withheld"), 1.0, null);
    fingerprint(order, false);
    UUID withholding = world.user("snapshot-withholding", false);
    jdbc.update(
        "INSERT INTO exchange_name_withholding (user_id, withhold_name) VALUES (?, true)",
        withholding);
    world.assignee(order, withholding, "withheld note");
    world.assignee(order, member, null);
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
      UUID order = materialOrder(other, own, world.material("Page " + i), 1.0, null);
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
            world.getClient(),
            ExchangeMetricNames.TAG_ROUTE,
            "snapshot");
    double before = reads.count();

    mockMvc.perform(relayed(get(PATH), READ)).andExpect(status().isOk());

    assertThat(reads.count()).isEqualTo(before + 1);
  }

  @Test
  void theFamilySwitchClosesTheRoute() throws Exception {
    world.switches(true, false, true);

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
    return world.relayed(request, member, capabilities);
  }

  private @NotNull UUID orgUnit(@NotNull String name) {
    return world.squadron(name, world.getProfit());
  }

  private @NotNull UUID materialOrder(
      @NotNull UUID responsible,
      @NotNull UUID requesting,
      @NotNull UUID material,
      double amount,
      @Nullable String comment) {
    return world
        .order()
        .requesting(requesting)
        .responsible(responsible)
        .handle("snapshot-requester")
        .comment(comment)
        .priority(null)
        .material(material, "NONE", amount)
        .create();
  }

  private @NotNull UUID auecOrder(@NotNull UUID responsible, @NotNull UUID requesting, int target) {
    return world
        .order()
        .requesting(requesting)
        .responsible(responsible)
        .handle("private auec contact")
        .priority(null)
        .auec(target)
        .create();
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
}

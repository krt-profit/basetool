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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeCapability;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClient;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClientRepository;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClientStatus;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeSettings;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeSettingsRepository;
import de.greluc.krt.profit.basetool.backend.exchange.internal.KnownExchangeClients;
import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.platform.api.ActingMemberHeader;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import de.greluc.krt.profit.basetool.backend.privacy.api.DataExportService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * A Logistician of a pool books stock in for a member of the unit, books out, sells and moves
 * another member's entry and edits its note and stolen marker through the exchange, with the
 * Lager's rules, audit, notifications and journal; every other pool, personal entry, stale version
 * and unlisted holder is refused per op (REQ-XCH-045, REQ-XCH-046, REQ-XCH-044).
 */
@SpringBootTest
class ExchangeOrgStockWriteControllerTest {

  private static final String CHANGES = "/api/v1/exchange/org-stock/changes";
  private static final String MEMBERS = "/api/v1/exchange/org-stock/members";
  private static final String TARGETS = "/api/v1/exchange/org-stock/targets";
  private static final String GATEWAY = "55555555-5555-5555-5555-555555555555";
  private static final String KEY = "Kx9_" + "w".repeat(39);
  private static final String WRITE = "exchange.orgstock.write";

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private ExchangeClientRepository clientRepository;
  @Autowired private ExchangeSettingsRepository settingsRepository;
  @Autowired private KnownExchangeClients knownClients;
  @Autowired private MeterRegistry meterRegistry;
  @Autowired private DataExportService dataExportService;
  @Autowired private JsonMapper jsonMapper;
  @Autowired private JdbcTemplate jdbc;

  private final List<UUID> users = new ArrayList<>();
  private final List<UUID> orgUnits = new ArrayList<>();
  private final List<UUID> materials = new ArrayList<>();
  private final List<UUID> locations = new ArrayList<>();
  private final List<UUID> missions = new ArrayList<>();
  private final List<UUID> jobOrders = new ArrayList<>();
  private final List<String> clients = new ArrayList<>();

  private MockMvc mockMvc;
  private UUID member;
  private UUID holder;
  private String client;
  private boolean wasEnabled;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy.class))
            .build();
    member = user("writer");
    holder = user("holder");
    client =
        register(
            EnumSet.of(
                ExchangeCapability.CONNECT,
                ExchangeCapability.ORGSTOCK_READ,
                ExchangeCapability.ORGSTOCK_DETAILS,
                ExchangeCapability.ORGSTOCK_WRITE));
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    wasEnabled = settings.isEnabled();
    settings.setEnabled(true);
    settingsRepository.saveAndFlush(settings);
  }

  @AfterEach
  void tearDown() {
    clients.forEach(id -> jdbc.update("DELETE FROM exchange_client WHERE client_id = ?", id));
    for (UUID id : users) {
      jdbc.update("DELETE FROM inventory_item WHERE user_id = ?", id);
      jdbc.update("DELETE FROM org_unit_membership WHERE user_id = ?", id);
      jdbc.update("DELETE FROM user_roles WHERE user_id = ?", id);
      jdbc.update("DELETE FROM exchange_name_withholding WHERE user_id = ?", id);
      jdbc.update("DELETE FROM notification WHERE recipient_user_id = ?", id);
    }
    jobOrders.forEach(id -> jdbc.update("DELETE FROM job_order WHERE id = ?", id));
    for (UUID id : missions) {
      jdbc.update(
          "DELETE FROM mission_finance_entry WHERE mission_participant_id IN"
              + " (SELECT id FROM mission_participant WHERE mission_id = ?)",
          id);
      jdbc.update("DELETE FROM mission_participant WHERE mission_id = ?", id);
      jdbc.update("DELETE FROM mission WHERE id = ?", id);
    }
    users.forEach(id -> jdbc.update("DELETE FROM app_user WHERE id = ?", id));
    orgUnits.reversed().forEach(id -> jdbc.update("DELETE FROM org_unit WHERE id = ?", id));
    materials.forEach(id -> jdbc.update("DELETE FROM material WHERE id = ?", id));
    locations.forEach(id -> jdbc.update("DELETE FROM location WHERE id = ?", id));
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    settings.setEnabled(wasEnabled);
    settingsRepository.saveAndFlush(settings);
  }

  @Test
  void aLogisticianBooksInForAnotherMemberAsTheWebDoesAndTheWriteIsAuditedAndJournaled()
      throws Exception {
    UUID staffel = orgUnit();
    joins(member, staffel, true);
    joins(holder, staffel, false);
    UUID titanium = material("SCU");
    UUID area18 = location();
    double applied = writes("applied");

    mockMvc
        .perform(
            relayed(post(CHANGES), WRITE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    ops(
                        bookIn(staffel, ref(staffel, holder), titanium, area18, "12.5", "SCU")
                            .replace("\"stolen\"", "\"note\":\"Hangar 3\",\"stolen\""))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(1))
        .andExpect(jsonPath("$.results.length()").value(0));

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT * FROM inventory_item WHERE user_id = ? AND material_id = ?", holder, titanium);
    assertThat(row.get("owning_org_unit_id")).isEqualTo(staffel);
    assertThat(row.get("personal")).isEqualTo(false);
    assertThat(((Number) row.get("amount")).doubleValue()).isEqualTo(12.5);
    assertThat(row.get("quality")).isEqualTo(500);
    assertThat(row.get("note")).isEqualTo("Hangar 3");
    Map<String, Object> audit =
        jdbc.queryForMap(
            "SELECT * FROM audit_event WHERE subject_id = ? AND event_type ="
                + " 'INVENTORY_ITEM_CREATED'",
            row.get("id"));
    assertThat(audit.get("actor_user_id")).isEqualTo(member);
    assertThat(audit.get("target_user_id")).isEqualTo(holder);
    assertThat(audit.get("client_id")).isEqualTo(client);
    assertThat(audit.get("details"))
        .isEqualTo("qty=12.5 q=500 personal=false jobOrder=- mission=-");
    Map<String, Object> journal =
        jdbc.queryForMap(
            "SELECT * FROM exchange_journal WHERE entity_key = ?", row.get("id").toString());
    assertThat(journal.get("user_id")).isEqualTo(member);
    assertThat(journal.get("resource")).isEqualTo("ORG_STOCK");
    assertThat(journal.get("action")).isEqualTo("ORG_STOCK_BOOK_IN");
    assertThat(journal.get("before_state")).isNull();
    assertThat((String) journal.get("after_state")).contains(holder.toString());
    assertThat(writes("applied")).isEqualTo(applied + 1);
  }

  @Test
  void aLogisticianBooksOutAnotherMembersEntryAndTheHolderIsTold() throws Exception {
    UUID staffel = orgUnit();
    joins(member, staffel, true);
    joins(holder, staffel, false);
    UUID entry = stock(holder, material("SCU"), location(), 10, staffel, false);

    mockMvc
        .perform(
            relayed(post(CHANGES), WRITE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(ops(bookOut(staffel, entry, 0, "4", "SCU"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(1));

    assertThat(amountOf(entry)).isEqualTo(6);
    Map<String, Object> audit =
        jdbc.queryForMap(
            "SELECT * FROM audit_event WHERE subject_id = ? AND event_type ="
                + " 'INVENTORY_ITEM_CONSUMED'",
            entry);
    assertThat(audit.get("actor_user_id")).isEqualTo(member);
    assertThat(audit.get("client_id")).isEqualTo(client);
    assertThat(notificationsOf(holder)).isPositive();
    Map<String, Object> journal =
        jdbc.queryForMap("SELECT * FROM exchange_journal WHERE entity_key = ?", entry.toString());
    assertThat(journal.get("action")).isEqualTo("ORG_STOCK_BOOK_OUT");
    assertThat(journal.get("removal")).isEqualTo(true);
    assertThat((String) journal.get("before_state")).contains("\"amount\":10.0");
    assertThat((String) journal.get("after_state")).contains("\"amount\":6.0");
  }

  @Test
  void theWritersDataExportCarriesTheJournalWithoutTheHolder() throws Exception {
    UUID staffel = orgUnit();
    joins(member, staffel, true);
    UUID entry = stock(holder, material("SCU"), location(), 10, staffel, false);
    mockMvc
        .perform(
            relayed(post(CHANGES), WRITE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(ops(bookOut(staffel, entry, 0, "4", "SCU"))))
        .andExpect(status().isOk());
    assertThat(
            (String)
                jdbc.queryForObject(
                    "SELECT before_state FROM exchange_journal WHERE entity_key = ?",
                    String.class,
                    entry.toString()))
        .contains(holder.toString());

    String export = jsonMapper.writeValueAsString(dataExportService.export(member));

    assertThat(export).contains("ORG_STOCK_BOOK_OUT").doesNotContain(holder.toString());
  }

  @Test
  void bookingOutTheWholeEntryDeletesItAndJournalsNoAfterState() throws Exception {
    UUID staffel = orgUnit();
    joins(member, staffel, true);
    UUID entry = stock(holder, material("SCU"), location(), 3, staffel, false);

    mockMvc
        .perform(
            relayed(post(CHANGES), WRITE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(ops(bookOut(staffel, entry, 0, "3", "SCU"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(1));

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inventory_item WHERE id = ?", Long.class, entry))
        .isZero();
    assertThat(
            jdbc.queryForMap(
                    "SELECT * FROM exchange_journal WHERE entity_key = ?", entry.toString())
                .get("after_state"))
        .isNull();
  }

  @Test
  void anOpOutsideTheWritersLogisticianPoolsOrOnAPersonalRowIsRefusedAndChangesNothing()
      throws Exception {
    UUID own = orgUnit();
    UUID plain = orgUnit();
    UUID other = orgUnit();
    joins(member, own, true);
    joins(member, plain, false);
    joins(holder, own, false);
    joins(holder, plain, false);
    UUID titanium = material("SCU");
    UUID area18 = location();
    UUID inPlain = stock(holder, titanium, area18, 5, plain, false);
    UUID inOther = stock(holder, titanium, area18, 5, other, false);
    UUID personal = stock(holder, titanium, area18, 5, own, true);

    mockMvc
        .perform(
            relayed(post(CHANGES), WRITE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    ops(
                        bookIn(plain, ref(own, holder), titanium, area18, "1", "SCU"),
                        bookOut(plain, inPlain, 0, "1", "SCU"),
                        bookOut(own, inOther, 0, "1", "SCU"),
                        bookOut(other, inOther, 0, "1", "SCU"),
                        bookOut(own, personal, 0, "1", "SCU"),
                        bookOut(own, UUID.randomUUID(), 0, "1", "SCU"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(0))
        .andExpect(jsonPath("$.notApplied").value(6))
        .andExpect(jsonPath("$.results[*].reason", everyItem(is("NOT_PERMITTED"))));

    assertThat(amountOf(inPlain)).isEqualTo(5);
    assertThat(amountOf(inOther)).isEqualTo(5);
    assertThat(amountOf(personal)).isEqualTo(5);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inventory_item WHERE user_id = ?", Long.class, holder))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM exchange_journal WHERE user_id = ?", Long.class, member))
        .isZero();
  }

  @Test
  void aBookInForAWithholdingMemberOrANonMemberIsRefused() throws Exception {
    UUID staffel = orgUnit();
    UUID elsewhere = orgUnit();
    joins(member, staffel, true);
    joins(member, elsewhere, true);
    joins(holder, staffel, false);
    UUID outsider = user("outsider");
    joins(outsider, elsewhere, false);
    String holderRef = ref(staffel, holder);
    String outsiderRef = ref(elsewhere, outsider);
    withholds(holder, true);
    UUID titanium = material("SCU");
    UUID area18 = location();

    mockMvc
        .perform(
            relayed(post(CHANGES), WRITE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    ops(
                        bookIn(staffel, holderRef, titanium, area18, "1", "SCU"),
                        bookIn(staffel, outsiderRef, titanium, area18, "1", "SCU"),
                        bookIn(staffel, "not-a-ref", titanium, area18, "1", "SCU"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(0))
        .andExpect(jsonPath("$.results.length()").value(3))
        .andExpect(jsonPath("$.results[*].reason", everyItem(is("HOLDER_UNKNOWN"))));

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inventory_item WHERE material_id = ?", Long.class, titanium))
        .isZero();
  }

  @Test
  void theRosterListsTheUnitsDirectMembersByNameWithoutThoseWhoWithholdTheirName()
      throws Exception {
    UUID staffel = orgUnit();
    joins(member, staffel, true);
    joins(holder, staffel, false);
    UUID hidden = user("hidden");
    joins(hidden, staffel, false);
    withholds(hidden, true);
    jdbc.update("UPDATE app_user SET display_name = 'Zulu' WHERE id = ?", member);
    jdbc.update("UPDATE app_user SET display_name = 'Alpha' WHERE id = ?", holder);

    String body = roster(staffel);

    assertThat(JsonPath.<String>read(body, "$.unit.id")).isEqualTo(staffel.toString());
    assertThat(JsonPath.<List<String>>read(body, "$.items[*].name"))
        .containsExactly("Alpha", "Zulu");
    assertThat(JsonPath.<List<String>>read(body, "$.items[*].ref"))
        .allSatisfy(ref -> assertThat(ref).hasSize(43))
        .doesNotContain(hidden.toString(), holder.toString());
    assertThat(body).doesNotContain(hidden.toString());
  }

  @Test
  void theRosterOfAUnitTheWriterIsNoLogisticianOfIsRefused() throws Exception {
    UUID plain = orgUnit();
    joins(member, plain, false);

    mockMvc
        .perform(relayed(get(MEMBERS).param("unit", plain.toString()), WRITE))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(relayed(get(MEMBERS).param("unit", UUID.randomUUID().toString()), WRITE))
        .andExpect(status().isForbidden());
  }

  @Test
  void aStaleVersionIsAVersionConflictAndChangesNothing() throws Exception {
    UUID staffel = orgUnit();
    joins(member, staffel, true);
    UUID entry = stock(holder, material("SCU"), location(), 10, staffel, false);

    mockMvc
        .perform(
            relayed(post(CHANGES), WRITE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    ops(
                        bookOut(staffel, entry, 0, "1", "SCU"),
                        bookOut(staffel, entry, 0, "1", "SCU"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(1))
        .andExpect(jsonPath("$.results[0].index").value(1))
        .andExpect(jsonPath("$.results[0].result").value("rejected"))
        .andExpect(jsonPath("$.results[0].reason").value("VERSION_CONFLICT"));

    assertThat(amountOf(entry)).isEqualTo(9);
  }

  @Test
  void earmarkedStockIsProtectedUnlessTheBookOutNamesTheEarmark() throws Exception {
    UUID staffel = orgUnit();
    joins(member, staffel, true);
    UUID titanium = material("SCU");
    UUID entry = stock(holder, titanium, location(), 10, staffel, false);
    UUID order = earmarkToJobOrder(entry, 6, staffel);

    mockMvc
        .perform(
            relayed(post(CHANGES), WRITE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(ops(bookOut(staffel, entry, 0, "5", "SCU"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.results[0].reason").value("STOCK_EARMARKED"));
    assertThat(amountOf(entry)).isEqualTo(10);

    mockMvc
        .perform(
            relayed(post(CHANGES), WRITE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    ops(
                        bookOut(staffel, entry, 0, "5", "SCU")
                            .replace(
                                "}}",
                                "},\"fromJobOrders\":[{\"jobOrder\":\""
                                    + order
                                    + "\",\"quantity\":{\"amount\":1,\"unit\":\"SCU\"}}]}"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(1));
    assertThat(amountOf(entry)).isEqualTo(5);
    assertThat(
            jdbc.queryForObject(
                "SELECT amount FROM inventory_item_job_order_allocation WHERE inventory_item_id ="
                    + " ?",
                Double.class,
                entry))
        .isEqualTo(5);
  }

  @Test
  void aBookInCarriesItsEarmarksAndRefusesMoreThanItsAmount() throws Exception {
    UUID staffel = orgUnit();
    joins(member, staffel, true);
    joins(holder, staffel, false);
    UUID titanium = material("SCU");
    UUID area18 = location();
    UUID mission = mission();
    String earmark =
        ",\"missions\":[{\"mission\":\""
            + mission
            + "\",\"quantity\":{\"amount\":AMOUNT,\"unit\":\"SCU\"}}]}";
    String ref = ref(staffel, holder);

    mockMvc
        .perform(
            relayed(post(CHANGES), WRITE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    ops(
                        withSuffix(
                            bookIn(staffel, ref, titanium, area18, "4", "SCU"),
                            earmark.replace("AMOUNT", "3")),
                        withSuffix(
                            bookIn(staffel, ref, titanium, area18, "2", "SCU"),
                            earmark.replace("AMOUNT", "3")))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(1))
        .andExpect(jsonPath("$.results[0].index").value(1))
        .andExpect(jsonPath("$.results[0].reason").value("OVER_ALLOCATION"));

    assertThat(
            jdbc.queryForObject(
                """
                SELECT a.amount FROM inventory_item_mission_allocation a
                JOIN inventory_item i ON i.id = a.inventory_item_id
                WHERE i.user_id = ? AND a.mission_id = ?
                """,
                Double.class,
                holder,
                mission))
        .isEqualTo(3);
  }

  @Test
  void theScuMergeOptInFoldsTheBookInIntoTheHoldersEntry() throws Exception {
    UUID staffel = orgUnit();
    joins(member, staffel, true);
    joins(holder, staffel, false);
    UUID titanium = material("SCU");
    UUID area18 = location();
    UUID existing = stock(holder, titanium, area18, 5, staffel, false);
    jdbc.update("UPDATE inventory_item SET quality = 500 WHERE id = ?", existing);

    mockMvc
        .perform(
            relayed(post(CHANGES), WRITE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    ops(
                        bookIn(staffel, ref(staffel, holder), titanium, area18, "3", "SCU")
                            .replace("\"stolen\"", "\"mergeScu\":true,\"stolen\""))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(1));

    assertThat(
            jdbc.queryForList(
                "SELECT amount FROM inventory_item WHERE user_id = ? AND material_id = ?",
                Double.class,
                holder,
                titanium))
        .containsExactly(8.0);
    assertThat(
            jdbc.queryForMap(
                "SELECT * FROM exchange_journal WHERE entity_key = ?", existing.toString()))
        .satisfies(
            journal -> {
              assertThat((String) journal.get("before_state")).contains("\"amount\":5.0");
              assertThat(journal.get("after_state")).isNull();
            });
    assertThat(
            jdbc.queryForList(
                "SELECT after_state FROM exchange_journal WHERE user_id = ? AND before_state IS"
                    + " NULL",
                String.class,
                member))
        .singleElement()
        .satisfies(after -> assertThat(after).contains("\"amount\":8.0"));
  }

  @Test
  void aDryRunWritesNothing() throws Exception {
    UUID staffel = orgUnit();
    joins(member, staffel, true);
    UUID entry = stock(holder, material("SCU"), location(), 10, staffel, false);

    mockMvc
        .perform(
            relayed(post(CHANGES), WRITE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"dryRun\":true,\"ops\":[" + bookOut(staffel, entry, 0, "4", "SCU") + "]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dryRun").value(true))
        .andExpect(jsonPath("$.applied").value(1));

    assertThat(amountOf(entry)).isEqualTo(10);
  }

  @Test
  void withoutTheWriteCapabilityTheRoutesAreRefused() throws Exception {
    UUID staffel = orgUnit();
    joins(member, staffel, true);

    mockMvc
        .perform(
            relayed(post(CHANGES), "exchange.orgstock.read")
                .contentType(MediaType.APPLICATION_JSON)
                .content(ops(bookOut(staffel, UUID.randomUUID(), 0, "1", "SCU"))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SCOPE_MISSING"));
    mockMvc
        .perform(relayed(get(MEMBERS).param("unit", staffel.toString()), "exchange.orgstock.read"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SCOPE_MISSING"));
  }

  @Test
  void aSaleOfAnotherMembersEntryBooksTheMissionShareOfTheProceedsAsTheWebDoes() throws Exception {
    UUID staffel = orgUnit();
    joins(member, staffel, true);
    joins(holder, staffel, false);
    UUID entry = stock(holder, material("SCU"), location(), 10, staffel, false);
    UUID mission = mission();
    earmarkToMission(entry, mission, 4);
    UUID participant = participates(mission, member);

    mockMvc
        .perform(
            relayed(post(CHANGES), WRITE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    ops(
                        entryOp(
                            "sell",
                            staffel,
                            entry,
                            0,
                            ",\"quantity\":{\"amount\":4,\"unit\":\"SCU\"},\"terminal\":\"TDD"
                                + " Area18\",\"proceeds\":1000,\"fromMissions\":[{\"mission\":\""
                                + mission
                                + "\",\"quantity\":{\"amount\":4,\"unit\":\"SCU\"}}]"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(1));

    assertThat(amountOf(entry)).isEqualTo(6);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inventory_item_mission_allocation WHERE inventory_item_id ="
                    + " ?",
                Long.class,
                entry))
        .isZero();
    Map<String, Object> finance =
        jdbc.queryForMap(
            "SELECT * FROM mission_finance_entry WHERE mission_participant_id = ?", participant);
    assertThat(finance.get("type")).isEqualTo("INCOME");
    assertThat(((Number) finance.get("amount")).doubleValue()).isEqualTo(1000);
    Map<String, Object> audit =
        jdbc.queryForMap(
            "SELECT * FROM audit_event WHERE subject_id = ? AND event_type = 'INVENTORY_ITEM_SOLD'",
            entry);
    assertThat(audit.get("actor_user_id")).isEqualTo(member);
    assertThat(audit.get("target_user_id")).isEqualTo(holder);
    assertThat(audit.get("client_id")).isEqualTo(client);
    assertThat(notificationsOf(holder)).isPositive();
    Map<String, Object> journal =
        jdbc.queryForMap("SELECT * FROM exchange_journal WHERE entity_key = ?", entry.toString());
    assertThat(journal.get("action")).isEqualTo("ORG_STOCK_SELL");
    assertThat(journal.get("removal")).isEqualTo(true);
  }

  @Test
  void aMoveToAnotherLocationKeepsHolderAndPoolAndIsAuditedAsATransfer() throws Exception {
    UUID staffel = orgUnit();
    joins(member, staffel, true);
    joins(holder, staffel, false);
    UUID titanium = material("SCU");
    UUID entry = stock(holder, titanium, location(), 10, staffel, false);
    UUID hangar = location();
    long feed = feedRowsOf(staffel);

    mockMvc
        .perform(
            relayed(post(CHANGES), WRITE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    ops(
                        entryOp(
                            "move-location",
                            staffel,
                            entry,
                            0,
                            ",\"quantity\":{\"amount\":4,\"unit\":\"SCU\"},\"location\":{\"name\":"
                                + "\"Writes "
                                + hangar
                                + "\"}"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(1));

    assertThat(amountOf(entry)).isEqualTo(6);
    Map<String, Object> moved =
        jdbc.queryForMap("SELECT * FROM inventory_item WHERE location_id = ?", hangar);
    assertThat(moved.get("user_id")).isEqualTo(holder);
    assertThat(moved.get("owning_org_unit_id")).isEqualTo(staffel);
    assertThat(moved.get("personal")).isEqualTo(false);
    assertThat(((Number) moved.get("amount")).doubleValue()).isEqualTo(4);
    Map<String, Object> audit =
        jdbc.queryForMap(
            "SELECT * FROM audit_event WHERE subject_id = ? AND event_type ="
                + " 'INVENTORY_ITEM_TRANSFERRED'",
            entry);
    assertThat(audit.get("actor_user_id")).isEqualTo(member);
    assertThat(audit.get("client_id")).isEqualTo(client);
    assertThat(
            jdbc.queryForList(
                "SELECT action FROM exchange_journal WHERE user_id = ? AND removal = false",
                String.class,
                member))
        .containsExactly("ORG_STOCK_MOVE_LOCATION", "ORG_STOCK_MOVE_LOCATION");
    assertThat(feedRowsOf(staffel)).isGreaterThan(feed);
  }

  @Test
  void aMoveToAMemberOfAnotherLogisticianUnitLandsInThatUnitsPoolAndTellsBothHolders()
      throws Exception {
    UUID staffel = orgUnit();
    UUID other = orgUnit();
    joins(member, staffel, true);
    joins(member, other, true);
    joins(holder, staffel, false);
    UUID receiver = user("receiver");
    joins(receiver, other, false);
    UUID titanium = material("SCU");
    UUID area18 = location();
    UUID entry = stock(holder, titanium, area18, 10, staffel, false);

    mockMvc
        .perform(
            relayed(post(CHANGES), WRITE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(ops(moveHolder(staffel, entry, "4", other, ref(other, receiver)))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(1));

    assertThat(amountOf(entry)).isEqualTo(6);
    Map<String, Object> moved =
        jdbc.queryForMap(
            "SELECT * FROM inventory_item WHERE user_id = ? AND material_id = ?",
            receiver,
            titanium);
    assertThat(moved.get("owning_org_unit_id")).isEqualTo(other);
    assertThat(moved.get("location_id")).isEqualTo(area18);
    assertThat(((Number) moved.get("amount")).doubleValue()).isEqualTo(4);
    assertThat(
            jdbc.queryForMap(
                    "SELECT * FROM audit_event WHERE subject_id = ? AND event_type ="
                        + " 'INVENTORY_ITEM_TRANSFERRED'",
                    entry)
                .get("target_user_id"))
        .isEqualTo(receiver);
    assertThat(notificationsOf(holder)).isPositive();
    assertThat(notificationsOf(receiver)).isPositive();
    Map<String, Object> source =
        jdbc.queryForMap("SELECT * FROM exchange_journal WHERE entity_key = ?", entry.toString());
    assertThat(source.get("action")).isEqualTo("ORG_STOCK_MOVE_HOLDER");
    assertThat(source.get("removal")).isEqualTo(true);
    assertThat(
            jdbc.queryForMap(
                    "SELECT * FROM exchange_journal WHERE entity_key = ?",
                    moved.get("id").toString())
                .get("removal"))
        .isEqualTo(false);
  }

  @Test
  void aMoveToAMemberOutsideTheWritersLogisticianUnitsOrWhoWithholdsTheirNameIsRefused()
      throws Exception {
    UUID staffel = orgUnit();
    UUID plain = orgUnit();
    joins(member, staffel, true);
    joins(member, plain, false);
    joins(holder, staffel, false);
    UUID outsider = user("outsider");
    joins(outsider, plain, false);
    UUID hidden = user("hidden");
    joins(hidden, staffel, false);
    String hiddenRef = ref(staffel, hidden);
    withholds(hidden, true);
    UUID entry = stock(holder, material("SCU"), location(), 10, staffel, false);

    mockMvc
        .perform(
            relayed(post(CHANGES), WRITE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    ops(
                        moveHolder(staffel, entry, "1", plain, "any-ref"),
                        moveHolder(staffel, entry, "1", staffel, hiddenRef),
                        moveHolder(staffel, entry, "1", staffel, "not-a-ref"),
                        moveHolder(staffel, entry, "1", staffel, ref(staffel, holder)))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(0))
        .andExpect(jsonPath("$.unchanged").value(1))
        .andExpect(jsonPath("$.results[0].reason").value("NOT_PERMITTED"))
        .andExpect(jsonPath("$.results[1].reason").value("HOLDER_UNKNOWN"))
        .andExpect(jsonPath("$.results[2].reason").value("HOLDER_UNKNOWN"))
        .andExpect(jsonPath("$.results[3].result").value("unchanged"));

    assertThat(amountOf(entry)).isEqualTo(10);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inventory_item WHERE user_id IN (?, ?)",
                Long.class,
                outsider,
                hidden))
        .isZero();
  }

  @Test
  void aMoveOfPieceStockMergesIntoTheReceiversEntryAndJournalsTheFoldedEntry() throws Exception {
    UUID staffel = orgUnit();
    UUID other = orgUnit();
    joins(member, staffel, true);
    joins(member, other, true);
    UUID receiver = user("receiver");
    joins(receiver, other, false);
    UUID beacon = material("PIECE");
    UUID area18 = location();
    UUID entry = stock(holder, beacon, area18, 5, staffel, false);
    UUID existing = stock(receiver, beacon, area18, 3, other, false);

    mockMvc
        .perform(
            relayed(post(CHANGES), WRITE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    ops(
                        moveHolder(staffel, entry, "2", other, ref(other, receiver))
                            .replace("\"SCU\"", "\"PIECE\""))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(1));

    assertThat(
            jdbc.queryForList(
                "SELECT amount FROM inventory_item WHERE user_id = ? AND material_id = ?",
                Double.class,
                receiver,
                beacon))
        .containsExactly(5.0);
    assertThat(amountOf(entry)).isEqualTo(3);
    assertThat(
            jdbc.queryForMap(
                    "SELECT * FROM exchange_journal WHERE entity_key = ?", existing.toString())
                .get("after_state"))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM exchange_journal WHERE user_id = ?", Long.class, member))
        .isEqualTo(3);
  }

  @Test
  void aMovePoolMovesTheEntryIntoAnotherPoolOfItsHolderAndTellsThem() throws Exception {
    UUID staffel = orgUnit();
    UUID other = orgUnit();
    joins(member, staffel, true);
    joins(member, other, true);
    joins(holder, staffel, false);
    joins(holder, other, false);
    UUID whole = stock(holder, material("SCU"), location(), 10, staffel, false);
    UUID split = stock(holder, material("SCU"), location(), 10, staffel, false);

    mockMvc
        .perform(
            relayed(post(CHANGES), WRITE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    ops(
                        entryOp("move-pool", staffel, whole, 0, ",\"toUnit\":\"" + other + "\""),
                        entryOp(
                            "move-pool",
                            staffel,
                            split,
                            0,
                            ",\"toUnit\":\""
                                + other
                                + "\",\"quantity\":{\"amount\":4,\"unit\":\"SCU\"}"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(2));

    assertThat(
            jdbc.queryForObject(
                "SELECT owning_org_unit_id FROM inventory_item WHERE id = ?", UUID.class, whole))
        .isEqualTo(other);
    assertThat(amountOf(split)).isEqualTo(6);
    assertThat(
            jdbc.queryForList(
                "SELECT amount FROM inventory_item WHERE user_id = ? AND owning_org_unit_id = ?"
                    + " AND id <> ?",
                Double.class,
                holder,
                other,
                whole))
        .containsExactly(4.0);
    Map<String, Object> audit =
        jdbc.queryForMap(
            "SELECT * FROM audit_event WHERE subject_id = ? AND event_type ="
                + " 'INVENTORY_POOL_CHANGED'",
            whole);
    assertThat(audit.get("actor_user_id")).isEqualTo(member);
    assertThat(audit.get("client_id")).isEqualTo(client);
    assertThat(notificationsOf(holder)).isPositive();
    Map<String, Object> journal =
        jdbc.queryForMap("SELECT * FROM exchange_journal WHERE entity_key = ?", whole.toString());
    assertThat(journal.get("action")).isEqualTo("ORG_STOCK_MOVE_POOL");
    assertThat(journal.get("removal")).isEqualTo(true);
    assertThat((String) journal.get("after_state")).contains(other.toString());
  }

  @Test
  void aMovePoolOutsideTheWritersUnitsOrTheHoldersUnitsIsRefused() throws Exception {
    UUID staffel = orgUnit();
    UUID plain = orgUnit();
    UUID foreign = orgUnit();
    joins(member, staffel, true);
    joins(member, plain, false);
    joins(member, foreign, true);
    joins(holder, staffel, false);
    joins(holder, plain, false);
    UUID entry = stock(holder, material("SCU"), location(), 10, staffel, false);

    mockMvc
        .perform(
            relayed(post(CHANGES), WRITE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    ops(
                        entryOp("move-pool", staffel, entry, 0, ",\"toUnit\":\"" + plain + "\""),
                        entryOp("move-pool", staffel, entry, 0, ",\"toUnit\":\"" + foreign + "\""),
                        entryOp("move-pool", staffel, entry, 0, ",\"toUnit\":\"" + staffel + "\""),
                        moveHolder(staffel, entry, "1", foreign, ref(staffel, holder)))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(0))
        .andExpect(jsonPath("$.unchanged").value(1))
        .andExpect(jsonPath("$.results[0].reason").value("NOT_PERMITTED"))
        .andExpect(jsonPath("$.results[1].reason").value("HOLDER_UNKNOWN"))
        .andExpect(jsonPath("$.results[2].result").value("unchanged"))
        .andExpect(jsonPath("$.results[3].reason").value("HOLDER_UNKNOWN"));

    assertThat(
            jdbc.queryForObject(
                "SELECT owning_org_unit_id FROM inventory_item WHERE id = ?", UUID.class, entry))
        .isEqualTo(staffel);
  }

  @Test
  void aNoteEditIsAuditedAndTheSameNoteAgainIsUnchanged() throws Exception {
    UUID staffel = orgUnit();
    joins(member, staffel, true);
    UUID entry = stock(holder, material("SCU"), location(), 10, staffel, false);

    mockMvc
        .perform(
            relayed(post(CHANGES), WRITE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    ops(
                        entryOp("note", staffel, entry, 0, ",\"note\":\"Hangar 7\""),
                        entryOp("note", staffel, entry, 1, ",\"note\":\" Hangar 7 \""))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(1))
        .andExpect(jsonPath("$.unchanged").value(1));

    assertThat(
            jdbc.queryForObject(
                "SELECT note FROM inventory_item WHERE id = ?", String.class, entry))
        .isEqualTo("Hangar 7");
    Map<String, Object> audit =
        jdbc.queryForMap(
            "SELECT * FROM audit_event WHERE subject_id = ? AND event_type ="
                + " 'INVENTORY_ITEM_NOTE_UPDATED'",
            entry);
    assertThat(audit.get("actor_user_id")).isEqualTo(member);
    assertThat(audit.get("client_id")).isEqualTo(client);
    Map<String, Object> journal =
        jdbc.queryForMap("SELECT * FROM exchange_journal WHERE entity_key = ?", entry.toString());
    assertThat(journal.get("action")).isEqualTo("ORG_STOCK_NOTE");
    assertThat((String) journal.get("after_state")).doesNotContain("Hangar 7");

    mockMvc
        .perform(
            relayed(post(CHANGES), WRITE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(ops(entryOp("note", staffel, entry, 1, ""))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(1));
    assertThat(
            jdbc.queryForObject(
                "SELECT note FROM inventory_item WHERE id = ?", String.class, entry))
        .isNull();
  }

  @Test
  void aStolenMarkingChangesTheWholeEntryOrSplitsOffAPart() throws Exception {
    UUID staffel = orgUnit();
    joins(member, staffel, true);
    UUID titanium = material("SCU");
    UUID area18 = location();
    UUID whole = stock(holder, titanium, area18, 5, staffel, false);
    UUID split = stock(holder, material("SCU"), area18, 10, staffel, false);

    mockMvc
        .perform(
            relayed(post(CHANGES), WRITE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    ops(
                        entryOp("stolen", staffel, whole, 0, ",\"stolen\":true"),
                        entryOp(
                            "stolen",
                            staffel,
                            split,
                            0,
                            ",\"stolen\":true,\"quantity\":{\"amount\":3,\"unit\":\"SCU\"}"),
                        entryOp("stolen", staffel, whole, 1, ",\"stolen\":true"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(2))
        .andExpect(jsonPath("$.unchanged").value(1));

    assertThat(
            jdbc.queryForObject(
                "SELECT stolen FROM inventory_item WHERE id = ?", Boolean.class, whole))
        .isTrue();
    assertThat(amountOf(split)).isEqualTo(7);
    assertThat(
            jdbc.queryForList(
                "SELECT amount FROM inventory_item WHERE id <> ? AND stolen AND user_id = ? AND"
                    + " material_id <> ?",
                Double.class,
                split,
                holder,
                titanium))
        .containsExactly(3.0);
    assertThat(
            jdbc.queryForMap(
                    "SELECT * FROM audit_event WHERE subject_id = ? AND event_type ="
                        + " 'INVENTORY_STOLEN_MARKED'",
                    whole)
                .get("client_id"))
        .isEqualTo(client);
    assertThat(
            jdbc.queryForList(
                "SELECT action FROM exchange_journal WHERE user_id = ?", String.class, member))
        .hasSize(3)
        .containsOnly("ORG_STOCK_STOLEN");
  }

  @Test
  void aPartialStolenMarkingThatWouldLeaveEarmarksUncoveredIsRefused() throws Exception {
    UUID staffel = orgUnit();
    joins(member, staffel, true);
    UUID entry = stock(holder, material("SCU"), location(), 10, staffel, false);
    earmarkToJobOrder(entry, 8, staffel);

    mockMvc
        .perform(
            relayed(post(CHANGES), WRITE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    ops(
                        entryOp(
                            "stolen",
                            staffel,
                            entry,
                            0,
                            ",\"stolen\":true,\"quantity\":{\"amount\":3,\"unit\":\"SCU\"}"),
                        entryOp(
                            "stolen",
                            staffel,
                            entry,
                            0,
                            ",\"stolen\":true,\"quantity\":{\"amount\":11,\"unit\":\"SCU\"}"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(0))
        .andExpect(jsonPath("$.results[0].reason").value("STOCK_EARMARKED"))
        .andExpect(jsonPath("$.results[1].reason").value("INSUFFICIENT_STOCK"));

    assertThat(amountOf(entry)).isEqualTo(10);
  }

  @Test
  void theNewOpsAreRefusedOutsideTheWritersPoolsAndOnStaleVersions() throws Exception {
    UUID staffel = orgUnit();
    UUID plain = orgUnit();
    joins(member, staffel, true);
    joins(member, plain, false);
    UUID inPlain = stock(holder, material("SCU"), location(), 5, plain, false);
    UUID entry = stock(holder, material("SCU"), location(), 5, staffel, false);
    String sale =
        ",\"quantity\":{\"amount\":1,\"unit\":\"SCU\"},\"terminal\":\"T\",\"proceeds\":10";

    mockMvc
        .perform(
            relayed(post(CHANGES), WRITE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    ops(
                        entryOp("sell", plain, inPlain, 0, sale),
                        entryOp("note", plain, inPlain, 0, ",\"note\":\"x\""),
                        entryOp("stolen", plain, inPlain, 0, ",\"stolen\":true"),
                        entryOp("sell", staffel, inPlain, 0, sale),
                        entryOp("sell", staffel, entry, 3, sale),
                        entryOp("note", staffel, entry, 3, ",\"note\":\"x\""))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(0))
        .andExpect(jsonPath("$.results[0].reason").value("NOT_PERMITTED"))
        .andExpect(jsonPath("$.results[1].reason").value("NOT_PERMITTED"))
        .andExpect(jsonPath("$.results[2].reason").value("NOT_PERMITTED"))
        .andExpect(jsonPath("$.results[3].reason").value("NOT_PERMITTED"))
        .andExpect(jsonPath("$.results[4].reason").value("VERSION_CONFLICT"))
        .andExpect(jsonPath("$.results[5].reason").value("VERSION_CONFLICT"));

    assertThat(amountOf(inPlain)).isEqualTo(5);
    assertThat(amountOf(entry)).isEqualTo(5);
  }

  @Test
  void aLogisticianEarmarksAnotherMembersEntryAsTheWebDoesAndTheChangeIsAuditedJournaledAndFed()
      throws Exception {
    UUID staffel = orgUnit();
    joins(member, staffel, true);
    joins(holder, staffel, false);
    UUID titanium = material("SCU");
    UUID entry = stock(holder, titanium, location(), 10, staffel, false);
    UUID order = jobOrder(staffel, titanium, "OPEN");
    UUID mission = mission();
    long feed = feedRowsOf(staffel);

    mockMvc
        .perform(
            relayed(post(CHANGES), WRITE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    ops(
                        allocation("allocation-add", staffel, entry, 0, toJobOrder(order), "4"),
                        allocation("allocation-add", staffel, entry, 1, toMission(mission), "3"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(2))
        .andExpect(jsonPath("$.results.length()").value(0));

    assertThat(jobOrderEarmark(entry, order)).isEqualTo(4);
    assertThat(missionEarmark(entry, mission)).isEqualTo(3);
    assertThat(versionOf(entry)).isEqualTo(2);
    List<Map<String, Object>> audits =
        jdbc.queryForList(
            "SELECT * FROM audit_event WHERE subject_id = ? AND event_type ="
                + " 'INVENTORY_ALLOCATION_ADDED' ORDER BY details",
            entry);
    assertThat(audits).hasSize(2);
    assertThat(audits)
        .allSatisfy(
            audit -> {
              assertThat(audit.get("actor_user_id")).isEqualTo(member);
              assertThat(audit.get("target_user_id")).isEqualTo(holder);
              assertThat(audit.get("client_id")).isEqualTo(client);
            });
    assertThat(audits.getFirst().get("details")).asString().contains("dim=JOB_ORDER");
    assertThat(
            jdbc.queryForList(
                "SELECT action FROM exchange_journal WHERE entity_key = ? AND removal = false",
                String.class,
                entry.toString()))
        .containsOnly("ORG_STOCK_EARMARK_ADD")
        .hasSize(2);
    assertThat(
            jdbc.queryForList(
                "SELECT after_state FROM exchange_journal WHERE entity_key = ?",
                String.class,
                entry.toString()))
        .anySatisfy(after -> assertThat(after).contains(order.toString()).contains("4.0"));
    assertThat(feedRowsOf(staffel)).isGreaterThan(feed);
  }

  @Test
  void aLogisticianChangesAndRemovesEarmarksAndAnEditThatFindsTheEntryAsAskedIsUnchanged()
      throws Exception {
    UUID staffel = orgUnit();
    joins(member, staffel, true);
    UUID entry = stock(holder, material("SCU"), location(), 10, staffel, false);
    UUID order = earmarkToJobOrder(entry, 6, staffel);
    UUID mission = mission();
    earmarkToMission(entry, mission, 2);
    long feed = feedRowsOf(staffel);

    mockMvc
        .perform(
            relayed(post(CHANGES), WRITE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    ops(
                        allocation("allocation-change", staffel, entry, 0, toJobOrder(order), "3"),
                        allocation("allocation-change", staffel, entry, 1, toMission(mission), "2"),
                        allocation(
                            "allocation-remove", staffel, entry, 1, toMission(mission), null),
                        allocation(
                            "allocation-remove", staffel, entry, 2, toMission(mission), null))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(2))
        .andExpect(jsonPath("$.unchanged").value(2))
        .andExpect(jsonPath("$.results[0].index").value(1))
        .andExpect(jsonPath("$.results[0].result").value("unchanged"))
        .andExpect(jsonPath("$.results[1].index").value(3))
        .andExpect(jsonPath("$.results[1].result").value("unchanged"));

    assertThat(jobOrderEarmark(entry, order)).isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inventory_item_mission_allocation WHERE inventory_item_id ="
                    + " ?",
                Long.class,
                entry))
        .isZero();
    assertThat(
            jdbc.queryForList(
                "SELECT event_type FROM audit_event WHERE subject_id = ? AND event_type LIKE"
                    + " 'INVENTORY_ALLOCATION_%'",
                String.class, entry))
        .containsExactlyInAnyOrder("INVENTORY_ALLOCATION_CHANGED", "INVENTORY_ALLOCATION_REMOVED");
    assertThat(
            jdbc.queryForList(
                "SELECT action FROM exchange_journal WHERE entity_key = ?",
                String.class,
                entry.toString()))
        .containsExactlyInAnyOrder("ORG_STOCK_EARMARK_CHANGE", "ORG_STOCK_EARMARK_REMOVE");
    assertThat(feedRowsOf(staffel)).isGreaterThan(feed);
  }

  @Test
  void aChangeToTheAmountAnEarmarkHasIsUnchangedEvenOnAnOverAllocatedEntry() throws Exception {
    UUID staffel = orgUnit();
    joins(member, staffel, true);
    UUID entry = stock(holder, material("SCU"), location(), 4, staffel, false);
    UUID order = earmarkToJobOrder(entry, 6, staffel);

    mockMvc
        .perform(
            relayed(post(CHANGES), WRITE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    ops(
                        allocation("allocation-change", staffel, entry, 0, toJobOrder(order), "6"),
                        allocation("allocation-change", staffel, entry, 0, toJobOrder(order), "5"),
                        allocation(
                            "allocation-change", staffel, entry, 0, toJobOrder(order), "3"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(1))
        .andExpect(jsonPath("$.results[0].result").value("unchanged"))
        .andExpect(jsonPath("$.results[1].reason").value("OVER_ALLOCATION"));

    assertThat(jobOrderEarmark(entry, order)).isEqualTo(3);
  }

  @Test
  void aMoveTakesTheEarmarksItsPlanNamesAlongAndAPlanOnAMissingEarmarkIsRefused() throws Exception {
    UUID staffel = orgUnit();
    joins(member, staffel, true);
    UUID titanium = material("SCU");
    UUID entry = stock(holder, titanium, location(), 10, staffel, false);
    UUID order = earmarkToJobOrder(entry, 6, staffel);
    UUID elsewhere = location();
    String plan =
        ",\"fromJobOrders\":[{\"jobOrder\":\"ORDER\",\"quantity\":{\"amount\":4,\"unit\":\"SCU\"}}]";
    String move =
        ",\"quantity\":{\"amount\":8,\"unit\":\"SCU\"},\"location\":{\"name\":\"Writes "
            + elsewhere
            + "\"}";

    mockMvc
        .perform(
            relayed(post(CHANGES), WRITE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    ops(
                        entryOp(
                            "move-location",
                            staffel,
                            entry,
                            0,
                            move + plan.replace("ORDER", UUID.randomUUID().toString())),
                        entryOp(
                            "move-location",
                            staffel,
                            entry,
                            0,
                            move + plan.replace("ORDER", order.toString())))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(1))
        .andExpect(jsonPath("$.results[0].index").value(0))
        .andExpect(jsonPath("$.results[0].reason").value("EARMARK_TARGET_INVALID"));

    assertThat(amountOf(entry)).isEqualTo(2);
    assertThat(jobOrderEarmark(entry, order)).isEqualTo(2);
    UUID moved =
        jdbc.queryForObject(
            "SELECT id FROM inventory_item WHERE user_id = ? AND location_id = ?",
            UUID.class,
            holder,
            elsewhere);
    assertThat(amountOf(moved)).isEqualTo(8);
    assertThat(jobOrderEarmark(moved, order)).isEqualTo(4);
  }

  @Test
  void anEarmarkThatOverAllocatesNamesAWrongOrDuplicateTargetOrAPersonalEntryIsRefused()
      throws Exception {
    UUID staffel = orgUnit();
    UUID plain = orgUnit();
    joins(member, staffel, true);
    joins(member, plain, false);
    UUID titanium = material("SCU");
    UUID area18 = location();
    UUID entry = stock(holder, titanium, area18, 10, staffel, false);
    earmarkToJobOrder(entry, 6, staffel);
    UUID order = jobOrder(staffel, titanium, "OPEN");
    UUID unneeded = jobOrder(staffel, material("SCU"), "OPEN");
    UUID personal = stock(holder, titanium, area18, 5, staffel, true);
    UUID inPlain = stock(holder, titanium, area18, 5, plain, false);
    UUID mission = mission();

    mockMvc
        .perform(
            relayed(post(CHANGES), WRITE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    ops(
                        allocation("allocation-add", staffel, entry, 0, toJobOrder(order), "1"),
                        allocation("allocation-add", staffel, entry, 1, toJobOrder(order), "1"),
                        allocation("allocation-add", staffel, entry, 1, toMission(mission), "11"),
                        allocation("allocation-change", staffel, entry, 1, toJobOrder(order), "5"),
                        allocation("allocation-add", staffel, entry, 1, toJobOrder(unneeded), "1"),
                        allocation(
                            "allocation-add", staffel, entry, 1, toMission(UUID.randomUUID()), "1"),
                        allocation("allocation-change", staffel, entry, 1, toMission(mission), "1"),
                        allocation("allocation-add", staffel, personal, 0, toJobOrder(order), "1"),
                        allocation("allocation-add", plain, inPlain, 0, toJobOrder(order), "1"),
                        allocation("allocation-add", staffel, entry, 0, toMission(mission), "1"),
                        withSuffix(
                            entryOp("allocation-add", staffel, entry, 1, toMission(mission)),
                            ",\"quantity\":{\"amount\":1,\"unit\":\"PIECE\"}}"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(1))
        .andExpect(jsonPath("$.results.length()").value(10))
        .andExpect(jsonPath("$.results[0].reason").value("EARMARK_TARGET_INVALID"))
        .andExpect(jsonPath("$.results[1].reason").value("OVER_ALLOCATION"))
        .andExpect(jsonPath("$.results[2].reason").value("OVER_ALLOCATION"))
        .andExpect(jsonPath("$.results[3].reason").value("EARMARK_TARGET_INVALID"))
        .andExpect(jsonPath("$.results[4].reason").value("EARMARK_TARGET_INVALID"))
        .andExpect(jsonPath("$.results[5].reason").value("EARMARK_TARGET_INVALID"))
        .andExpect(jsonPath("$.results[6].reason").value("NOT_PERMITTED"))
        .andExpect(jsonPath("$.results[7].reason").value("NOT_PERMITTED"))
        .andExpect(jsonPath("$.results[8].reason").value("VERSION_CONFLICT"))
        .andExpect(jsonPath("$.results[9].reason").value("UNIT_MISMATCH"));

    assertThat(jobOrderEarmark(entry, order)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inventory_item_mission_allocation WHERE inventory_item_id ="
                    + " ?",
                Long.class,
                entry))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inventory_item_job_order_allocation WHERE inventory_item_id"
                    + " IN (?, ?)",
                Long.class,
                personal,
                inPlain))
        .isZero();
  }

  @Test
  void theTargetsAreTheOrdersAndMissionsTheWebPickerOffersForThePoolAndMaterial() throws Exception {
    UUID staffel = orgUnit();
    UUID other = orgUnit("SQUADRON");
    jdbc.update("UPDATE org_unit SET is_profit_eligible = TRUE WHERE id = ?", staffel);
    joins(member, staffel, true);
    UUID titanium = material("SCU");
    UUID open = jobOrder(staffel, titanium, "OPEN");
    UUID inProgress = jobOrder(staffel, titanium, "IN_PROGRESS");
    jobOrder(staffel, titanium, "COMPLETED");
    jobOrder(staffel, material("SCU"), "OPEN");
    jobOrder(other, titanium, "OPEN");
    UUID own = mission(staffel, true, "PLANNED");
    UUID open2 = mission(other, false, "ACTIVE");
    mission(other, true, "PLANNED");

    String body =
        mockMvc
            .perform(
                relayed(
                    get(TARGETS)
                        .param("unit", staffel.toString())
                        .param("material", titanium.toString()),
                    WRITE))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(JsonPath.<String>read(body, "$.unit.id")).isEqualTo(staffel.toString());
    assertThat(JsonPath.<List<String>>read(body, "$.jobOrders[*].id"))
        .containsExactlyInAnyOrder(open.toString(), inProgress.toString());
    assertThat(JsonPath.<List<String>>read(body, "$.jobOrders[*].status"))
        .containsExactlyInAnyOrder("OPEN", "IN_PROGRESS");
    assertThat(JsonPath.<List<Integer>>read(body, "$.jobOrders[*].number"))
        .allSatisfy(number -> assertThat(number).isPositive());
    assertThat(JsonPath.<List<String>>read(body, "$.missions[*].id"))
        .contains(own.toString(), open2.toString())
        .doesNotContainAnyElementsOf(
            jdbc.queryForList(
                "SELECT CAST(id AS varchar) FROM mission WHERE owning_org_unit_id = ? AND"
                    + " is_internal = TRUE",
                String.class,
                other));
    assertThat(JsonPath.<List<String>>read(body, "$.missions[?(@.id == '" + own + "')].name"))
        .containsExactly("Op " + own);
    assertThat(JsonPath.<List<String>>read(body, "$.missions[?(@.id == '" + own + "')].status"))
        .containsExactly("PLANNED");
  }

  @Test
  void theTargetsOfAUnitTheWriterIsNoLogisticianOfAreRefused() throws Exception {
    UUID plain = orgUnit();
    joins(member, plain, false);
    UUID titanium = material("SCU");

    mockMvc
        .perform(
            relayed(
                get(TARGETS).param("unit", plain.toString()).param("material", titanium.toString()),
                WRITE))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            relayed(
                get(TARGETS).param("unit", plain.toString()).param("material", titanium.toString()),
                "exchange.orgstock.read"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SCOPE_MISSING"));
  }

  @Test
  void anUnknownMaterialHasNoTargets() throws Exception {
    UUID staffel = orgUnit();
    joins(member, staffel, true);
    mission(staffel, true, "PLANNED");

    mockMvc
        .perform(
            relayed(
                get(TARGETS)
                    .param("unit", staffel.toString())
                    .param("material", UUID.randomUUID().toString()),
                WRITE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.jobOrders.length()").value(0))
        .andExpect(jsonPath("$.missions.length()").value(0));
  }

  /**
   * Builds an earmark op on an entry.
   *
   * @param kind {@code allocation-add}, {@code allocation-change} or {@code allocation-remove}
   * @param unit the pool the op names
   * @param entry the entry
   * @param version the version the client read
   * @param target the target member, starting with a comma
   * @param amount the SCU amount, or {@code null} for none
   * @return the op
   */
  private static @NotNull String allocation(
      @NotNull String kind,
      @NotNull UUID unit,
      @NotNull UUID entry,
      long version,
      @NotNull String target,
      @Nullable String amount) {
    return entryOp(
        kind,
        unit,
        entry,
        version,
        target
            + (amount == null ? "" : ",\"quantity\":{\"amount\":" + amount + ",\"unit\":\"SCU\"}"));
  }

  /**
   * Names a job order as an earmark's target.
   *
   * @param order the job order
   * @return the member, starting with a comma
   */
  private static @NotNull String toJobOrder(@NotNull UUID order) {
    return ",\"jobOrder\":\"" + order + "\"";
  }

  /**
   * Names a mission as an earmark's target.
   *
   * @param mission the mission
   * @return the member, starting with a comma
   */
  private static @NotNull String toMission(@NotNull UUID mission) {
    return ",\"mission\":\"" + mission + "\"";
  }

  /**
   * Reads an entry's earmark to a job order.
   *
   * @param entry the entry
   * @param order the job order
   * @return the amount
   */
  private double jobOrderEarmark(@NotNull UUID entry, @NotNull UUID order) {
    Double amount =
        jdbc.queryForObject(
            "SELECT amount FROM inventory_item_job_order_allocation WHERE inventory_item_id = ?"
                + " AND job_order_id = ?",
            Double.class,
            entry,
            order);
    return amount == null ? 0 : amount;
  }

  /**
   * Reads an entry's earmark to a mission.
   *
   * @param entry the entry
   * @param mission the mission
   * @return the amount
   */
  private double missionEarmark(@NotNull UUID entry, @NotNull UUID mission) {
    Double amount =
        jdbc.queryForObject(
            "SELECT amount FROM inventory_item_mission_allocation WHERE inventory_item_id = ? AND"
                + " mission_id = ?",
            Double.class,
            entry,
            mission);
    return amount == null ? 0 : amount;
  }

  /**
   * Reads an entry's version.
   *
   * @param entry the entry
   * @return its version
   */
  private long versionOf(@NotNull UUID entry) {
    Long version =
        jdbc.queryForObject("SELECT version FROM inventory_item WHERE id = ?", Long.class, entry);
    return version == null ? -1 : version;
  }

  /**
   * Seeds a job order of a unit that needs a material.
   *
   * @param unit the requesting and responsible unit
   * @param material the material it needs
   * @param status its status
   * @return the order id
   */
  private @NotNull UUID jobOrder(
      @NotNull UUID unit, @NotNull UUID material, @NotNull String status) {
    UUID order = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO job_order (id, priority, created_at, updated_at, version, status,"
            + " requesting_org_unit_id, responsible_org_unit_id, type)"
            + " VALUES (?, 2, now(), now(), 0, ?, ?, ?, 'MATERIAL')",
        order,
        status,
        unit,
        unit);
    jobOrders.add(order);
    jdbc.update(
        """
        INSERT INTO job_order_material (id, job_order_id, material_id, amount, created_at,
                                        updated_at, version, quality_tier_id)
        VALUES (?, ?, ?, 20, now(), now(), 0, '6b1f2e0a-3c1d-4f5e-9a10-000000000000')
        """,
        UUID.randomUUID(),
        order,
        material);
    return order;
  }

  /**
   * Seeds a mission of a unit.
   *
   * @param unit the owning unit
   * @param internal whether only the unit sees it
   * @param status its status
   * @return its id
   */
  private @NotNull UUID mission(@NotNull UUID unit, boolean internal, @NotNull String status) {
    UUID mission = mission();
    jdbc.update(
        "UPDATE mission SET owning_org_unit_id = ?, is_internal = ?, status = ?,"
            + " planned_start_time = now() WHERE id = ?",
        unit,
        internal,
        status,
        mission);
    return mission;
  }

  /**
   * Builds an op on an entry.
   *
   * @param kind the op
   * @param unit the pool the op names
   * @param entry the entry
   * @param version the version the client read
   * @param members further members, each starting with a comma
   * @return the op
   */
  private static @NotNull String entryOp(
      @NotNull String kind,
      @NotNull UUID unit,
      @NotNull UUID entry,
      long version,
      @NotNull String members) {
    return "{\"op\":\""
        + kind
        + "\",\"unit\":\""
        + unit
        + "\",\"entry\":\""
        + entry
        + "\",\"version\":"
        + version
        + members
        + "}";
  }

  /**
   * Builds a {@code move-holder} op of an SCU amount.
   *
   * @param unit the pool the entry lies in
   * @param entry the entry
   * @param amount the amount
   * @param toUnit the pool that receives it
   * @param holderRef the new holder's ref
   * @return the op
   */
  private static @NotNull String moveHolder(
      @NotNull UUID unit,
      @NotNull UUID entry,
      @NotNull String amount,
      @NotNull UUID toUnit,
      @NotNull String holderRef) {
    return entryOp(
        "move-holder",
        unit,
        entry,
        0,
        ",\"quantity\":{\"amount\":"
            + amount
            + ",\"unit\":\"SCU\"},\"toUnit\":\""
            + toUnit
            + "\",\"holder\":\""
            + holderRef
            + "\"");
  }

  /**
   * Counts the org-stock feed rows of a pool.
   *
   * @param unit the pool's unit
   * @return the rows
   */
  private long feedRowsOf(@NotNull UUID unit) {
    Long rows =
        jdbc.queryForObject(
            "SELECT count(*) FROM exchange_change WHERE org_unit_id = ?", Long.class, unit);
    return rows == null ? 0 : rows;
  }

  /**
   * Earmarks part of a row to a mission.
   *
   * @param inventoryItem the row
   * @param mission the mission
   * @param amount the earmarked amount
   */
  private void earmarkToMission(@NotNull UUID inventoryItem, @NotNull UUID mission, double amount) {
    jdbc.update(
        """
        INSERT INTO inventory_item_mission_allocation (id, inventory_item_id, mission_id, amount,
                                                       version, created_at, updated_at)
        VALUES (?, ?, ?, ?, 0, now(), now())
        """,
        UUID.randomUUID(),
        inventoryItem,
        mission,
        amount);
  }

  /**
   * Makes a member a participant of a mission.
   *
   * @param mission the mission
   * @param user the member
   * @return the participant's id
   */
  private @NotNull UUID participates(@NotNull UUID mission, @NotNull UUID user) {
    UUID participant = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO mission_participant (id, version, mission_id, user_id) VALUES (?, 0, ?, ?)",
        participant,
        mission,
        user);
    return participant;
  }

  /**
   * Reads a unit's roster as the writer.
   *
   * @param unit the unit
   * @return the answer's body
   * @throws Exception if the request fails
   */
  private @NotNull String roster(@NotNull UUID unit) throws Exception {
    return mockMvc
        .perform(relayed(get(MEMBERS).param("unit", unit.toString()), WRITE))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /**
   * Reads a member's ref from a unit's roster.
   *
   * @param unit the unit
   * @param user the member
   * @return the ref
   * @throws Exception if the request fails
   */
  private @NotNull String ref(@NotNull UUID unit, @NotNull UUID user) throws Exception {
    String name = "Roster " + user;
    jdbc.update("UPDATE app_user SET display_name = ? WHERE id = ?", name, user);
    List<String> refs = JsonPath.read(roster(unit), "$.items[?(@.name == '" + name + "')].ref");
    assertThat(refs).hasSize(1);
    return refs.getFirst();
  }

  /**
   * Wraps ops into a change set.
   *
   * @param ops the ops
   * @return the change set
   */
  private static @NotNull String ops(@NotNull String... ops) {
    return "{\"ops\":[" + String.join(",", ops) + "]}";
  }

  /**
   * Appends members to an op object.
   *
   * @param op the op, ending in its closing brace
   * @param suffix the members to add, ending in a closing brace
   * @return the op
   */
  private static @NotNull String withSuffix(@NotNull String op, @NotNull String suffix) {
    return op.substring(0, op.length() - 1) + suffix;
  }

  /**
   * Builds a book-in op of a material at quality 500, not stolen.
   *
   * @param unit the pool
   * @param holderRef the holder's ref
   * @param material the material
   * @param location the location, named by its name
   * @param amount the amount
   * @param quantityUnit the amount's unit
   * @return the op
   */
  private @NotNull String bookIn(
      @NotNull UUID unit,
      @NotNull String holderRef,
      @NotNull UUID material,
      @NotNull UUID location,
      @NotNull String amount,
      @NotNull String quantityUnit) {
    return "{\"op\":\"book-in\",\"unit\":\""
        + unit
        + "\",\"holder\":\""
        + holderRef
        + "\",\"material\":{\"bt\":\""
        + material
        + "\"},\"location\":{\"name\":\"Writes "
        + location
        + "\"},\"quality\":500,\"stolen\":false,\"quantity\":{\"amount\":"
        + amount
        + ",\"unit\":\""
        + quantityUnit
        + "\"}}";
  }

  /**
   * Builds a book-out op.
   *
   * @param unit the pool the op names
   * @param entry the entry
   * @param version the version the client read
   * @param amount the amount
   * @param quantityUnit the amount's unit
   * @return the op
   */
  private static @NotNull String bookOut(
      @NotNull UUID unit,
      @NotNull UUID entry,
      long version,
      @NotNull String amount,
      @NotNull String quantityUnit) {
    return "{\"op\":\"book-out\",\"unit\":\""
        + unit
        + "\",\"entry\":\""
        + entry
        + "\",\"version\":"
        + version
        + ",\"quantity\":{\"amount\":"
        + amount
        + ",\"unit\":\""
        + quantityUnit
        + "\"}}";
  }

  /**
   * Reads the org-stock write counter of the current client for one outcome.
   *
   * @param outcome the outcome
   * @return its count, {@code 0} when it has none yet
   */
  private double writes(@NotNull String outcome) {
    Counter counter =
        meterRegistry
            .find(MetricNames.EXCHANGE_WRITES)
            .tags(
                MetricNames.TAG_CLIENT_ID,
                client,
                MetricNames.TAG_RESOURCE,
                "org_stock",
                MetricNames.TAG_OUTCOME,
                outcome)
            .counter();
    return counter == null ? 0 : counter.count();
  }

  /**
   * Waits briefly for a member's notifications, which are written after the write commits.
   *
   * @param recipient the member
   * @return their notifications
   * @throws InterruptedException if the wait is interrupted
   */
  private long notificationsOf(@NotNull UUID recipient) throws InterruptedException {
    long count = 0;
    for (int attempt = 0; attempt < 50 && count == 0; attempt++) {
      Long found =
          jdbc.queryForObject(
              "SELECT count(*) FROM notification WHERE recipient_user_id = ?",
              Long.class,
              recipient);
      count = found == null ? 0 : found;
      if (count == 0) {
        Thread.sleep(100);
      }
    }
    return count;
  }

  /**
   * Reads an entry's amount.
   *
   * @param entry the entry
   * @return its amount
   */
  private double amountOf(@NotNull UUID entry) {
    Double amount =
        jdbc.queryForObject("SELECT amount FROM inventory_item WHERE id = ?", Double.class, entry);
    return amount == null ? 0 : amount;
  }

  /**
   * Registers an active client and makes it the current one.
   *
   * @param capabilities the granted capabilities
   * @return the client id
   */
  private @NotNull String register(@NotNull Set<ExchangeCapability> capabilities) {
    String id = "vk-" + UUID.randomUUID().toString().substring(0, 8);
    ExchangeClient registered = new ExchangeClient();
    registered.setClientId(id);
    registered.setDisplayName("VerseKit");
    registered.setStatus(ExchangeClientStatus.ACTIVE);
    registered.setCapabilities(EnumSet.copyOf(capabilities));
    clientRepository.saveAndFlush(registered);
    knownClients.invalidate();
    clients.add(id);
    return id;
  }

  /**
   * Stores a member's withholding choice.
   *
   * @param user the member
   * @param withhold the choice
   */
  private void withholds(@NotNull UUID user, boolean withhold) {
    jdbc.update(
        """
        INSERT INTO exchange_name_withholding (user_id, withhold_name) VALUES (?, ?)
        ON CONFLICT (user_id) DO UPDATE SET withhold_name = EXCLUDED.withhold_name
        """,
        user,
        withhold);
  }

  /**
   * Inserts and commits a material stock row of quality 1.
   *
   * @param owner the holder
   * @param material the material
   * @param location the location
   * @param amount the amount
   * @param pool the owning org unit
   * @param personal whether the row is personal
   * @return the row id
   */
  private @NotNull UUID stock(
      @NotNull UUID owner,
      @NotNull UUID material,
      @NotNull UUID location,
      double amount,
      @NotNull UUID pool,
      boolean personal) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO inventory_item (id, user_id, material_id, location_id, quality, amount,
                                    personal, stolen, owning_org_unit_id, version)
        VALUES (?, ?, ?, ?, 1, ?, ?, false, ?, 0)
        """,
        id,
        owner,
        material,
        location,
        amount,
        personal,
        pool);
    return id;
  }

  /**
   * Seeds a mission.
   *
   * @return its id
   */
  private @NotNull UUID mission() {
    UUID mission = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO mission (id, name, version) VALUES (?, ?, 0)", mission, "Op " + mission);
    missions.add(mission);
    return mission;
  }

  /**
   * Earmarks part of a row to a new job order of the unit.
   *
   * @param inventoryItem the row
   * @param amount the earmarked amount
   * @param unit the requesting and responsible unit
   * @return the order id
   */
  private @NotNull UUID earmarkToJobOrder(
      @NotNull UUID inventoryItem, double amount, @NotNull UUID unit) {
    UUID order = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO job_order (id, priority, created_at, updated_at, version, status,"
            + " requesting_org_unit_id, responsible_org_unit_id, type)"
            + " VALUES (?, 2, now(), now(), 0, 'OPEN', ?, ?, 'MATERIAL')",
        order,
        unit,
        unit);
    jobOrders.add(order);
    jdbc.update(
        """
        INSERT INTO inventory_item_job_order_allocation (id, inventory_item_id, job_order_id,
                                                         amount, delivered, version, created_at,
                                                         updated_at)
        VALUES (?, ?, ?, ?, false, 0, now(), now())
        """,
        UUID.randomUUID(),
        inventoryItem,
        order,
        amount);
    return order;
  }

  /**
   * Seeds a material.
   *
   * @param quantityType {@code SCU} or {@code PIECE}
   * @return its id
   */
  private @NotNull UUID material(@NotNull String quantityType) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO material (id, name, type, quantity_type, is_manual_raw_material,
                              is_job_order, is_visible, source_systems)
        VALUES (?, ?, 'RAW', ?, false, false, true, 'UEX_ONLY')
        """,
        id,
        "Writes " + id,
        quantityType);
    materials.add(id);
    return id;
  }

  /**
   * Seeds a location named {@code Writes <id>}.
   *
   * @return its id
   */
  private @NotNull UUID location() {
    UUID id = UUID.randomUUID();
    jdbc.update("INSERT INTO location (id, name, hidden) VALUES (?, ?, false)", id, "Writes " + id);
    locations.add(id);
    return id;
  }

  /**
   * Seeds an active Spezialkommando.
   *
   * @return its id
   */
  private @NotNull UUID orgUnit() {
    return orgUnit("SPECIAL_COMMAND");
  }

  /**
   * Seeds an active org unit of a kind.
   *
   * @param kind the kind
   * @return its id
   */
  private @NotNull UUID orgUnit(@NotNull String kind) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO org_unit (id, kind, name, shorthand, active, is_promotion_enabled,"
            + " is_profit_eligible) VALUES (?, ?, ?, ?, TRUE, FALSE, FALSE)",
        id,
        kind,
        "Pool " + id,
        "W" + id.toString().substring(0, 8));
    orgUnits.add(id);
    return id;
  }

  /**
   * Makes a user a direct member of an org unit.
   *
   * @param user the user
   * @param orgUnit the org unit
   * @param logistician whether the membership carries the Logistician flag
   */
  private void joins(@NotNull UUID user, @NotNull UUID orgUnit, boolean logistician) {
    jdbc.update(
        "INSERT INTO org_unit_membership (user_id, org_unit_id, role, is_logistician)"
            + " VALUES (?, ?, 'MEMBER', ?)",
        user,
        orgUnit,
        logistician);
  }

  /**
   * Seeds a member.
   *
   * @param username the username prefix
   * @return the member's id
   */
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

  /**
   * Adds the gateway's identity and the relay headers.
   *
   * @param request the request
   * @param capabilities the relayed capabilities
   * @return the request
   */
  private @NotNull MockHttpServletRequestBuilder relayed(
      @NotNull MockHttpServletRequestBuilder request, @NotNull String capabilities) {
    return request
        .with(jwt().jwt(t -> t.subject(GATEWAY).claim("azp", "test-ingest-gateway")))
        .header(ActingMemberHeader.ON_BEHALF_OF_HEADER, member.toString())
        .header(ActingMemberHeader.EXCHANGE_CLIENT_HEADER, client)
        .header(ActingMemberHeader.EXCHANGE_CAPABILITIES_HEADER, capabilities)
        .header(ActingMemberHeader.EXCHANGE_INSTALLATION_HEADER, KEY);
  }
}

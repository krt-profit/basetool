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
 * A Logistician of a pool books stock in for a member of the unit and books another member's entry
 * out through the exchange, with the Lager's rules, audit, notifications and journal; every other
 * pool, personal entry, stale version and unlisted holder is refused per op (REQ-XCH-045,
 * REQ-XCH-046, REQ-XCH-044).
 */
@SpringBootTest
class ExchangeOrgStockWriteControllerTest {

  private static final String CHANGES = "/api/v1/exchange/org-stock/changes";
  private static final String MEMBERS = "/api/v1/exchange/org-stock/members";
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
    missions.forEach(id -> jdbc.update("DELETE FROM mission WHERE id = ?", id));
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
    UUID order = earmarkToJobOrder(entry, titanium, 6, staffel);

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
   * Earmarks part of a row to a new job order of the unit that needs the material.
   *
   * @param inventoryItem the row
   * @param material the material the order needs
   * @param amount the earmarked amount
   * @param unit the requesting and responsible unit
   * @return the order id
   */
  private @NotNull UUID earmarkToJobOrder(
      @NotNull UUID inventoryItem, @NotNull UUID material, double amount, @NotNull UUID unit) {
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
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO org_unit (id, kind, name, shorthand, active, is_promotion_enabled,"
            + " is_profit_eligible) VALUES (?, 'SPECIAL_COMMAND', ?, ?, TRUE, FALSE, FALSE)",
        id,
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

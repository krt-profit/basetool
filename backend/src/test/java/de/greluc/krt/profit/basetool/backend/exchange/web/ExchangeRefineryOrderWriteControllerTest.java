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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import de.greluc.krt.profit.basetool.backend.exchange.api.ExchangeProblemException;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ConnectedAppMassChangeRequestDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ConnectedAppMassChangeResultDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeCapability;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClient;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClientRepository;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClientStatus;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeLiveSync;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeLocationResolver;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeMassChangeService;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeRefineryOrderKeys;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeRefineryOrderLinkRepository;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeSettings;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeSettingsRepository;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeUndoResultDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeUndoService;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeRefineryOrderChangeSet;
import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.platform.api.ActingMemberHeader;
import de.greluc.krt.profit.basetool.backend.support.TestPlaces;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/**
 * A client's writes to the member's own refinery orders through one change set, relayed from the
 * ingest gateway, and their undo (REQ-XCH-052, REQ-XCH-056). Writes commit, because the feed and
 * the undo read only finished transactions.
 */
@SpringBootTest
class ExchangeRefineryOrderWriteControllerTest {

  private static final String PATH = "/api/v1/exchange/me/refinery-orders/changes";
  private static final String FEED = "/api/v1/exchange/me/refinery-orders";
  private static final String GATEWAY = "55555555-5555-5555-5555-555555555555";
  private static final String KEY = "Kx9_" + "w".repeat(39);

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private ExchangeClientRepository clientRepository;
  @Autowired private ExchangeSettingsRepository settingsRepository;
  @Autowired private ExchangeUndoService undoService;
  @Autowired private ExchangeMassChangeService massChangeService;
  @Autowired private ExchangeRefineryOrderKeys refineryOrderKeys;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactionManager;
  @MockitoSpyBean private ExchangeLiveSync liveSync;

  /** Spied by the same name as in the other exchange write tests, so they share one context. */
  @MockitoSpyBean private ExchangeLocationResolver locationResolver;

  private MockMvc mockMvc;
  private UUID member;
  private UUID other;
  private String client;
  private String otherClient;
  private boolean wasEnabled;
  private UUID refinery;
  private UUID plainPlace;
  private UUID unit;
  private UUID foreignUnit;
  private UUID ore;
  private UUID refined;
  private final List<UUID> locations = new ArrayList<>();
  private final List<UUID> materials = new ArrayList<>();
  private final List<UUID> missions = new ArrayList<>();

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy.class))
            .build();
    member = user("refinery-writer").getId();
    other = user("refinery-bystander").getId();
    client = register("vk-");
    otherClient = register("ot-");
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    wasEnabled = settings.isEnabled();
    settings.setEnabled(true);
    settingsRepository.saveAndFlush(settings);
    refinery = place("ARC-L1 Refinery", true);
    plainPlace = place("Lorville Gate", false);
    unit = orgUnit();
    foreignUnit = orgUnit();
    jdbc.update(
        "INSERT INTO org_unit_membership (user_id, org_unit_id, role) VALUES (?, ?, 'MEMBER')",
        member,
        unit);
    refined = material("Quantainium (Refined)", "REFINED", null);
    ore = material("Quantainium", "RAW", refined);
  }

  @AfterEach
  void tearDown() {
    for (UUID id : List.of(member, other)) {
      jdbc.update(
          "DELETE FROM refinery_good WHERE refinery_order_id IN"
              + " (SELECT id FROM refinery_order WHERE owner_id = ?)",
          id);
      jdbc.update("DELETE FROM refinery_order WHERE owner_id = ?", id);
      jdbc.update("DELETE FROM exchange_refinery_order_link WHERE user_id = ?", id);
      jdbc.update("DELETE FROM exchange_journal WHERE user_id = ?", id);
      jdbc.update("DELETE FROM org_unit_membership WHERE user_id = ?", id);
      jdbc.update("DELETE FROM user_roles WHERE user_id = ?", id);
      jdbc.update("DELETE FROM app_user WHERE id = ?", id);
    }
    missions.forEach(id -> jdbc.update("DELETE FROM mission WHERE id = ?", id));
    jdbc.update("DELETE FROM exchange_client WHERE client_id IN (?, ?)", client, otherClient);
    for (UUID id : locations) {
      UUID city = jdbc.queryForObject("SELECT city_id FROM location WHERE id = ?", UUID.class, id);
      jdbc.update("DELETE FROM location WHERE id = ?", id);
      jdbc.update("DELETE FROM city WHERE id = ?", city);
    }
    materials.reversed().forEach(id -> jdbc.update("DELETE FROM material WHERE id = ?", id));
    jdbc.update("DELETE FROM org_unit WHERE id IN (?, ?)", unit, foreignUnit);
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    settings.setEnabled(wasEnabled);
    settingsRepository.saveAndFlush(settings);
  }

  @Test
  void aCreateAnUpdateByItsKeyAndAGoodAddApplyInOrderAndNameTheOrderTheRowAndTheCounters()
      throws Exception {
    String body =
        changes(
                client,
                create("vk-1")
                    + ","
                    + """
                    {"op": "update", "clientKey": "vk-1", "versions": {"gameData": 0},
                     "endsAt": "2030-01-01T12:00:00Z", "durationMinutes": 90}
                    """
                    + ","
                    + """
                    {"opId": "add", "op": "good-add", "clientKey": "vk-1", "versions": {"gameData": 1},
                     "good": {"input": {"bt": "%s"}, "inputQuantity": 500, "outputQuantity": 400,
                              "quality": 700}}
                    """
                        .formatted(ore))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.applied").value(3))
            .andExpect(jsonPath("$.notApplied").value(0))
            .andExpect(jsonPath("$.orders.length()").value(3))
            .andExpect(jsonPath("$.orders[2].opId").value("add"))
            .andExpect(jsonPath("$.orders[2].versions.gameData").value(2))
            .andExpect(jsonPath("$.orders[2].versions.finances").value(0))
            .andExpect(jsonPath("$.orders[2].goods.length()").value(1))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = JsonPath.read(body, "$.orders[0].id");
    assertThat(JsonPath.<String>read(body, "$.orders[2].id")).isEqualTo(id);
    String goodId = JsonPath.read(body, "$.orders[2].goods[0]");

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT status, created_source, created_client, owning_org_unit_id, started_at,"
                + " duration_minutes, game_data_version FROM refinery_order WHERE id = ?",
            UUID.fromString(id));
    assertThat(row.get("status")).isEqualTo("IN_PROGRESS");
    assertThat(row.get("created_source")).isEqualTo("CLIENT");
    assertThat(row.get("created_client")).isEqualTo(client);
    assertThat(row.get("owning_org_unit_id")).isEqualTo(unit);
    assertThat(((java.sql.Timestamp) row.get("started_at")).toInstant())
        .isEqualTo(Instant.parse("2030-01-01T10:30:00Z"));
    assertThat(((Number) row.get("duration_minutes")).longValue()).isEqualTo(90L);
    assertThat(
            jdbc.queryForObject(
                "SELECT output_material_id FROM refinery_good WHERE id = ?",
                UUID.class,
                UUID.fromString(goodId)))
        .isEqualTo(refined);

    String feed = relayed(get(FEED), client).andReturn().getResponse().getContentAsString();
    assertThat(JsonPath.<List<String>>read(feed, "$.items[?(@.id == '" + id + "')].clientKey"))
        .containsExactly("vk-1");
    String foreign = relayed(get(FEED), otherClient).andReturn().getResponse().getContentAsString();
    assertThat(JsonPath.<List<Object>>read(foreign, "$.items[?(@.id == '" + id + "')].clientKey"))
        .isEmpty();
  }

  @Test
  void aKnownKeyIsAlreadyExistsAndAnotherClientsKeyOrAnotherOwnersOrderIsUnknown()
      throws Exception {
    String first = changes(client, create("vk-2")).andReturn().getResponse().getContentAsString();
    String id = JsonPath.read(first, "$.orders[0].id");
    UUID foreign = foreignOrder();

    changes(
            client,
            create("vk-2")
                + ","
                + "{\"op\": \"update\", \"id\": \""
                + foreign
                + "\", \"versions\": {\"finances\": 0}, \"expenses\": 10}")
        .andExpect(jsonPath("$.results[0].reason").value("ALREADY_EXISTS"))
        .andExpect(jsonPath("$.results[0].id").value(id))
        .andExpect(jsonPath("$.results[1].reason").value("ORDER_UNKNOWN"));
    changes(
            otherClient,
            "{\"op\": \"update\", \"clientKey\": \"vk-2\", \"versions\": {\"finances\": 0},"
                + " \"expenses\": 10}")
        .andExpect(jsonPath("$.results[0].reason").value("ORDER_UNKNOWN"));
  }

  @Test
  void referencesResolveExactlyAndAreRefusedWithTheirReason() throws Exception {
    UUID plainOre = material("Agricium", "REFINED", null);
    String unknownPlace = "{\"name\": \"No such place " + UUID.randomUUID() + "\"}";
    changes(
            client,
            createWith("r-1", "{\"name\": \"Lorville Gate " + plainPlace + "\"}", unit, null)
                + ","
                + createWith("r-2", unknownPlace, unit, null)
                + ","
                + createWith(
                    "r-3",
                    refineryRef(),
                    unit,
                    "[{\"input\": {\"bt\": \""
                        + plainOre
                        + "\"}, \"inputQuantity\": 1, \"outputQuantity\": 1, \"quality\": 1}]")
                + ","
                + createWith("r-4", refineryRef(), foreignUnit, null)
                + ","
                + "{\"op\": \"create\", \"clientKey\": \"r-5\", \"orgUnit\": {\"id\": \""
                + unit
                + "\"}, \"location\": "
                + refineryRef()
                + ", \"method\": {\"code\": \"NO-SUCH-CODE\"}}")
        .andExpect(jsonPath("$.applied").value(0))
        .andExpect(jsonPath("$.results[0].reason").value("NOT_A_REFINERY"))
        .andExpect(jsonPath("$.results[1].reason").value("LOCATION_UNKNOWN"))
        .andExpect(jsonPath("$.results[2].reason").value("MATERIAL_NOT_RAW"))
        .andExpect(jsonPath("$.results[3].reason").value("NOT_A_MEMBER"))
        .andExpect(jsonPath("$.results[4].result").value("unmatched"))
        .andExpect(jsonPath("$.results[4].reason").value("UNMATCHED"));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM refinery_order WHERE owner_id = ?", Integer.class, member))
        .isZero();
  }

  @Test
  void aWebEditOfTheOtherSectionLetsAnUpdateApplyAndOfItsOwnRefusesItAndTheRestOfTheOrder()
      throws Exception {
    String first = changes(client, create("vk-3")).andReturn().getResponse().getContentAsString();
    UUID id = UUID.fromString(JsonPath.read(first, "$.orders[0].id"));
    jdbc.update("UPDATE refinery_order SET finances_version = 4, expenses = 99 WHERE id = ?", id);

    changes(
            client,
            "{\"op\": \"update\", \"id\": \""
                + id
                + "\", \"versions\": {\"gameData\": 0}, \"status\": \"OPEN\"}")
        .andExpect(jsonPath("$.applied").value(1))
        .andExpect(jsonPath("$.orders[0].versions.gameData").value(1))
        .andExpect(jsonPath("$.orders[0].versions.finances").value(4));

    changes(
            client,
            "{\"op\": \"update\", \"id\": \""
                + id
                + "\", \"versions\": {\"finances\": 0}, \"expenses\": 5},"
                + "{\"op\": \"update\", \"id\": \""
                + id
                + "\", \"versions\": {\"gameData\": 1}, \"status\": \"IN_PROGRESS\"}")
        .andExpect(jsonPath("$.applied").value(0))
        .andExpect(jsonPath("$.results[0].reason").value("VERSION_CONFLICT"))
        .andExpect(jsonPath("$.results[1].reason").value("PRECEDING_OP_REJECTED"));
    assertThat(
            jdbc.queryForObject(
                "SELECT expenses FROM refinery_order WHERE id = ?", Double.class, id))
        .isEqualTo(99.0);
  }

  @Test
  void aDryRunDecidesEveryOpAndWritesNothing() throws Exception {
    changes(
            client,
            true,
            create("vk-4")
                + ","
                + "{\"op\": \"update\", \"clientKey\": \"vk-4\", \"versions\": {\"finances\": 0},"
                + " \"expenses\": 10}")
        .andExpect(jsonPath("$.dryRun").value(true))
        .andExpect(jsonPath("$.applied").value(2))
        .andExpect(jsonPath("$.orders").doesNotExist());
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM refinery_order WHERE owner_id = ?", Integer.class, member))
        .isZero();
  }

  @Test
  void aDryRunAnswersACancelOrPickupOfAnOrderTheSetAlreadyClosedAsTheRealRunDoes()
      throws Exception {
    UUID twiceCanceled = ownOrder("IN_PROGRESS");
    UUID canceledThenPickedUp = ownOrder("IN_PROGRESS");
    UUID twicePickedUp = ownOrder("IN_PROGRESS");
    String ops =
        String.join(
            ",",
            cancel(twiceCanceled, 0),
            cancel(twiceCanceled, 1),
            cancel(canceledThenPickedUp, 0),
            pickup("\"id\": \"" + canceledThenPickedUp + "\"", 1),
            pickup("\"id\": \"" + twicePickedUp + "\"", 0),
            pickup("\"id\": \"" + twicePickedUp + "\"", 1));

    for (boolean dryRun : List.of(true, false)) {
      changes(client, dryRun, ops)
          .andExpect(jsonPath("$.dryRun").value(dryRun))
          .andExpect(jsonPath("$.applied").value(3))
          .andExpect(jsonPath("$.unchanged").value(2))
          .andExpect(jsonPath("$.notApplied").value(1))
          .andExpect(jsonPath("$.results[0].index").value(1))
          .andExpect(jsonPath("$.results[0].result").value("unchanged"))
          .andExpect(jsonPath("$.results[1].index").value(3))
          .andExpect(jsonPath("$.results[1].reason").value("ORDER_CANCELED"))
          .andExpect(jsonPath("$.results[2].index").value(5))
          .andExpect(jsonPath("$.results[2].result").value("unchanged"));
    }
    assertThat(statusOf(twiceCanceled)).isEqualTo("CANCELED");
    assertThat(statusOf(twicePickedUp)).isEqualTo("COMPLETED");
  }

  @Test
  void aKeyACreateTakesOverAfterADeleteStillNamesTheDeletedOrdersTombstone() throws Exception {
    String first = changes(client, create("vk-r")).andReturn().getResponse().getContentAsString();
    UUID gone = UUID.fromString(JsonPath.read(first, "$.orders[0].id"));
    String cursor =
        JsonPath.read(
            relayed(get(FEED), client).andReturn().getResponse().getContentAsString(),
            "$.nextCursor");
    changes(client, delete(gone)).andExpect(jsonPath("$.applied").value(1));

    String second =
        changes(client, create("vk-r"))
            .andExpect(jsonPath("$.applied").value(1))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String next = JsonPath.read(second, "$.orders[0].id");

    String feed =
        relayed(get(FEED).param("cursor", cursor), client)
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(JsonPath.<List<String>>read(feed, "$.removed[?(@.key == '" + gone + "')].clientKey"))
        .containsExactly("vk-r");
    assertThat(JsonPath.<List<String>>read(feed, "$.items[?(@.id == '" + next + "')].clientKey"))
        .containsExactly("vk-r");
    changes(
            client,
            "{\"op\": \"update\", \"clientKey\": \"vk-r\", \"versions\": {\"finances\": 0},"
                + " \"expenses\": 10}")
        .andExpect(jsonPath("$.orders[0].id").value(next));
  }

  @Test
  void anUndoThatRemovesACreatedOrderTellsItsDetailPageItIsGone() throws Exception {
    Instant since = Instant.now().minus(1, ChronoUnit.MINUTES);
    String created = changes(client, create("vk-g")).andReturn().getResponse().getContentAsString();
    UUID id = UUID.fromString(JsonPath.read(created, "$.orders[0].id"));

    undoService.undo(member, client, since);

    verify(liveSync)
        .refineryOrdersChanged(
            anyCollection(), anyCollection(), argThat(deleted -> deleted.contains(id)));
  }

  @Test
  void anUndoRemovesAnUnchangedCreateAndRollsBackAGoodsOpButSkipsAnOrderChangedInTheWeb()
      throws Exception {
    Instant since = Instant.now().minus(1, ChronoUnit.MINUTES);
    String created =
        changes(client, create("vk-5") + "," + create("vk-6"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID removed = UUID.fromString(JsonPath.read(created, "$.orders[0].id"));
    UUID changed = UUID.fromString(JsonPath.read(created, "$.orders[1].id"));
    jdbc.update("UPDATE refinery_order SET game_data_version = 7 WHERE id = ?", changed);
    String snapshot = relayed(get(FEED), client).andReturn().getResponse().getContentAsString();
    String cursor = JsonPath.read(snapshot, "$.nextCursor");

    ExchangeUndoResultDto result = undoService.undo(member, client, since);

    String feed =
        relayed(get(FEED).param("cursor", cursor), client)
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(
            JsonPath.<List<String>>read(feed, "$.removed[?(@.key == '" + removed + "')].clientKey"))
        .containsExactly("vk-5");

    assertThat(result.restored()).isEqualTo(1);
    assertThat(result.skipped())
        .extracting(ExchangeUndoResultDto.Skipped::reason)
        .containsExactly("CHANGED_AFTERWARDS");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM refinery_order WHERE id = ?", Integer.class, removed))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM refinery_order WHERE id = ?", Integer.class, changed))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE event_type = 'REFINERY_ORDER_DELETED'"
                    + " AND subject_id = ?",
                Integer.class,
                removed))
        .isEqualTo(1);
  }

  @Test
  void anUndoOfAGoodRemovalBringsTheRowBackUnderItsIdAndRestoresTheFinances() throws Exception {
    UUID id = foreignOrder();
    jdbc.update("UPDATE refinery_order SET owner_id = ? WHERE id = ?", member, id);
    UUID good = UUID.randomUUID();
    for (UUID row : List.of(good, UUID.randomUUID())) {
      jdbc.update(
          """
          INSERT INTO refinery_good (id, version, input_material_id, input_quantity,
                                     output_material_id, output_quantity, quality,
                                     refinery_order_id)
          VALUES (?, 0, ?, 300, ?, 200, 500, ?)
          """,
          row,
          ore,
          refined,
          id);
    }
    Instant since = Instant.now().minus(1, ChronoUnit.MINUTES);
    changes(
            client,
            "{\"op\": \"good-remove\", \"id\": \""
                + id
                + "\", \"versions\": {\"gameData\": 0}, \"goodId\": \""
                + good
                + "\"},{\"op\": \"update\", \"id\": \""
                + id
                + "\", \"versions\": {\"finances\": 0}, \"oreSales\": 1234}")
        .andExpect(jsonPath("$.applied").value(2));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM refinery_good WHERE id = ?", Integer.class, good))
        .isZero();

    ExchangeUndoResultDto result = undoService.undo(member, client, since);

    assertThat(result.restored()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT input_quantity FROM refinery_good WHERE id = ? AND refinery_order_id = ?",
                Integer.class,
                good,
                id))
        .isEqualTo(300);
    assertThat(
            jdbc.queryForObject(
                "SELECT ore_sales FROM refinery_order WHERE id = ?", Double.class, id))
        .isNull();
  }

  @Test
  void aCancelSetsCanceledAsAChangeIsUnchangedTheSecondTimeAndIsUndoneBySection() throws Exception {
    String first = changes(client, create("c-1")).andReturn().getResponse().getContentAsString();
    UUID id = UUID.fromString(JsonPath.read(first, "$.orders[0].id"));
    String cursor =
        JsonPath.read(
            relayed(get(FEED), client).andReturn().getResponse().getContentAsString(),
            "$.nextCursor");
    backdateJournal();
    Instant since = Instant.now().minus(1, ChronoUnit.MINUTES);

    changes(client, cancel(id, 0))
        .andExpect(jsonPath("$.applied").value(1))
        .andExpect(jsonPath("$.orders[0].versions.gameData").value(1))
        .andExpect(jsonPath("$.orders[0].versions.finances").value(0));
    changes(client, cancel(id, 1))
        .andExpect(jsonPath("$.unchanged").value(1))
        .andExpect(jsonPath("$.results[0].result").value("unchanged"));

    assertThat(statusOf(id)).isEqualTo("CANCELED");
    assertThat(audited("REFINERY_ORDER_CANCELED", id)).isEqualTo(1);
    String feed =
        relayed(get(FEED).param("cursor", cursor), client)
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(JsonPath.<List<String>>read(feed, "$.items[?(@.id == '" + id + "')].status"))
        .containsExactly("CANCELED");
    assertThat(JsonPath.<List<Object>>read(feed, "$.removed[?(@.key == '" + id + "')]")).isEmpty();

    ExchangeUndoResultDto result = undoService.undo(member, client, since);

    assertThat(result.restored()).isEqualTo(1);
    assertThat(statusOf(id)).isEqualTo("IN_PROGRESS");
  }

  @Test
  void aDeleteRemovesAnOpenAPickedUpAndAMissionLinkedOrderButRefusesAStoredOne() throws Exception {
    String first =
        changes(
                client,
                createWith(
                    "d-1",
                    refineryRef(),
                    unit,
                    "[{\"input\": {\"bt\": \""
                        + ore
                        + "\"}, \"inputQuantity\": 500, \"outputQuantity\": 400,"
                        + " \"quality\": 700}]"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID open = UUID.fromString(JsonPath.read(first, "$.orders[0].id"));
    String cursor =
        JsonPath.read(
            relayed(get(FEED), client).andReturn().getResponse().getContentAsString(),
            "$.nextCursor");
    UUID pickedUp = ownOrder();
    jdbc.update(
        "UPDATE refinery_order SET status = 'COMPLETED', picked_up_at = now(),"
            + " picked_up_client = ? WHERE id = ?",
        client,
        pickedUp);
    UUID linked = ownOrder();
    jdbc.update("UPDATE refinery_order SET mission_id = ? WHERE id = ?", mission(), linked);
    UUID stored = ownOrder();
    jdbc.update("UPDATE refinery_order SET stored_at = now() WHERE id = ?", stored);

    changes(
            client,
            delete(stored)
                + ","
                + delete(open)
                + ","
                + delete(pickedUp)
                + ","
                + delete(linked)
                + ","
                + cancel(open, 0))
        .andExpect(jsonPath("$.applied").value(3))
        .andExpect(jsonPath("$.results[0].reason").value("ORDER_STORED"))
        .andExpect(jsonPath("$.results[1].index").value(4))
        .andExpect(jsonPath("$.results[1].reason").value("ORDER_UNKNOWN"));

    for (UUID gone : List.of(open, pickedUp, linked)) {
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM refinery_order WHERE id = ?", Integer.class, gone))
          .isZero();
      assertThat(audited("REFINERY_ORDER_DELETED", gone)).isEqualTo(1);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM refinery_good WHERE refinery_order_id = ?",
                Integer.class,
                open))
        .isZero();
    assertThat(statusOf(stored)).isEqualTo("OPEN");
    String feed =
        relayed(get(FEED).param("cursor", cursor), client)
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(JsonPath.<List<String>>read(feed, "$.removed[?(@.key == '" + open + "')].clientKey"))
        .containsExactly("d-1");
    assertThat(JsonPath.<List<Object>>read(feed, "$.removed[?(@.key == '" + linked + "')]"))
        .hasSize(1);
  }

  @Test
  void deletesAboveTheGuardAreHeldAndApplyOnceConfirmedWhileCancelsNeverTripIt() throws Exception {
    List<UUID> orders = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      orders.add(ownOrder());
    }
    changes(client, String.join(",", orders.stream().map(id -> cancel(id, 0)).toList()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(6));
    String deletes =
        String.join(
            ",",
            orders.stream()
                .map(id ->
                    "{\"op\": \"delete\", \"id\": \""
                        + id
                        + "\", \"versions\": {\"gameData\": 1, \"finances\": 0}}")
                .toList());

    changes(client, deletes)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("MASS_CHANGE_CONFIRMATION_REQUIRED"));
    assertThat(ownedOrders()).isEqualTo(6);

    ConnectedAppMassChangeResultDto confirmed =
        massChangeService.confirm(
            member,
            new ConnectedAppMassChangeRequestDto(
                client, KEY, "refinery-orders", "{\"ops\": [" + deletes + "]}", Instant.now()));

    assertThat(confirmed.applied()).isEqualTo(6);
    assertThat(ownedOrders()).isZero();
  }

  @Test
  void anUndoRecreatesADeletedOrderUnderItsIdsWithItsKeyMarkerAndMission() throws Exception {
    String first =
        changes(
                client,
                createWith(
                    "u-1",
                    refineryRef(),
                    unit,
                    "[{\"input\": {\"bt\": \""
                        + ore
                        + "\"}, \"inputQuantity\": 500, \"outputQuantity\": 400,"
                        + " \"quality\": 700}]"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID id = UUID.fromString(JsonPath.read(first, "$.orders[0].id"));
    UUID good = UUID.fromString(JsonPath.read(first, "$.orders[0].goods[0]"));
    UUID linkedMission = mission();
    jdbc.update(
        "UPDATE refinery_order SET mission_id = ?, expenses = 1200 WHERE id = ?",
        linkedMission,
        id);
    backdateJournal();
    Instant since = Instant.now().minus(1, ChronoUnit.MINUTES);
    changes(client, delete(id)).andExpect(jsonPath("$.applied").value(1));
    String cursor =
        JsonPath.read(
            relayed(get(FEED), client).andReturn().getResponse().getContentAsString(),
            "$.nextCursor");

    ExchangeUndoResultDto result = undoService.undo(member, client, since);

    assertThat(result.restored()).isEqualTo(1);
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT status, created_source, created_client, owning_org_unit_id, mission_id,"
                + " expenses, game_data_version, finances_version FROM refinery_order"
                + " WHERE id = ?",
            id);
    assertThat(row.get("status")).isEqualTo("OPEN");
    assertThat(row.get("created_source")).isEqualTo("CLIENT");
    assertThat(row.get("created_client")).isEqualTo(client);
    assertThat(row.get("owning_org_unit_id")).isEqualTo(unit);
    assertThat(row.get("mission_id")).isEqualTo(linkedMission);
    assertThat(row.get("expenses")).isEqualTo(1200.0);
    assertThat(((Number) row.get("game_data_version")).longValue()).isEqualTo(1L);
    assertThat(
            jdbc.queryForObject(
                "SELECT input_quantity FROM refinery_good WHERE id = ? AND refinery_order_id = ?",
                Integer.class,
                good,
                id))
        .isEqualTo(500);
    assertThat(audited("REFINERY_ORDER_RESTORED", id)).isEqualTo(1);
    String feed =
        relayed(get(FEED).param("cursor", cursor), client)
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(JsonPath.<List<String>>read(feed, "$.items[?(@.id == '" + id + "')].clientKey"))
        .containsExactly("u-1");
  }

  @Test
  void withoutTheWriteCapabilityTheChangeSetIsRefused() throws Exception {
    mockMvc
        .perform(
            relayHeaders(
                post(PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"ops\": [" + create("vk-7") + "]}"),
                client,
                "exchange.refinery.read"))
        .andExpect(status().isForbidden());
  }

  @Test
  void aPickupClosesARunningOrderBeforeItsEndAndBooksNothing() throws Exception {
    String created =
        changes(
                client,
                create("vk-p1")
                    + ","
                    + """
                    {"op": "update", "clientKey": "vk-p1", "versions": {"gameData": 0},
                     "endsAt": "2099-01-01T12:00:00Z", "durationMinutes": 90}
                    """)
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID id = UUID.fromString(JsonPath.read(created, "$.orders[0].id"));
    int stock = jdbc.queryForObject("SELECT COUNT(*) FROM inventory_item", Integer.class);
    String snapshot = relayed(get(FEED), client).andReturn().getResponse().getContentAsString();
    String cursor = JsonPath.read(snapshot, "$.nextCursor");

    changes(client, pickup("\"clientKey\": \"vk-p1\"", 1))
        .andExpect(jsonPath("$.applied").value(1))
        .andExpect(jsonPath("$.orders[0].id").value(id.toString()))
        .andExpect(jsonPath("$.orders[0].versions.gameData").value(2))
        .andExpect(jsonPath("$.orders[0].versions.finances").value(0));

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT status, picked_up_at, picked_up_client, stored_at, last_change_client"
                + " FROM refinery_order WHERE id = ?",
            id);
    assertThat(row.get("status")).isEqualTo("COMPLETED");
    assertThat(row.get("picked_up_at")).isNotNull();
    assertThat(row.get("picked_up_client")).isEqualTo(client);
    assertThat(row.get("last_change_client")).isEqualTo(client);
    assertThat(row.get("stored_at")).isNull();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_item", Integer.class))
        .isEqualTo(stock);
    String feed =
        relayed(get(FEED).param("cursor", cursor), client)
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(JsonPath.<List<String>>read(feed, "$.items[?(@.id == '" + id + "')].pickedUpAt"))
        .hasSize(1)
        .doesNotContainNull();
  }

  @Test
  void aPickupOfACompletedOrderIsUnchangedOfACancelledOneRefusedAndOfAStaleCounterAConflict()
      throws Exception {
    UUID completed = ownOrder("COMPLETED");
    UUID canceled = ownOrder("CANCELED");
    UUID open = ownOrder("OPEN");

    changes(
            client,
            pickup("\"id\": \"" + completed + "\"", 0)
                + ","
                + pickup("\"id\": \"" + canceled + "\"", 0)
                + ","
                + pickup("\"id\": \"" + open + "\"", 3))
        .andExpect(jsonPath("$.applied").value(0))
        .andExpect(jsonPath("$.results[0].result").value("unchanged"))
        .andExpect(jsonPath("$.results[1].reason").value("ORDER_CANCELED"))
        .andExpect(jsonPath("$.results[2].reason").value("VERSION_CONFLICT"));
    assertThat(
            jdbc.queryForList(
                "SELECT status FROM refinery_order WHERE id IN (?, ?, ?) ORDER BY status",
                String.class,
                completed,
                canceled,
                open))
        .containsExactly("CANCELED", "COMPLETED", "OPEN");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM refinery_order WHERE owner_id = ? AND picked_up_at IS NOT"
                    + " NULL",
                Integer.class,
                member))
        .isZero();
  }

  @Test
  void anUndoOfAPickupReopensTheOrderAndClearsTheMarker() throws Exception {
    UUID id = ownOrder("IN_PROGRESS");
    Instant since = Instant.now().minus(1, ChronoUnit.MINUTES);
    changes(client, pickup("\"id\": \"" + id + "\"", 0)).andExpect(jsonPath("$.applied").value(1));

    ExchangeUndoResultDto result = undoService.undo(member, client, since);

    assertThat(result.restored()).isEqualTo(1);
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT status, picked_up_at, picked_up_client FROM refinery_order WHERE id = ?", id);
    assertThat(row.get("status")).isEqualTo("IN_PROGRESS");
    assertThat(row.get("picked_up_at")).isNull();
    assertThat(row.get("picked_up_client")).isNull();
  }

  @Test
  void anUndoOfAPickupAndADeleteRecreatesTheOrderRunningAndWithoutTheMarker() throws Exception {
    UUID id = ownOrder("IN_PROGRESS");
    Instant since = Instant.now().minus(1, ChronoUnit.MINUTES);
    changes(client, pickup("\"id\": \"" + id + "\"", 0)).andExpect(jsonPath("$.applied").value(1));
    changes(
            client,
            "{\"op\": \"delete\", \"id\": \""
                + id
                + "\", \"versions\": {\"gameData\": 1, \"finances\": 0}}")
        .andExpect(jsonPath("$.applied").value(1));

    ExchangeUndoResultDto result = undoService.undo(member, client, since);

    assertThat(result.restored()).isEqualTo(1);
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT status, picked_up_at, picked_up_client FROM refinery_order WHERE id = ?", id);
    assertThat(row.get("status")).isEqualTo("IN_PROGRESS");
    assertThat(row.get("picked_up_at")).isNull();
    assertThat(row.get("picked_up_client")).isNull();
  }

  @Test
  void anUndoRecreatesOrdersTheSameSetWroteAndThenDeletedWhereverTheJournalListsTheDelete()
      throws Exception {
    UUID updated = ownOrder();
    UUID canceled = ownOrder();
    UUID goodAdded = ownOrder();
    Instant since = Instant.now().minus(1, ChronoUnit.MINUTES);
    changes(
            client,
            String.join(
                ",",
                "{\"op\": \"update\", \"id\": \""
                    + updated
                    + "\", \"versions\": {\"finances\": 0}, \"expenses\": 10}",
                delete(updated, 0, 1),
                cancel(canceled, 0),
                delete(canceled, 1, 0),
                "{\"op\": \"good-add\", \"id\": \""
                    + goodAdded
                    + "\", \"versions\": {\"gameData\": 0}, \"good\": {\"input\": {\"bt\": \""
                    + ore
                    + "\"}, \"inputQuantity\": 5, \"outputQuantity\": 4, \"quality\": 7}}",
                delete(goodAdded, 1, 0)))
        .andExpect(jsonPath("$.applied").value(6));
    for (UUID order : List.of(updated, canceled, goodAdded)) {
      jdbc.update(
          "UPDATE exchange_journal SET id = ? WHERE entity_key = ? AND action = 'REFINERY_DELETE'",
          new UUID(0L, ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)),
          order.toString());
    }

    ExchangeUndoResultDto result = undoService.undo(member, client, since);

    assertThat(result.skipped()).isEmpty();
    assertThat(result.restored()).isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "SELECT expenses FROM refinery_order WHERE id = ?", Double.class, updated))
        .isNull();
    assertThat(statusOf(canceled)).isEqualTo("OPEN");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM refinery_good WHERE refinery_order_id = ?",
                Integer.class,
                goodAdded))
        .isZero();
  }

  @Test
  void anUndoSkipsAnOrderWhoseGameDataTheWebMovedBetweenTwoOfTheClientsWrites() throws Exception {
    UUID id = ownOrder();
    UUID moved = place("Second Refinery", true);
    Instant since = Instant.now().minus(1, ChronoUnit.MINUTES);
    changes(
            client,
            "{\"op\": \"update\", \"id\": \""
                + id
                + "\", \"versions\": {\"finances\": 0},"
                + " \"expenses\": 10}")
        .andExpect(jsonPath("$.applied").value(1));
    jdbc.update(
        "UPDATE refinery_order SET location_id = ?, game_data_version = 1 WHERE id = ?", moved, id);
    changes(client, pickup("\"id\": \"" + id + "\"", 1)).andExpect(jsonPath("$.applied").value(1));

    ExchangeUndoResultDto result = undoService.undo(member, client, since);

    assertThat(result.restored()).isZero();
    assertThat(result.skipped())
        .extracting(ExchangeUndoResultDto.Skipped::reason)
        .containsExactly("CHANGED_AFTERWARDS");
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT location_id, status, expenses FROM refinery_order WHERE id = ?", id);
    assertThat(row.get("location_id")).isEqualTo(moved);
    assertThat(row.get("status")).isEqualTo("COMPLETED");
    assertThat(row.get("expenses")).isEqualTo(10.0);
  }

  @Test
  void anUndoSkipsAnOrderWhoseFinancesTheWebChangedBeforeTheClientsFirstFinancesWrite()
      throws Exception {
    UUID id = ownOrder();
    Instant since = Instant.now().minus(1, ChronoUnit.MINUTES);
    changes(
            client,
            "{\"op\": \"update\", \"id\": \""
                + id
                + "\", \"versions\": {\"gameData\": 0},"
                + " \"status\": \"IN_PROGRESS\"}")
        .andExpect(jsonPath("$.applied").value(1));
    jdbc.update("UPDATE refinery_order SET expenses = 50, finances_version = 1 WHERE id = ?", id);
    changes(
            client,
            "{\"op\": \"update\", \"id\": \""
                + id
                + "\", \"versions\": {\"finances\": 1},"
                + " \"expenses\": 70}")
        .andExpect(jsonPath("$.applied").value(1));

    ExchangeUndoResultDto result = undoService.undo(member, client, since);

    assertThat(result.skipped())
        .extracting(ExchangeUndoResultDto.Skipped::reason)
        .containsExactly("CHANGED_AFTERWARDS");
    assertThat(statusOf(id)).isEqualTo("IN_PROGRESS");
    assertThat(
            jdbc.queryForObject(
                "SELECT expenses FROM refinery_order WHERE id = ?", Double.class, id))
        .isEqualTo(70.0);
  }

  @Test
  void anUndoSetsBackTheRunOfAnOrderWhoseStationLostItsRefineryAndKeepsItsLocation()
      throws Exception {
    UUID id = ownOrder();
    UUID canceled = ownOrder();
    Instant since = Instant.now().minus(1, ChronoUnit.MINUTES);
    changes(
            client,
            "{\"op\": \"update\", \"id\": \""
                + id
                + "\", \"versions\": {\"gameData\": 0}, \"endsAt\": \"2030-01-01T12:00:00Z\","
                + " \"durationMinutes\": 90},"
                + cancel(canceled, 0))
        .andExpect(jsonPath("$.applied").value(2));
    jdbc.update(
        "UPDATE city SET has_refinery_terminal = false WHERE id = (SELECT city_id FROM location"
            + " WHERE id = ?)",
        refinery);

    ExchangeUndoResultDto result = undoService.undo(member, client, since);

    assertThat(result.skipped()).isEmpty();
    assertThat(result.restored()).isEqualTo(2);
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT location_id, started_at, duration_minutes FROM refinery_order WHERE id = ?",
            id);
    assertThat(row.get("location_id")).isEqualTo(refinery);
    assertThat(row.get("started_at")).isNull();
    assertThat(row.get("duration_minutes")).isNull();
    assertThat(statusOf(canceled)).isEqualTo("OPEN");
  }

  @Test
  void anUndoOfADeleteAndOfACreateThatTookItsKeyOverGivesTheKeyBackToTheDeletedOrder()
      throws Exception {
    Instant since = Instant.now().minus(1, ChronoUnit.MINUTES);
    String first = changes(client, create("k-1")).andReturn().getResponse().getContentAsString();
    UUID deleted = UUID.fromString(JsonPath.read(first, "$.orders[0].id"));
    backdateJournal();
    changes(client, delete(deleted)).andExpect(jsonPath("$.applied").value(1));
    String second = changes(client, create("k-1")).andReturn().getResponse().getContentAsString();
    UUID replacement = UUID.fromString(JsonPath.read(second, "$.orders[0].id"));

    ExchangeUndoResultDto result = undoService.undo(member, client, since);

    assertThat(result.restored()).isEqualTo(2);
    assertThat(ownedOrders()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT retired_at IS NOT NULL FROM exchange_refinery_order_link"
                    + " WHERE refinery_order_id = ?",
                Boolean.class,
                replacement))
        .isTrue();
    String feed = relayed(get(FEED), client).andReturn().getResponse().getContentAsString();
    assertThat(JsonPath.<List<String>>read(feed, "$.items[?(@.id == '" + deleted + "')].clientKey"))
        .containsExactly("k-1");
    changes(
            client,
            "{\"op\": \"update\", \"clientKey\": \"k-1\", \"versions\": {\"finances\": 1},"
                + " \"expenses\": 10}")
        .andExpect(jsonPath("$.orders[0].id").value(deleted.toString()));
  }

  @Test
  void anUndoOfADeleteThatRestoresAnEarlierRunClearsTheReadyMarker() throws Exception {
    UUID id = ownOrder();
    jdbc.update(
        "UPDATE refinery_order SET started_at = '2020-01-01T00:00:00Z', duration_minutes = 60,"
            + " ready_notified_at = now() WHERE id = ?",
        id);
    Instant since = Instant.now().minus(1, ChronoUnit.MINUTES);
    changes(
            client,
            "{\"op\": \"update\", \"id\": \""
                + id
                + "\", \"versions\": {\"gameData\": 0}, \"endsAt\": \"2021-06-01T00:00:00Z\","
                + " \"durationMinutes\": 60}")
        .andExpect(jsonPath("$.applied").value(1));
    jdbc.update("UPDATE refinery_order SET ready_notified_at = now() WHERE id = ?", id);
    changes(client, delete(id, 1, 0)).andExpect(jsonPath("$.applied").value(1));

    ExchangeUndoResultDto result = undoService.undo(member, client, since);

    assertThat(result.restored()).isEqualTo(1);
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT started_at, ready_notified_at FROM refinery_order WHERE id = ?", id);
    assertThat(((java.sql.Timestamp) row.get("started_at")).toInstant())
        .isEqualTo(Instant.parse("2020-01-01T00:00:00Z"));
    assertThat(row.get("ready_notified_at")).isNull();
  }

  @Test
  void theRetentionDeletesTheKeyOfAGoneOrderOnlyOnceNothingRefersToTheOrder() throws Exception {
    UUID neverReferenced = UUID.randomUUID();
    UUID announced = ownOrder();
    jdbc.update("DELETE FROM refinery_order WHERE id = ?", announced);
    UUID existing = ownOrder();
    jdbc.update(
        "DELETE FROM exchange_change WHERE resource = 'REFINERY_ORDER' AND entity_key = ?",
        existing.toString());
    for (UUID order : List.of(neverReferenced, announced, existing)) {
      jdbc.update(
          "INSERT INTO exchange_refinery_order_link (id, user_id, client_id, client_key,"
              + " refinery_order_id) VALUES (?, ?, ?, ?, ?)",
          UUID.randomUUID(),
          member,
          client,
          "p-" + order,
          order);
    }

    refineryOrderKeys.purgeUnreferenced();

    assertThat(
            jdbc.queryForList(
                "SELECT refinery_order_id FROM exchange_refinery_order_link WHERE user_id = ?",
                UUID.class,
                member))
        .containsExactlyInAnyOrder(announced, existing);
  }

  @Test
  void twoRefsToOneMaterialResolveAndACreateNamesItsNewGoodsInTheOrderItListsThem()
      throws Exception {
    String byName =
        "{\"input\": {\"bt\": \""
            + ore
            + "\", \"name\": \"Quantainium "
            + ore
            + "\"}, \"inputQuantity\": 300, \"outputQuantity\": 200, \"quality\": 700}";

    String body =
        changes(
                client,
                createWith("m-1", refineryRef(), unit, "[" + good(500, 400) + "," + byName + "]")
                    + ","
                    + createWith(
                        "m-2",
                        refineryRef(),
                        unit,
                        "["
                            + good(100, 90)
                            + ","
                            + good(200, 150)
                            + ","
                            + good(300, 250)
                            + ","
                            + good(400, 350)
                            + "]"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.applied").value(2))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(inputQuantities(JsonPath.read(body, "$.orders[0].goods"))).containsExactly(500, 300);
    assertThat(inputQuantities(JsonPath.read(body, "$.orders[1].goods")))
        .containsExactly(100, 200, 300, 400);
  }

  @Test
  void aCreateWithoutGoodsAndARemovalOfAnOrdersLastGoodAreGoodsRequired() throws Exception {
    String created = changes(client, create("g-1")).andReturn().getResponse().getContentAsString();
    String id = JsonPath.read(created, "$.orders[0].id");
    String good = JsonPath.read(created, "$.orders[0].goods[0]");

    changes(
            client,
            createWith("g-2", refineryRef(), unit, null)
                + ","
                + createWith("g-3", refineryRef(), unit, "[]")
                + ",{\"op\": \"good-remove\", \"id\": \""
                + id
                + "\", \"versions\": {\"gameData\": 0}, \"goodId\": \""
                + good
                + "\"}")
        .andExpect(jsonPath("$.applied").value(0))
        .andExpect(jsonPath("$.results[0].reason").value("GOODS_REQUIRED"))
        .andExpect(jsonPath("$.results[1].reason").value("GOODS_REQUIRED"))
        .andExpect(jsonPath("$.results[2].reason").value("GOODS_REQUIRED"));

    assertThat(ownedOrders()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM refinery_good WHERE id = ?",
                Integer.class,
                UUID.fromString(good)))
        .isEqualTo(1);
  }

  @Test
  void anotherMembersOrderIsNeverLockedSoItsOwnersConcurrentEditDoesNotHoldUpTheSet()
      throws Exception {
    UUID theirs = foreignOrder();
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread owner =
        new Thread(() ->
            new TransactionTemplate(transactionManager)
                .executeWithoutResult(_ -> {
                  jdbc.queryForList(
                      "SELECT id FROM refinery_order WHERE id = ? FOR UPDATE", theirs);
                  locked.countDown();
                  awaitQuietly(release);
                }));
    owner.start();
    try {
      assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
      assertTimeoutPreemptively(
          Duration.ofSeconds(10),
          () ->
              changes(
                      client,
                      "{\"op\": \"update\", \"id\": \""
                          + theirs
                          + "\", \"versions\": {\"finances\": 0}, \"expenses\": 10},"
                          + cancel(theirs, 0)
                          + ","
                          + delete(theirs))
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$.applied").value(0))
                  .andExpect(jsonPath("$.results[0].reason").value("ORDER_UNKNOWN"))
                  .andExpect(jsonPath("$.results[1].reason").value("ORDER_UNKNOWN"))
                  .andExpect(jsonPath("$.results[2].reason").value("ORDER_UNKNOWN")));
    } finally {
      release.countDown();
      owner.join(30_000);
    }
    assertThat(statusOf(theirs)).isEqualTo("OPEN");
  }

  @Test
  void aCreateWaitsForAConcurrentCreateUnderItsKeyAndThenNamesThatOrder() throws Exception {
    UUID theirs = UUID.randomUUID();
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread concurrent =
        new Thread(() ->
            new TransactionTemplate(transactionManager)
                .executeWithoutResult(_ -> {
                  jdbc.queryForList(
                      "SELECT pg_advisory_xact_lock(?, hashtext(CAST(? AS text) || '|' || ?"
                          + " || '|' || ?))",
                      ExchangeRefineryOrderLinkRepository.KEY_LOCK_SPACE,
                      member,
                      client,
                      "k-race");
                  locked.countDown();
                  awaitQuietly(release);
                  jdbc.update(
                      "INSERT INTO refinery_order (id, version, owner_id, location_id, status,"
                          + " last_changed_at) VALUES (?, 0, ?, ?, 'OPEN', now())",
                      theirs,
                      member,
                      refinery);
                  jdbc.update(
                      "INSERT INTO exchange_refinery_order_link (id, user_id, client_id,"
                          + " client_key, refinery_order_id) VALUES (?, ?, ?, 'k-race', ?)",
                      UUID.randomUUID(),
                      member,
                      client,
                      theirs);
                }));
    concurrent.start();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
      Future<String> result =
          executor.submit(() ->
              changes(client, create("k-race")).andReturn().getResponse().getContentAsString());
      assertTimeoutPreemptively(
          Duration.ofSeconds(10),
          () -> {
            while (jdbc.queryForObject(
                    "SELECT COUNT(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted",
                    Integer.class)
                == 0) {
              Thread.onSpinWait();
            }
          });
      release.countDown();
      String body = result.get(30, TimeUnit.SECONDS);
      assertThat(JsonPath.<Integer>read(body, "$.applied")).isZero();
      assertThat(JsonPath.<String>read(body, "$.results[0].reason")).isEqualTo("ALREADY_EXISTS");
      assertThat(JsonPath.<String>read(body, "$.results[0].id")).isEqualTo(theirs.toString());
    } finally {
      release.countDown();
      concurrent.join(30_000);
      executor.shutdownNow();
    }
    assertThat(ownedOrders()).isEqualTo(1);
  }

  @Test
  void aRunLongerThanAYearIsMalformed() throws Exception {
    UUID id = ownOrder();
    String update =
        "{\"op\": \"update\", \"id\": \""
            + id
            + "\", \"versions\": {\"gameData\": 0}, \"endsAt\": \"2030-01-01T12:00:00Z\","
            + " \"durationMinutes\": %d}";

    changes(client, update.formatted(ExchangeRefineryOrderChangeSet.MAX_DURATION_MINUTES + 1))
        .andExpect(status().isBadRequest());
    changes(client, update.formatted(ExchangeRefineryOrderChangeSet.MAX_DURATION_MINUTES))
        .andExpect(jsonPath("$.applied").value(1));
  }

  @Test
  void fieldsAnOpKindDoesNotUseAreIgnored() throws Exception {
    UUID id = ownOrder();

    changes(
            client,
            "{\"op\": \"update\", \"id\": \""
                + id
                + "\", \"versions\": {\"finances\": 0}, \"expenses\": 10, \"orgUnit\": {\"id\": \""
                + foreignUnit
                + "\"}, \"goodId\": \"x\"},"
                + "{\"op\": \"pickup\", \"id\": \""
                + id
                + "\", \"versions\": {\"gameData\": 0}, \"location\": {\"name\": \"Lorville Gate "
                + plainPlace
                + "\"}, \"durationMinutes\": 90, \"method\": {\"code\": \"NO-SUCH-CODE\"}}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(2));

    assertThat(statusOf(id)).isEqualTo("COMPLETED");
  }

  @Test
  void deletesThatRemoveNothingNeverHoldTheSetBack() throws Exception {
    for (int i = 0; i < 6; i++) {
      ownOrder();
    }
    String deletes =
        String.join(",", IntStream.range(0, 6).mapToObj(_ -> delete(UUID.randomUUID())).toList());

    changes(client, deletes)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(0))
        .andExpect(jsonPath("$.results[5].reason").value("ORDER_UNKNOWN"));
  }

  @Test
  void aSecondCreateUnderTheKeyOfARefusedOneIsRefusedAsItsSuccessorNotAsExisting()
      throws Exception {
    changes(
            client,
            createWith("x-1", refineryRef(), foreignUnit, "[" + good(500, 400) + "]")
                + ","
                + create("x-1"))
        .andExpect(jsonPath("$.results[0].reason").value("NOT_A_MEMBER"))
        .andExpect(jsonPath("$.results[1].reason").value("PRECEDING_OP_REJECTED"))
        .andExpect(jsonPath("$.results[1].id").doesNotExist());
  }

  @Test
  void anEndBeyondTheMicrosecondTheDatabaseKeepsIsUnchangedWhenSentAgain() throws Exception {
    UUID id = ownOrder();
    String update =
        "{\"op\": \"update\", \"id\": \""
            + id
            + "\", \"versions\": {\"gameData\": %d}, \"endsAt\":"
            + " \"2030-01-01T12:00:00.123456789Z\", \"durationMinutes\": 90}";

    changes(client, update.formatted(0)).andExpect(jsonPath("$.applied").value(1));
    changes(client, update.formatted(1)).andExpect(jsonPath("$.unchanged").value(1));
  }

  @Test
  void aGoodWhoseInputIsNoLongerRefinableStaysWritableInTheDryRunAndTheRealRunAlike()
      throws Exception {
    String created = changes(client, create("r-1")).andReturn().getResponse().getContentAsString();
    String id = JsonPath.read(created, "$.orders[0].id");
    String good = JsonPath.read(created, "$.orders[0].goods[0]");
    jdbc.update("UPDATE material SET type = 'REFINED' WHERE id = ?", ore);
    String update =
        "{\"op\": \"good-update\", \"id\": \""
            + id
            + "\", \"versions\": {\"gameData\": 0}, \"goodId\": \""
            + good
            + "\", \"outputQuantity\": 350}";

    changes(client, true, update).andExpect(jsonPath("$.applied").value(1));
    changes(client, update).andExpect(status().isOk()).andExpect(jsonPath("$.applied").value(1));

    assertThat(
            jdbc.queryForObject(
                "SELECT output_quantity FROM refinery_good WHERE id = ?",
                Integer.class,
                UUID.fromString(good)))
        .isEqualTo(350);
  }

  @Test
  void aGoodStoredWithoutItsYieldQualityOrOutputNeitherBreaksTheFeedNorAWrite() throws Exception {
    UUID id = ownOrder();
    UUID legacy = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO refinery_good (id, version, input_material_id, input_quantity,
                                   output_material_id, output_quantity, quality, refinery_order_id)
        VALUES (?, 0, ?, 300, NULL, NULL, NULL, ?)
        """,
        legacy,
        ore,
        id);

    relayed(get(FEED), client)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[?(@.id == '" + id + "')].goods.length()").value(0));
    changes(
            client,
            "{\"op\": \"good-add\", \"id\": \""
                + id
                + "\", \"versions\": {\"gameData\": 0}, \"good\": "
                + good(500, 400)
                + "}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(1));

    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM refinery_good WHERE refinery_order_id = ?",
                Integer.class,
                id))
        .isEqualTo(2);
  }

  @Test
  void aCancelSentAgainWithItsFirstCounterIsUnchanged() throws Exception {
    UUID id = ownOrder();

    changes(client, cancel(id, 0)).andExpect(jsonPath("$.applied").value(1));
    changes(client, cancel(id, 0)).andExpect(jsonPath("$.unchanged").value(1));
  }

  @Test
  void aStagedSetThatBooksStockNeedsTheStoreCapabilityWhenTheMemberConfirmsIt() throws Exception {
    UUID id = ownOrder();
    changes(client, cancel(UUID.randomUUID(), 0)).andExpect(status().isOk());
    String staged =
        "{\"ops\": [{\"op\": \"pickup\", \"id\": \""
            + id
            + "\", \"versions\": {\"gameData\": 0}, \"lines\": [{\"material\": {\"bt\": \""
            + refined
            + "\"}, \"location\": "
            + refineryRef()
            + ", \"quality\": 500, \"quantity\": {\"amount\": 1, \"unit\": \"SCU\"}}]}]}";
    ConnectedAppMassChangeRequestDto request =
        new ConnectedAppMassChangeRequestDto(client, KEY, "refinery-orders", staged, Instant.now());

    assertThatThrownBy(() -> massChangeService.confirm(member, request))
        .isInstanceOfSatisfying(
            ExchangeProblemException.class,
            problem -> assertThat(problem.code()).isEqualTo("SCOPE_MISSING"));
    assertThat(statusOf(id)).isEqualTo("OPEN");
  }

  /**
   * Reads the input quantities of goods rows in the given order.
   *
   * @param goods the rows' ids
   * @return each row's input quantity
   */
  private @NotNull List<Integer> inputQuantities(@NotNull List<String> goods) {
    return goods.stream()
        .map(good ->
            jdbc.queryForObject(
                "SELECT input_quantity FROM refinery_good WHERE id = ?",
                Integer.class,
                UUID.fromString(good)))
        .toList();
  }

  /**
   * Waits for a latch, keeping the thread's interrupt.
   *
   * @param latch the latch
   */
  private static void awaitQuietly(@NotNull CountDownLatch latch) {
    try {
      latch.await(30, TimeUnit.SECONDS);
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Moves every journal entry of the member an hour back, so an undo since a minute ago leaves them
   * out whatever the database clock's drift against the JVM's.
   */
  private void backdateJournal() {
    jdbc.update(
        "UPDATE exchange_journal SET recorded_at = recorded_at - INTERVAL '1 hour'"
            + " WHERE user_id = ?",
        member);
  }

  /**
   * A pickup without store lines.
   *
   * @param address the {@code id} or {@code clientKey} member, without braces
   * @param gameData the echoed game-data counter
   * @return the op
   */
  private static @NotNull String pickup(@NotNull String address, long gameData) {
    return "{\"op\": \"pickup\", " + address + ", \"versions\": {\"gameData\": " + gameData + "}}";
  }

  /**
   * Inserts and commits an order of the member at the test refinery.
   *
   * @param status its status
   * @return its id
   */
  private @NotNull UUID ownOrder(@NotNull String status) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO refinery_order (id, version, owner_id, location_id, status, last_changed_at)"
            + " VALUES (?, 0, ?, ?, ?, now())",
        id,
        member,
        refinery,
        status);
    return id;
  }

  /**
   * Posts a change set.
   *
   * @param as the client
   * @param ops the ops, comma-separated
   * @return the result
   * @throws Exception if the request fails
   */
  private @NotNull ResultActions changes(@NotNull String as, @NotNull String ops) throws Exception {
    return changes(as, false, ops);
  }

  /**
   * Posts a change set.
   *
   * @param as the client
   * @param dryRun whether only to decide
   * @param ops the ops, comma-separated
   * @return the result
   * @throws Exception if the request fails
   */
  private @NotNull ResultActions changes(@NotNull String as, boolean dryRun, @NotNull String ops)
      throws Exception {
    return relayed(
        post(PATH)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"dryRun\": " + dryRun + ", \"ops\": [" + ops + "]}"),
        as);
  }

  /**
   * A create at the test refinery in the member's unit, running, with one good of the test ore.
   *
   * @param key the client key
   * @return the op
   */
  private @NotNull String create(@NotNull String key) {
    return "{\"op\": \"create\", \"clientKey\": \""
        + key
        + "\", \"orgUnit\": {\"id\": \""
        + unit
        + "\"}, \"location\": "
        + refineryRef()
        + ", \"status\": \"IN_PROGRESS\", \"goods\": ["
        + good(500, 400)
        + "]}";
  }

  /**
   * A good of the test ore at grade 700.
   *
   * @param inputQuantity the input amount in units
   * @param outputQuantity the yield in units
   * @return the good
   */
  private @NotNull String good(int inputQuantity, int outputQuantity) {
    return "{\"input\": {\"bt\": \""
        + ore
        + "\"}, \"inputQuantity\": "
        + inputQuantity
        + ", \"outputQuantity\": "
        + outputQuantity
        + ", \"quality\": 700}";
  }

  /**
   * A create with the given place, unit and goods.
   *
   * @param key the client key
   * @param location the location reference
   * @param orgUnit the owning unit
   * @param goods the goods array, or {@code null}
   * @return the op
   */
  private @NotNull String createWith(
      @NotNull String key, @NotNull String location, @NotNull UUID orgUnit, String goods) {
    return "{\"op\": \"create\", \"clientKey\": \""
        + key
        + "\", \"orgUnit\": {\"id\": \""
        + orgUnit
        + "\"}, \"location\": "
        + location
        + (goods == null ? "" : ", \"goods\": " + goods)
        + "}";
  }

  /**
   * A cancel of an order.
   *
   * @param id the order
   * @param gameData the echoed game-data counter
   * @return the op
   */
  private static @NotNull String cancel(@NotNull UUID id, long gameData) {
    return "{\"op\": \"cancel\", \"id\": \""
        + id
        + "\", \"versions\": {\"gameData\": "
        + gameData
        + "}}";
  }

  /**
   * A delete of an order whose counters are both still at 0.
   *
   * @param id the order
   * @return the op
   */
  private static @NotNull String delete(@NotNull UUID id) {
    return delete(id, 0, 0);
  }

  /**
   * A delete of an order.
   *
   * @param id the order
   * @param gameData the echoed game-data counter
   * @param finances the echoed finances counter
   * @return the op
   */
  private static @NotNull String delete(@NotNull UUID id, long gameData, long finances) {
    return "{\"op\": \"delete\", \"id\": \""
        + id
        + "\", \"versions\": {\"gameData\": "
        + gameData
        + ", \"finances\": "
        + finances
        + "}}";
  }

  /**
   * Reads an order's status.
   *
   * @param id the order
   * @return its status
   */
  private @NotNull String statusOf(@NotNull UUID id) {
    return jdbc.queryForObject("SELECT status FROM refinery_order WHERE id = ?", String.class, id);
  }

  /**
   * Counts the audit events of one type about one order.
   *
   * @param type the event type
   * @param id the order
   * @return the number of events
   */
  private int audited(@NotNull String type, @NotNull UUID id) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM audit_event WHERE event_type = ? AND subject_id = ?",
        Integer.class,
        type,
        id);
  }

  /**
   * Counts the member's orders.
   *
   * @return the number of orders the member owns
   */
  private int ownedOrders() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM refinery_order WHERE owner_id = ?", Integer.class, member);
  }

  /**
   * Inserts and commits an open order of the member at the test refinery, counters at 0.
   *
   * @return its id
   */
  private @NotNull UUID ownOrder() {
    UUID id = foreignOrder();
    jdbc.update("UPDATE refinery_order SET owner_id = ? WHERE id = ?", member, id);
    return id;
  }

  /**
   * Inserts and commits a mission.
   *
   * @return its id
   */
  private @NotNull UUID mission() {
    UUID id = UUID.randomUUID();
    jdbc.update("INSERT INTO mission (id, name, version) VALUES (?, ?, 0)", id, "Op " + id);
    missions.add(id);
    return id;
  }

  /**
   * The test refinery's reference by its exact name.
   *
   * @return the reference
   */
  private @NotNull String refineryRef() {
    return "{\"name\": \"ARC-L1 Refinery " + refinery + "\"}";
  }

  /**
   * Inserts and commits an order of the other member at the test refinery.
   *
   * @return its id
   */
  private @NotNull UUID foreignOrder() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO refinery_order (id, version, owner_id, location_id, status, last_changed_at)"
            + " VALUES (?, 0, ?, ?, 'OPEN', now())",
        id,
        other,
        refinery);
    return id;
  }

  /**
   * Inserts a location on a city of its own named {@code <name> <id>}.
   *
   * @param name the name prefix
   * @param hostsRefinery whether its city has a refinery terminal
   * @return its id
   */
  private @NotNull UUID place(@NotNull String name, boolean hostsRefinery) {
    UUID id = UUID.randomUUID();
    TestPlaces.insertLocation(jdbc, id, name + " " + id);
    jdbc.update(
        "UPDATE city SET has_refinery_terminal = ? WHERE id = (SELECT city_id FROM location"
            + " WHERE id = ?)",
        hostsRefinery,
        id);
    locations.add(id);
    return id;
  }

  /**
   * Seeds a material.
   *
   * @param name the name prefix
   * @param type {@code RAW} or {@code REFINED}
   * @param refinedInto the material it refines into, or {@code null}
   * @return its id
   */
  private @NotNull UUID material(@NotNull String name, @NotNull String type, UUID refinedInto) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO material (id, name, type, quantity_type, is_manual_raw_material,
                              is_job_order, is_visible, source_systems, refined_material_id)
        VALUES (?, ?, ?, 'SCU', false, false, true, 'UEX_ONLY', ?)
        """,
        id,
        name + " " + id,
        type,
        refinedInto);
    materials.add(id);
    return id;
  }

  /**
   * Seeds an org unit.
   *
   * @return its id
   */
  private @NotNull UUID orgUnit() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO org_unit (id, kind, name, shorthand, active,"
            + " is_profit_eligible) VALUES (?, 'SQUADRON', ?, ?, TRUE, FALSE)",
        id,
        "Unit " + id,
        "U" + id.toString().substring(0, 8));
    return id;
  }

  /**
   * Registers an active client with the refinery capabilities.
   *
   * @param prefix the client id's prefix
   * @return the client id
   */
  private @NotNull String register(@NotNull String prefix) {
    String id = prefix + UUID.randomUUID().toString().substring(0, 8);
    ExchangeClient registered = new ExchangeClient();
    registered.setClientId(id);
    registered.setDisplayName("Client " + id);
    registered.setStatus(ExchangeClientStatus.ACTIVE);
    registered.setCapabilities(
        EnumSet.of(
            ExchangeCapability.CONNECT,
            ExchangeCapability.REFINERY_READ,
            ExchangeCapability.REFINERY_WRITE));
    clientRepository.saveAndFlush(registered);
    return id;
  }

  /**
   * Seeds a member.
   *
   * @param username the username prefix
   * @return the member
   */
  private @NotNull User user(@NotNull String username) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username + "-" + UUID.randomUUID());
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    user.setRoles(new HashSet<>(Set.of(roleRepository.findByCode(Roles.KRT_MEMBER).orElseThrow())));
    return userRepository.saveAndFlush(user);
  }

  /**
   * Performs a request relayed for the member with both refinery capabilities.
   *
   * @param request the request
   * @param as the client
   * @return the result
   * @throws Exception if the request fails
   */
  private @NotNull ResultActions relayed(
      @NotNull MockHttpServletRequestBuilder request, @NotNull String as) throws Exception {
    return mockMvc.perform(
        relayHeaders(request, as, "exchange.refinery.read,exchange.refinery.write"));
  }

  /**
   * Adds the gateway's identity and the relay headers.
   *
   * @param request the request
   * @param as the client
   * @param capabilities the relayed capabilities
   * @return the request
   */
  private @NotNull MockHttpServletRequestBuilder relayHeaders(
      @NotNull MockHttpServletRequestBuilder request,
      @NotNull String as,
      @NotNull String capabilities) {
    return request
        .with(jwt().jwt(t -> t.subject(GATEWAY).claim("azp", "test-ingest-gateway")))
        .header(ActingMemberHeader.ON_BEHALF_OF_HEADER, member.toString())
        .header(ActingMemberHeader.EXCHANGE_CLIENT_HEADER, as)
        .header(ActingMemberHeader.EXCHANGE_CAPABILITIES_HEADER, capabilities)
        .header(ActingMemberHeader.EXCHANGE_INSTALLATION_HEADER, KEY);
  }
}

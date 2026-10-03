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

package de.greluc.krt.profit.basetool.backend.controller.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.ExchangeCapability;
import de.greluc.krt.profit.basetool.backend.model.ExchangeClient;
import de.greluc.krt.profit.basetool.backend.model.ExchangeClientStatus;
import de.greluc.krt.profit.basetool.backend.model.ExchangeJournalEntry;
import de.greluc.krt.profit.basetool.backend.model.ExchangeSettings;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.scwiki.Blueprint;
import de.greluc.krt.profit.basetool.backend.repository.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeClientRepository;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeJournalRepository;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeSettingsRepository;
import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.service.BlueprintNameNormalizer;
import de.greluc.krt.profit.basetool.backend.service.DefaultBlueprintKeyService;
import de.greluc.krt.profit.basetool.backend.support.ActingMemberHeader;
import de.greluc.krt.profit.basetool.backend.support.KnownExchangeClients;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/**
 * A client's blueprint changes, relayed from the ingest gateway: adds and removes planned as a
 * whole, refusals per op, the mass-change guard, and every write journaled (REQ-XCH-014,
 * REQ-XCH-015, REQ-XCH-021, REQ-XCH-022). Writes commit, because the tombstone check reads finished
 * transactions.
 */
@SpringBootTest
@TestPropertySource(properties = "app.security.ingest-gateway.client-ids=test-ingest-gateway")
class ExchangeBlueprintWriteControllerTest {

  private static final String PATH = "/api/v1/exchange/me/blueprints/changes";
  private static final String GATEWAY = "55555555-5555-5555-5555-555555555555";
  private static final String KEY = "Kx9_" + "w".repeat(39);
  private static final String OTHER_KEY = "Kx9_" + "v".repeat(39);

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private ExchangeClientRepository clientRepository;
  @Autowired private ExchangeSettingsRepository settingsRepository;
  @Autowired private ExchangeJournalRepository journalRepository;
  @Autowired private BlueprintRepository blueprintRepository;
  @Autowired private BlueprintNameNormalizer normalizer;
  @Autowired private DefaultBlueprintKeyService defaultKeys;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private KnownExchangeClients knownClients;
  @Autowired private MeterRegistry meterRegistry;

  private MockMvc mockMvc;
  private UUID member;
  private String client;
  private boolean wasEnabled;
  private final List<UUID> blueprints = new ArrayList<>();
  private final List<String> defaults = new ArrayList<>();

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy.class))
            .build();
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("bp-writer-" + UUID.randomUUID());
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    user.setRoles(new HashSet<>(Set.of(roleRepository.findByCode(Roles.KRT_MEMBER).orElseThrow())));
    member = userRepository.saveAndFlush(user).getId();
    client = "vk-" + UUID.randomUUID().toString().substring(0, 8);
    ExchangeClient registered = new ExchangeClient();
    registered.setClientId(client);
    registered.setDisplayName("VerseKit");
    registered.setStatus(ExchangeClientStatus.ACTIVE);
    registered.setCapabilities(
        EnumSet.of(ExchangeCapability.CONNECT, ExchangeCapability.BLUEPRINTS_WRITE));
    clientRepository.saveAndFlush(registered);
    knownClients.invalidate();
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    wasEnabled = settings.isEnabled();
    settings.setEnabled(true);
    settingsRepository.saveAndFlush(settings);
  }

  @AfterEach
  void tearDown() {
    jdbc.update("DELETE FROM exchange_client WHERE client_id = ?", client);
    jdbc.update("DELETE FROM personal_blueprint WHERE owner_user_id = ?", member);
    jdbc.update("DELETE FROM user_roles WHERE user_id = ?", member);
    jdbc.update("DELETE FROM app_user WHERE id = ?", member);
    defaults.forEach(k -> jdbc.update("DELETE FROM default_blueprint WHERE product_key = ?", k));
    defaultKeys.refresh();
    blueprints.forEach(id -> jdbc.update("DELETE FROM blueprint WHERE id = ?", id));
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    settings.setEnabled(wasEnabled);
    settingsRepository.saveAndFlush(settings);
  }

  @Test
  void addsAndRemovesAreAppliedAndJournaled() throws Exception {
    String rifle = product("Arrowhead Rifle");
    String pistol = product("Arclight Pistol");
    owns(pistol);
    double framesBefore = frames("blueprints_own");

    change(
            """
            {"ops":[{"opId":"a1","op":"add","ref":{"bt":"%s"}},
                    {"op":"remove","key":"%s"}]}
            """
                .formatted(rifle, pistol),
            KEY)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(2))
        .andExpect(jsonPath("$.unchanged").value(0))
        .andExpect(jsonPath("$.results.length()").value(0));

    assertThat(owned()).containsExactly(rifle);
    assertThat(
            jdbc.queryForList(
                "SELECT event_type || ':' || client_id FROM audit_event WHERE actor_user_id = ?"
                    + " AND event_type IN ('BLUEPRINT_ADDED', 'BLUEPRINT_REMOVED')",
                String.class,
                member))
        .containsExactlyInAnyOrder("BLUEPRINT_ADDED:" + client, "BLUEPRINT_REMOVED:" + client);
    assertThat(frames("blueprints_own")).isEqualTo(framesBefore + 1);
    assertThat(clientCount(MetricNames.EXCHANGE_WRITES, "outcome", "applied")).isEqualTo(2);
    assertThat(clientCount(MetricNames.EXCHANGE_REMOVALS, "resource", "blueprint")).isEqualTo(1);
    List<ExchangeJournalEntry> journal =
        journalRepository.findAllByUserIdOrderByRecordedAtAsc(member);
    assertThat(journal)
        .extracting(e -> e.getAction().name())
        .containsExactlyInAnyOrder("BLUEPRINT_ADD", "BLUEPRINT_REMOVE");
    assertThat(journal).extracting(ExchangeJournalEntry::getClientId).containsOnly(client);
    assertThat(journal)
        .filteredOn(ExchangeJournalEntry::isRemoval)
        .singleElement()
        .satisfies(e -> assertThat(e.getBeforeState()).contains(pistol));
  }

  @Test
  void anAddRecordsTheClientAndTheSourceItNames() throws Exception {
    String rifle = product("Arrowhead Rifle");
    String pistol = product("Arclight Pistol");

    change(
            """
            {"ops":[{"op":"add","ref":{"bt":"%s"},"provenance":{"source":"log"}},
                    {"op":"add","ref":{"bt":"%s"},"provenance":{"source":"default"}}]}
            """
                .formatted(rifle, pistol),
            KEY)
        .andExpect(jsonPath("$.applied").value(2));

    assertThat(
            jdbc.queryForList(
                "SELECT product_key || ':' || source || ':' || source_client_id"
                    + " FROM personal_blueprint WHERE owner_user_id = ?",
                String.class,
                member))
        .containsExactlyInAnyOrder(rifle + ":LOG:" + client, pistol + ":OTHER:" + client);
  }

  @Test
  void opsThatFindTheirStateOrNoProductAreReportedWithoutWriting() throws Exception {
    String rifle = product("Arrowhead Rifle");
    owns(rifle);

    change(
            """
            {"ops":[{"opId":"dup","op":"add","ref":{"bt":"%s"}},
                    {"op":"remove","key":"nothing-like-this"},
                    {"op":"add","ref":{"bt":"no-such-product-%s"}}]}
            """
                .formatted(rifle, UUID.randomUUID()),
            KEY)
        .andExpect(jsonPath("$.applied").value(0))
        .andExpect(jsonPath("$.unchanged").value(2))
        .andExpect(jsonPath("$.notApplied").value(1))
        .andExpect(jsonPath("$.results[0].opId").value("dup"))
        .andExpect(jsonPath("$.results[0].result").value("unchanged"))
        .andExpect(jsonPath("$.results[2].result").value("unmatched"))
        .andExpect(jsonPath("$.results[2].reason").value("UNMATCHED"));

    assertThat(journalRepository.findAllByUserIdOrderByRecordedAtAsc(member)).isEmpty();
  }

  @Test
  void aDefaultBlueprintIsNotRemovable() throws Exception {
    String rifle = product("Arrowhead Rifle");
    owns(rifle);
    jdbc.update(
        "INSERT INTO default_blueprint (id, product_key, product_name) VALUES (?, ?, ?)",
        UUID.randomUUID(),
        rifle,
        rifle);
    defaults.add(rifle);
    defaultKeys.refresh();

    change("{\"ops\":[{\"op\":\"remove\",\"key\":\"%s\"}]}".formatted(rifle), KEY)
        .andExpect(jsonPath("$.results[0].result").value("rejected"))
        .andExpect(jsonPath("$.results[0].reason").value("DEFAULT_NOT_REMOVABLE"));

    assertThat(owned()).containsExactly(rifle);
  }

  @Test
  void aReAddOfWhatTheMemberRemovedInTheWebNeedsTheOverride() throws Exception {
    String rifle = product("Arrowhead Rifle");
    owns(rifle);
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            s -> {
              jdbc.queryForObject(
                  "SELECT set_config('basetool.change_source', 'web', true)", String.class);
              jdbc.update("DELETE FROM personal_blueprint WHERE owner_user_id = ?", member);
            });
    String add = "{\"ops\":[{\"op\":\"add\",\"ref\":{\"bt\":\"%s\"}%s}]}";

    change(add.formatted(rifle, ""), KEY)
        .andExpect(jsonPath("$.results[0].reason").value("REMOVED_ELSEWHERE"));
    assertThat(owned()).isEmpty();

    change(add.formatted(rifle, ",\"override\":true"), KEY)
        .andExpect(jsonPath("$.applied").value(1));
    assertThat(owned()).containsExactly(rifle);
    assertThat(journalRepository.findAllByUserIdOrderByRecordedAtAsc(member))
        .singleElement()
        .satisfies(e -> assertThat(e.getAfterState()).contains("\"override\":true"));
  }

  @Test
  void anInstallationMayReAddWhatItRemovedButAnotherMayNot() throws Exception {
    String rifle = product("Arrowhead Rifle");
    owns(rifle);
    change("{\"ops\":[{\"op\":\"remove\",\"key\":\"%s\"}]}".formatted(rifle), KEY)
        .andExpect(jsonPath("$.applied").value(1));
    String add = "{\"ops\":[{\"op\":\"add\",\"ref\":{\"bt\":\"%s\"}}]}".formatted(rifle);

    change(add, OTHER_KEY).andExpect(jsonPath("$.results[0].reason").value("REMOVED_ELSEWHERE"));
    change(add, KEY).andExpect(jsonPath("$.applied").value(1));

    assertThat(owned()).containsExactly(rifle);
  }

  @Test
  void aDryRunReportsWithoutWriting() throws Exception {
    String rifle = product("Arrowhead Rifle");

    change(
            "{\"ops\":[{\"op\":\"add\",\"ref\":{\"bt\":\"%s\"}}],\"dryRun\":true}".formatted(rifle),
            KEY)
        .andExpect(jsonPath("$.dryRun").value(true))
        .andExpect(jsonPath("$.applied").value(1));

    assertThat(owned()).isEmpty();
    assertThat(journalRepository.findAllByUserIdOrderByRecordedAtAsc(member)).isEmpty();
  }

  @Test
  void aBatchThatRemovesTooMuchIsHeldBackWholly() throws Exception {
    StringBuilder ops = new StringBuilder();
    for (int i = 0; i < 6; i++) {
      String key = product("Held Blueprint " + i);
      owns(key);
      ops.append(i == 0 ? "" : ",")
          .append("{\"op\":\"remove\",\"key\":\"")
          .append(key)
          .append("\"}");
    }

    change("{\"ops\":[" + ops + "]}", KEY)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("MASS_CHANGE_CONFIRMATION_REQUIRED"));

    assertThat(owned()).hasSize(6);
    assertThat(journalRepository.findAllByUserIdOrderByRecordedAtAsc(member)).isEmpty();
  }

  @Test
  void withoutTheWriteCapabilityTheChangeIsRefused() throws Exception {
    mockMvc
        .perform(
            post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ops\":[{\"op\":\"remove\",\"key\":\"x\"}]}")
                .with(jwt().jwt(t -> t.subject(GATEWAY).claim("azp", "test-ingest-gateway")))
                .header(ActingMemberHeader.ON_BEHALF_OF_HEADER, member.toString())
                .header(ActingMemberHeader.EXCHANGE_CLIENT_HEADER, client)
                .header(ActingMemberHeader.EXCHANGE_CAPABILITIES_HEADER, "exchange.connect")
                .header(ActingMemberHeader.EXCHANGE_INSTALLATION_HEADER, KEY))
        .andExpect(status().isForbidden());
  }

  /**
   * Posts a change set as the test client's installation.
   *
   * @param json the change set
   * @param installation the installation's key thumbprint
   * @return the result
   * @throws Exception if the request fails
   */
  private ResultActions change(@NotNull String json, @NotNull String installation)
      throws Exception {
    return mockMvc.perform(
        post(PATH)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json)
            .with(jwt().jwt(t -> t.subject(GATEWAY).claim("azp", "test-ingest-gateway")))
            .header(ActingMemberHeader.ON_BEHALF_OF_HEADER, member.toString())
            .header(ActingMemberHeader.EXCHANGE_CLIENT_HEADER, client)
            .header(ActingMemberHeader.EXCHANGE_CAPABILITIES_HEADER, "exchange.blueprints.write")
            .header(ActingMemberHeader.EXCHANGE_INSTALLATION_HEADER, installation));
  }

  /**
   * Seeds an active catalogue product with a unique name.
   *
   * @param name the name's stem
   * @return its product key
   */
  private @NotNull String product(@NotNull String name) {
    Blueprint blueprint = new Blueprint();
    blueprint.setScwikiUuid(UUID.randomUUID());
    blueprint.setScwikiKey("bp_" + UUID.randomUUID());
    blueprint.setOutputName(name + " " + UUID.randomUUID().toString().substring(0, 8));
    blueprint.setIsAvailableByDefault(false);
    Blueprint saved = blueprintRepository.saveAndFlush(blueprint);
    blueprints.add(saved.getId());
    return normalizer.normalize(saved.getOutputName());
  }

  /**
   * Gives the member a blueprint directly, as the web would.
   *
   * @param productKey the product key
   */
  private void owns(@NotNull String productKey) {
    jdbc.update(
        """
        INSERT INTO personal_blueprint (id, owner_user_id, product_key, product_name)
        VALUES (?, ?, ?, ?)
        """,
        UUID.randomUUID(),
        member,
        productKey,
        productKey);
  }

  /**
   * Lists the member's product keys.
   *
   * @return the keys
   */
  private @NotNull List<String> owned() {
    return jdbc.queryForList(
        "SELECT product_key FROM personal_blueprint WHERE owner_user_id = ?", String.class, member);
  }

  /**
   * Reads how many live-sync frames the backend accepted for one room class.
   *
   * @param topicClass the room class's metric label
   * @return the count so far
   */
  private double frames(@NotNull String topicClass) {
    io.micrometer.core.instrument.Counter counter =
        meterRegistry
            .find(MetricNames.LIVESYNC_PUBLISH_ACCEPTED)
            .tag(MetricNames.TAG_TOPIC_CLASS, topicClass)
            .counter();
    return counter == null ? 0 : counter.count();
  }

  /**
   * Reads one of this test client's exchange counters.
   *
   * @param name the meter name
   * @param tag a further tag to narrow by
   * @param value its value
   * @return the count, 0 before the first
   */
  private double clientCount(@NotNull String name, @NotNull String tag, @NotNull String value) {
    io.micrometer.core.instrument.Counter counter =
        meterRegistry.find(name).tag(MetricNames.TAG_CLIENT_ID, client).tag(tag, value).counter();
    return counter == null ? 0 : counter.count();
  }
}

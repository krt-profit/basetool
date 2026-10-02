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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.ExchangeCapability;
import de.greluc.krt.profit.basetool.backend.model.ExchangeClient;
import de.greluc.krt.profit.basetool.backend.model.ExchangeClientStatus;
import de.greluc.krt.profit.basetool.backend.model.ExchangeInstallation;
import de.greluc.krt.profit.basetool.backend.model.ExchangeSettings;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeClientRepository;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeInstallationRepository;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeSettingsRepository;
import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.service.DefaultBlueprintKeyService;
import de.greluc.krt.profit.basetool.backend.service.exchange.ExchangeBlueprintFeedService;
import de.greluc.krt.profit.basetool.backend.support.ActingMemberHeader;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/**
 * The member's blueprints as a snapshot and a change feed, relayed from the ingest gateway
 * (REQ-XCH-013, REQ-XCH-015). Writes commit, because the feed reads only finished transactions.
 */
@SpringBootTest
@TestPropertySource(properties = "app.security.ingest-gateway.client-ids=test-ingest-gateway")
class ExchangeBlueprintControllerTest {

  private static final String PATH = "/api/v1/exchange/me/blueprints";
  private static final String GATEWAY = "55555555-5555-5555-5555-555555555555";
  private static final String KEY = "Kx9_" + "e".repeat(39);
  private static final String DEFAULT_PRODUCT = "feed-default-arrowhead";

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private ExchangeClientRepository clientRepository;
  @Autowired private ExchangeSettingsRepository settingsRepository;
  @Autowired private ExchangeInstallationRepository installationRepository;
  @Autowired private DefaultBlueprintKeyService defaultKeys;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbc;

  private MockMvc mockMvc;
  private UUID member;
  private UUID other;
  private String client;
  private ExchangeInstallation installation;
  private boolean wasEnabled;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy.class))
            .build();
    member = user("feed-member").getId();
    other = user("feed-other").getId();
    client = "vk-" + UUID.randomUUID().toString().substring(0, 8);
    ExchangeClient registered = new ExchangeClient();
    registered.setClientId(client);
    registered.setDisplayName("VerseKit");
    registered.setStatus(ExchangeClientStatus.ACTIVE);
    registered.setCapabilities(
        EnumSet.of(ExchangeCapability.CONNECT, ExchangeCapability.BLUEPRINTS_READ));
    registered = clientRepository.saveAndFlush(registered);
    installation = new ExchangeInstallation();
    installation.setClient(registered);
    installation.setUser(userRepository.findById(member).orElseThrow());
    installation.setKeyThumbprint(KEY);
    installation.setFirstSeenAt(Instant.now());
    installation.setLastSeenAt(Instant.now());
    installation = installationRepository.saveAndFlush(installation);
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    wasEnabled = settings.isEnabled();
    settings.setEnabled(true);
    settingsRepository.saveAndFlush(settings);
  }

  @AfterEach
  void tearDown() {
    jdbc.update("DELETE FROM default_blueprint WHERE product_key = ?", DEFAULT_PRODUCT);
    defaultKeys.refresh();
    jdbc.update(
        "UPDATE exchange_feed_horizon SET purged_through_tx = 0, purged_through_seq = 0 WHERE id ="
            + " 1");
    jdbc.update("DELETE FROM exchange_client WHERE client_id = ?", client);
    for (UUID id : List.of(member, other)) {
      jdbc.update("DELETE FROM personal_blueprint WHERE owner_user_id = ?", id);
      jdbc.update("DELETE FROM user_roles WHERE user_id = ?", id);
      jdbc.update("DELETE FROM app_user WHERE id = ?", id);
    }
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    settings.setEnabled(wasEnabled);
    settingsRepository.saveAndFlush(settings);
  }

  @Test
  void aSnapshotPagesThroughTheMembersBlueprintsAndEndsAtTheFeed() throws Exception {
    blueprint(member, "arrowhead");
    blueprint(member, "p4-ar");
    blueprint(member, "s71");
    blueprint(other, "not-mine");

    String first =
        read(relayed(get(PATH).param("limit", "2"), "exchange.blueprints.read"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.removed.length()").value(0))
            .andExpect(jsonPath("$.hasMore").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String next = JsonPath.read(first, "$.nextCursor");
    assertThat(next).startsWith("s1.");

    String second =
        read(relayed(
                get(PATH).param("limit", "2").param("cursor", next), "exchange.blueprints.read"))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.hasMore").value(false))
            .andReturn()
            .getResponse()
            .getContentAsString();

    List<String> keys = new ArrayList<>(JsonPath.<List<String>>read(first, "$.items[*].key"));
    keys.addAll(JsonPath.read(second, "$.items[*].key"));
    assertThat(keys).containsExactlyInAnyOrder("arrowhead", "p4-ar", "s71");
    assertThat((String) JsonPath.read(second, "$.nextCursor")).startsWith("f1.");
  }

  @Test
  void theFeedAnswersAnAdditionAndATombstoneNamingTheRemovingInstallation() throws Exception {
    blueprint(member, "arrowhead");
    blueprint(member, "s71");
    String cursor = snapshotEnd();

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            s -> {
              jdbc.queryForObject(
                  "SELECT set_config('basetool.change_source', ?, true)",
                  String.class,
                  "client|" + client + "|" + KEY);
              jdbc.update(
                  "DELETE FROM personal_blueprint WHERE owner_user_id = ? AND product_key = 's71'",
                  member);
            });
    blueprint(member, "p4-ar");

    read(relayed(get(PATH).param("cursor", cursor), "exchange.blueprints.read"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].key").value("p4-ar"))
        .andExpect(jsonPath("$.items[0].ref.bt").value("p4-ar"))
        .andExpect(jsonPath("$.items[0].isDefault").value(false))
        .andExpect(jsonPath("$.items[0].acquiredAt").doesNotExist())
        .andExpect(jsonPath("$.removed.length()").value(1))
        .andExpect(jsonPath("$.removed[0].key").value("s71"))
        .andExpect(jsonPath("$.removed[0].removedBy.channel").value("client"))
        .andExpect(jsonPath("$.removed[0].removedBy.clientId").value(client))
        .andExpect(
            jsonPath("$.removed[0].removedBy.installationId")
                .value(installation.getId().toString()))
        .andExpect(jsonPath("$.hasMore").value(false));
  }

  @Test
  void aWriterThatCommitsAfterALaterOneIsNeverPassed() throws Exception {
    String cursor = snapshotEnd();

    try (Connection slow = dataSource.getConnection()) {
      slow.setAutoCommit(false);
      try (PreparedStatement insert =
          slow.prepareStatement(
              "INSERT INTO personal_blueprint (id, owner_user_id, product_key, product_name)"
                  + " VALUES (?, ?, 'slow', 'slow')")) {
        insert.setObject(1, UUID.randomUUID());
        insert.setObject(2, member);
        insert.executeUpdate();
      }
      blueprint(member, "fast");

      String held =
          read(relayed(get(PATH).param("cursor", cursor), "exchange.blueprints.read"))
              .andExpect(jsonPath("$.items.length()").value(0))
              .andReturn()
              .getResponse()
              .getContentAsString();
      cursor = JsonPath.read(held, "$.nextCursor");
      slow.commit();
    }

    String caughtUp =
        read(relayed(get(PATH).param("cursor", cursor), "exchange.blueprints.read"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(JsonPath.<List<String>>read(caughtUp, "$.items[*].key"))
        .containsExactly("slow", "fast");
  }

  @Test
  void aKeyChangedTwiceIsAnsweredOnceWithItsCurrentState() throws Exception {
    blueprint(member, "arrowhead");
    String cursor = snapshotEnd();

    jdbc.update(
        "DELETE FROM personal_blueprint WHERE owner_user_id = ? AND product_key = 'arrowhead'",
        member);
    blueprint(member, "arrowhead");

    read(relayed(get(PATH).param("cursor", cursor), "exchange.blueprints.read"))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.removed.length()").value(0));
  }

  @Test
  void anIdleFeedKeepsItsCursorAtTheWatermark() throws Exception {
    String cursor = snapshotEnd();
    blueprint(other, "someone-elses");

    String body =
        read(relayed(get(PATH).param("cursor", cursor), "exchange.blueprints.read"))
            .andExpect(jsonPath("$.items.length()").value(0))
            .andReturn()
            .getResponse()
            .getContentAsString();

    String next = JsonPath.read(body, "$.nextCursor");
    assertThat(Long.parseLong(next.split("\\.")[1]))
        .isGreaterThan(Long.parseLong(cursor.split("\\.")[1]));
  }

  @Test
  void aProductBeyondThePublishedLimitsIsKeyedByItsHashAndItsNameIsCut() throws Exception {
    String longKey = "k".repeat(200);
    jdbc.update(
        """
        INSERT INTO personal_blueprint (id, owner_user_id, product_key, product_name)
        VALUES (?, ?, ?, ?)
        """,
        UUID.randomUUID(),
        member,
        longKey,
        "n".repeat(255));
    String key = ExchangeBlueprintFeedService.keyOf(longKey);

    read(relayed(get(PATH), "exchange.blueprints.read"))
        .andExpect(jsonPath("$.items[0].key").value(key))
        .andExpect(jsonPath("$.items[0].ref.bt").value(key))
        .andExpect(jsonPath("$.items[0].ref.name").value("n".repeat(200)));
  }

  @Test
  void aRecordedSourceIsPublishedAsTheProvenanceAndAnUnrecordedOneIsLeftOut() throws Exception {
    jdbc.update(
        """
        INSERT INTO personal_blueprint (id, owner_user_id, product_key, product_name, source,
                                        source_client_id)
        VALUES (?, ?, 'a-logged', 'A logged', 'LOG', 'versekit')
        """,
        UUID.randomUUID(),
        member);
    blueprint(member, "b-unrecorded");

    read(relayed(get(PATH), "exchange.blueprints.read"))
        .andExpect(jsonPath("$.items[?(@.key == 'a-logged')].provenance.source").value("log"))
        .andExpect(jsonPath("$.items[?(@.key == 'b-unrecorded')].provenance").isEmpty());
  }

  @Test
  void aDefaultBlueprintIsMarkedAndItsSetChangeReachesTheFeed() throws Exception {
    blueprint(member, DEFAULT_PRODUCT);
    String cursor = snapshotEnd();

    jdbc.update(
        "INSERT INTO default_blueprint (id, product_key, product_name) VALUES (?, ?, 'Arrowhead')",
        UUID.randomUUID(),
        DEFAULT_PRODUCT);
    defaultKeys.refresh();

    read(relayed(get(PATH).param("cursor", cursor), "exchange.blueprints.read"))
        .andExpect(jsonPath("$.items[0].key").value(DEFAULT_PRODUCT))
        .andExpect(jsonPath("$.items[0].isDefault").value(true));
  }

  @Test
  void aCursorBelowTheHorizonHasExpired() throws Exception {
    jdbc.update(
        "UPDATE exchange_feed_horizon SET purged_through_tx = 9000000000, purged_through_seq = 1"
            + " WHERE id = 1");

    read(relayed(get(PATH).param("cursor", "f1.5.0"), "exchange.blueprints.read"))
        .andExpect(status().isGone())
        .andExpect(jsonPath("$.code").value("CURSOR_EXPIRED"));
  }

  @Test
  void aCursorTheServerDidNotIssueHasExpired() throws Exception {
    read(relayed(get(PATH).param("cursor", "not-a-cursor"), "exchange.blueprints.read"))
        .andExpect(status().isGone())
        .andExpect(jsonPath("$.code").value("CURSOR_EXPIRED"));
  }

  @Test
  void withoutTheReadCapabilityTheFeedIsRefused() throws Exception {
    read(relayed(get(PATH), "exchange.connect")).andExpect(status().isForbidden());
  }

  /**
   * Reads the snapshot to its end.
   *
   * @return the feed cursor it ends with
   * @throws Exception if the request fails
   */
  private @NotNull String snapshotEnd() throws Exception {
    String body =
        read(relayed(get(PATH), "exchange.blueprints.read"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.nextCursor");
  }

  /**
   * Performs a request.
   *
   * @param request the request
   * @return the result
   * @throws Exception if the request fails
   */
  private ResultActions read(@NotNull MockHttpServletRequestBuilder request) throws Exception {
    return mockMvc.perform(request);
  }

  /**
   * Inserts and commits a blueprint.
   *
   * @param owner the owner
   * @param productKey the product key
   */
  private void blueprint(@NotNull UUID owner, @NotNull String productKey) {
    jdbc.update(
        """
        INSERT INTO personal_blueprint (id, owner_user_id, product_key, product_name)
        VALUES (?, ?, ?, ?)
        """,
        UUID.randomUUID(),
        owner,
        productKey,
        productKey);
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

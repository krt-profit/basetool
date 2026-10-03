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
import de.greluc.krt.profit.basetool.backend.model.ExchangeSettings;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeClientRepository;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeSettingsRepository;
import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.ActingMemberHeader;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import java.util.ArrayList;
import java.util.EnumSet;
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
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The member's own ships as a snapshot and a change feed, relayed from the ingest gateway
 * (REQ-XCH-013, REQ-XCH-017). Writes commit, because the feed reads only finished transactions.
 */
@SpringBootTest
@TestPropertySource(properties = "app.security.ingest-gateway.client-ids=test-ingest-gateway")
class ExchangeShipControllerTest {

  private static final String PATH = "/api/v1/exchange/me/ships";
  private static final String GATEWAY = "55555555-5555-5555-5555-555555555555";
  private static final String KEY = "Kx9_" + "h".repeat(39);

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private ExchangeClientRepository clientRepository;
  @Autowired private ExchangeSettingsRepository settingsRepository;
  @Autowired private JdbcTemplate jdbc;

  private MockMvc mockMvc;
  private UUID member;
  private UUID other;
  private String client;
  private boolean wasEnabled;
  private final List<UUID> shipTypes = new ArrayList<>();
  private final List<UUID> locations = new ArrayList<>();

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy.class))
            .build();
    member = user("ship-member").getId();
    other = user("ship-other").getId();
    client = "vk-" + UUID.randomUUID().toString().substring(0, 8);
    ExchangeClient registered = new ExchangeClient();
    registered.setClientId(client);
    registered.setDisplayName("VerseKit");
    registered.setStatus(ExchangeClientStatus.ACTIVE);
    registered.setCapabilities(
        EnumSet.of(ExchangeCapability.CONNECT, ExchangeCapability.HANGAR_READ));
    clientRepository.saveAndFlush(registered);
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    wasEnabled = settings.isEnabled();
    settings.setEnabled(true);
    settingsRepository.saveAndFlush(settings);
  }

  @AfterEach
  void tearDown() {
    jdbc.update("DELETE FROM exchange_client WHERE client_id = ?", client);
    for (UUID id : List.of(member, other)) {
      jdbc.update("DELETE FROM ship WHERE owner_id = ?", id);
      jdbc.update("DELETE FROM user_roles WHERE user_id = ?", id);
      jdbc.update("DELETE FROM app_user WHERE id = ?", id);
    }
    shipTypes.forEach(id -> jdbc.update("DELETE FROM ship_type WHERE id = ?", id));
    locations.forEach(id -> jdbc.update("DELETE FROM location WHERE id = ?", id));
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    settings.setEnabled(wasEnabled);
    settingsRepository.saveAndFlush(settings);
  }

  @Test
  void theSnapshotHoldsTheMembersShipsInTheContractShape() throws Exception {
    UUID cutlass = shipType("Cutlass Black");
    UUID area18 = location("Area18 " + UUID.randomUUID());
    UUID named = ship(member, "Black Betty", cutlass, "LTI", area18, true);
    UUID unnamed = ship(member, null, cutlass, "6", null, false);
    ship(other, "Not mine", cutlass, "LTI", null, false);

    String body =
        read(relayed(get(PATH)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.removed.length()").value(0))
            .andExpect(jsonPath("$.hasMore").value(false))
            .andReturn()
            .getResponse()
            .getContentAsString();

    String first = "$.items[?(@.shipId == '" + named + "')]";
    assertThat(JsonPath.<List<String>>read(body, first + ".name")).containsExactly("Black Betty");
    assertThat(JsonPath.<List<String>>read(body, first + ".shipType.bt"))
        .containsExactly(cutlass.toString());
    assertThat(JsonPath.<List<String>>read(body, first + ".insurance.kind")).containsExactly("LTI");
    assertThat(JsonPath.<List<Object>>read(body, first + ".insurance.months")).isEmpty();
    assertThat(JsonPath.<List<Boolean>>read(body, first + ".fitted")).containsExactly(true);
    assertThat(JsonPath.<List<String>>read(body, first + ".location.name")).hasSize(1);
    assertThat(JsonPath.<List<Integer>>read(body, first + ".version")).containsExactly(0);

    String second = "$.items[?(@.shipId == '" + unnamed + "')]";
    assertThat(JsonPath.<List<Object>>read(body, second + ".name")).isEmpty();
    assertThat(JsonPath.<List<Object>>read(body, second + ".location")).isEmpty();
    assertThat(JsonPath.<List<String>>read(body, second + ".insurance.kind"))
        .containsExactly("MONTHS");
    assertThat(JsonPath.<List<Integer>>read(body, second + ".insurance.months")).containsExactly(6);
  }

  @Test
  void aSnapshotPagesByShipAndEndsAtTheFeed() throws Exception {
    UUID cutlass = shipType("Cutlass Black");
    for (int i = 0; i < 3; i++) {
      ship(member, "Ship " + i, cutlass, "LTI", null, false);
    }

    String first =
        read(relayed(get(PATH).param("limit", "2")))
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.hasMore").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String next = JsonPath.read(first, "$.nextCursor");

    String second =
        read(relayed(get(PATH).param("limit", "2").param("cursor", next)))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.hasMore").value(false))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat((String) JsonPath.read(second, "$.nextCursor")).startsWith("f1.");
  }

  @Test
  void theFeedAnswersAnEditedShipAndATombstoneForOneGivenAway() throws Exception {
    UUID cutlass = shipType("Cutlass Black");
    UUID kept = ship(member, "Kept", cutlass, "LTI", null, false);
    UUID given = ship(member, "Given", cutlass, "LTI", null, false);
    String cursor = snapshotEnd();

    jdbc.update("UPDATE ship SET fitted = true, version = 1 WHERE id = ?", kept);
    jdbc.update("UPDATE ship SET owner_id = ? WHERE id = ?", other, given);

    read(relayed(get(PATH).param("cursor", cursor)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].shipId").value(kept.toString()))
        .andExpect(jsonPath("$.items[0].fitted").value(true))
        .andExpect(jsonPath("$.items[0].version").value(1))
        .andExpect(jsonPath("$.removed.length()").value(1))
        .andExpect(jsonPath("$.removed[0].key").value(given.toString()))
        .andExpect(jsonPath("$.removed[0].removedBy.channel").value("system"));
  }

  @Test
  void withoutTheReadCapabilityTheFeedIsRefused() throws Exception {
    read(relayedWith(get(PATH), "exchange.connect")).andExpect(status().isForbidden());
  }

  /**
   * Reads the snapshot to its end.
   *
   * @return the feed cursor it ends with
   * @throws Exception if the request fails
   */
  private @NotNull String snapshotEnd() throws Exception {
    String body = read(relayed(get(PATH))).andReturn().getResponse().getContentAsString();
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
   * Inserts and commits a ship.
   *
   * @param owner the owner
   * @param name the name, or {@code null}
   * @param type the ship type
   * @param insurance the stored insurance
   * @param location the location, or {@code null}
   * @param fitted whether it is fitted
   * @return the ship's id
   */
  private @NotNull UUID ship(
      @NotNull UUID owner,
      @Nullable String name,
      @NotNull UUID type,
      @NotNull String insurance,
      @Nullable UUID location,
      boolean fitted) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO ship (id, version, name, ship_type_id, insurance, location_id, fitted, owner_id)
        VALUES (?, 0, ?, ?, ?, ?, ?, ?)
        """,
        id,
        name,
        type,
        insurance,
        location,
        fitted,
        owner);
    return id;
  }

  /**
   * Seeds a ship type.
   *
   * @param name the name
   * @return its id
   */
  private @NotNull UUID shipType(@NotNull String name) {
    UUID id = UUID.randomUUID();
    jdbc.update("INSERT INTO ship_type (id, name) VALUES (?, ?)", id, name + " " + id);
    shipTypes.add(id);
    return id;
  }

  /**
   * Seeds a location.
   *
   * @param name the unique name
   * @return its id
   */
  private @NotNull UUID location(@NotNull String name) {
    UUID id = UUID.randomUUID();
    jdbc.update("INSERT INTO location (id, name, hidden) VALUES (?, ?, false)", id, name);
    locations.add(id);
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
   * Adds the gateway's identity and the relay headers with the read capability.
   *
   * @param request the request
   * @return the request
   */
  private @NotNull MockHttpServletRequestBuilder relayed(
      @NotNull MockHttpServletRequestBuilder request) {
    return relayedWith(request, "exchange.hangar.read");
  }

  /**
   * Adds the gateway's identity and the relay headers.
   *
   * @param request the request
   * @param capabilities the relayed capabilities
   * @return the request
   */
  private @NotNull MockHttpServletRequestBuilder relayedWith(
      @NotNull MockHttpServletRequestBuilder request, @NotNull String capabilities) {
    return request
        .with(jwt().jwt(t -> t.subject(GATEWAY).claim("azp", "test-ingest-gateway")))
        .header(ActingMemberHeader.ON_BEHALF_OF_HEADER, member.toString())
        .header(ActingMemberHeader.EXCHANGE_CLIENT_HEADER, client)
        .header(ActingMemberHeader.EXCHANGE_CAPABILITIES_HEADER, capabilities)
        .header(ActingMemberHeader.EXCHANGE_INSTALLATION_HEADER, KEY);
  }
}

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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
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
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@Transactional
@TestPropertySource(properties = "app.security.ingest-gateway.client-ids=test-ingest-gateway")
class ExchangeAccountCheckControllerTest {

  private static final String PATH = "/api/v1/exchange/me/account-check";
  private static final UUID MEMBER = UUID.fromString("44444444-4444-4444-4444-4444444440c1");
  private static final String GATEWAY = "55555555-5555-5555-5555-555555555555";
  private static final String KEY = "Kx9_" + "c".repeat(39);
  private static final String STORED = "Cutter_Pilot-7";

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private ExchangeClientRepository clientRepository;
  @Autowired private ExchangeSettingsRepository settingsRepository;
  @Autowired private MeterRegistry meterRegistry;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy.class))
            .build();
    User member = new User();
    member.setId(MEMBER);
    member.setUsername("account-check-member");
    member.setApprovalStatus(ApprovalStatus.ACTIVE);
    member.setInKeycloak(true);
    member.setRoles(
        new HashSet<>(Set.of(roleRepository.findByCode(Roles.KRT_MEMBER).orElseThrow())));
    userRepository.saveAndFlush(member);
    ExchangeClient client = new ExchangeClient();
    client.setClientId("versekit-acc");
    client.setDisplayName("VerseKit");
    client.setStatus(ExchangeClientStatus.ACTIVE);
    client.setCapabilities(EnumSet.of(ExchangeCapability.CONNECT));
    clientRepository.saveAndFlush(client);
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    settings.setEnabled(true);
    settingsRepository.saveAndFlush(settings);
  }

  @Test
  void aMemberWithoutAStoredHandleIsUnknown() throws Exception {
    mockMvc
        .perform(check("Cutter_Pilot-7", "exchange.connect"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result").value("unknown"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"Cutter_Pilot-7", "cutter_pilot-7", "CUTTER_PILOT-7"})
  void theStoredHandleMatchesIgnoringCase(@NotNull String handle) throws Exception {
    storeHandle(STORED);

    mockMvc
        .perform(check(handle, "exchange.connect"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result").value("match"));
  }

  @Test
  void anotherHandleIsAMismatchAndTheStoredOneIsNeverReturned() throws Exception {
    storeHandle(STORED);

    String body =
        mockMvc
            .perform(check("Alt_Account", "exchange.connect"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.result").value("mismatch"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).isEqualTo("{\"result\":\"mismatch\"}").doesNotContain(STORED);
  }

  @ParameterizedTest
  @ValueSource(strings = {"ab", "has space", "semi;colon", "umlautä", ""})
  void aValueThatIsNoRsiHandleIsRefusedWithoutEchoingIt(@NotNull String handle) throws Exception {
    String body =
        mockMvc
            .perform(check(handle, "exchange.connect"))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString();

    if (!handle.isEmpty()) {
      assertThat(body.replaceAll("\"correlationId\":\"[^\"]*\"", "")).doesNotContain(handle);
    }
  }

  @Test
  void aMissingHandleIsRefused() throws Exception {
    mockMvc.perform(check(null, "exchange.connect")).andExpect(status().isBadRequest());
  }

  @Test
  void aTokenWithoutTheConnectScopeIsRefused() throws Exception {
    mockMvc
        .perform(check("Cutter_Pilot-7", "exchange.stock.read"))
        .andExpect(status().isForbidden());
  }

  @Test
  void neitherHandleEverReachesALogLine() throws Exception {
    storeHandle(STORED);
    Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    root.addAppender(appender);
    try {
      mockMvc.perform(check("Other_Pilot", "exchange.connect")).andExpect(status().isOk());
      mockMvc.perform(check("bad handle!", "exchange.connect")).andExpect(status().isBadRequest());
    } finally {
      root.detachAppender(appender);
    }
    assertThat(appender.list)
        .noneMatch(e -> e.getFormattedMessage().contains(STORED))
        .noneMatch(e -> e.getFormattedMessage().contains("Other_Pilot"))
        .noneMatch(e -> e.getFormattedMessage().contains("bad handle!"));
  }

  @Test
  void everyAnswerIsCountedByOutcome() throws Exception {
    double unknown = count("unknown");
    mockMvc.perform(check("Cutter_Pilot-7", "exchange.connect")).andExpect(status().isOk());
    storeHandle(STORED);
    double match = count("match");
    double mismatch = count("mismatch");
    mockMvc.perform(check("cutter_pilot-7", "exchange.connect")).andExpect(status().isOk());
    mockMvc.perform(check("Alt_Account", "exchange.connect")).andExpect(status().isOk());

    assertThat(count("unknown")).isEqualTo(unknown + 1);
    assertThat(count("match")).isEqualTo(match + 1);
    assertThat(count("mismatch")).isEqualTo(mismatch + 1);
  }

  /**
   * Stores an RSI handle on the member's profile.
   *
   * @param handle the handle
   */
  private void storeHandle(@NotNull String handle) {
    User member = userRepository.findById(MEMBER).orElseThrow();
    member.setRsiHandle(handle);
    userRepository.saveAndFlush(member);
  }

  /**
   * Reads the account-check counter of one outcome.
   *
   * @param outcome the outcome label
   * @return the current count
   */
  private double count(@NotNull String outcome) {
    return meterRegistry
        .get(MetricNames.EXCHANGE_ACCOUNT_CHECKS)
        .tag(MetricNames.TAG_OUTCOME, outcome)
        .counter()
        .count();
  }

  /**
   * Builds a relayed account check.
   *
   * @param handle the handle, or {@code null} for a body without one
   * @param capabilities the capabilities the gateway relays
   * @return the request
   */
  private static @NotNull MockHttpServletRequestBuilder check(
      @Nullable String handle, @NotNull String capabilities) {
    Map<String, String> body = handle == null ? Map.of() : Map.of("handle", handle);
    return post(PATH)
        .with(jwt().jwt(t -> t.subject(GATEWAY).claim("azp", "test-ingest-gateway")))
        .header(ActingMemberHeader.ON_BEHALF_OF_HEADER, MEMBER.toString())
        .header(ActingMemberHeader.EXCHANGE_CLIENT_HEADER, "versekit-acc")
        .header(ActingMemberHeader.EXCHANGE_CAPABILITIES_HEADER, capabilities)
        .header(ActingMemberHeader.EXCHANGE_INSTALLATION_HEADER, KEY)
        .contentType(MediaType.APPLICATION_JSON)
        .content(JsonMapper.builder().build().writeValueAsString(body));
  }
}

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import de.greluc.krt.profit.basetool.backend.exchange.api.ActingMemberHeader;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
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
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@Transactional
class ExchangeInstallationControllerTest {

  private static final String PATH = "/api/v1/exchange/me/installation";
  private static final UUID MEMBER = UUID.fromString("44444444-4444-4444-4444-4444444440b1");
  private static final String GATEWAY = "55555555-5555-5555-5555-555555555555";
  private static final String KEY = "Kx9_" + "b".repeat(39);

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private ExchangeClientRepository clientRepository;
  @Autowired private ExchangeSettingsRepository settingsRepository;
  @Autowired private ExchangeInstallationRepository installationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy.class))
            .build();
    User member = new User();
    member.setId(MEMBER);
    member.setUsername("installation-member");
    member.setApprovalStatus(ApprovalStatus.ACTIVE);
    member.setInKeycloak(true);
    member.setRoles(
        new HashSet<>(Set.of(roleRepository.findByCode(Roles.KRT_MEMBER).orElseThrow())));
    userRepository.saveAndFlush(member);
    ExchangeClient client = new ExchangeClient();
    client.setClientId("versekit-inst");
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
  void theUpsertTellsWhetherItCreatedTheInstallation() {
    Instant now = Instant.now();
    List<ExchangeInstallationRepository.Touched> first =
        installationRepository.touch("versekit-inst", MEMBER, KEY, now, now.minusSeconds(300));
    assertThat(first).singleElement().satisfies(t -> assertThat(t.getInserted()).isTrue());

    assertThat(
            installationRepository.touch("versekit-inst", MEMBER, KEY, now, now.minusSeconds(300)))
        .as("seen again within the interval: nothing written")
        .isEmpty();

    Instant later = now.plusSeconds(600);
    assertThat(
            installationRepository.touch(
                "versekit-inst", MEMBER, KEY, later, later.minusSeconds(1)))
        .singleElement()
        .satisfies(
            t -> {
              assertThat(t.getInserted()).as("a later touch updates, it does not create").isFalse();
              assertThat(t.getId()).isEqualTo(first.getFirst().getId());
            });
  }

  @Test
  void theInstallationIsCreatedOnFirstSightAndKeepsItsIdWhenLabelled() throws Exception {
    String first =
        mockMvc
            .perform(relayed(get(PATH)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.label").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = JsonPath.read(first, "$.installationId");

    mockMvc
        .perform(label("Gaming-PC 2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.installationId").value(id))
        .andExpect(jsonPath("$.label").value("Gaming-PC 2"));

    ExchangeInstallation stored =
        installationRepository.findById(UUID.fromString(id)).orElseThrow();
    assertThat(stored.getKeyThumbprint()).isEqualTo(KEY);
    assertThat(id).isNotEqualTo(KEY);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "tab\tinside",
        "be\u0007ll",
        "a\u0000b",
        "evil\u202Egnp.exe",
        "zero\u200Bwidth",
        "slash/inside",
        "12345678901234567890123456789012345678901"
      })
  void aLabelBreakingTheRuleIsRefused(@NotNull String label) throws Exception {
    mockMvc.perform(label(label)).andExpect(status().isBadRequest());
  }

  @Test
  void spacesAndControlsAtTheEdgesAreTrimmedAway() throws Exception {
    mockMvc
        .perform(label("  Laptop\u0007 "))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.label").value("Laptop"));
  }

  @Test
  void aHomoglyphOnlyLabelIsAcceptedAndShownAfterTheClientName() throws Exception {
    mockMvc
        .perform(label("\u0420\u0430\u0443\u0420\u0430l"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.label").value("\u0420\u0430\u0443\u0420\u0430l"));
  }

  @Test
  void theLabelNeverReachesALogLine() throws Exception {
    Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    root.addAppender(appender);
    try {
      mockMvc.perform(label("SecretHost-Lucas")).andExpect(status().isOk());
      mockMvc.perform(label("bad\u202Elabel")).andExpect(status().isBadRequest());
    } finally {
      root.detachAppender(appender);
    }
    assertThat(appender.list)
        .noneMatch(e -> e.getFormattedMessage().contains("SecretHost"))
        .noneMatch(e -> e.getFormattedMessage().contains("bad\u202Elabel"));
  }

  @Test
  void aRevokedInstallationIsRefusedWithTheGatewaysCode() throws Exception {
    mockMvc.perform(relayed(get(PATH))).andExpect(status().isOk());
    ExchangeInstallation installation =
        installationRepository.findByKey("versekit-inst", MEMBER, KEY).orElseThrow();
    installation.setRevokedAt(Instant.now());
    installationRepository.saveAndFlush(installation);

    mockMvc
        .perform(relayed(get(PATH)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INSTALLATION_REVOKED"));
  }

  @Test
  void anExchangeCallWithoutAnInstallationKeyIsRefused() throws Exception {
    mockMvc
        .perform(
            get(PATH)
                .with(jwt().jwt(t -> t.subject(GATEWAY).claim("azp", "test-ingest-gateway")))
                .header(ActingMemberHeader.ON_BEHALF_OF_HEADER, MEMBER.toString())
                .header(ActingMemberHeader.EXCHANGE_CLIENT_HEADER, "versekit-inst")
                .header(ActingMemberHeader.EXCHANGE_CAPABILITIES_HEADER, "exchange.connect"))
        .andExpect(status().isForbidden());
  }

  /**
   * Builds a labelling request.
   *
   * @param label the label
   * @return the request
   */
  private @NotNull MockHttpServletRequestBuilder label(@NotNull String label) {
    return relayed(post(PATH))
        .contentType(MediaType.APPLICATION_JSON)
        .content(JsonMapper.builder().build().writeValueAsString(java.util.Map.of("label", label)));
  }

  /**
   * Adds the gateway's identity and the relay headers.
   *
   * @param request the request
   * @return the request
   */
  private static @NotNull MockHttpServletRequestBuilder relayed(
      @NotNull MockHttpServletRequestBuilder request) {
    return request
        .with(jwt().jwt(t -> t.subject(GATEWAY).claim("azp", "test-ingest-gateway")))
        .header(ActingMemberHeader.ON_BEHALF_OF_HEADER, MEMBER.toString())
        .header(ActingMemberHeader.EXCHANGE_CLIENT_HEADER, "versekit-inst")
        .header(ActingMemberHeader.EXCHANGE_CAPABILITIES_HEADER, "exchange.connect")
        .header(ActingMemberHeader.EXCHANGE_INSTALLATION_HEADER, KEY);
  }
}

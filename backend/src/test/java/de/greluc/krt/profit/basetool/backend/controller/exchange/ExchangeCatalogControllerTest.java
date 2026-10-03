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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.ExchangeCapability;
import de.greluc.krt.profit.basetool.backend.model.ExchangeClient;
import de.greluc.krt.profit.basetool.backend.model.ExchangeClientStatus;
import de.greluc.krt.profit.basetool.backend.model.ExchangeSettings;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeClientRepository;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeSettingsRepository;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.service.exchange.ExchangeCatalogService;
import de.greluc.krt.profit.basetool.backend.service.exchange.ExchangeRevocationMirror;
import de.greluc.krt.profit.basetool.backend.support.ActingMemberHeader;
import de.greluc.krt.profit.basetool.backend.support.AuthenticatedSubject;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@Transactional
class ExchangeCatalogControllerTest {

  private static final String PATH = "/api/v1/exchange/catalog/locations";
  private static final UUID MEMBER = UUID.fromString("44444444-4444-4444-4444-4444444440a1");
  private static final String GATEWAY = "55555555-5555-5555-5555-555555555555";

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private ExchangeClientRepository clientRepository;
  @Autowired private ExchangeSettingsRepository settingsRepository;
  @MockitoSpyBean private ExchangeCatalogService catalogService;
  @MockitoBean private ExchangeRevocationMirror revocationMirror;

  private MockMvc mockMvc;
  private ExchangeClient client;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy.class))
            .build();

    User member = new User();
    member.setId(MEMBER);
    member.setUsername("exchange-admin");
    member.setApprovalStatus(ApprovalStatus.ACTIVE);
    member.setInKeycloak(true);
    member.setRoles(
        new HashSet<>(
            Set.of(
                roleRepository.findByCode(Roles.ADMIN).orElseThrow(),
                roleRepository.findByCode(Roles.KRT_MEMBER).orElseThrow())));
    userRepository.saveAndFlush(member);

    Location visible = new Location();
    visible.setName("Xch Visible Station");
    visible.setHidden(false);
    locationRepository.saveAndFlush(visible);
    Location hidden = new Location();
    hidden.setName("Xch Hidden Station");
    hidden.setHidden(true);
    locationRepository.saveAndFlush(hidden);

    client = new ExchangeClient();
    client.setClientId("versekit-test");
    client.setDisplayName("VerseKit");
    client.setStatus(ExchangeClientStatus.ACTIVE);
    client.setCapabilities(
        EnumSet.of(ExchangeCapability.CONNECT, ExchangeCapability.BLUEPRINTS_READ));
    clientRepository.saveAndFlush(client);
    setSwitch(true);
  }

  @Test
  void anAdminMemberReadsTheCatalogueWithTheReducedExchangeAuthentication() throws Exception {
    List<String> seen = new ArrayList<>();
    List<String> clients = new ArrayList<>();
    doAnswer(
            invocation -> {
              Authentication current = SecurityContextHolder.getContext().getAuthentication();
              current.getAuthorities().stream()
                  .map(GrantedAuthority::getAuthority)
                  .forEach(seen::add);
              clients.add(AuthenticatedSubject.authorizedParty(current).orElse(null));
              return invocation.callRealMethod();
            })
        .when(catalogService)
        .locations();

    mockMvc
        .perform(relayed("exchange.connect,exchange.blueprints.read,exchange.unknown"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[?(@.name == 'Xch Visible Station')]").exists())
        .andExpect(jsonPath("$.items[?(@.name == 'Xch Hidden Station')]").doesNotExist());

    assertThat(seen)
        .containsExactlyInAnyOrder(
            "ROLE_EXCHANGE_MEMBER",
            "XCH_CAPABILITY:exchange.blueprints.read",
            "XCH_CAPABILITY:exchange.connect");
    assertThat(clients).containsExactly("versekit-test");
  }

  @Test
  void aBrowserSessionCannotReachTheExchangeLayerEvenAsAdmin() throws Exception {
    mockMvc
        .perform(
            get(PATH)
                .with(
                    jwt()
                        .jwt(
                            token ->
                                token.subject(MEMBER.toString()).claim("azp", "basetool-frontend"))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void theGatewayWithoutAnActingMemberCannotReachTheExchangeLayer() throws Exception {
    mockMvc.perform(get(PATH).with(gateway())).andExpect(status().isForbidden());
  }

  @Test
  void theSwitchOffRefusesWithTheGatewaysCode() throws Exception {
    setSwitch(false);

    mockMvc
        .perform(relayed("exchange.connect"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("EXCHANGE_DISABLED"));
  }

  @Test
  void aSuspendedClientIsRefusedWithTheGatewaysCode() throws Exception {
    client.setStatus(ExchangeClientStatus.SUSPENDED);
    clientRepository.saveAndFlush(client);

    mockMvc
        .perform(relayed("exchange.connect"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CLIENT_SUSPENDED"));
  }

  @Test
  void aClientTheRegistryDoesNotListIsRefusedWithTheGatewaysCode() throws Exception {
    clientRepository.delete(client);
    clientRepository.flush();

    mockMvc
        .perform(relayed("exchange.connect"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CLIENT_NOT_ALLOWED"));
  }

  @Test
  void aCapabilityTheRegistryDoesNotGrantIsNotEnough() throws Exception {
    mockMvc
        .perform(relayed("exchange.stock.write"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SCOPE_MISSING"));
  }

  @Test
  void theBackendRefusesAConnectionMadeBeforeTheMemberDisconnectedTheClient() throws Exception {
    Instant revokedAt = Instant.parse("2026-09-27T10:00:00Z");
    when(revocationMirror.revokedAt("versekit-test", MEMBER)).thenReturn(revokedAt);

    mockMvc
        .perform(
            relayed("exchange.connect")
                .header(
                    ActingMemberHeader.EXCHANGE_CONNECTED_AT_HEADER,
                    Long.toString(revokedAt.getEpochSecond())))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("CLIENT_REVOKED"));
    mockMvc
        .perform(relayed("exchange.connect"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("CLIENT_REVOKED"));
    mockMvc
        .perform(
            relayed("exchange.connect")
                .header(
                    ActingMemberHeader.EXCHANGE_CONNECTED_AT_HEADER,
                    Long.toString(revokedAt.getEpochSecond() + 1)))
        .andExpect(status().isOk());
  }

  @Test
  void anUnreadableRevocationMirrorFailsClosedWithTheGatewaysCode() throws Exception {
    when(revocationMirror.revokedAt("versekit-test", MEMBER))
        .thenThrow(new RedisConnectionFailureException("down"));

    mockMvc
        .perform(relayed("exchange.connect"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("REGISTRY_UNAVAILABLE"));
  }

  @Test
  void aBrowserSessionCannotSendTheRelayedIssueTime() throws Exception {
    mockMvc
        .perform(
            get(PATH)
                .with(
                    jwt()
                        .jwt(
                            token ->
                                token.subject(MEMBER.toString()).claim("azp", "basetool-frontend")))
                .header(ActingMemberHeader.EXCHANGE_CONNECTED_AT_HEADER, "1790000000"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACTING_MEMBER_REFUSED"));
  }

  @Test
  void aPendingMemberIsStoppedByTheApprovalGate() throws Exception {
    User member = userRepository.findById(MEMBER).orElseThrow();
    member.setApprovalStatus(ApprovalStatus.PENDING);
    userRepository.saveAndFlush(member);

    mockMvc
        .perform(relayed("exchange.connect"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PENDING_APPROVAL"));
  }

  /**
   * Builds a gateway request acting for the member, relayed for the test client.
   *
   * @param capabilities the relayed scopes
   * @return the request
   */
  private static @NotNull MockHttpServletRequestBuilder relayed(@NotNull String capabilities) {
    return get(PATH)
        .with(gateway())
        .header(ActingMemberHeader.ON_BEHALF_OF_HEADER, MEMBER.toString())
        .header(ActingMemberHeader.EXCHANGE_CLIENT_HEADER, "versekit-test")
        .header(ActingMemberHeader.EXCHANGE_CAPABILITIES_HEADER, capabilities)
        .header(ActingMemberHeader.EXCHANGE_INSTALLATION_HEADER, "k".repeat(43));
  }

  /**
   * The gateway's own service-account token.
   *
   * @return the request post-processor
   */
  private static @NotNull JwtRequestPostProcessor gateway() {
    return jwt().jwt(token -> token.subject(GATEWAY).claim("azp", "test-ingest-gateway"));
  }

  /**
   * Sets the global exchange switch.
   *
   * @param enabled the state
   */
  private void setSwitch(boolean enabled) {
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    settings.setEnabled(enabled);
    settingsRepository.saveAndFlush(settings);
  }
}

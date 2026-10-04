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

package de.greluc.krt.profit.basetool.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDomain;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.exchange.api.events.ExchangeInstallationConnectedEvent;
import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.ExchangeCapability;
import de.greluc.krt.profit.basetool.backend.model.ExchangeClient;
import de.greluc.krt.profit.basetool.backend.model.ExchangeClientRevocation;
import de.greluc.krt.profit.basetool.backend.model.ExchangeClientStatus;
import de.greluc.krt.profit.basetool.backend.model.ExchangeInstallation;
import de.greluc.krt.profit.basetool.backend.model.Notification;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.AuditEventRepository;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeClientRepository;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeClientRevocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeInstallationRepository;
import de.greluc.krt.profit.basetool.backend.repository.NotificationRepository;
import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.service.KeycloakService;
import de.greluc.krt.profit.basetool.backend.service.NotificationCreationService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@Transactional
class ConnectedAppsControllerTest {

  private static final String PATH = "/api/v1/connected-apps";
  private static final UUID MEMBER = UUID.fromString("44444444-4444-4444-4444-4444444440c1");
  private static final UUID OTHER = UUID.fromString("44444444-4444-4444-4444-4444444440c2");
  private static final String DESKTOP_UNSEEN = "$[0].installations[?(@.label == 'Desktop')].unseen";
  private static final String LAPTOP_UNSEEN = "$[0].installations[?(@.label == 'Laptop')].unseen";

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private ExchangeClientRepository clientRepository;
  @Autowired private ExchangeInstallationRepository installationRepository;
  @Autowired private ExchangeClientRevocationRepository revocationRepository;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private NotificationRepository notificationRepository;
  @Autowired private NotificationCreationService notificationCreationService;
  @MockitoBean private KeycloakService keycloakService;
  @Autowired private JdbcTemplate jdbc;

  private MockMvc mockMvc;
  private ExchangeClient client;
  private ExchangeInstallation mine;
  private ExchangeInstallation theirs;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy.class))
            .build();
    User member = user(MEMBER, "connected-member");
    User other = user(OTHER, "connected-other");
    client = new ExchangeClient();
    client.setClientId("versekit-ca");
    client.setDisplayName("VerseKit");
    client.setStatus(ExchangeClientStatus.ACTIVE);
    client.setCapabilities(EnumSet.of(ExchangeCapability.CONNECT, ExchangeCapability.STOCK_READ));
    clientRepository.saveAndFlush(client);
    mine = installation(member, "m".repeat(43), "Desktop");
    theirs = installation(other, "o".repeat(43), "Theirs");
  }

  @Test
  void eachClientListsItsLatestWritesWithTheirNamesNewestFirst() throws Exception {
    journal(
        MEMBER, "BLUEPRINT", "rifle", "BLUEPRINT_ADD", "Arrowhead Rifle", "2026-09-27T10:00:00Z");
    journal(
        MEMBER,
        "BLUEPRINT",
        "pistol",
        "BLUEPRINT_REMOVE",
        "Arclight Pistol",
        "2026-09-27T11:00:00Z");
    journal(OTHER, "BLUEPRINT", "theirs", "BLUEPRINT_ADD", "Not Mine", "2026-09-27T12:00:00Z");
    jdbc.update(
        "UPDATE exchange_journal SET undone_at = now() WHERE user_id = ? AND entity_key = 'pistol'",
        MEMBER);

    mockMvc
        .perform(get(PATH).with(browser(MEMBER)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].activity.length()").value(2))
        .andExpect(jsonPath("$[0].activity[0].action").value("BLUEPRINT_REMOVE"))
        .andExpect(jsonPath("$[0].activity[0].label").value("Arclight Pistol"))
        .andExpect(jsonPath("$[0].activity[0].undone").value(true))
        .andExpect(jsonPath("$[0].activity[1].label").value("Arrowhead Rifle"))
        .andExpect(jsonPath("$[0].activity[1].undone").value(false));
  }

  @Test
  void theMemberSeesOwnConnectionsOnly() throws Exception {
    mockMvc
        .perform(get(PATH).with(browser(MEMBER)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].clientId").value("versekit-ca"))
        .andExpect(jsonPath("$[0].displayName").value("VerseKit"))
        .andExpect(jsonPath("$[0].installations.length()").value(1))
        .andExpect(jsonPath("$[0].installations[0].label").value("Desktop"));
  }

  @Test
  void aNewInstallationStaysUnseenUntilTheMemberMarksThatInstallationSeen() throws Exception {
    ExchangeInstallation second =
        installation(userRepository.findById(MEMBER).orElseThrow(), "n".repeat(43), "Laptop");
    notificationRepository.saveAndFlush(connected(MEMBER, mine.getId()));
    notificationRepository.saveAndFlush(connected(MEMBER, second.getId()));
    Notification others = notificationRepository.saveAndFlush(connected(OTHER, theirs.getId()));

    mockMvc
        .perform(get(PATH).with(browser(MEMBER)))
        .andExpect(jsonPath(DESKTOP_UNSEEN).value(contains(true)))
        .andExpect(jsonPath(LAPTOP_UNSEEN).value(contains(true)));

    mockMvc
        .perform(post(PATH + "/installations/" + mine.getId() + "/seen").with(browser(MEMBER)))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(post(PATH + "/installations/" + theirs.getId() + "/seen").with(browser(MEMBER)))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(get(PATH).with(browser(MEMBER)))
        .andExpect(jsonPath(DESKTOP_UNSEEN).value(contains(false)))
        .andExpect(jsonPath(LAPTOP_UNSEEN).value(contains(true)));
    assertThat(notificationRepository.findById(others.getId()).orElseThrow().isRead())
        .as("marking seen touches only the caller's own notifications")
        .isFalse();
    assertThat(auditEventRepository.findAll())
        .noneMatch(
            e -> e.getDomain() == AuditDomain.CONNECTED_APPS && MEMBER.equals(e.getTargetUserId()));
  }

  @Test
  void theSeededRuleTellsOnlyTheConnectedMemberAndNamesTheClientNotTheLabel() throws Exception {
    notificationCreationService.createFromEvent(
        new ExchangeInstallationConnectedEvent(MEMBER, mine.getId(), "VerseKit"));

    assertThat(
            notificationRepository.findByRecipientUserIdOrderByCreatedAtDesc(
                MEMBER, PageRequest.of(0, 10)))
        .singleElement()
        .satisfies(
            n -> {
              assertThat(n.getType()).isEqualTo(NotificationType.EXCHANGE_INSTALLATION_CONNECTED);
              assertThat(n.getEntityId()).isEqualTo(mine.getId());
              assertThat(n.getParams()).contains("VerseKit").doesNotContain("Desktop");
            });
    assertThat(notificationRepository.countByRecipientUserIdAndReadFalse(OTHER)).isZero();
    mockMvc
        .perform(get(PATH).with(browser(MEMBER)))
        .andExpect(jsonPath("$[0].installations[0].unseen").value(true));
  }

  @Test
  void anInstallationWithoutANotificationIsNotUnseen() throws Exception {
    mockMvc
        .perform(get(PATH).with(browser(MEMBER)))
        .andExpect(jsonPath("$[0].installations[0].unseen").value(false));
  }

  @Test
  void disconnectingAnInstallationRevokesItAndIsAudited() throws Exception {
    mockMvc
        .perform(delete(PATH + "/installations/" + mine.getId()).with(browser(MEMBER)))
        .andExpect(status().isNoContent());

    assertThat(installationRepository.findById(mine.getId()).orElseThrow().getRevokedAt())
        .isNotNull();
    assertThat(auditEventRepository.findAll())
        .anyMatch(
            e ->
                e.getDomain() == AuditDomain.CONNECTED_APPS
                    && e.getEventType() == AuditEventType.EXCHANGE_INSTALLATION_DISCONNECTED
                    && mine.getId().equals(e.getSubjectId())
                    && (e.getDetails() == null || !e.getDetails().contains("Desktop")));
    mockMvc.perform(get(PATH).with(browser(MEMBER))).andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void anotherMembersInstallationIsNotFound() throws Exception {
    mockMvc
        .perform(delete(PATH + "/installations/" + theirs.getId()).with(browser(MEMBER)))
        .andExpect(status().isNotFound());
    assertThat(installationRepository.findById(theirs.getId()).orElseThrow().getRevokedAt())
        .isNull();
  }

  @Test
  void disconnectingAClientStoresTheTimeAndRemovesTheConsent() throws Exception {
    mockMvc
        .perform(delete(PATH + "/versekit-ca").with(browser(MEMBER)))
        .andExpect(status().isNoContent());

    verify(keycloakService).revokeConsent(MEMBER, "versekit-ca");
    verify(keycloakService).endSessionsHeldOnlyBy(MEMBER, "versekit-ca");
    assertThat(
            revocationRepository.findById(new ExchangeClientRevocation.Key(client.getId(), MEMBER)))
        .isPresent();
    mockMvc.perform(get(PATH).with(browser(MEMBER))).andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void theDisconnectAnswersOnlyInASecondAfterTheRevocationsSecond() throws Exception {
    Thread.sleep(1_000 - Instant.now().toEpochMilli() % 1_000);

    mockMvc
        .perform(delete(PATH + "/versekit-ca").with(browser(MEMBER)))
        .andExpect(status().isNoContent());
    Instant answered = Instant.now();

    Instant revokedAt =
        revocationRepository
            .findById(new ExchangeClientRevocation.Key(client.getId(), MEMBER))
            .orElseThrow()
            .getRevokedAt();
    assertThat(answered.getEpochSecond())
        .as("a connection started once the disconnect answered is issued after the revocation")
        .isGreaterThan(revokedAt.getEpochSecond());
  }

  @Test
  void anUnreachableKeycloakFailsTheDisconnect() throws Exception {
    doThrow(new IllegalStateException("down"))
        .when(keycloakService)
        .revokeConsent(any(), eq("versekit-ca"));

    mockMvc
        .perform(delete(PATH + "/versekit-ca").with(browser(MEMBER)))
        .andExpect(status().isBadGateway());
  }

  @Test
  void neitherTheAppNorTheGatewayReachTheControls() throws Exception {
    mockMvc
        .perform(
            get(PATH)
                .with(
                    jwt().jwt(t -> t.subject(MEMBER.toString()).claim("azp", "basetool-android"))))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            delete(PATH + "/versekit-ca")
                .with(
                    jwt()
                        .jwt(
                            t -> t.subject(MEMBER.toString()).claim("azp", "test-ingest-gateway"))))
        .andExpect(status().isForbidden());
    verify(keycloakService, never()).revokeConsent(any(), any());
  }

  /**
   * A member's browser session.
   *
   * @param member the member
   * @return the request post-processor
   */
  private static @NotNull JwtRequestPostProcessor browser(@NotNull UUID member) {
    return jwt().jwt(t -> t.subject(member.toString()).claim("azp", "basetool-frontend"));
  }

  /**
   * Seeds a member.
   *
   * @param id the id
   * @param username the username
   * @return the member
   */
  private @NotNull User user(@NotNull UUID id, @NotNull String username) {
    User user = new User();
    user.setId(id);
    user.setUsername(username);
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    user.setRoles(new HashSet<>(Set.of(roleRepository.findByCode(Roles.KRT_MEMBER).orElseThrow())));
    return userRepository.saveAndFlush(user);
  }

  /**
   * Seeds an installation.
   *
   * @param user the member
   * @param key the key thumbprint
   * @param label the label
   * @return the installation
   */
  private @NotNull ExchangeInstallation installation(
      @NotNull User user, @NotNull String key, @NotNull String label) {
    ExchangeInstallation installation = new ExchangeInstallation();
    installation.setClient(client);
    installation.setUser(user);
    installation.setKeyThumbprint(key);
    installation.setLabel(label);
    installation.setFirstSeenAt(Instant.now());
    installation.setLastSeenAt(Instant.now());
    return installationRepository.saveAndFlush(installation);
  }

  /**
   * Builds an unread new-connection notification.
   *
   * @param recipient the member
   * @param installationId the announced installation
   * @return the notification
   */
  private static @NotNull Notification connected(
      @NotNull UUID recipient, @NotNull UUID installationId) {
    return Notification.builder()
        .recipientUserId(recipient)
        .type(NotificationType.EXCHANGE_INSTALLATION_CONNECTED)
        .entityType("EXCHANGE_INSTALLATION")
        .entityId(installationId)
        .build();
  }

  /**
   * Journals one write of the test client.
   *
   * @param member the member
   * @param resource the resource
   * @param key the entry key
   * @param action the action
   * @param name the blueprint's name in the journaled state
   * @param at when it was written
   */
  private void journal(
      UUID member, String resource, String key, String action, String name, String at) {
    String state = "{\"productKey\":\"" + key + "\",\"productName\":\"" + name + "\"}";
    boolean removal = action.endsWith("REMOVE");
    jdbc.update(
        """
        INSERT INTO exchange_journal (id, user_id, client_id, installation_key, batch_id, resource,
                                      entity_key, action, removal, before_state, after_state,
                                      recorded_at)
        VALUES (?, ?, 'versekit-ca', ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        UUID.randomUUID(),
        member,
        "m".repeat(43),
        UUID.randomUUID(),
        resource,
        key,
        action,
        removal,
        removal ? state : null,
        removal ? null : state,
        java.sql.Timestamp.from(java.time.Instant.parse(at)));
  }
}

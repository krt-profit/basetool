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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.exchange.api.events.ExchangeBulkUndoAppliedEvent;
import de.greluc.krt.profit.basetool.backend.exchange.api.events.ExchangeInstallationConnectedEvent;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.Notification;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.NotificationMuteRepository;
import de.greluc.krt.profit.basetool.backend.repository.NotificationRepository;
import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.service.NotificationCreationService;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * The member's notification preferences (REQ-NOTIF-027): the API, its isolation per member and the
 * effect on the notifications the engine writes.
 */
@SpringBootTest
@Transactional
class NotificationPreferencesControllerTest {

  private static final String PATH = "/api/v1/notifications/preferences";
  private static final UUID MEMBER = UUID.fromString("44444444-4444-4444-4444-4444444450c1");
  private static final UUID OTHER = UUID.fromString("44444444-4444-4444-4444-4444444450c2");

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private NotificationRepository notificationRepository;
  @Autowired private NotificationMuteRepository notificationMuteRepository;
  @Autowired private NotificationCreationService notificationCreationService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy.class))
            .build();
    user(MEMBER, "prefs-member");
    user(OTHER, "prefs-other");
  }

  @Test
  void listsEveryTypeAndMarksTheUnmutableOnes() throws Exception {
    mockMvc
        .perform(get(PATH).with(browser(MEMBER)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(NotificationType.values().length))
        .andExpect(jsonPath("$[?(@.type == 'JOB_ORDER_CREATED')].mutable").value(true))
        .andExpect(jsonPath("$[?(@.type == 'JOB_ORDER_CREATED')].muted").value(false))
        .andExpect(
            jsonPath("$[?(@.type == 'EXCHANGE_INSTALLATION_CONNECTED')].mutable").value(false))
        .andExpect(jsonPath("$[?(@.type == 'ACCOUNT_DELETION_REQUESTED')].mutable").value(false));
  }

  @Test
  void aMemberMutesAndUnmutesAType() throws Exception {
    mute(MEMBER, "JOB_ORDER_CREATED", true)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.type").value("JOB_ORDER_CREATED"))
        .andExpect(jsonPath("$.muted").value(true));
    mute(MEMBER, "JOB_ORDER_CREATED", true).andExpect(status().isOk());

    mockMvc
        .perform(get(PATH).with(browser(MEMBER)))
        .andExpect(jsonPath("$[?(@.type == 'JOB_ORDER_CREATED')].muted").value(true));
    assertThat(
            notificationMuteRepository.existsByUserIdAndType(
                MEMBER, NotificationType.JOB_ORDER_CREATED))
        .isTrue();

    mute(MEMBER, "JOB_ORDER_CREATED", false)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.muted").value(false));
    assertThat(notificationMuteRepository.findByUserId(MEMBER)).isEmpty();
  }

  @Test
  void aMuteBelongsToTheMemberWhoSetItOnly() throws Exception {
    mute(MEMBER, "JOB_ORDER_CREATED", true).andExpect(status().isOk());

    mockMvc
        .perform(get(PATH).with(browser(OTHER)))
        .andExpect(jsonPath("$[?(@.type == 'JOB_ORDER_CREATED')].muted").value(false));
  }

  @Test
  void aTypeThatCannotBeMutedIsRefusedWithBadRequest() throws Exception {
    mute(MEMBER, "EXCHANGE_INSTALLATION_CONNECTED", true).andExpect(status().isBadRequest());
    mute(MEMBER, "ACCOUNT_DELETION_REQUESTED", true).andExpect(status().isBadRequest());

    assertThat(notificationMuteRepository.findByUserId(MEMBER)).isEmpty();
  }

  @Test
  void anUnknownTypeAndAMissingFlagAreBadRequests() throws Exception {
    mute(MEMBER, "NO_SUCH_TYPE", true).andExpect(status().isBadRequest());
    mockMvc
        .perform(
            put(PATH + "/JOB_ORDER_CREATED")
                .with(browser(MEMBER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void anAnonymousCallerIsRefused() throws Exception {
    mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
  }

  @Test
  void aMutedTypeIsNeitherStoredNorDeliveredAndAnUnmutedOneIs() {
    notificationCreationService.createFromEvent(
        new ExchangeBulkUndoAppliedEvent(MEMBER, UUID.randomUUID(), "VerseKit", 3));
    assertThat(inbox(MEMBER)).hasSize(1);

    notificationMuteRepository.save(
        de.greluc.krt.profit.basetool.backend.model.NotificationMute.builder()
            .userId(MEMBER)
            .type(NotificationType.EXCHANGE_BULK_UNDO_APPLIED)
            .build());
    notificationCreationService.createFromEvent(
        new ExchangeBulkUndoAppliedEvent(MEMBER, UUID.randomUUID(), "VerseKit", 5));
    assertThat(inbox(MEMBER)).hasSize(1);

    notificationCreationService.createFromEvent(
        new ExchangeBulkUndoAppliedEvent(OTHER, UUID.randomUUID(), "VerseKit", 5));
    assertThat(inbox(OTHER)).hasSize(1);
  }

  @Test
  void theNewConnectionSignalIsDeliveredEvenWithAStrayMuteRow() {
    notificationMuteRepository.save(
        de.greluc.krt.profit.basetool.backend.model.NotificationMute.builder()
            .userId(MEMBER)
            .type(NotificationType.EXCHANGE_INSTALLATION_CONNECTED)
            .build());

    notificationCreationService.createFromEvent(
        new ExchangeInstallationConnectedEvent(MEMBER, UUID.randomUUID(), "VerseKit"));

    assertThat(inbox(MEMBER))
        .extracting(Notification::getType)
        .containsExactly(NotificationType.EXCHANGE_INSTALLATION_CONNECTED);
  }

  private org.springframework.test.web.servlet.ResultActions mute(
      @NotNull UUID member, @NotNull String type, boolean muted) throws Exception {
    return mockMvc.perform(
        put(PATH + "/" + type)
            .with(browser(member))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"muted\":" + muted + "}"));
  }

  private @NotNull List<Notification> inbox(@NotNull UUID member) {
    return notificationRepository
        .findAllByRecipientUserId(member, PageRequest.of(0, 50))
        .getContent();
  }

  private static @NotNull JwtRequestPostProcessor browser(@NotNull UUID member) {
    return jwt().jwt(t -> t.subject(member.toString()).claim("azp", "basetool-frontend"));
  }

  private void user(@NotNull UUID id, @NotNull String username) {
    User user = new User();
    user.setId(id);
    user.setUsername(username);
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    user.setRoles(new HashSet<>(Set.of(roleRepository.findByCode(Roles.KRT_MEMBER).orElseThrow())));
    userRepository.saveAndFlush(user);
  }
}

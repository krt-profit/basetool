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

package de.greluc.krt.profit.basetool.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.config.MailProperties;
import de.greluc.krt.profit.basetool.backend.event.DiscordRegistrationPendingEvent;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * Mockito unit tests for {@link PendingRegistrationMailService}. Uses the real backend message
 * bundle so the assertions also pin that the {@code email.pendingRegistration.*} keys exist and
 * localize; the locale is forced to German to match the configured default. Recipients come from
 * the mocked {@link UserRepository#findAllAdmins()} because the event carries no addresses.
 */
@ExtendWith(MockitoExtension.class)
class PendingRegistrationMailServiceTest {

  @Mock private MailService mailService;
  @Mock private MailProperties mailProperties;
  @Mock private UserRepository userRepository;

  private MessageSource messageSource;
  private PendingRegistrationMailService service;

  @BeforeEach
  void setUp() {
    ResourceBundleMessageSource ms = new ResourceBundleMessageSource();
    ms.setBasename("messages");
    ms.setDefaultEncoding("UTF-8");
    ms.setFallbackToSystemLocale(false);
    messageSource = ms;
    service =
        new PendingRegistrationMailService(
            mailService, messageSource, mailProperties, userRepository);
  }

  /**
   * Builds an admin {@link User} carrying the given display name and e-mail, exercising the
   * name/e-mail fields the service reads.
   *
   * @param displayName the admin's display name (drives the greeting via {@code getEffectiveName})
   * @param email the admin's e-mail address, or {@code null}/blank to model "no address on file"
   * @return a minimally populated admin user
   */
  private static User admin(String displayName, String email) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("user-" + displayName);
    user.setDisplayName(displayName);
    user.setEmail(email);
    return user;
  }

  @Test
  void noAdmins_sendsNothing() {
    when(userRepository.findAllAdmins()).thenReturn(List.of());

    service.sendPendingRegistrationMail(
        new DiscordRegistrationPendingEvent(UUID.randomUUID(), "Newbie"));

    verify(mailService, never()).send(any());
  }

  @Test
  void adminsWithoutEmail_sendNothing() {
    when(mailProperties.resolveDefaultLocale()).thenReturn(Locale.GERMAN);
    when(userRepository.findAllAdmins()).thenReturn(List.of(admin("Iceman", "   ")));

    service.sendPendingRegistrationMail(
        new DiscordRegistrationPendingEvent(UUID.randomUUID(), "Newbie"));

    verify(mailService, never()).send(any());
  }

  @Test
  void withUsername_sendsOneMailPerAdminWithEmail() {
    when(mailProperties.resolveDefaultLocale()).thenReturn(Locale.GERMAN);
    when(userRepository.findAllAdmins())
        .thenReturn(
            List.of(
                admin("Maverick", "mav@example.test"),
                admin("Goose", "   "),
                admin("Iceman", "ice@example.test")));

    service.sendPendingRegistrationMail(
        new DiscordRegistrationPendingEvent(UUID.randomUUID(), "Newbie"));

    ArgumentCaptor<MailMessage> msg = ArgumentCaptor.forClass(MailMessage.class);
    verify(mailService, times(2)).send(msg.capture());
    List<MailMessage> sent = msg.getAllValues();

    assertThat(sent)
        .extracting(MailMessage::to)
        .containsExactly("mav@example.test", "ice@example.test");
    assertThat(sent)
        .allSatisfy(
            m -> {
              assertThat(m.subject())
                  .isEqualTo(
                      messageSource.getMessage(
                          "email.pendingRegistration.subject", null, Locale.GERMAN));
              assertThat(m.body())
                  .contains("Newbie")
                  .contains(messageSource.getMessage("email.signoff", null, Locale.GERMAN));
            });
    // Each admin is greeted by their own effective name.
    assertThat(sent.get(0).body()).contains("Maverick");
    assertThat(sent.get(1).body()).contains("Iceman");
  }

  @Test
  void nullUsername_usesGenericBody() {
    when(mailProperties.resolveDefaultLocale()).thenReturn(Locale.GERMAN);
    when(userRepository.findAllAdmins()).thenReturn(List.of(admin("Maverick", "mav@example.test")));

    service.sendPendingRegistrationMail(
        new DiscordRegistrationPendingEvent(UUID.randomUUID(), null));

    ArgumentCaptor<MailMessage> msg = ArgumentCaptor.forClass(MailMessage.class);
    verify(mailService).send(msg.capture());
    assertThat(msg.getValue().body())
        .contains(
            messageSource.getMessage("email.pendingRegistration.bodyNoName", null, Locale.GERMAN));
  }
}

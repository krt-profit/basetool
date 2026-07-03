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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.config.MailProperties;
import de.greluc.krt.profit.basetool.backend.event.UserApprovalDecidedEvent;
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
 * Mockito unit tests for {@link UserApprovalMailService}. Uses the real backend message bundle so
 * the assertions also pin that the {@code email.*} keys exist and localize; the locale is forced to
 * German to match the configured default.
 */
@ExtendWith(MockitoExtension.class)
class UserApprovalMailServiceTest {

  @Mock private MailService mailService;
  @Mock private MailProperties mailProperties;

  private MessageSource messageSource;
  private UserApprovalMailService service;

  @BeforeEach
  void setUp() {
    ResourceBundleMessageSource ms = new ResourceBundleMessageSource();
    ms.setBasename("messages");
    ms.setDefaultEncoding("UTF-8");
    ms.setFallbackToSystemLocale(false);
    messageSource = ms;
    service = new UserApprovalMailService(mailService, messageSource, mailProperties);
  }

  @Test
  void noEmailOnFile_skipsSend() {
    service.sendDecisionMail(
        new UserApprovalDecidedEvent(UUID.randomUUID(), true, null, "Maverick", null));

    verify(mailService, never()).send(any());
  }

  @Test
  void approvedWithName_composesApprovalMail() {
    when(mailProperties.resolveDefaultLocale()).thenReturn(Locale.GERMAN);

    service.sendDecisionMail(
        new UserApprovalDecidedEvent(
            UUID.randomUUID(), true, "pilot@example.test", "Maverick", null));

    ArgumentCaptor<MailMessage> msg = ArgumentCaptor.forClass(MailMessage.class);
    verify(mailService).send(msg.capture());
    assertThat(msg.getValue().to()).isEqualTo("pilot@example.test");
    assertThat(msg.getValue().subject())
        .isEqualTo(messageSource.getMessage("email.approval.subject", null, Locale.GERMAN));
    assertThat(msg.getValue().body())
        .contains("Maverick")
        .contains(messageSource.getMessage("email.approval.body", null, Locale.GERMAN))
        .contains(messageSource.getMessage("email.signoff", null, Locale.GERMAN));
  }

  @Test
  void rejectedWithReason_includesReasonAndLabel() {
    when(mailProperties.resolveDefaultLocale()).thenReturn(Locale.GERMAN);

    service.sendDecisionMail(
        new UserApprovalDecidedEvent(
            UUID.randomUUID(), false, "x@example.test", "Goose", "Kein KRT-Mitglied"));

    ArgumentCaptor<MailMessage> msg = ArgumentCaptor.forClass(MailMessage.class);
    verify(mailService).send(msg.capture());
    assertThat(msg.getValue().subject())
        .isEqualTo(messageSource.getMessage("email.rejection.subject", null, Locale.GERMAN));
    assertThat(msg.getValue().body())
        .contains("Kein KRT-Mitglied")
        .contains(messageSource.getMessage("email.rejection.reasonLabel", null, Locale.GERMAN));
  }

  @Test
  void rejectedWithoutReason_usesLocalizedPlaceholder() {
    when(mailProperties.resolveDefaultLocale()).thenReturn(Locale.GERMAN);

    service.sendDecisionMail(
        new UserApprovalDecidedEvent(UUID.randomUUID(), false, "x@example.test", null, "   "));

    ArgumentCaptor<MailMessage> msg = ArgumentCaptor.forClass(MailMessage.class);
    verify(mailService).send(msg.capture());
    assertThat(msg.getValue().body())
        .contains(messageSource.getMessage("email.rejection.noReason", null, Locale.GERMAN));
  }
}

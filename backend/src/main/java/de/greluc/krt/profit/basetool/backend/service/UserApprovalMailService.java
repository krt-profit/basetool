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

import de.greluc.krt.profit.basetool.backend.config.MailProperties;
import de.greluc.krt.profit.basetool.backend.event.UserApprovalDecidedEvent;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Composes and sends the account approval/rejection decision e-mail (REQ-NOTIF-014).
 *
 * <p>Turns a {@link UserApprovalDecidedEvent} into a localized plain-text {@link MailMessage} and
 * hands it to the channel-agnostic {@link MailService}. Localization uses the backend {@link
 * MessageSource} and {@link MailProperties#resolveDefaultLocale() default locale} (no per-recipient
 * locale is stored yet). For a rejection the admin's free-text reason is written into the body;
 * when none was given a localized placeholder is used instead. A recipient with no e-mail on file
 * is skipped. Nothing here logs the address, name or reason (all PII).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserApprovalMailService {

  private final MailService mailService;
  private final MessageSource messageSource;
  private final MailProperties mailProperties;

  /**
   * Composes and sends the decision e-mail for one committed approve/reject event, best-effort. A
   * recipient without an e-mail address on file is skipped with a debug log.
   *
   * @param event the after-commit approval/rejection event
   */
  public void sendDecisionMail(@NotNull UserApprovalDecidedEvent event) {
    String email = event.recipientEmail();
    if (!StringUtils.hasText(email)) {
      log.debug("Decided user {} has no e-mail on file; skipping decision mail.", event.userId());
      return;
    }
    Locale locale = mailProperties.resolveDefaultLocale();
    String greeting = greeting(event.recipientName(), locale);
    String signoff = messageSource.getMessage("email.signoff", null, locale);
    String subject;
    String body;
    if (event.approved()) {
      subject = messageSource.getMessage("email.approval.subject", null, locale);
      body =
          greeting
              + "\n\n"
              + messageSource.getMessage("email.approval.body", null, locale)
              + "\n\n"
              + signoff;
    } else {
      subject = messageSource.getMessage("email.rejection.subject", null, locale);
      String reasonText =
          StringUtils.hasText(event.reason())
              ? event.reason()
              : messageSource.getMessage("email.rejection.noReason", null, locale);
      body =
          greeting
              + "\n\n"
              + messageSource.getMessage("email.rejection.body", null, locale)
              + "\n\n"
              + messageSource.getMessage("email.rejection.reasonLabel", null, locale)
              + "\n"
              + reasonText
              + "\n\n"
              + signoff;
    }
    mailService.send(new MailMessage(email, subject, body));
  }

  /**
   * Builds the greeting line, addressing the recipient by name when one is present and using a
   * name-less variant otherwise.
   *
   * @param name the recipient's effective name, or {@code null}/blank
   * @param locale the locale to render in
   * @return the localized greeting line
   */
  private String greeting(String name, Locale locale) {
    return StringUtils.hasText(name)
        ? messageSource.getMessage("email.greeting", new Object[] {name}, locale)
        : messageSource.getMessage("email.greetingGeneric", null, locale);
  }
}

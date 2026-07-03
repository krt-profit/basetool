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
import de.greluc.krt.profit.basetool.backend.event.DiscordRegistrationPendingEvent;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Composes and sends the "a new registration is awaiting approval" e-mail to every admin
 * (REQ-NOTIF-015).
 *
 * <p>Second consumer of the reusable transactional e-mail channel (REQ-NOTIF-013) after the account
 * decision mail (REQ-NOTIF-014): it mirrors, on the e-mail channel, the in-app admin notification
 * that REQ-NOTIF-012 already raises when a brand-new non-admin account enters {@code PENDING}. The
 * triggering {@link DiscordRegistrationPendingEvent} deliberately carries no PII beyond the new
 * user's display username, so this service resolves the admin <em>recipients</em> itself via {@link
 * UserRepository#findAllAdmins()} rather than from the event — one localized plain-text {@link
 * MailMessage} is sent per admin that has an e-mail address on file.
 *
 * <p>Localization uses the backend {@link MessageSource} and the {@link
 * MailProperties#resolveDefaultLocale() default locale} (no per-recipient locale is stored yet),
 * exactly like {@link UserApprovalMailService}. Each admin is greeted by their effective name; the
 * new registrant's username is interpolated into the body when present and a name-less variant is
 * used otherwise. Admins without an address are skipped. Nothing here logs an address, a name or
 * the username (all PII, REQ-OBS) — only the recipient <em>count</em>.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PendingRegistrationMailService {

  private final MailService mailService;
  private final MessageSource messageSource;
  private final MailProperties mailProperties;
  private final UserRepository userRepository;

  /**
   * Composes and sends the pending-registration notice to every admin with an e-mail address,
   * best-effort. The admin list is read fresh from the DB (the event carries no recipients); admins
   * without an address on file are skipped, and when no admin can be mailed the method returns
   * without sending. Each send is delegated to the best-effort {@link MailService}, so one failing
   * recipient never aborts the rest.
   *
   * @param event the after-commit pending-registration event (carries the new user's id and
   *     username only)
   */
  public void sendPendingRegistrationMail(@NotNull DiscordRegistrationPendingEvent event) {
    List<User> admins = userRepository.findAllAdmins();
    if (admins.isEmpty()) {
      log.debug("No admin recipients for pending-registration mail of user {}.", event.userId());
      return;
    }
    Locale locale = mailProperties.resolveDefaultLocale();
    String subject = messageSource.getMessage("email.pendingRegistration.subject", null, locale);
    String signoff = messageSource.getMessage("email.signoff", null, locale);
    String bodyText = bodyText(event.username(), locale);
    int recipients = 0;
    for (User admin : admins) {
      String email = admin.getEmail();
      if (!StringUtils.hasText(email)) {
        continue;
      }
      String body =
          greeting(admin.getEffectiveName(), locale) + "\n\n" + bodyText + "\n\n" + signoff;
      mailService.send(new MailMessage(email, subject, body));
      recipients++;
    }
    log.debug("Dispatched pending-registration mail to {} admin recipient(s).", recipients);
  }

  /**
   * Builds the localized body paragraph, interpolating the new registrant's username when present
   * and falling back to a name-less variant when the event carried none.
   *
   * @param username the new registrant's display username, or {@code null}/blank
   * @param locale the locale to render in
   * @return the localized body paragraph (without greeting or sign-off)
   */
  private String bodyText(@Nullable String username, Locale locale) {
    return StringUtils.hasText(username)
        ? messageSource.getMessage(
            "email.pendingRegistration.body", new Object[] {username}, locale)
        : messageSource.getMessage("email.pendingRegistration.bodyNoName", null, locale);
  }

  /**
   * Builds the greeting line, addressing the admin by their effective name when one is present and
   * using a name-less variant otherwise.
   *
   * @param name the admin's effective name, or {@code null}/blank
   * @param locale the locale to render in
   * @return the localized greeting line
   */
  private String greeting(String name, Locale locale) {
    return StringUtils.hasText(name)
        ? messageSource.getMessage("email.greeting", new Object[] {name}, locale)
        : messageSource.getMessage("email.greetingGeneric", null, locale);
  }
}

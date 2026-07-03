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
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * {@link MailService} backed by Spring's {@link JavaMailSender} (SMTP).
 *
 * <p>Three gates keep mail off unless deliberately configured: the {@code app.mail.enabled} flag
 * (an explicit kill-switch, ships {@code true}); a non-blank {@code spring.mail.host} (the
 * effective switch — set it to start sending, e.g. an SMTP relay); and the presence of the {@link
 * JavaMailSender} bean (autoconfigured by Spring Boot only when the host is set, injected
 * optionally via {@link ObjectProvider}). Any gate closed makes {@link #send} a logged no-op — so a
 * blank host, which Docker Compose may still pass through as an empty env var, never lets a broken
 * sender fire. A delivery failure is caught and logged, never rethrown, so best-effort mail can
 * never break the caller. The recipient address and body are <b>never</b> logged (PII); only the
 * static localized subject is.
 */
@Service
@Slf4j
public class SmtpMailService implements MailService {

  private final MailProperties mailProperties;
  private final ObjectProvider<JavaMailSender> mailSenderProvider;
  private final String smtpHost;

  /**
   * Wires the mail gates: the app-level flag, the configured SMTP host and the optional sender
   * bean.
   *
   * @param mailProperties the app-level mail gate + envelope metadata
   * @param mailSenderProvider optional provider of the autoconfigured {@link JavaMailSender}
   * @param smtpHost the configured {@code spring.mail.host} (blank when SMTP is not configured)
   */
  public SmtpMailService(
      MailProperties mailProperties,
      ObjectProvider<JavaMailSender> mailSenderProvider,
      @Value("${spring.mail.host:}") String smtpHost) {
    this.mailProperties = mailProperties;
    this.mailSenderProvider = mailSenderProvider;
    this.smtpHost = smtpHost;
  }

  @Override
  public void send(@NotNull MailMessage message) {
    if (!mailProperties.isEnabled()) {
      log.debug(
          "Mail disabled (app.mail.enabled=false); dropping '{}' message.", message.subject());
      return;
    }
    if (!StringUtils.hasText(smtpHost)) {
      log.debug(
          "No SMTP host configured (spring.mail.host blank); dropping '{}' message.",
          message.subject());
      return;
    }
    JavaMailSender sender = mailSenderProvider.getIfAvailable();
    if (sender == null) {
      log.warn(
          "SMTP host is set but no JavaMailSender bean is available; dropping '{}' message.",
          message.subject());
      return;
    }
    try {
      SimpleMailMessage mail = new SimpleMailMessage();
      mail.setFrom(formatFrom());
      mail.setTo(message.to());
      mail.setSubject(message.subject());
      mail.setText(message.body());
      sender.send(mail);
      log.info("Sent '{}' mail to one recipient.", message.subject());
    } catch (MailException e) {
      log.error("Failed to send '{}' mail.", message.subject(), e);
    }
  }

  /**
   * Builds the {@code From} header, prefixing the configured display name when present ({@code Name
   * <addr>}) and falling back to the bare address otherwise.
   *
   * @return the envelope-sender value for {@link SimpleMailMessage#setFrom}
   */
  private String formatFrom() {
    String name = mailProperties.getFromName();
    String address = mailProperties.getFrom();
    return (name != null && !name.isBlank()) ? name + " <" + address + ">" : address;
  }
}

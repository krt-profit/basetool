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
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
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
 * <p>{@link #send} is a logged no-op unless {@code app.mail.enabled} is true, {@code
 * spring.mail.host} is non-blank and a {@link JavaMailSender} bean exists. Delivery failures are
 * logged, never rethrown; recipient and body are never logged. Every outcome increments {@code
 * basetool_mail_total{outcome}}.
 */
@Service
@Slf4j
public class SmtpMailService implements MailService {

  private final MailProperties mailProperties;
  private final ObjectProvider<JavaMailSender> mailSenderProvider;
  private final MeterRegistry meterRegistry;
  private final String smtpHost;

  /**
   * Wires the mail gates: the app-level flag, the configured SMTP host and the optional sender
   * bean.
   *
   * @param mailProperties the app-level mail gate + envelope metadata
   * @param mailSenderProvider optional provider of the autoconfigured {@link JavaMailSender}
   * @param meterRegistry the registry the per-outcome {@code basetool_mail_total} counter is bumped
   *     against
   * @param smtpHost the configured {@code spring.mail.host} (blank when SMTP is not configured)
   */
  public SmtpMailService(
      MailProperties mailProperties,
      ObjectProvider<JavaMailSender> mailSenderProvider,
      MeterRegistry meterRegistry,
      @Value("${spring.mail.host:}") String smtpHost) {
    this.mailProperties = mailProperties;
    this.mailSenderProvider = mailSenderProvider;
    this.meterRegistry = meterRegistry;
    this.smtpHost = smtpHost;
  }

  @Override
  public void send(@NotNull MailMessage message) {
    if (!mailProperties.enabled()) {
      log.debug(
          "Mail disabled (app.mail.enabled=false); dropping '{}' message.", message.subject());
      recordOutcome(MetricNames.MAIL_DROPPED_DISABLED);
      return;
    }
    if (!StringUtils.hasText(smtpHost)) {
      log.debug(
          "No SMTP host configured (spring.mail.host blank); dropping '{}' message.",
          message.subject());
      recordOutcome(MetricNames.MAIL_DROPPED_NO_HOST);
      return;
    }
    JavaMailSender sender = mailSenderProvider.getIfAvailable();
    if (sender == null) {
      log.warn(
          "SMTP host is set but no JavaMailSender bean is available; dropping '{}' message.",
          message.subject());
      recordOutcome(MetricNames.MAIL_DROPPED_NO_SENDER);
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
      recordOutcome(MetricNames.MAIL_SENT);
    } catch (MailException e) {
      log.error("Failed to send '{}' mail.", message.subject(), e);
      recordOutcome(MetricNames.MAIL_FAILED);
    }
  }

  /**
   * Increments {@code basetool_mail_total} for one bounded delivery {@code outcome}. Only the
   * outcome is recorded — never the recipient address or subject (PII / unbounded).
   *
   * @param outcome one of the {@code MAIL_*} bounded outcome values in {@link MetricNames}
   */
  private void recordOutcome(@NotNull String outcome) {
    meterRegistry.counter(MetricNames.MAIL, MetricNames.TAG_OUTCOME, outcome).increment();
  }

  /**
   * Builds the {@code From} header, prefixing the configured display name when present ({@code Name
   * <addr>}) and falling back to the bare address otherwise.
   *
   * @return the envelope-sender value for {@link SimpleMailMessage#setFrom}
   */
  private String formatFrom() {
    String name = mailProperties.fromName();
    String address = mailProperties.from();
    return (name != null && !name.isBlank()) ? name + " <" + address + ">" : address;
  }
}

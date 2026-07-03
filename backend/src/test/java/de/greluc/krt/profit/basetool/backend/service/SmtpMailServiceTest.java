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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.config.MailProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Mockito unit tests for {@link SmtpMailService}: the three off-gates (disabled flag / blank host /
 * absent sender) and the best-effort send path (envelope composition + swallowed delivery failure).
 */
@ExtendWith(MockitoExtension.class)
class SmtpMailServiceTest {

  private static final String HOST = "smtp.example.test";

  @Mock private ObjectProvider<JavaMailSender> senderProvider;
  @Mock private JavaMailSender javaMailSender;

  private static final MailMessage MSG = new MailMessage("dest@example.test", "Subject", "Body");

  private static MailProperties props(boolean enabled) {
    MailProperties p = new MailProperties();
    p.setEnabled(enabled);
    p.setFrom("no-reply@example.test");
    p.setFromName("Profit Basetool");
    return p;
  }

  @Test
  void disabled_dropsMessageWithoutTouchingTheSender() {
    SmtpMailService service = new SmtpMailService(props(false), senderProvider, HOST);

    service.send(MSG);

    verify(senderProvider, never()).getIfAvailable();
    verify(javaMailSender, never()).send(any(SimpleMailMessage.class));
  }

  @Test
  void enabledButBlankHost_dropsMessageWithoutTouchingTheSender() {
    SmtpMailService service = new SmtpMailService(props(true), senderProvider, "");

    service.send(MSG);

    verify(senderProvider, never()).getIfAvailable();
    verify(javaMailSender, never()).send(any(SimpleMailMessage.class));
  }

  @Test
  void enabledWithHostButNoSenderBean_dropsMessage() {
    when(senderProvider.getIfAvailable()).thenReturn(null);
    SmtpMailService service = new SmtpMailService(props(true), senderProvider, HOST);

    service.send(MSG);

    verify(javaMailSender, never()).send(any(SimpleMailMessage.class));
  }

  @Test
  void enabledWithHostAndSender_sendsWithComposedEnvelope() {
    when(senderProvider.getIfAvailable()).thenReturn(javaMailSender);
    SmtpMailService service = new SmtpMailService(props(true), senderProvider, HOST);

    service.send(MSG);

    ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(javaMailSender).send(sent.capture());
    SimpleMailMessage mail = sent.getValue();
    assertThat(mail.getFrom()).isEqualTo("Profit Basetool <no-reply@example.test>");
    assertThat(mail.getTo()).containsExactly("dest@example.test");
    assertThat(mail.getSubject()).isEqualTo("Subject");
    assertThat(mail.getText()).isEqualTo("Body");
  }

  @Test
  void deliveryFailure_isSwallowed() {
    when(senderProvider.getIfAvailable()).thenReturn(javaMailSender);
    doThrow(new MailSendException("smtp down"))
        .when(javaMailSender)
        .send(any(SimpleMailMessage.class));
    SmtpMailService service = new SmtpMailService(props(true), senderProvider, HOST);

    // Best-effort: a failed send must not propagate to the caller.
    service.send(MSG);

    verify(javaMailSender).send(any(SimpleMailMessage.class));
  }
}

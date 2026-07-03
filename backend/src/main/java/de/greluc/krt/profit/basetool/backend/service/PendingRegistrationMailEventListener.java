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

import de.greluc.krt.profit.basetool.backend.config.AsyncConfig;
import de.greluc.krt.profit.basetool.backend.event.DiscordRegistrationPendingEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends the admin "new registration awaiting approval" e-mail after the registration transaction
 * commits (REQ-NOTIF-015).
 *
 * <p>Runs alongside {@code NotificationEventListener}: the same {@link
 * DiscordRegistrationPendingEvent} drives both the in-app admin notification (via its {@code
 * NotificationEvent} supertype, REQ-NOTIF-012) and — through this listener — the admin e-mail on a
 * second channel. Like the account-decision mail listener it fires only on {@link
 * TransactionPhase#AFTER_COMMIT} (a rolled-back registration mails nothing) and runs on the
 * dedicated {@link AsyncConfig#MAIL_EXECUTOR} so SMTP latency stays off the request thread and
 * cannot starve in-app notification creation. Any failure is swallowed and logged — the
 * registration already committed, and best-effort mail must never surface to the user or trigger a
 * rollback.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PendingRegistrationMailEventListener {

  private final PendingRegistrationMailService pendingRegistrationMailService;

  /**
   * Sends the pending-registration admin mail for a committed new registration.
   *
   * @param event the published pending-registration event
   */
  @Async(AsyncConfig.MAIL_EXECUTOR)
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onDiscordRegistrationPending(DiscordRegistrationPendingEvent event) {
    try {
      pendingRegistrationMailService.sendPendingRegistrationMail(event);
    } catch (RuntimeException e) {
      log.error("Failed to send pending-registration admin mail for user {}.", event.userId(), e);
    }
  }
}

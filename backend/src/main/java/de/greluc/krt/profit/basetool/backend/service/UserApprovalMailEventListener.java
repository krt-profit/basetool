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
import de.greluc.krt.profit.basetool.backend.event.UserApprovalDecidedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends the account approval/rejection decision e-mail after the deciding transaction commits
 * (REQ-NOTIF-014).
 *
 * <p>Mirrors {@code NotificationEventListener}: it fires only on {@link
 * TransactionPhase#AFTER_COMMIT} (a rolled-back decision sends nothing) and runs on the dedicated
 * {@link AsyncConfig#MAIL_EXECUTOR} so SMTP latency stays off the request thread. Any failure is
 * swallowed and logged — the decision already committed, and best-effort mail must never surface to
 * the admin or trigger a rollback.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserApprovalMailEventListener {

  private final UserApprovalMailService userApprovalMailService;

  /**
   * Sends the decision mail for a committed approve/reject.
   *
   * @param event the published approval/rejection event
   */
  @Async(AsyncConfig.MAIL_EXECUTOR)
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onUserApprovalDecided(UserApprovalDecidedEvent event) {
    try {
      userApprovalMailService.sendDecisionMail(event);
    } catch (RuntimeException e) {
      log.error("Failed to send account-decision mail for user {}.", event.userId(), e);
    }
  }
}

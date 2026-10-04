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

import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicAuthorizer;
import de.greluc.krt.profit.basetool.backend.support.LiveSyncAuthorization;
import de.greluc.krt.profit.basetool.backend.support.LiveSyncTopic;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

/**
 * The bank module's {@link LiveSyncTopicAuthorizer}: a bank-account room opens for whoever passes
 * the member-facing org-unit bank account read (REQ-APP-BANK-007), not the wider bank-staff read.
 */
@Service
@RequiredArgsConstructor
public class OrgUnitBankLiveSyncTopicAuthorizer implements LiveSyncTopicAuthorizer {

  private final OrgUnitBankAccessService orgUnitBankAccessService;

  /**
   * Decides the bank-account rooms.
   *
   * @return {@code BANK_ACCOUNT}
   */
  @Override
  @NotNull
  public Set<LiveSyncAuthorization> authorizations() {
    return Set.of(LiveSyncAuthorization.BANK_ACCOUNT);
  }

  /**
   * Performs the detail read; a refused or missing account surfaces as its exception, which the
   * caller counts as a refusal.
   *
   * @param topic a parsed bank-account topic
   * @return {@code true} once the detail read has succeeded
   */
  @Override
  public boolean mayJoin(@NotNull LiveSyncTopic topic) {
    orgUnitBankAccessService.getViewableAccountDetail(topic.requiredResourceId());
    return true;
  }
}

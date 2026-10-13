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

package de.greluc.krt.profit.basetool.backend.identity.internal;

import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.BusinessGaugeSource;
import de.greluc.krt.profit.basetool.backend.platform.api.BusinessGauges;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The identity module's business gauges: registrations awaiting a decision and member accounts
 * waiting for their deletion (REQ-OBS-011).
 */
@Component
@RequiredArgsConstructor
public class IdentityBusinessGauges implements BusinessGaugeSource {

  private final UserRepository userRepository;

  private final AtomicLong registrationPending = new AtomicLong();
  private final AtomicLong registrationOldestAge = new AtomicLong();
  private final AtomicLong usersPendingDeletion = new AtomicLong();
  private final AtomicLong usersPendingDeletionOldestAge = new AtomicLong();

  @Override
  public void register(@NotNull MeterRegistry registry) {
    BusinessGauges.countGauge(registry, MetricNames.REGISTRATION_PENDING, registrationPending);
    BusinessGauges.ageGauge(
        registry, MetricNames.REGISTRATION_PENDING_OLDEST_AGE, registrationOldestAge);
    BusinessGauges.countGauge(registry, MetricNames.USERS_PENDING_DELETION, usersPendingDeletion);
    BusinessGauges.ageGauge(
        registry, MetricNames.USERS_PENDING_DELETION_OLDEST_AGE, usersPendingDeletionOldestAge);
  }

  @Override
  public void sample() {
    registrationPending.set(userRepository.countByApprovalStatus(ApprovalStatus.PENDING));
    registrationOldestAge.set(
        BusinessGauges.ageSeconds(
            userRepository.findOldestCreatedAtByApprovalStatus(ApprovalStatus.PENDING)));
    usersPendingDeletion.set(userRepository.countOrphanedMemberAccounts());
    usersPendingDeletionOldestAge.set(
        BusinessGauges.ageSeconds(userRepository.findOldestOrphanedMemberAbsenceStamp()));
  }
}

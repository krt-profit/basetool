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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.kernel.HandleAnonymisation;
import de.greluc.krt.profit.basetool.backend.platform.api.NameRetentionParticipant;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Clears the notes and reasons and replaces the handle snapshots of booking requests decided more
 * than the name retention ago (REQ-DATA-047), and records it in the bank's own trail.
 */
@Component
@RequiredArgsConstructor
public class BookingRequestNameRetention implements NameRetentionParticipant {

  private final BankBookingRequestRepository bookingRequestRepository;
  private final BankAuditService bankAuditService;

  @Override
  public @NotNull String area() {
    return "booking_requests";
  }

  @Override
  @Transactional
  public int removeNamesBefore(@NotNull Instant cutoff) {
    int changed =
        bookingRequestRepository.clearNamesDecidedBefore(cutoff, HandleAnonymisation.SENTINEL);
    if (changed > 0) {
      bankAuditService.record(
          BankAuditEventType.NAME_RETENTION_APPLIED,
          null,
          null,
          null,
          AuditDetails.of("bookingRequests", changed).with("cutoff", cutoff));
    }
    return changed;
  }
}

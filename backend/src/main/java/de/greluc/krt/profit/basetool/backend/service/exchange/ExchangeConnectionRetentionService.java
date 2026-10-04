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

package de.greluc.krt.profit.basetool.backend.service.exchange;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeClientRevocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeInstallationRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes disconnected exchange installations and members' client revocations past their retention
 * (REQ-XCH-035), in one transaction and in an order that never shows a disconnected installation as
 * connected again.
 */
@Service
@RequiredArgsConstructor
public class ExchangeConnectionRetentionService {

  private final ExchangeInstallationRepository installationRepository;
  private final ExchangeClientRevocationRepository revocationRepository;
  private final AuditRecorder auditRecorder;

  /**
   * Deletes every installation disconnected before the cutoff, then every installation a
   * whole-client disconnect before the cutoff ended, then those client revocations, and records one
   * {@link AuditEventType#EXCHANGE_CONNECTIONS_PURGED} with the counts when anything was deleted.
   *
   * @param cutoff the oldest disconnect still kept
   * @return what was deleted
   */
  @Transactional
  public @NotNull Purged purgeBefore(@NotNull Instant cutoff) {
    int installations =
        installationRepository.deleteRevokedBefore(cutoff)
            + installationRepository.deleteEndedByRevocationsBefore(cutoff);
    int revocations = revocationRepository.deleteRevokedBefore(cutoff);
    Purged purged = new Purged(installations, revocations);
    if (purged.total() > 0) {
      auditRecorder.record(
          AuditEventType.EXCHANGE_CONNECTIONS_PURGED,
          null,
          null,
          null,
          AuditDetails.of("installations", installations)
              .with("revocations", revocations)
              .with("cutoff", cutoff));
    }
    return purged;
  }

  /**
   * What one sweep deleted.
   *
   * @param installations the disconnected installations deleted
   * @param revocations the client revocations deleted
   */
  public record Purged(int installations, int revocations) {

    /**
     * Returns the number of rows deleted.
     *
     * @return installations plus revocations
     */
    public int total() {
      return installations + revocations;
    }
  }
}

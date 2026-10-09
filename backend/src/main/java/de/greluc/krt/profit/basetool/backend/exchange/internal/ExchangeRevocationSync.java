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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeRevocationRow;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repairs the revocation mirror (REQ-XCH-008): every denial and client revocation the database
 * still enforces is written back when the mirror lacks it, for example after a Redis restart.
 * Nothing is ever removed; the entries expire on their own.
 */
@Service
@RequiredArgsConstructor
public class ExchangeRevocationSync {

  private final ExchangeRevocationMirror revocationMirror;
  private final ExchangeInstallationRepository installationRepository;
  private final ExchangeClientRevocationRepository revocationRepository;
  private final Clock clock = Clock.systemUTC();

  /**
   * Writes back every enforced entry the mirror lacks.
   *
   * @return the number of entries written
   * @throws RuntimeException when Redis could not be read or written
   */
  @Transactional(readOnly = true)
  public int repair() {
    if (!revocationMirror.isActive()) {
      return 0;
    }
    Instant since = clock.instant().minus(ExchangeRevocationMirror.RETENTION);
    int written = 0;
    for (ExchangeInstallation installation : installationRepository.findRevokedSince(since)) {
      if (!revocationMirror.isDenied(installation.getKeyThumbprint())) {
        revocationMirror.deny(installation.getKeyThumbprint(), installation.getRevokedAt());
        written++;
      }
    }
    for (ExchangeRevocationRow row : revocationRepository.findRevokedSince(since)) {
      if (!revocationMirror.isRevoked(row.clientId(), row.userId(), row.revokedAt())) {
        revocationMirror.revoke(row.clientId(), row.userId(), row.revokedAt());
        written++;
      }
    }
    return written;
  }
}

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

import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Purges change-feed entries past their retention and moves the feed's horizon, below which a
 * cursor has expired (REQ-XCH-013).
 */
@Service
@RequiredArgsConstructor
public class ExchangeChangeRetentionService {

  private final ExchangeChangeRepository changeRepository;
  private final ExchangeFeedHorizonRepository horizonRepository;

  /**
   * Deletes every entry up to the feed position of the last one older than the cutoff and records
   * that position as the horizon; the horizon never moves back.
   *
   * @param cutoff the oldest change still kept
   * @param now the time recorded with the horizon
   * @return the number of entries deleted
   */
  @Transactional
  public int purgeOlderThan(@NotNull Instant cutoff, @NotNull Instant now) {
    Optional<ExchangeChangeRepository.Position> last = changeRepository.lastPositionBefore(cutoff);
    if (last.isEmpty()) {
      return 0;
    }
    ExchangeFeedPosition through =
        new ExchangeFeedPosition(last.get().getTx(), last.get().getSeq());
    int deleted = changeRepository.deleteThrough(through.tx(), through.seq());
    ExchangeFeedHorizon horizon =
        horizonRepository
            .findById(ExchangeFeedHorizon.SINGLETON_ID)
            .orElseThrow(() -> new IllegalStateException("exchange_feed_horizon row missing"));
    if (positionOf(horizon).isBefore(through)) {
      horizon.setPurgedThroughTx(through.tx());
      horizon.setPurgedThroughSeq(through.seq());
      horizon.setPurgedAt(now);
      horizonRepository.save(horizon);
    }
    return deleted;
  }

  /**
   * Returns the feed position up to which the feed has lost entries.
   *
   * @return the horizon, {@link ExchangeFeedPosition#START} before the first purge
   */
  @Transactional(readOnly = true)
  public @NotNull ExchangeFeedPosition horizon() {
    return horizonRepository
        .findById(ExchangeFeedHorizon.SINGLETON_ID)
        .map(ExchangeChangeRetentionService::positionOf)
        .orElse(ExchangeFeedPosition.START);
  }

  /**
   * Reads the horizon row's position.
   *
   * @param horizon the row
   * @return its position
   */
  private static @NotNull ExchangeFeedPosition positionOf(@NotNull ExchangeFeedHorizon horizon) {
    return new ExchangeFeedPosition(horizon.getPurgedThroughTx(), horizon.getPurgedThroughSeq());
  }
}

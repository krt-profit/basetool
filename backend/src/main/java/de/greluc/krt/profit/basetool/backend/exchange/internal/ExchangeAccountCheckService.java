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

import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeAccountCheckResult;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Compares an RSI handle with the one on the member's profile without ever disclosing it
 * (REQ-XCH-031).
 */
@Service
@RequiredArgsConstructor
public class ExchangeAccountCheckService {

  /** The member whose profile holds the handle. */
  private final UserRepository userRepository;

  /** The registry {@code basetool_exchange_account_checks_total} binds to. */
  private final MeterRegistry meterRegistry;

  /** Registers the account-check counter for every result at zero. */
  @PostConstruct
  void registerCounters() {
    for (ExchangeAccountCheckResult result : ExchangeAccountCheckResult.values()) {
      counter(result);
    }
  }

  /**
   * Answers whether {@code handle} is the member's stored RSI handle, ignoring case.
   *
   * @param member the acting member
   * @param handle the handle the client saw; compared only, never logged or stored
   * @return {@code UNKNOWN} when the member stored no handle, otherwise {@code MATCH} or {@code
   *     MISMATCH}
   */
  @Transactional(readOnly = true)
  public @NotNull ExchangeAccountCheckResult check(@NotNull UUID member, @NotNull String handle) {
    User user = Entities.require(userRepository.findById(member), "User not found");
    String stored = user.getRsiHandle();
    ExchangeAccountCheckResult result;
    if (stored == null) {
      result = ExchangeAccountCheckResult.UNKNOWN;
    } else if (stored.equalsIgnoreCase(handle)) {
      result = ExchangeAccountCheckResult.MATCH;
    } else {
      result = ExchangeAccountCheckResult.MISMATCH;
    }
    counter(result).increment();
    return result;
  }

  /**
   * Registers or returns the counter of one result.
   *
   * @param result the result its {@code outcome} label names
   * @return the counter
   */
  private @NotNull Counter counter(@NotNull ExchangeAccountCheckResult result) {
    return meterRegistry.counter(
        MetricNames.EXCHANGE_ACCOUNT_CHECKS, MetricNames.TAG_OUTCOME, result.getValue());
  }
}

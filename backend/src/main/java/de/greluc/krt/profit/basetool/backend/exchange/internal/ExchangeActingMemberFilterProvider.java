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

import de.greluc.krt.profit.basetool.backend.exchange.api.ActingMemberAuthorities;
import de.greluc.krt.profit.basetool.backend.exchange.api.IngestGatewayProperties;
import de.greluc.krt.profit.basetool.backend.kernel.ProblemResponseFactory;
import de.greluc.krt.profit.basetool.backend.platform.api.ActingMemberFilterProvider;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.Filter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The exchange's {@link ActingMemberFilterProvider}: builds the {@link ActingMemberFilter} from the
 * configured gateways, the acting member's reduced authorities and the problem-response
 * infrastructure.
 */
@Component
@RequiredArgsConstructor
public class ExchangeActingMemberFilterProvider implements ActingMemberFilterProvider {

  /** The configured ingest gateway clients. */
  private final IngestGatewayProperties ingestGatewayProperties;

  /** Assembles the acting member's reduced exchange authorities. */
  private final ActingMemberAuthorities actingMemberAuthorities;

  /** Resolves the refusal texts. */
  private final MessageSource messageSource;

  /** Writes the RFC 7807 refusals. */
  private final ProblemResponseFactory problemResponseFactory;

  /** Serialises the refusals. */
  private final ObjectMapper objectMapper;

  /** Counts the refusals. */
  private final MeterRegistry meterRegistry;

  @Override
  @NotNull
  public Filter actingMemberFilter() {
    return new ActingMemberFilter(
        ingestGatewayProperties,
        actingMemberAuthorities,
        messageSource,
        problemResponseFactory,
        objectMapper,
        meterRegistry);
  }
}

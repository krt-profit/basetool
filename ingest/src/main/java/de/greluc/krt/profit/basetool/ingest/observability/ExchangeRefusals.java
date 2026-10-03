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

package de.greluc.krt.profit.basetool.ingest.observability;

import de.greluc.krt.profit.basetool.ingest.registry.ExchangeRegistry;
import de.greluc.krt.profit.basetool.ingest.registry.ExchangeRegistryReader;
import de.greluc.krt.profit.basetool.ingest.registry.ExchangeUnavailableException;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Component;

/**
 * Counts the gateway's exchange refusals by their problem code in snake case and by registry client
 * (REQ-XCH-025, REQ-XCH-028).
 */
@Component
@RequiredArgsConstructor
public class ExchangeRefusals {

  /** A request without a DPoP proof or with an unbound token. */
  public static final String DPOP_REQUIRED = "DPOP_REQUIRED";

  /** An invalid, replayed or foreign proof, or one without the server nonce. */
  public static final String DPOP_INVALID = "DPOP_INVALID";

  /** A proof of a member that already holds its cap of live proofs. */
  public static final String DPOP_PROOF_LIMIT = "DPOP_PROOF_LIMIT";

  /** A token that is missing, invalid, or not issued for this gateway. */
  public static final String UNAUTHENTICATED = "UNAUTHENTICATED";

  /** The exchange has no such route. */
  public static final String NOT_FOUND = "NOT_FOUND";

  /** The registry or the revocations cannot be read. */
  public static final String REGISTRY_UNAVAILABLE = "REGISTRY_UNAVAILABLE";

  /** The exchange is switched off. */
  public static final String EXCHANGE_DISABLED = "EXCHANGE_DISABLED";

  /** The token's client is not in the registry. */
  public static final String CLIENT_NOT_ALLOWED = "CLIENT_NOT_ALLOWED";

  /** The client is suspended. */
  public static final String CLIENT_SUSPENDED = "CLIENT_SUSPENDED";

  /** The token's DPoP key is on the deny list. */
  public static final String INSTALLATION_REVOKED = "INSTALLATION_REVOKED";

  /** The member disconnected the client after the token was issued. */
  public static final String CLIENT_REVOKED = "CLIENT_REVOKED";

  /** The route's capability is not in the token or not granted to the client. */
  public static final String SCOPE_MISSING = "SCOPE_MISSING";

  /** The client's version is below its minimum. */
  public static final String CLIENT_VERSION_UNSUPPORTED = "CLIENT_VERSION_UNSUPPORTED";

  /** A per-period limit is exhausted. */
  public static final String RATE_LIMITED = "RATE_LIMITED";

  /** The daily write quota is exhausted. */
  public static final String QUOTA_EXCEEDED = "QUOTA_EXCEEDED";

  /** A dependency of the gate, such as the quota counter, is unreachable. */
  public static final String SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";

  /** A write carries no usable Idempotency-Key. */
  public static final String IDEMPOTENCY_KEY_MISSING = "IDEMPOTENCY_KEY_MISSING";

  /** An Idempotency-Key was reused for a different request. */
  public static final String IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";

  /** The first request with this Idempotency-Key is still in flight. */
  public static final String IDEMPOTENCY_IN_PROGRESS = "IDEMPOTENCY_IN_PROGRESS";

  /** A Redis byte budget of the exchange is full. */
  public static final String EXCHANGE_BUDGET_EXHAUSTED = "EXCHANGE_BUDGET_EXHAUSTED";

  /** The gateway already relays as many large change sets as it admits at once. */
  public static final String RELAY_BUSY = "RELAY_BUSY";

  /** Every code this counter knows, registered at zero. */
  public static final @Unmodifiable List<String> CODES =
      List.of(
          DPOP_REQUIRED,
          DPOP_INVALID,
          DPOP_PROOF_LIMIT,
          UNAUTHENTICATED,
          NOT_FOUND,
          REGISTRY_UNAVAILABLE,
          EXCHANGE_DISABLED,
          CLIENT_NOT_ALLOWED,
          CLIENT_SUSPENDED,
          INSTALLATION_REVOKED,
          CLIENT_REVOKED,
          SCOPE_MISSING,
          CLIENT_VERSION_UNSUPPORTED,
          RATE_LIMITED,
          QUOTA_EXCEEDED,
          SERVICE_UNAVAILABLE,
          IDEMPOTENCY_KEY_MISSING,
          IDEMPOTENCY_KEY_REUSED,
          IDEMPOTENCY_IN_PROGRESS,
          EXCHANGE_BUDGET_EXHAUSTED,
          RELAY_BUSY);

  private final MeterRegistry meterRegistry;

  /** Tells a registered client from any other token's {@code azp}. */
  private final ExchangeRegistryReader registryReader;

  /** Registers every reason at zero, so a first refusal is an increase. */
  @PostConstruct
  void register() {
    CODES.forEach(
        code ->
            meterRegistry.counter(
                MetricNames.EXCHANGE_REFUSED,
                MetricNames.TAG_REASON,
                reason(code),
                MetricNames.TAG_CLIENT_ID,
                MetricNames.EXCHANGE_CLIENT_NONE));
  }

  /**
   * Counts one refusal.
   *
   * @param code the problem code
   * @param client the {@code client_id} label, from {@link #clientLabel(String)} or an admitted
   *     request's registry client id
   */
  public void count(@NotNull String code, @NotNull String client) {
    meterRegistry
        .counter(
            MetricNames.EXCHANGE_REFUSED,
            MetricNames.TAG_REASON,
            reason(code),
            MetricNames.TAG_CLIENT_ID,
            client)
        .increment();
  }

  /**
   * Returns the bounded {@code client_id} label of a token's {@code azp}: the client id when the
   * registry lists it, never an arbitrary value.
   *
   * @param azp the token's authorized party, or {@code null} before authentication
   * @return the client id, {@code none}, {@code unregistered}, or {@code unknown} while the
   *     registry cannot be read
   */
  public @NotNull String clientLabel(@Nullable String azp) {
    if (azp == null || azp.isBlank()) {
      return MetricNames.EXCHANGE_CLIENT_NONE;
    }
    try {
      return clientLabel(azp, registryReader.current());
    } catch (ExchangeUnavailableException e) {
      return MetricNames.EXCHANGE_CLIENT_UNKNOWN;
    }
  }

  /**
   * Returns the bounded {@code client_id} label of a token's {@code azp} against a registry.
   *
   * @param azp the token's authorized party, or {@code null}
   * @param registry the registry
   * @return the client id when listed, otherwise {@code none} or {@code unregistered}
   */
  public static @NotNull String clientLabel(
      @Nullable String azp, @NotNull ExchangeRegistry registry) {
    if (azp == null || azp.isBlank()) {
      return MetricNames.EXCHANGE_CLIENT_NONE;
    }
    return registry.clients().containsKey(azp) ? azp : MetricNames.EXCHANGE_CLIENT_UNREGISTERED;
  }

  /**
   * Returns the metric reason of a problem code.
   *
   * @param code the problem code
   * @return the code in lower case
   */
  static @NotNull String reason(@NotNull String code) {
    return code.toLowerCase(Locale.ROOT);
  }
}

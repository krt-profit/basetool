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

package de.greluc.krt.profit.basetool.backend.exchange.api;

import de.greluc.krt.profit.basetool.backend.exception.DomainProblem;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.http.HttpStatus;

/**
 * A refusal of the exchange layer carrying a code of the exchange error registry, which the ingest
 * gateway passes on to the client unchanged (REQ-XCH-011, REQ-XCH-025); the gate's refusals carry
 * the code and status the gateway's own gate answers for the same situation.
 *
 * <p>The status and code are per instance; the title and detail come from {@code
 * problem.<code>.title} and {@code .detail}.
 */
public final class ExchangeProblemException extends DomainProblem {

  /** A feed cursor older than the retained changes, or not one the server issued. */
  public static final String CURSOR_EXPIRED = "CURSOR_EXPIRED";

  /** A change set that removes more than the mass-change guard allows without confirmation. */
  public static final String MASS_CHANGE_CONFIRMATION_REQUIRED =
      "MASS_CHANGE_CONFIRMATION_REQUIRED";

  /** The global exchange switch is off. */
  public static final String EXCHANGE_DISABLED = "EXCHANGE_DISABLED";

  /** The relayed client is not in the registry. */
  public static final String CLIENT_NOT_ALLOWED = "CLIENT_NOT_ALLOWED";

  /** The relayed client is suspended in the registry. */
  public static final String CLIENT_SUSPENDED = "CLIENT_SUSPENDED";

  /** The member disconnected the calling installation. */
  public static final String INSTALLATION_REVOKED = "INSTALLATION_REVOKED";

  /** The member disconnected the client at or after the relayed connection time. */
  public static final String CLIENT_REVOKED = "CLIENT_REVOKED";

  /** The needed capability was not relayed or is not granted to the client. */
  public static final String SCOPE_MISSING = "SCOPE_MISSING";

  /** The registry mirror or the revocations cannot be read, so the request fails closed. */
  public static final String REGISTRY_UNAVAILABLE = "REGISTRY_UNAVAILABLE";

  private final HttpStatus status;
  private final String code;

  /**
   * Creates the refusal.
   *
   * @param status the HTTP status
   * @param code the exchange error code
   * @param message the log message, never shown to the client
   */
  public ExchangeProblemException(
      @NotNull HttpStatus status, @NotNull String code, @NotNull String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  /**
   * Creates the refusal with the failure behind it, kept for the log only.
   *
   * @param status the HTTP status
   * @param code the exchange error code
   * @param message the log message, never shown to the client
   * @param cause the failure behind the refusal
   */
  public ExchangeProblemException(
      @NotNull HttpStatus status,
      @NotNull String code,
      @NotNull String message,
      @NotNull Throwable cause) {
    super(message, cause);
    this.status = status;
    this.code = code;
  }

  /**
   * The refusal while the global exchange switch is off.
   *
   * @return the {@code 503 EXCHANGE_DISABLED} refusal
   */
  public static @NotNull ExchangeProblemException exchangeDisabled() {
    return new ExchangeProblemException(
        HttpStatus.SERVICE_UNAVAILABLE, EXCHANGE_DISABLED, "The exchange is switched off.");
  }

  /**
   * The refusal of a client the registry does not list.
   *
   * @return the {@code 403 CLIENT_NOT_ALLOWED} refusal
   */
  public static @NotNull ExchangeProblemException clientNotAllowed() {
    return new ExchangeProblemException(
        HttpStatus.FORBIDDEN, CLIENT_NOT_ALLOWED, "The client is not in the exchange registry.");
  }

  /**
   * The refusal of a suspended client.
   *
   * @return the {@code 403 CLIENT_SUSPENDED} refusal
   */
  public static @NotNull ExchangeProblemException clientSuspended() {
    return new ExchangeProblemException(
        HttpStatus.FORBIDDEN, CLIENT_SUSPENDED, "The client is suspended.");
  }

  /**
   * The refusal of an installation the member disconnected.
   *
   * @return the {@code 401 INSTALLATION_REVOKED} refusal
   */
  public static @NotNull ExchangeProblemException installationRevoked() {
    return new ExchangeProblemException(
        HttpStatus.UNAUTHORIZED, INSTALLATION_REVOKED, "The installation was disconnected.");
  }

  /**
   * The refusal of a connection made at or before the member's disconnect of the client.
   *
   * @return the {@code 401 CLIENT_REVOKED} refusal
   */
  public static @NotNull ExchangeProblemException clientRevoked() {
    return new ExchangeProblemException(
        HttpStatus.UNAUTHORIZED,
        CLIENT_REVOKED,
        "The member disconnected the client after this connection was made.");
  }

  /**
   * The refusal of a capability that was not relayed or is not granted.
   *
   * @return the {@code 403 SCOPE_MISSING} refusal
   */
  public static @NotNull ExchangeProblemException scopeMissing() {
    return new ExchangeProblemException(
        HttpStatus.FORBIDDEN, SCOPE_MISSING, "The needed capability is not relayed and granted.");
  }

  /**
   * The refusal while the exchange state the check needs cannot be read.
   *
   * @param cause the failed read
   * @return the {@code 503 REGISTRY_UNAVAILABLE} refusal
   */
  public static @NotNull ExchangeProblemException registryUnavailable(@NotNull Throwable cause) {
    return new ExchangeProblemException(
        HttpStatus.SERVICE_UNAVAILABLE,
        REGISTRY_UNAVAILABLE,
        "The exchange revocations could not be read.",
        cause);
  }

  /**
   * The refusal of a feed cursor the server can no longer serve.
   *
   * @return the {@code 410 CURSOR_EXPIRED} refusal
   */
  public static @NotNull ExchangeProblemException cursorExpired() {
    return new ExchangeProblemException(
        HttpStatus.GONE, CURSOR_EXPIRED, "The feed cursor is older than the retained changes.");
  }

  /**
   * The refusal of a change set the member must confirm in the browser; nothing was written.
   *
   * @return the {@code 409 MASS_CHANGE_CONFIRMATION_REQUIRED} refusal
   */
  public static @NotNull ExchangeProblemException massChangeConfirmationRequired() {
    return new ExchangeProblemException(
        HttpStatus.CONFLICT,
        MASS_CHANGE_CONFIRMATION_REQUIRED,
        "The change set removes more than the mass-change guard allows.");
  }

  @Override
  public HttpStatus status() {
    return status;
  }

  @Override
  public String code() {
    return code;
  }

  @NotNull
  @Override
  public String logLabel() {
    return "Exchange refusal";
  }

  @NotNull
  @Unmodifiable
  @Override
  public Map<String, ?> logExtra() {
    return Map.of("exchangeCode", code);
  }
}

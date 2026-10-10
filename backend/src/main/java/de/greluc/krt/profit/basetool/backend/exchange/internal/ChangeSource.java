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

import de.greluc.krt.profit.basetool.backend.platform.api.AuthenticatedSubject;
import de.greluc.krt.profit.basetool.backend.platform.api.SubjectAuthentication;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Names who is writing, in the form the change-feed triggers read from the transaction variable
 * {@value #VARIABLE} (REQ-XCH-013, ADR-0224).
 *
 * <p>The value is {@code web}, {@code app}, {@code system}, or {@code client|<clientId>|<key>} for
 * a request an exchange client relayed.
 */
@Component
@RequiredArgsConstructor
public class ChangeSource {

  /** The client a write in this thread is recorded for, while {@link #asClient} runs it. */
  private static final ThreadLocal<String> ON_BEHALF = new ThreadLocal<>();

  /**
   * Runs a write the member confirmed in the browser as the exchange client's own, so the change
   * feed records the installation that staged it (REQ-XCH-021).
   *
   * @param clientId the client
   * @param installationKey the installation that staged the write
   * @param write the write, which must open its transaction inside this call
   * @param <T> its result
   * @return its result
   */
  public static <T> T asClient(
      @NotNull String clientId, @NotNull String installationKey, @NotNull Supplier<T> write) {
    String previous = ON_BEHALF.get();
    ON_BEHALF.set("client|" + clientId + "|" + installationKey);
    try {
      return write.get();
    } finally {
      if (previous == null) {
        ON_BEHALF.remove();
      } else {
        ON_BEHALF.set(previous);
      }
    }
  }

  /** The transaction-local variable the triggers read. */
  public static final String VARIABLE = "basetool.change_source";

  /** Which clients are the web and the app. */
  private final ChangeSourceProperties properties;

  /**
   * Returns the source of the current thread's writes.
   *
   * @return the client a confirmed write runs for, else the source for the current authentication,
   *     {@code system} when there is none
   */
  public @NotNull String current() {
    String client = ON_BEHALF.get();
    if (client != null) {
      return client;
    }
    return of(SecurityContextHolder.getContext().getAuthentication());
  }

  /**
   * Returns the source of the writes made under an authentication.
   *
   * @param authentication the authentication, or {@code null} outside a request
   * @return {@code client|<clientId>|<key>} for a relayed exchange client, {@code web} or {@code
   *     app} for their clients, otherwise {@code system}
   */
  public @NotNull String of(@Nullable Authentication authentication) {
    if (authentication instanceof SubjectAuthentication subject
        && subject.externalClient() != null) {
      String key = subject.exchangeInstallationKey();
      return "client|" + subject.externalClient() + "|" + (key == null ? "" : key);
    }
    Optional<String> party = AuthenticatedSubject.authorizedParty(authentication);
    if (party.filter(properties.webClientIds()::contains).isPresent()) {
      return "web";
    }
    if (party.filter(properties.appClientIds()::contains).isPresent()) {
      return "app";
    }
    return "system";
  }
}

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

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Function;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The per-client pseudonyms of org-stock holders: HMAC-SHA256 of the user id under the client's
 * salt, so one member has the same ref at every request of a client and a different one at every
 * other client (REQ-XCH-043).
 */
@Component
@RequiredArgsConstructor
public class ExchangeHolderRefs {

  /** The MAC algorithm. */
  private static final String ALGORITHM = "HmacSHA256";

  /** Reads the salt, which never leaves the backend. */
  private final ExchangeClientRepository clientRepository;

  /**
   * Returns the ref function of one client.
   *
   * @param clientId the Keycloak client id
   * @return a function from a user id to its ref, unpadded base64url of the 32-byte MAC
   * @throws IllegalStateException when the client is not registered
   */
  public @NotNull Function<UUID, String> forClient(@NotNull String clientId) {
    byte[] salt =
        clientRepository
            .findHolderRefSalt(clientId)
            .orElseThrow(() -> new IllegalStateException("Unknown exchange client"));
    return user -> ref(salt, user);
  }

  /**
   * Computes one ref.
   *
   * @param salt the client's salt
   * @param user the user id
   * @return unpadded base64url of HMAC-SHA256(salt, the user id's 16 bytes)
   */
  static @NotNull String ref(byte @NotNull [] salt, @NotNull UUID user) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(salt, ALGORITHM));
      byte[] id =
          ByteBuffer.allocate(16)
              .putLong(user.getMostSignificantBits())
              .putLong(user.getLeastSignificantBits())
              .array();
      return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(id));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is unavailable", e);
    }
  }
}

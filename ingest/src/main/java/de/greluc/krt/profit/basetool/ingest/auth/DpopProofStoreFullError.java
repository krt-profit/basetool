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

package de.greluc.krt.profit.basetool.ingest.auth;

import java.io.Serial;
import lombok.Getter;
import org.springframework.security.oauth2.core.OAuth2Error;

/**
 * The proof verifier's refusal of an exchange proof because the exchange's replay store holds its
 * total cap of live proofs (REQ-XCH-006), carried through Spring's validation to the entry point,
 * which answers it {@code 503 SERVICE_UNAVAILABLE}.
 */
@Getter
public final class DpopProofStoreFullError extends OAuth2Error {

  /** The OAuth error code of this refusal. */
  public static final String ERROR_CODE = "dpop_store_full";

  @Serial private static final long serialVersionUID = 1L;

  /** The whole seconds until the store's earliest live proof no longer counts, at least one. */
  private final long retryAfterSeconds;

  /**
   * Creates the refusal.
   *
   * @param retryAfterSeconds the whole seconds until a proof may be sent again
   */
  public DpopProofStoreFullError(long retryAfterSeconds) {
    super(ERROR_CODE, "The DPoP replay store is full.", null);
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

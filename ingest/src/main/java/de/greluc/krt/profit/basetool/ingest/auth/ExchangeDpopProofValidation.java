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

import java.net.URI;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.oauth2.core.ClaimAccessor;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.DPoPProofContext;
import org.springframework.security.oauth2.jwt.DPoPProofJwtDecoderFactory;
import org.springframework.security.oauth2.jwt.DPoPProofReplayValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;

/**
 * Builds the DPoP proof verifier: Spring's checks ({@code htm}, {@code htu}, {@code iat}, key
 * binding, {@code ath}, {@code jti} replay) everywhere, plus, on exchange routes, the server nonce,
 * a {@link DpopProofLimitError} for a member at its proof cap and a {@link DpopProofStoreFullError}
 * for a full store (REQ-XCH-006). The {@code jti} replay cache is one {@link DpopProofReplayStore}
 * per path scope, partitioned by the access token's member, so neither one member nor a request
 * outside {@code /exchange} can fill the cache the exchange relies on.
 */
public final class ExchangeDpopProofValidation {

  /** The OAuth error code that asks the client to retry with the nonce of the challenge. */
  public static final String USE_DPOP_NONCE = "use_dpop_nonce";

  /** The proof claim carrying the server nonce. */
  static final String NONCE_CLAIM = "nonce";

  /** The root path of the exchange routes. */
  private static final String EXCHANGE_ROOT = "/exchange";

  /** Not instantiable. */
  private ExchangeDpopProofValidation() {}

  /**
   * Creates the proof verifier factory.
   *
   * @param nonces the server nonces
   * @param proofs the replay caches of the exchange and of every other route
   * @return the factory
   */
  public static @NotNull DPoPProofJwtDecoderFactory factory(
      @NotNull ExchangeDpopNonces nonces, @NotNull DpopProofReplayStores proofs) {
    OAuth2TokenValidator<Jwt> nonce = nonceValidator(nonces);
    DPoPProofJwtDecoderFactory factory = new DPoPProofJwtDecoderFactory();
    factory.setJwtValidatorFactory(
        context -> {
          boolean exchange = isExchange(context);
          DpopProofReplayStore.MemberView view =
              (exchange ? proofs.exchange() : proofs.other()).forMember(subjectOf(context));
          DPoPProofReplayValidator replay = new DPoPProofReplayValidator(view);
          OAuth2TokenValidator<Jwt> defaults =
              DPoPProofJwtDecoderFactory.createDefaultJwtValidatorFactory(List.of(replay))
                  .apply(context);
          if (!exchange) {
            return defaults;
          }
          DelegatingOAuth2TokenValidator<Jwt> withNonce =
              new DelegatingOAuth2TokenValidator<>(nonce, capRefusals(defaults, view));
          withNonce.setFailOnError(true);
          return withNonce;
        });
    return factory;
  }

  /**
   * Creates the validator that requires a current server nonce.
   *
   * @param nonces the server nonces
   * @return the validator
   */
  static @NotNull OAuth2TokenValidator<Jwt> nonceValidator(@NotNull ExchangeDpopNonces nonces) {
    OAuth2Error error =
        new OAuth2Error(
            USE_DPOP_NONCE, "The DPoP proof must carry the server nonce from DPoP-Nonce.", null);
    return proof ->
        nonces.isValid(proof.getClaimAsString(NONCE_CLAIM))
            ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(error);
  }

  /**
   * Reports a proof the replay check refused for the member cap as a {@link DpopProofLimitError},
   * and one it refused for a full store as a {@link DpopProofStoreFullError}, instead of Spring's
   * generic replay error; every other result passes unchanged.
   *
   * @param defaults Spring's proof checks, the replay check last
   * @param view the member's view of the replay cache the replay check claims through
   * @return the validator
   */
  static @NotNull OAuth2TokenValidator<Jwt> capRefusals(
      @NotNull OAuth2TokenValidator<Jwt> defaults, @NotNull DpopProofReplayStore.MemberView view) {
    return proof -> {
      OAuth2TokenValidatorResult result = defaults.validate(proof);
      if (!result.hasErrors()) {
        return result;
      }
      Long memberCap = view.proofLimitRetryAfter();
      if (memberCap != null) {
        return OAuth2TokenValidatorResult.failure(new DpopProofLimitError(memberCap));
      }
      Long storeFull = view.storeFullRetryAfter();
      return storeFull != null
          ? OAuth2TokenValidatorResult.failure(new DpopProofStoreFullError(storeFull))
          : result;
    };
  }

  /**
   * Whether a proof must meet the exchange rules; fails closed, so a target whose path cannot be
   * read counts as an exchange route and needs the nonce.
   *
   * @param context the proof's context
   * @return {@code false} only for a readable target path outside {@code /exchange}
   */
  static boolean isExchange(@NotNull DPoPProofContext context) {
    return isExchangeTarget(context.getTargetUri());
  }

  /**
   * Whether a target URI must meet the exchange rules, failing closed like {@link
   * #isExchange(DPoPProofContext)}.
   *
   * @param targetUri the request's target URI as the proof must name it
   * @return {@code false} only for a readable target path outside {@code /exchange}
   */
  static boolean isExchangeTarget(@NotNull String targetUri) {
    try {
      String path = URI.create(targetUri).getPath();
      return path == null
          || path.isEmpty()
          || path.equals(EXCHANGE_ROOT)
          || path.startsWith(EXCHANGE_ROOT + "/");
    } catch (IllegalArgumentException unreadable) {
      return true;
    }
  }

  /**
   * Reads the member the proof's access token was issued to.
   *
   * @param context the proof's context
   * @return the token's {@code sub}, or {@code null} when there is no token or no subject
   */
  static @Nullable String subjectOf(@NotNull DPoPProofContext context) {
    ClaimAccessor token = context.getAccessToken();
    return token == null ? null : token.getClaimAsString(JwtClaimNames.SUB);
  }
}

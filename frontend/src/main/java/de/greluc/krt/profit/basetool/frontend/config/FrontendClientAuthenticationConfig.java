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

package de.greluc.krt.profit.basetool.frontend.config;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.util.StringUtils;

/**
 * Chooses how the frontend authenticates to Keycloak's token endpoint from one fact: whether a
 * client secret is configured (REQ-SEC-069, ADR-0001).
 *
 * <p>With {@code KEYCLOAK_FRONTEND_CLIENT_SECRET} set, the {@code keycloak} registration becomes a
 * <strong>confidential</strong> client: {@code client_secret_basic} <em>and</em> PKCE, so a
 * captured authorization code cannot be redeemed without the secret. Without it the registration
 * stays the <strong>public</strong> client it has always been: {@code none}, PKCE only. PKCE is on
 * in both modes — {@code ClientSettings.requireProofKey} defaults to {@code true} in Spring
 * Security 7, and {@code FrontendClientAuthenticationConfigTest} pins it.
 *
 * <p><strong>Why one variable decides and not two.</strong> The pair "secret" and "authentication
 * method" can only be wrong in two ways, and both are outages: a method of {@code
 * client_secret_basic} with no secret sends an empty password, a secret with the method {@code
 * none} never sends it to a confidential client. Deriving the method from the secret leaves one
 * thing to set and nothing to disagree.
 *
 * <p><strong>Why the order of the rollout does not matter to the code.</strong> Keycloak accepts
 * {@code client_secret_basic} from a client it still considers public — it ignores the credentials
 * (measured on Keycloak 26.7 on 2026-09-23: a public client answers a wrong Basic secret exactly as
 * it answers none). So the frontend can be switched to confidential first and Keycloak second, with
 * no moment in which logins fail; see {@code docs/OAUTH2_CONFIDENTIAL_CLIENT_MIGRATION.md}.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class FrontendClientAuthenticationConfig {

  /** The registration id of the frontend's Keycloak login in {@code application.yml}. */
  static final String REGISTRATION_ID = "keycloak";

  /**
   * Registers the post-processor that settles the {@code keycloak} registration's authentication
   * method before Spring Boot turns the properties into a {@code ClientRegistrationRepository}.
   *
   * <p>{@code static}, as Spring requires of a {@link BeanPostProcessor} factory method, so the
   * processor exists before any other bean of this configuration is created.
   *
   * @return the post-processor.
   */
  @Bean
  public static @NotNull BeanPostProcessor frontendClientAuthenticationSelector() {
    return new BeanPostProcessor() {
      @Override
      public @NotNull Object postProcessBeforeInitialization(
          @NotNull Object bean, @NotNull String beanName) {
        if (bean instanceof OAuth2ClientProperties properties) {
          select(properties.getRegistration().get(REGISTRATION_ID));
        }
        return bean;
      }
    };
  }

  /**
   * Sets the registration's authentication method from whether it carries a secret, and normalises
   * a blank secret to none at all.
   *
   * @param registration the {@code keycloak} registration, or {@code null} when a profile defines
   *     none (then nothing is done).
   * @return {@code true} when the registration is now confidential, {@code false} when it is public
   *     or absent.
   */
  static boolean select(OAuth2ClientProperties.@Nullable Registration registration) {
    if (registration == null) {
      return false;
    }
    if (StringUtils.hasText(registration.getClientSecret())) {
      registration.setClientAuthenticationMethod(
          ClientAuthenticationMethod.CLIENT_SECRET_BASIC.getValue());
      log.info(
          "OAuth2 client '{}' is CONFIDENTIAL: client_secret_basic and PKCE (ADR-0001).",
          REGISTRATION_ID);
      return true;
    }
    registration.setClientSecret(null);
    registration.setClientAuthenticationMethod(ClientAuthenticationMethod.NONE.getValue());
    log.info(
        "OAuth2 client '{}' is PUBLIC: PKCE only. Set KEYCLOAK_FRONTEND_CLIENT_SECRET to make it"
            + " confidential (ADR-0001).",
        REGISTRATION_ID);
    return false;
  }
}

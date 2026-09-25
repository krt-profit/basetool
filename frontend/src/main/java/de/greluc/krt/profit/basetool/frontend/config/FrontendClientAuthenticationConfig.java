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
 * Chooses how the frontend authenticates to Keycloak's token endpoint from whether a client secret
 * is configured (REQ-SEC-069, ADR-0001).
 *
 * <p>With {@code KEYCLOAK_FRONTEND_CLIENT_SECRET} set the client is confidential ({@code
 * client_secret_basic}); without it, public ({@code none}). PKCE is on in both modes.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class FrontendClientAuthenticationConfig {

  /** The registration id of the frontend's Keycloak login in {@code application.yml}. */
  static final String REGISTRATION_ID = "keycloak";

  /**
   * Registers the post-processor that sets the {@code keycloak} registration's authentication
   * method before the {@code ClientRegistrationRepository} is built.
   *
   * @return the post-processor
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
   * Sets the registration's authentication method from whether it carries a secret, normalising a
   * blank secret to none.
   *
   * @param registration the {@code keycloak} registration, or {@code null} when none is defined
   * @return {@code true} when the registration is now confidential, {@code false} when public or
   *     absent
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

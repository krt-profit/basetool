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

package de.greluc.krt.profit.basetool.keycloak.spi.exchange;

import lombok.extern.jbosslog.JBossLog;
import org.jetbrains.annotations.NotNull;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.forms.login.freemarker.FreeMarkerLoginFormsProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

/**
 * Registers {@link DeviceConsentLoginFormsProvider} as the realm's login forms provider.
 *
 * <p>Its positive {@link #order()} makes it the default of the {@code login} SPI over Keycloak's
 * own {@code freemarker} factory without any {@code spi-login--provider} setting (ADR-0228).
 */
@JBossLog
public class DeviceConsentLoginFormsProviderFactory extends FreeMarkerLoginFormsProviderFactory {

  /** The provider id. */
  public static final String PROVIDER_ID = "krt-freemarker";

  /** The priority that outranks Keycloak's own factory, whose order is {@code 0}. */
  static final int ORDER = 100;

  @Override
  public @NotNull LoginFormsProvider create(KeycloakSession session) {
    return new DeviceConsentLoginFormsProvider(session);
  }

  @Override
  public void postInit(KeycloakSessionFactory factory) {
    log.infof("Login forms provider %s registered with order %d", PROVIDER_ID, ORDER);
  }

  @Override
  public @NotNull String getId() {
    return PROVIDER_ID;
  }

  @Override
  public int order() {
    return ORDER;
  }
}

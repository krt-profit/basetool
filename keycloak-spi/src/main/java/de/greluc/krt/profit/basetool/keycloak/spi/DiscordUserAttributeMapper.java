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

package de.greluc.krt.profit.basetool.keycloak.spi;

import org.jetbrains.annotations.NotNull;
import org.keycloak.broker.oidc.mappers.AbstractJsonUserAttributeMapper;

/**
 * JSON attribute-importer mapper for the Discord identity provider, used to import the profile's
 * {@code id} into the {@code discord_user_id} user attribute (REQ-DATA-006).
 */
public class DiscordUserAttributeMapper extends AbstractJsonUserAttributeMapper {

  /** Stable mapper id shown in the admin console. */
  public static final String PROVIDER_ID = "discord-user-attribute-mapper";

  private static final String[] COMPATIBLE_PROVIDERS = {DiscordIdentityProviderFactory.PROVIDER_ID};

  @Override
  public @NotNull String[] getCompatibleProviders() {
    return COMPATIBLE_PROVIDERS;
  }

  @Override
  public @NotNull String getId() {
    return PROVIDER_ID;
  }
}

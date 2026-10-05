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

package de.greluc.krt.profit.basetool.backend.service.exchange;

import de.greluc.krt.profit.basetool.backend.config.KeycloakSyncProperties;
import de.greluc.krt.profit.basetool.backend.exchange.api.IngestGatewayProperties;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ChangeSourceProperties;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ConnectedAppsProperties;
import de.greluc.krt.profit.basetool.backend.platform.api.PartialRoleScopeProperties;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The Keycloak client ids of the Basetool's own software as the backend is configured with them:
 * the web login, the app, the ingest gateway and the backend's admin client. None of them may be
 * registered as an exchange client (REQ-XCH-003); the SC Extractor is not among them, because it
 * takes part in the exchange as a registered client itself.
 */
@Component
@RequiredArgsConstructor
public class FirstPartyClientIds {

  /** The ingest gateway's client ids. */
  private final @NotNull IngestGatewayProperties ingestGatewayProperties;

  /** The web login's client ids, as the connected-apps page knows them. */
  private final @NotNull ConnectedAppsProperties connectedAppsProperties;

  /** The web and app client ids, as the change feed knows them. */
  private final @NotNull ChangeSourceProperties changeSourceProperties;

  /** The app's client ids, as the partial-role guard knows them. */
  private final @NotNull PartialRoleScopeProperties partialRoleScopeProperties;

  /** The backend's own Keycloak admin client. */
  private final @NotNull KeycloakSyncProperties keycloakSyncProperties;

  /**
   * Tells whether a client id belongs to the Basetool's own software.
   *
   * @param clientId the client id to check
   * @return {@code true} when any of the configured first-party ids equals it
   */
  public boolean contains(@Nullable String clientId) {
    return clientId != null && all().contains(clientId);
  }

  /**
   * Collects every configured first-party id.
   *
   * @return the ids, blanks left out
   */
  private @NotNull Set<String> all() {
    Set<String> ids = new HashSet<>();
    addAll(ids, ingestGatewayProperties.clientIds());
    addAll(ids, connectedAppsProperties.webClientIds());
    addAll(ids, changeSourceProperties.webClientIds());
    addAll(ids, changeSourceProperties.appClientIds());
    addAll(ids, partialRoleScopeProperties.clientIds());
    if (keycloakSyncProperties.clientId() != null && !keycloakSyncProperties.clientId().isBlank()) {
      ids.add(keycloakSyncProperties.clientId());
    }
    return ids;
  }

  /**
   * Adds the non-blank ids of one property.
   *
   * @param target the set to fill
   * @param source the configured ids, or {@code null}
   */
  private static void addAll(@NotNull Set<String> target, @Nullable Collection<String> source) {
    if (source == null) {
      return;
    }
    source.stream().filter(id -> id != null && !id.isBlank()).forEach(target::add);
  }
}

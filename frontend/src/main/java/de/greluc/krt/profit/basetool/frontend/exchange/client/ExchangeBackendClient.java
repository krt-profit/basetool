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

package de.greluc.krt.profit.basetool.frontend.exchange.client;

import de.greluc.krt.profit.basetool.frontend.model.dto.ConnectedAppDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ConnectedAppMassChangeRequestDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ConnectedAppMassChangeResultDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeBulkUndoInstallationDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeBulkUndoPreviewDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeBulkUndoRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeBulkUndoRunDetailDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeBulkUndoRunDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeClientCreateRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeClientDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeClientStatusRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeClientUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeClientUsageDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeSettingsDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeSettingsUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeUndoRequestDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeUndoResultDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * Typed backend client of the exchange domain: the admin client registry, the global switch, the
 * bulk undo runs and the member's connected apps (REQ-XCH-003, REQ-XCH-008, REQ-XCH-021,
 * REQ-XCH-034), over {@link BackendApiClient} (plan §5.9, ADR-0032).
 */
@Service
@RequiredArgsConstructor
public class ExchangeBackendClient {

  /** The backend's registry endpoints. */
  private static final String CLIENTS = "/api/v1/admin/exchange-clients";

  /** The backend's global switch endpoint. */
  private static final String SETTINGS = "/api/v1/admin/exchange-settings";

  /** The backend's bulk undo runs. */
  private static final String UNDO_RUNS = "/api/v1/admin/exchange-undo-runs";

  /** The backend's connected-apps endpoints. */
  private static final String CONNECTED_APPS = "/api/v1/connected-apps";

  /** The backend's mass-change endpoints. */
  private static final String MASS_CHANGES = "/api/v1/connected-apps/mass-changes/";

  private static final ParameterizedTypeReference<List<ExchangeClientDto>> CLIENT_LIST =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<ExchangeClientUsageDto>> USAGE_LIST =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<ExchangeBulkUndoRunDto>> RUN_LIST =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<ExchangeBulkUndoInstallationDto>>
      INSTALLATION_LIST = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<ConnectedAppDto>> CONNECTED_APP_LIST =
      new ParameterizedTypeReference<>() {};

  /** Sends every call through the one filter chain and error mapping. */
  private final BackendApiClient backendApiClient;

  /**
   * Lists the registered exchange clients.
   *
   * @return the registry, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<ExchangeClientDto> clients() {
    return backendApiClient.get(CLIENTS, CLIENT_LIST);
  }

  /**
   * Reads one registered client.
   *
   * @param id the registry id
   * @return the client, or {@code null} when the backend sent no body
   */
  @Nullable
  public ExchangeClientDto client(@NotNull UUID id) {
    return backendApiClient.get(CLIENTS + "/{id}", ExchangeClientDto.class, id);
  }

  /**
   * Registers a client.
   *
   * @param request the new client
   * @return the registered client
   */
  @Nullable
  public ExchangeClientDto createClient(@NotNull ExchangeClientCreateRequest request) {
    return backendApiClient.post(CLIENTS, request, ExchangeClientDto.class);
  }

  /**
   * Edits a client, carrying the optimistic-lock version in the request.
   *
   * @param id the registry id
   * @param request the edited client
   * @return the edited client
   */
  @Nullable
  public ExchangeClientDto updateClient(
      @NotNull UUID id, @NotNull ExchangeClientUpdateRequest request) {
    return backendApiClient.put(CLIENTS + "/{id}", request, ExchangeClientDto.class, id);
  }

  /**
   * Suspends a client, which the gateway refuses from then on.
   *
   * @param id the registry id
   * @param request the version the admin last saw
   * @return the suspended client
   */
  @Nullable
  public ExchangeClientDto suspendClient(
      @NotNull UUID id, @NotNull ExchangeClientStatusRequest request) {
    return backendApiClient.post(CLIENTS + "/{id}/suspend", request, ExchangeClientDto.class, id);
  }

  /**
   * Activates a suspended client.
   *
   * @param id the registry id
   * @param request the version the admin last saw
   * @return the activated client
   */
  @Nullable
  public ExchangeClientDto activateClient(
      @NotNull UUID id, @NotNull ExchangeClientStatusRequest request) {
    return backendApiClient.post(CLIENTS + "/{id}/activate", request, ExchangeClientDto.class, id);
  }

  /**
   * Reads how widely each client is in use.
   *
   * @return one usage row per client, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<ExchangeClientUsageDto> usage() {
    return backendApiClient.get(CLIENTS + "/usage", USAGE_LIST);
  }

  /**
   * Reads the global exchange switch.
   *
   * @return the switch, or {@code null} when the backend sent no body
   */
  @Nullable
  public ExchangeSettingsDto settings() {
    return backendApiClient.get(SETTINGS, ExchangeSettingsDto.class);
  }

  /**
   * Turns the global exchange switch on or off.
   *
   * @param request the new state and the version the admin last saw
   * @return the switch
   */
  @Nullable
  public ExchangeSettingsDto updateSettings(@NotNull ExchangeSettingsUpdateRequest request) {
    return backendApiClient.put(SETTINGS, request, ExchangeSettingsDto.class);
  }

  /**
   * Counts the members and entries a bulk undo of the client would reach; writes nothing.
   *
   * @param id the registry id
   * @param request the scope
   * @return the counts
   */
  @Nullable
  public ExchangeBulkUndoPreviewDto previewUndo(
      @NotNull UUID id, @NotNull ExchangeBulkUndoRequest request) {
    return backendApiClient.post(
        CLIENTS + "/{id}/undo/preview", request, ExchangeBulkUndoPreviewDto.class, id);
  }

  /**
   * Suspends the client and starts undoing its writes for every member.
   *
   * @param id the registry id
   * @param request the scope
   * @return the started run
   */
  @Nullable
  public ExchangeBulkUndoRunDto startUndo(
      @NotNull UUID id, @NotNull ExchangeBulkUndoRequest request) {
    return backendApiClient.post(CLIENTS + "/{id}/undo", request, ExchangeBulkUndoRunDto.class, id);
  }

  /**
   * Lists the client's installations a bulk undo could be limited to.
   *
   * @param id the registry id
   * @param since the start of the span
   * @return the installations, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<ExchangeBulkUndoInstallationDto> undoInstallations(
      @NotNull UUID id, @NotNull Instant since) {
    return backendApiClient.get(
        CLIENTS + "/{id}/undo/installations?since={since}",
        INSTALLATION_LIST,
        id,
        since.toString());
  }

  /**
   * Lists the recent bulk undo runs, newest first.
   *
   * @return the runs, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<ExchangeBulkUndoRunDto> undoRuns() {
    return backendApiClient.get(UNDO_RUNS, RUN_LIST);
  }

  /**
   * Reads one bulk undo run with the entries it left alone.
   *
   * @param runId the run
   * @return the run, or {@code null} when the backend sent no body
   */
  @Nullable
  public ExchangeBulkUndoRunDetailDto undoRun(@NotNull UUID runId) {
    return backendApiClient.get(UNDO_RUNS + "/{runId}", ExchangeBulkUndoRunDetailDto.class, runId);
  }

  /**
   * Lists the clients connected to the caller's account with their installations.
   *
   * @return the connected apps, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<ConnectedAppDto> connectedApps() {
    return backendApiClient.get(CONNECTED_APPS, CONNECTED_APP_LIST);
  }

  /**
   * Disconnects a whole client for the caller.
   *
   * @param clientId the registry client id, already checked against the registry's shape
   */
  public void disconnectClient(@NotNull String clientId) {
    backendApiClient.delete(CONNECTED_APPS + "/{clientId}", Void.class, clientId);
  }

  /**
   * Marks one of the caller's new installations seen.
   *
   * @param installationId the installation
   */
  public void markInstallationSeen(@NotNull UUID installationId) {
    backendApiClient.post(
        CONNECTED_APPS + "/installations/{installationId}/seen", null, Void.class, installationId);
  }

  /**
   * Undoes a client's writes to the caller's blueprints, stock and ships since a point in time.
   *
   * @param clientId the registry client id, already checked against the registry's shape
   * @param request the point in time
   * @return the entries restored and skipped
   */
  @Nullable
  public ExchangeUndoResultDto undoClientWrites(
      @NotNull String clientId, @NotNull ExchangeUndoRequestDto request) {
    return backendApiClient.post(
        CONNECTED_APPS + "/{clientId}/undo", request, ExchangeUndoResultDto.class, clientId);
  }

  /**
   * Disconnects one of the caller's installations.
   *
   * @param installationId the installation
   */
  public void disconnectInstallation(@NotNull UUID installationId) {
    backendApiClient.delete(
        CONNECTED_APPS + "/installations/{installationId}", Void.class, installationId);
  }

  /**
   * Previews a held-back change set without applying it.
   *
   * @param request the change set
   * @return what applying it would do
   */
  @Nullable
  public ConnectedAppMassChangeResultDto previewMassChange(
      @NotNull ConnectedAppMassChangeRequestDto request) {
    return backendApiClient.post(
        MASS_CHANGES + "preview", request, ConnectedAppMassChangeResultDto.class);
  }

  /**
   * Applies a held-back change set the caller confirmed.
   *
   * @param request the change set
   * @return what was applied
   */
  @Nullable
  public ConnectedAppMassChangeResultDto confirmMassChange(
      @NotNull ConnectedAppMassChangeRequestDto request) {
    return backendApiClient.post(
        MASS_CHANGES + "confirm", request, ConnectedAppMassChangeResultDto.class);
  }
}

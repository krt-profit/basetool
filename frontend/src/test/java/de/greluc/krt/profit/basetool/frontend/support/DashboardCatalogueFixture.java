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

package de.greluc.krt.profit.basetool.frontend.support;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.dashboard.client.DashboardBackendClient;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardCatalogueResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CachedCatalog;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import tools.jackson.databind.json.JsonMapper;

/**
 * The dashboard widget catalogue as the backend serves it (REQ-UI-034), read from the backend's
 * golden file {@code api/dashboard-catalogue.json}, for tests stubbing a mocked client.
 */
public final class DashboardCatalogueFixture {

  private static final Path GOLDEN =
      Paths.get("backend", "src", "test", "resources", "api", "dashboard-catalogue.json");

  private static final DashboardCatalogueResponse STANDARD = read();

  private DashboardCatalogueFixture() {}

  /**
   * The catalogue the application ships.
   *
   * @return the parsed golden file
   */
  public static DashboardCatalogueResponse standard() {
    return STANDARD;
  }

  /**
   * Makes a mocked {@link BackendApiClient} answer the catalogue read.
   *
   * @param backendApiClient the mock
   */
  public static void stub(BackendApiClient backendApiClient) {
    when(backendApiClient.getCached(
            eq(CachedCatalog.DASHBOARD_WIDGETS), eq(DashboardCatalogueResponse.class)))
        .thenReturn(STANDARD);
  }

  /**
   * Makes a mocked {@link DashboardBackendClient} answer the catalogue read.
   *
   * @param client the mock
   */
  public static void stub(DashboardBackendClient client) {
    when(client.catalogue()).thenReturn(STANDARD);
  }

  private static DashboardCatalogueResponse read() {
    for (Path dir = Paths.get("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
      Path candidate = dir.resolve(GOLDEN);
      if (Files.isRegularFile(candidate)) {
        try {
          return JsonMapper.builder()
              .build()
              .readValue(Files.readString(candidate), DashboardCatalogueResponse.class);
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }
    }
    throw new IllegalStateException("The backend's dashboard-catalogue.json was not found");
  }
}

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

package de.greluc.krt.profit.basetool.frontend.settings.client;

import de.greluc.krt.profit.basetool.frontend.service.BackendClientHarness;
import de.greluc.krt.profit.basetool.frontend.settings.client.SettingsBackendClient.SystemSetting;
import de.greluc.krt.profit.basetool.frontend.settings.model.SystemSettingUpdateDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Pins the requests {@link SettingsBackendClient} sends (plan F3). */
class SettingsBackendClientTest {

  private BackendClientHarness backend;
  private SettingsBackendClient client;

  @BeforeEach
  void setUp() {
    backend = BackendClientHarness.start();
    client = new SettingsBackendClient(backend.backendApiClient());
  }

  @AfterEach
  void tearDown() {
    backend.close();
  }

  @Test
  void everySettingIsReadAndWrittenAtItsOwnKey() {
    for (SystemSetting ignored : SystemSetting.values()) {
      backend.answerJson("{}");
      backend.answerJson("{}");
    }

    for (SystemSetting setting : SystemSetting.values()) {
      client.read(setting);
      client.write(setting, new SystemSettingUpdateDto("7", 3L));
    }

    for (String key :
        new String[] {
          "job_order.age_yellow_days",
          "job_order.age_red_days",
          "refinery.rounding.mode",
          "operation.transfer_fee_rate"
        }) {
      backend.expect("GET", "/api/v1/settings/" + key);
      backend.expect("PUT", "/api/v1/settings/" + key, "{\"value\":\"7\",\"version\":3}");
    }
  }

  @Test
  void toggleListsArePagedByTemplate() {
    String empty = "{\"content\":[],\"page\":0,\"size\":1000,\"totalElements\":0,\"totalPages\":0}";
    backend.answerJson(empty);
    backend.answerJson(empty);

    client.squadronPage(2);
    client.specialCommandPage(0);

    backend.expect("GET", "/api/v1/squadrons?size=1000&sort=name,asc&page=2");
    backend.expect("GET", "/api/v1/special-commands?size=1000&sort=name,asc&page=0");
  }
}

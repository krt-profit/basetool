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

package de.greluc.krt.profit.basetool.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Holds {@code V440__drop_legacy_app_user_member_settings.sql} to its effect: the member settings
 * live only in {@code member_payout_setting} and {@code member_blueprint_sharing}.
 */
@SpringBootTest
class V440DropLegacyMemberSettingsMigrationTest {

  @Autowired private JdbcTemplate jdbc;

  @Test
  void appUserNoLongerCarriesTheMemberSettings() {
    List<String> columns =
        jdbc.queryForList(
            "SELECT column_name FROM information_schema.columns"
                + " WHERE table_schema = current_schema() AND table_name = 'app_user'",
            String.class);

    assertThat(columns)
        .isNotEmpty()
        .doesNotContain("default_payout_preference", "share_blueprints_globally");
  }

  @Test
  void theSettingsTablesKeepTheirColumns() {
    List<String> columns =
        jdbc.queryForList(
            "SELECT table_name || '.' || column_name FROM information_schema.columns"
                + " WHERE table_schema = current_schema()"
                + " AND table_name IN ('member_payout_setting', 'member_blueprint_sharing')",
            String.class);

    assertThat(columns)
        .contains(
            "member_payout_setting.default_payout_preference",
            "member_blueprint_sharing.share_globally");
  }
}

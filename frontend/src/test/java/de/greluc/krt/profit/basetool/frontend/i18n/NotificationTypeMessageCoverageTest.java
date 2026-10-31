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

package de.greluc.krt.profit.basetool.frontend.i18n;

import static de.greluc.krt.profit.basetool.frontend.support.BackendNotificationTypes.resolveModuleRelative;
import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.support.BackendNotificationTypes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tests that every {@code NotificationType} the backend can persist has a {@code
 * notifications.type.*} template in all three bundles (REQ-NOTIF-001).
 */
class NotificationTypeMessageCoverageTest {

  /** The three bundles, by the name a failure should name. */
  private static final Map<String, Path> BUNDLES =
      Map.of(
          "messages.properties",
          resolveModuleRelative("src/main/resources/messages.properties"),
          "messages_de.properties",
          resolveModuleRelative("src/main/resources/messages_de.properties"),
          "messages_en.properties",
          resolveModuleRelative("src/main/resources/messages_en.properties"));

  @Test
  void everyNotificationTypeHasATemplateInEveryBundle() throws IOException {
    Set<String> constants = BackendNotificationTypes.all();

    Map<String, List<String>> missingByBundle = new LinkedHashMap<>();
    for (Map.Entry<String, Path> bundle : BUNDLES.entrySet()) {
      String content = Files.readString(bundle.getValue(), StandardCharsets.UTF_8);
      List<String> missing = new ArrayList<>();
      for (String constant : constants) {
        if (!content.contains("notifications.type." + constant + "=")) {
          missing.add(constant);
        }
      }
      if (!missing.isEmpty()) {
        missingByBundle.put(bundle.getKey(), missing);
      }
    }

    assertThat(missingByBundle)
        .as(
            "Each of these notification types can reach a member's inbox with no message of its"
                + " own, so the bell renders notifications.type.generic and drops the render"
                + " parameters. Add notifications.type.<TYPE> to the bundle named here -- umlauts"
                + " as \\\\uXXXX in the .properties files.")
        .isEmpty();
  }
}

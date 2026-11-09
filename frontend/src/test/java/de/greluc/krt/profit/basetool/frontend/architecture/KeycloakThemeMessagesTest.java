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

package de.greluc.krt.profit.basetool.frontend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Keycloak renders every theme message through {@code java.text.MessageFormat}, which drops a lone
 * apostrophe; a literal one is written {@code ''} in the Keycloak theme bundles.
 */
class KeycloakThemeMessagesTest {

  /** A single apostrophe that is not half of a doubled one. */
  private static final Pattern LONE_APOSTROPHE = Pattern.compile("(?<!')'(?!')");

  /**
   * Finds every message whose apostrophe Keycloak would swallow.
   *
   * @param bundle repository-relative path of one theme bundle
   * @throws IOException if the bundle cannot be read
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "keycloak-theme/krt-theme/login/messages/messages_de.properties",
        "keycloak-theme/krt-theme/login/messages/messages_en.properties"
      })
  void noMessageHoldsALoneApostrophe(String bundle) throws IOException {
    Properties messages = new Properties();
    try (Reader reader = Files.newBufferedReader(resolve(bundle), StandardCharsets.UTF_8)) {
      messages.load(reader);
    }
    List<String> broken = new ArrayList<>();
    messages.forEach((key, value) -> {
      if (LONE_APOSTROPHE.matcher(value.toString()).find()) {
        broken.add(key.toString());
      }
    });

    assertThat(broken)
        .as("messages in %s with an apostrophe MessageFormat drops", bundle)
        .isEmpty();
  }

  /**
   * Resolves a repository-relative path whether the test runs from the {@code frontend} module or
   * the repository root.
   *
   * @param repositoryRelative the path relative to the repository root
   * @return the first candidate that exists
   */
  private static @NotNull Path resolve(@NotNull String repositoryRelative) {
    Path fromModule = Path.of("..").resolve(repositoryRelative);
    return Files.exists(fromModule) ? fromModule : Path.of(repositoryRelative);
  }
}

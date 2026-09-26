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

package de.greluc.krt.profit.basetool.frontend.oss;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Asserts that every directory holding a bundled font file also holds its licence text and that the
 * font is listed in {@code frontend/oss-bundled-components.json} (REQ-UI-021). Runs with the {@code
 * frontend} module as working directory.
 */
@DisplayName("Bundled third-party files")
class OssBundledComponentsTest {

  /** Every directory tree of the repository that ships font files. */
  private static final List<Path> FONT_ROOTS =
      List.of(
          Path.of("src/main/resources/static/fonts"),
          Path.of("../backend/src/main/resources/fonts"),
          Path.of("../keycloak-theme"));

  @Test
  @DisplayName("every directory with a font file carries OFL.txt beside it")
  void everyFontDirectoryCarriesItsLicence() throws IOException {
    List<Path> fontDirs = fontFiles().map(Path::getParent).distinct().toList();

    assertThat(fontDirs).as("the scan found no fonts; the roots above are wrong").isNotEmpty();
    for (Path dir : fontDirs) {
      assertThat(dir.resolve("OFL.txt")).as(dir.toString()).isRegularFile();
      assertThat(Files.readString(dir.resolve("OFL.txt")))
          .as(dir.toString())
          .contains("SIL OPEN FONT LICENSE Version 1.1")
          .contains("Reserved Font Name \"Lato\"");
    }
  }

  @Test
  @DisplayName("every shipped font family is listed in oss-bundled-components.json")
  void everyFontFamilyIsListed() throws IOException {
    List<String> listed =
        JsonMapper.builder()
            .build()
            .readValue(Path.of("oss-bundled-components.json").toFile(), OssLicenseReport.class)
            .components()
            .stream()
            .map(OssComponent::name)
            .toList();

    List<String> families =
        fontFiles()
            .map(p -> p.getFileName().toString())
            .map(
                name ->
                    name.substring(
                        0, name.indexOf('-') > 0 ? name.indexOf('-') : name.indexOf('.')))
            .distinct()
            .toList();

    assertThat(families).isNotEmpty();
    assertThat(listed).containsAll(families);
  }

  /**
   * Every font file under the roots.
   *
   * @return the {@code .ttf}, {@code .otf}, {@code .woff} and {@code .woff2} files
   * @throws IOException when a root cannot be walked
   */
  private static Stream<Path> fontFiles() throws IOException {
    Stream<Path> all = Stream.empty();
    for (Path root : FONT_ROOTS) {
      try (Stream<Path> walk = Files.walk(root)) {
        List<Path> fonts =
            walk.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().matches("(?i).+\\.(ttf|otf|woff2?)$"))
                .toList();
        all = Stream.concat(all, fonts.stream());
      }
    }
    return all;
  }
}

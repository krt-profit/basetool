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

package de.greluc.krt.profit.basetool.frontend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Pins that every Playwright class launches its browser through the single {@code
 * E2eSupport.launchBrowser} seam (ADR-0200).
 *
 * <p>Since 2026-09-22 {@code playwrightInstall} honours {@code -Pe2e.browser}, so a Firefox or
 * WebKit matrix cell of {@code .github/workflows/e2e.yml} installs only that engine. A class that
 * launches an engine of its own — {@code playwright.chromium().launch(...)} — then fails with a
 * {@code DriverException} in ten of the fifteen cells, which is exactly how the four ad-hoc mockup
 * harnesses turned main's E2E suite red. The label-gated E2E run is the first place that shows up;
 * this test moves it into the ordinary unit-test run. The sources are read as text from the
 * repository, the way {@link E2ePrebuiltImageParityTest} pins the prebuilt image names.
 */
class E2eBrowserLaunchSeamTest {

  /** The Playwright sources, relative to the {@code frontend} module the test runs in. */
  private static final Path E2E_SOURCES =
      Path.of("src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e");

  /** The one class allowed to pick an engine: it reads {@code e2e.browser}. */
  private static final String SEAM = "E2eSupport.java";

  /** A direct engine accessor on a {@code Playwright} instance. */
  private static final Pattern DIRECT_ENGINE =
      Pattern.compile("\\.(chromium|firefox|webkit)\\s*\\(\\s*\\)");

  /**
   * No Playwright class other than {@code E2eSupport} names an engine, so every class runs on the
   * engine its matrix cell installed.
   *
   * @throws IOException if the e2e source directory cannot be walked or a source cannot be read
   */
  @Test
  void noE2eClassLaunchesAnEngineOutsideTheSeam() throws IOException {
    List<String> offenders = new ArrayList<>();
    try (Stream<Path> sources = Files.list(E2E_SOURCES)) {
      for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
        String name = source.getFileName().toString();
        if (!SEAM.equals(name)
            && DIRECT_ENGINE.matcher(Files.readString(source, StandardCharsets.UTF_8)).find()) {
          offenders.add(name);
        }
      }
    }
    assertThat(offenders)
        .as(
            "launch the browser with E2eSupport.launchBrowser(playwright, managesStack): a"
                + " Firefox/WebKit matrix cell installs only its own engine (ADR-0200)")
        .isEmpty();
  }

  /**
   * The seam itself still names all three engines, so the guard above cannot pass merely because
   * the pattern stopped matching the way engines are launched.
   *
   * @throws IOException if {@code E2eSupport} cannot be read
   */
  @Test
  void theSeamIsWhereTheEnginesAreLaunched() throws IOException {
    String seam = Files.readString(E2E_SOURCES.resolve(SEAM), StandardCharsets.UTF_8);
    assertThat(DIRECT_ENGINE.matcher(seam).results().map(m -> m.group(1)).distinct())
        .containsExactlyInAnyOrder("chromium", "firefox", "webkit");
  }
}

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Pins together the three places that must agree on the names of the E2E stack's prebuilt images
 * (audit item CI-06).
 *
 * <p>Since 2026-09-22 {@code .github/workflows/e2e.yml} builds the backend and frontend images once
 * in a {@code build-stack} job and every matrix cell loads them and runs the suite with {@code
 * -Pe2e.prebuilt=true}, which makes {@code E2eStackExtension} boot them with {@code docker compose
 * up --no-build}. That only works while three independent strings name the same images:
 *
 * <ul>
 *   <li>the {@code BACKEND_IMAGE} / {@code FRONTEND_IMAGE} tags the workflow builds,
 *   <li>the {@code image:} template in {@code docker-compose.build.yml}, rendered with the tag the
 *       extension sets ({@code IRI_BASETOOL_VERSION}), which is what compose looks for, and
 *   <li>the extension's own {@code IMAGE_TAG}, which it hands compose and checks the store for.
 * </ul>
 *
 * <p>If any one drifts, compose does not find the loaded image and tries to pull {@code :e2e-local}
 * from GHCR. The extension refuses that case with a clear message at runtime; this test refuses it
 * in the ordinary unit-test run, before a label-gated E2E run is spent finding out. The files are
 * read as text from the repository, the same way {@link E2eAudienceEnforcementParityTest} pins the
 * audience across the realm export and the extension.
 */
class E2ePrebuiltImageParityTest {

  /** The E2E workflow, relative to the {@code frontend} module the test runs in. */
  private static final Path WORKFLOW = Path.of("..", ".github", "workflows", "e2e.yml");

  /** The compose override that names the built images. */
  private static final Path BUILD_OVERRIDE = Path.of("..", "docker-compose.build.yml");

  /** The extension that sets the tag and checks the store for the images. */
  private static final Path STACK_EXTENSION =
      Path.of("src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/E2eStackExtension.java");

  /** {@code IMAGE_TAG = "..."} in the extension, anchored on the declaration. */
  private static final Pattern IMAGE_TAG_CONSTANT =
      Pattern.compile("String\\s+IMAGE_TAG\\s*=\\s*\"([^\"]+)\"");

  /** A {@code BACKEND_IMAGE:} / {@code FRONTEND_IMAGE:} env entry in the workflow. */
  private static final Pattern WORKFLOW_IMAGE =
      Pattern.compile("(?m)^\\s+(BACKEND|FRONTEND)_IMAGE:\\s*(\\S+)\\s*$");

  /** An {@code image:} line of a built service in the compose override. */
  private static final Pattern COMPOSE_IMAGE =
      Pattern.compile("(?m)^\\s+image:\\s*(\\S*basetool-(backend|frontend):\\S+)\\s*$");

  /**
   * The workflow builds exactly the images the compose override names once the extension's tag is
   * substituted -- so {@code up --no-build} finds what {@code build-stack} built.
   *
   * @throws IOException if one of the three files cannot be read
   */
  @Test
  void theWorkflowBuildsTheImagesComposeBootsUnderTheExtensionsTag() throws IOException {
    String tag = extensionImageTag();
    List<String> built = workflowImages();
    List<String> expected = composeImages(tag);

    assertThat(expected)
        .as("docker-compose.build.yml names a backend and a frontend image")
        .hasSize(2);
    assertThat(built)
        .as(
            "e2e.yml's build-stack job must build the images compose looks for with"
                + " IRI_BASETOOL_VERSION=%s; a drift makes every matrix cell try to pull :%s from"
                + " GHCR",
            tag, tag)
        .containsExactlyInAnyOrderElementsOf(expected);
  }

  /**
   * The matrix job actually asks for prebuilt mode -- otherwise the build-stack job would build for
   * nobody and every cell would build again.
   *
   * @throws IOException if the workflow cannot be read
   */
  @Test
  void theMatrixRunsInPrebuiltModeAndDependsOnTheBuildJob() throws IOException {
    String workflow = Files.readString(WORKFLOW, StandardCharsets.UTF_8);
    assertThat(workflow).contains("-Pe2e.prebuilt=true").contains("needs: build-stack");
  }

  /**
   * Reads the extension's {@code IMAGE_TAG} literal.
   *
   * @return the tag the extension sets as {@code IRI_BASETOOL_VERSION}
   * @throws IOException if the extension source cannot be read
   */
  private static String extensionImageTag() throws IOException {
    Matcher m =
        IMAGE_TAG_CONSTANT.matcher(Files.readString(STACK_EXTENSION, StandardCharsets.UTF_8));
    assertThat(m.find()).as("E2eStackExtension declares IMAGE_TAG").isTrue();
    return m.group(1);
  }

  /**
   * Reads the two image names the workflow's {@code build-stack} job builds.
   *
   * @return the {@code BACKEND_IMAGE} and {@code FRONTEND_IMAGE} values, in file order
   * @throws IOException if the workflow cannot be read
   */
  private static List<String> workflowImages() throws IOException {
    Matcher m = WORKFLOW_IMAGE.matcher(Files.readString(WORKFLOW, StandardCharsets.UTF_8));
    List<String> images = new ArrayList<>();
    while (m.find()) {
      images.add(m.group(2));
    }
    return images;
  }

  /**
   * Renders the built services' {@code image:} templates the way compose does for the E2E stack:
   * {@code IRI_IMAGE_NAMESPACE} unset (its default applies) and {@code IRI_BASETOOL_VERSION} set to
   * {@code tag}. Distinct values only -- the prod and dev twins share one template.
   *
   * @param tag the tag the extension sets
   * @return the rendered backend and frontend image names
   * @throws IOException if the compose override cannot be read
   */
  private static List<String> composeImages(String tag) throws IOException {
    Matcher m = COMPOSE_IMAGE.matcher(Files.readString(BUILD_OVERRIDE, StandardCharsets.UTF_8));
    List<String> images = new ArrayList<>();
    while (m.find()) {
      String rendered =
          m.group(1)
              .replace("${IRI_IMAGE_NAMESPACE:-krt-profit}", "krt-profit")
              .replace("${IRI_BASETOOL_VERSION:-local}", tag);
      if (!images.contains(rendered)) {
        images.add(rendered);
      }
    }
    return images;
  }
}

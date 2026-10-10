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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the E2E workflow's image tags, the {@code image:} templates in {@code
 * docker-compose.build.yml} and {@code docker-compose.e2e.yml}, and {@code E2eStackExtension}'s
 * {@code IMAGE_TAG} and {@code BUILT_IMAGES} name the same prebuilt images, reading the files as
 * text.
 */
class E2ePrebuiltImageParityTest {

  /** The E2E workflow, relative to the {@code frontend} module the test runs in. */
  private static final Path WORKFLOW = Path.of("..", ".github", "workflows", "e2e.yml");

  /** The compose override that names the built application images. */
  private static final Path BUILD_OVERRIDE = Path.of("..", "docker-compose.build.yml");

  /** The compose override that names the built sandbox Keycloak image. */
  private static final Path E2E_OVERRIDE = Path.of("..", "docker-compose.e2e.yml");

  /** The extension that sets the tag and checks the store for the images. */
  private static final Path STACK_EXTENSION =
      Path.of("src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/E2eStackExtension.java");

  /** {@code IMAGE_TAG = "..."} in the extension, anchored on the declaration. */
  private static final Pattern IMAGE_TAG_CONSTANT =
      Pattern.compile("String\\s+IMAGE_TAG\\s*=\\s*\"([^\"]+)\"");

  /** One {@code "ghcr.io/...:" + IMAGE_TAG} entry of the extension's {@code BUILT_IMAGES}. */
  private static final Pattern EXTENSION_IMAGE =
      Pattern.compile("\"(ghcr\\.io/[^\"]+:)\"\\s*\\+\\s*IMAGE_TAG");

  /** A {@code <NAME>_IMAGE:} env entry of the workflow's {@code build-stack} job. */
  private static final Pattern WORKFLOW_IMAGE =
      Pattern.compile("(?m)^\\s+(BACKEND|FRONTEND|INGEST|KEYCLOAK)_IMAGE:\\s*(\\S+)\\s*$");

  /** An {@code image:} line of a built application service in the build override. */
  private static final Pattern BUILD_IMAGE =
      Pattern.compile("(?m)^\\s+image:\\s*(\\S*basetool-(backend|frontend|ingest):\\S+)\\s*$");

  /** The {@code image:} line of the built sandbox Keycloak in the E2E override. */
  private static final Pattern KEYCLOAK_IMAGE =
      Pattern.compile("(?m)^\\s+image:\\s*(\\S*basetool-sandbox-keycloak:\\S+)\\s*$");

  /**
   * The workflow builds exactly the images the compose overrides name once the extension's tag is
   * substituted -- so {@code up --no-build} finds what {@code build-stack} built.
   *
   * @throws IOException if one of the files cannot be read
   */
  @Test
  void theWorkflowBuildsTheImagesComposeBootsUnderTheExtensionsTag() throws IOException {
    String tag = extensionImageTag();
    List<String> expected = composeImages(tag);

    assertThat(expected)
        .as("the compose overrides name a backend, frontend, ingest and sandbox Keycloak image")
        .hasSize(4);
    assertThat(workflowImages())
        .as(
            "e2e.yml's build-stack job must build the images compose looks for with"
                + " IRI_BASETOOL_VERSION=%s; a drift makes every matrix cell try to pull :%s from"
                + " GHCR",
            tag, tag)
        .containsExactlyInAnyOrderElementsOf(expected);
  }

  /**
   * The extension checks the local store for exactly the images compose boots in prebuilt mode.
   *
   * @throws IOException if one of the files cannot be read
   */
  @Test
  void theExtensionChecksForTheImagesComposeBoots() throws IOException {
    String tag = extensionImageTag();
    Matcher m = EXTENSION_IMAGE.matcher(Files.readString(STACK_EXTENSION, StandardCharsets.UTF_8));
    List<String> checked = new ArrayList<>();
    while (m.find()) {
      checked.add(m.group(1) + tag);
    }
    assertThat(checked)
        .as("E2eStackExtension.BUILT_IMAGES must name the images compose boots")
        .containsExactlyInAnyOrderElementsOf(composeImages(tag));
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
   * Reads the image names the workflow's {@code build-stack} job builds.
   *
   * @return the {@code *_IMAGE} values, in file order
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
   * @return the rendered application and sandbox Keycloak image names
   * @throws IOException if a compose override cannot be read
   */
  private static List<String> composeImages(String tag) throws IOException {
    List<String> images = new ArrayList<>();
    collect(BUILD_IMAGE, BUILD_OVERRIDE, tag, images);
    collect(KEYCLOAK_IMAGE, E2E_OVERRIDE, tag, images);
    return images;
  }

  /**
   * Adds every distinct rendered {@code image:} template of one compose file that {@code pattern}
   * matches.
   *
   * @param pattern the {@code image:} line pattern, the template in group 1
   * @param file the compose file
   * @param tag the tag the extension sets
   * @param images the list to add to
   * @throws IOException if the file cannot be read
   */
  private static void collect(Pattern pattern, Path file, String tag, List<String> images)
      throws IOException {
    Matcher m = pattern.matcher(Files.readString(file, StandardCharsets.UTF_8));
    while (m.find()) {
      String rendered =
          m.group(1)
              .replace("${IRI_IMAGE_NAMESPACE:-krt-profit}", "krt-profit")
              .replace("${IRI_BASETOOL_VERSION:-local}", tag);
      if (!images.contains(rendered)) {
        images.add(rendered);
      }
    }
  }
}

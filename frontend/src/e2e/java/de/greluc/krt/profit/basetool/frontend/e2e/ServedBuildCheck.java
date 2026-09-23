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

package de.greluc.krt.profit.basetool.frontend.e2e;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes an ephemeral E2E stack provably this checkout's (2026-09-23).
 *
 * <p>Until then the stack tagged its locally built images {@code :e2e-local} — one name for every
 * checkout on a machine. Two checkouts running the suite at once (parallel worktrees, several
 * agents on one workstation) raced for that tag: one built, the other built after it, and the first
 * then booted {@code up --build}'s result under a tag that already pointed at the other checkout's
 * image. The suite went green against code it was never meant to test, with nothing in the output
 * saying so. That is the failure this class closes, in two halves:
 *
 * <ul>
 *   <li><strong>Names that cannot collide.</strong> A locally built stack uses an image tag and a
 *       compose project name derived from the checkout's own path ({@link #localImageTag}, {@link
 *       #composeProjectName}), so no two checkouts share images, containers, networks or volumes.
 *       The prebuilt CI path keeps its fixed tag: one runner, one checkout, and the build and
 *       matrix jobs must agree on a name ({@code E2ePrebuiltImageParityTest}).
 *   <li><strong>A check that asks the running stack.</strong> After {@code up}, {@link
 *       #assertServesThisCheckout} reads the landing page and compares the content hash in every
 *       {@code /js/} and {@code /css/} URL it links — Spring's content version strategy puts the
 *       MD5 of the served file there — with the MD5 of the same file in this checkout. A stack
 *       serving anything else fails the bring-up before a single test runs.
 * </ul>
 *
 * <p>Ports and Docker subnets stay fixed, deliberately: two stacks at once fail at {@code up} with
 * "port is already allocated" or "Pool overlaps", which is loud. {@link #assertPortFree} turns that
 * into a message naming the other stack.
 */
final class ServedBuildCheck {

  /** A content-hashed asset URL as the frontend renders it: {@code /js/name-<md5>.js}. */
  private static final Pattern ASSET =
      Pattern.compile("/(js|css)/([A-Za-z0-9._/-]+?)-([0-9a-f]{32})\\.(?:js|css)");

  /** Fewer compared assets than this means the check did not really look. */
  private static final int MIN_COMPARED = 10;

  private ServedBuildCheck() {}

  /**
   * The image tag a locally built stack uses: {@code e2e-<directory>-<hash of the path>}.
   *
   * @param root the checkout's root directory
   * @return a Docker tag unique to this checkout on this machine
   */
  static String localImageTag(Path root) {
    return "e2e-" + slug(root) + "-" + pathHash(root);
  }

  /**
   * The compose project name a locally built stack uses, for the same reason as the tag: two
   * checkouts whose directories share a name would otherwise share containers and volumes.
   *
   * @param root the checkout's root directory
   * @return a compose project name unique to this checkout on this machine
   */
  static String composeProjectName(Path root) {
    return "e2e-" + slug(root) + "-" + pathHash(root);
  }

  /**
   * Fails fast, naming the other stack, when a container already publishes the frontend port.
   *
   * @param publishedBy the names of running containers publishing the port, one per line
   * @param port the port
   */
  static void assertPortFree(String publishedBy, int port) {
    String others = publishedBy == null ? "" : publishedBy.strip();
    if (!others.isEmpty()) {
      throw new IllegalStateException(
          "Port "
              + port
              + " is already published by "
              + others.replace('\n', ',')
              + ". Only one ephemeral E2E stack can run on a machine at a time (fixed ports and"
              + " subnets); wait for that run to finish, or tear its stack down with"
              + " `docker compose -p <its project> down --volumes`.");
    }
  }

  /**
   * Reads the landing page of the stack and fails unless every content-hashed script and stylesheet
   * it links is byte for byte this checkout's file.
   *
   * <p>Scripts are served as they are in {@code src/main/resources/static/js}. Stylesheets are
   * served after {@code minifyStaticCss}, so they are compared with the processed copy under {@code
   * build/resources/main/static/css}, which the e2e compile has just produced from the same
   * sources.
   *
   * @param baseUrl the frontend origin
   * @param root the checkout's root directory
   * @param http a client that trusts the stack's TLS certificate
   * @throws IllegalStateException when an asset differs, is missing locally, or too few were found
   */
  static void assertServesThisCheckout(String baseUrl, Path root, HttpClient http) {
    String html;
    try {
      HttpResponse<String> response =
          http.send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/"))
                  .timeout(Duration.ofSeconds(30))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      html = response.body();
    } catch (IOException e) {
      throw new IllegalStateException("Could not read the landing page of " + baseUrl, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted reading the landing page of " + baseUrl, e);
    }
    List<String> mismatches = compareAssets(html, root);
    int compared = countAssets(html);
    if (compared < MIN_COMPARED) {
      throw new IllegalStateException(
          "The landing page of "
              + baseUrl
              + " links only "
              + compared
              + " content-hashed assets; expected at least "
              + MIN_COMPARED
              + ". Cannot tell whose build the stack is serving.");
    }
    if (!mismatches.isEmpty()) {
      throw new IllegalStateException(
          "The E2E stack at "
              + baseUrl
              + " is not serving this checkout ("
              + root
              + "): "
              + String.join("; ", mismatches)
              + ". Another checkout's stack or image is answering; see ServedBuildCheck.");
    }
    System.out.printf(
        "[E2E] served-build check: %d assets on %s match this checkout%n", compared, baseUrl);
  }

  /**
   * Compares every content-hashed asset a page links with this checkout's file.
   *
   * @param html the rendered page
   * @param root the checkout's root directory
   * @return one message per asset that differs or is missing locally; empty when all match
   */
  static List<String> compareAssets(String html, Path root) {
    List<String> mismatches = new ArrayList<>();
    Matcher asset = ASSET.matcher(html);
    while (asset.find()) {
      String kind = asset.group(1);
      String name = asset.group(2);
      String served = asset.group(3);
      Path local =
          "js".equals(kind)
              ? root.resolve("frontend/src/main/resources/static/js/" + name + ".js")
              : root.resolve("frontend/build/resources/main/static/css/" + name + ".css");
      if (!Files.isRegularFile(local)) {
        mismatches.add(kind + "/" + name + " is served but missing locally (" + local + ")");
        continue;
      }
      String expected = md5(local);
      if (!expected.equals(served)) {
        mismatches.add(kind + "/" + name + " served " + served + ", this checkout " + expected);
      }
    }
    return mismatches;
  }

  private static int countAssets(String html) {
    Matcher asset = ASSET.matcher(html);
    int n = 0;
    while (asset.find()) {
      n++;
    }
    return n;
  }

  private static String md5(Path file) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("MD5").digest(Files.readAllBytes(file)));
    } catch (IOException | NoSuchAlgorithmException e) {
      throw new IllegalStateException("Could not hash " + file, e);
    }
  }

  /**
   * The checkout directory's name, reduced to what a Docker tag and a compose project name accept.
   *
   * @param root the checkout's root directory
   * @return a lower-case slug of at most 40 characters, never empty
   */
  private static String slug(Path root) {
    Path name = root.toAbsolutePath().normalize().getFileName();
    String raw = name == null ? "root" : name.toString().toLowerCase(Locale.ROOT);
    String slug = raw.replaceAll("[^a-z0-9_-]+", "-").replaceAll("^[-_]+|[-_]+$", "");
    if (slug.isEmpty()) {
      slug = "checkout";
    }
    return slug.length() > 40 ? slug.substring(0, 40) : slug;
  }

  /**
   * The first eight hex digits of the SHA-256 of the checkout's absolute path, so two checkouts
   * whose directories share a name still differ.
   *
   * @param root the checkout's root directory
   * @return eight lower-case hex digits
   */
  private static String pathHash(Path root) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(
                  root.toAbsolutePath()
                      .normalize()
                      .toString()
                      .toLowerCase(Locale.ROOT)
                      .getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest).substring(0, 8);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}

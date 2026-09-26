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

import de.greluc.krt.profit.basetool.testsupport.redis.RedisAclTemplate;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit 5 extension that starts the full Basetool stack via the {@code docker compose} CLI once per
 * test run and tears it down at the end.
 *
 * <p>When {@code E2E_BASE_URL} (or {@code -De2e.baseUrl}) is set, it manages no Docker and the
 * tests run against that deployment. All credentials here are throwaway values matching {@code
 * realm-export.e2e.json} and {@code docker/test-tls/} (ADR-0139).
 */
public final class E2eStackExtension implements BeforeAllCallback {

  /** External origin the ephemeral frontend is reachable at (HTTPS, self-signed). */
  static final String EPHEMERAL_BASE_URL = "https://localhost:18081";

  /**
   * Password of the committed, non-secret test keystore in {@code docker/test-tls/} (ADR-0139),
   * passed to compose as {@code SERVER_SSL_KEY_STORE_PASSWORD}.
   */
  static final String KEYSTORE_PW = "basetool-test";

  /**
   * Image tag used only in prebuilt mode, matching the CI {@code build-stack} job ({@code
   * E2ePrebuiltImageParityTest}); locally built stacks use {@link #imageTag}.
   */
  private static final String IMAGE_TAG = "e2e-local";

  /**
   * The two images {@code docker-compose.build.yml} builds, named as compose names them under
   * {@link #IMAGE_TAG}; in prebuilt mode they must already be in the local Docker store.
   */
  static final List<String> BUILT_IMAGES =
      List.of(
          "ghcr.io/krt-profit/basetool-backend:" + IMAGE_TAG,
          "ghcr.io/krt-profit/basetool-frontend:" + IMAGE_TAG);

  /**
   * The JWT {@code aud} value the stack's backend enforces (REQ-SEC-024), passed as {@code
   * IRI_BACKEND_EXPECTED_AUDIENCES}.
   *
   * <p>Must match the {@code aud-basetool-backend} mapper in {@code realm-export.e2e.json} ({@code
   * E2eAudienceEnforcementParityTest}).
   */
  static final String EXPECTED_AUDIENCE = "basetool-backend";

  /** Canonical IRIDIUM Squadron id, opted into Job-Order processing during bootstrap. */
  private static final String IRIDIUM_SQUADRON_ID = "00000000-0000-0000-0000-000000000001";

  /**
   * Throwaway admin username from {@code realm-export.e2e.json}, used during bootstrap to enable
   * Job-Order processing for the IRIDIUM Squadron.
   */
  private static final String E2E_ADMIN_USER = "test-admin";

  /** Throwaway admin password matching {@link #E2E_ADMIN_USER} in {@code realm-export.e2e.json}. */
  private static final String E2E_ADMIN_PASSWORD = "test-admin-pw";

  /**
   * The RAW material the refinery import fixture's first row folds onto; seeded at bootstrap so
   * every create-form picker offers it (see {@link #bringUpAndSeed}).
   */
  static final String PICKER_MATERIAL_IMPORT = "E2E Import Material";

  /** The RAW material the refinery create-form tests pick; seeded at bootstrap. */
  static final String PICKER_MATERIAL_REFINERY = "E2E Refinery Material";

  /** A RAW material with a refined counterpart, for the keyboard-pick test; seeded at bootstrap. */
  static final String PICKER_MATERIAL_KEYBOARD_RAW = "E2E Keyboard Pick Raw";

  /** The refined counterpart of {@link #PICKER_MATERIAL_KEYBOARD_RAW}; seeded at bootstrap. */
  static final String PICKER_MATERIAL_KEYBOARD_REFINED = "E2E Keyboard Pick Refined";

  /** Max time to wait for one {@code docker compose up --build --wait} attempt to finish. */
  private static final Duration UP_TIMEOUT = Duration.ofMinutes(12);

  /**
   * How many times to attempt {@code docker compose up --build --wait} before giving up. The image
   * build downloads Gradle dependencies inside Docker (e.g. {@code ./gradlew
   * :frontend:dependencies}), which can hit a transient Maven Central 5xx and fail the whole
   * bring-up; one retry (after a teardown) re-runs the download and almost always succeeds.
   */
  private static final int COMPOSE_UP_ATTEMPTS = 2;

  /** Max time to wait for one {@code docker compose pull} attempt of the external images. */
  private static final Duration PULL_TIMEOUT = Duration.ofMinutes(5);

  /**
   * Number of attempts for pulling the external (non-built) images, retried with growing back-off.
   */
  private static final int PULL_ATTEMPTS = 4;

  /**
   * Base back-off slept between pull / up retries, multiplied by the attempt number so successive
   * waits grow (15s, 30s, 45s, …) and give a transient registry or Maven Central outage time to
   * clear instead of hammering it back-to-back.
   */
  private static final Duration RETRY_BACKOFF = Duration.ofSeconds(15);

  /** Max time to wait for {@code docker compose down}. */
  private static final Duration DOWN_TIMEOUT = Duration.ofMinutes(3);

  /**
   * The four stacked compose files, in precedence order (base -> test -> build -> e2e isolation).
   */
  private static final List<String> COMPOSE_FILES =
      List.of(
          "docker-compose.yml",
          "docker-compose.test.yml",
          "docker-compose.build.yml",
          "docker-compose.e2e.yml");

  /**
   * The dev-profile services the E2E stack needs. {@code ingest-dev}, the one other dev-profile
   * service, is not started; the edge and its ACME client are prod-profile only.
   */
  private static final List<String> SERVICES =
      List.of(
          "db-backend-dev",
          "db-keycloak-dev",
          "keycloak-dev",
          "redis-dev",
          "backend-dev",
          "frontend-dev");

  /**
   * The external services whose images are pulled from a registry ({@code db-backend-dev}, {@code
   * db-keycloak-dev}, {@code keycloak-dev}, {@code redis-dev}). {@code backend-dev} / {@code
   * frontend-dev} are deliberately excluded: they are built from local Dockerfiles and tagged with
   * {@link #imageTag()}, so {@code docker compose pull} of them would fail against the registry.
   */
  private static final List<String> PULLED_SERVICES =
      List.of("db-backend-dev", "db-keycloak-dev", "keycloak-dev", "redis-dev");

  /**
   * The throwaway client secret of {@code basetool-frontend} in {@code realm-export.e2e.json},
   * handed to the frontend as {@code KEYCLOAK_FRONTEND_CLIENT_SECRET} and presented by {@code
   * BackendSeeder}. Obviously synthetic and published on purpose; never a production value.
   */
  static final String FRONTEND_CLIENT_SECRET = "e2e-frontend-client-secret-do-not-use-in-prod";

  /** Guards one-time start across multiple test classes sharing this extension. */
  private static volatile boolean started = false;

  /**
   * Remembers the first bring-up failure so the remaining test classes fail fast with the original
   * cause instead of each re-running the multi-minute compose bring-up and re-failing identically —
   * the mode that turned one startup crash into a whole-job (~45 min) timeout reported only as a
   * "cancelled" run.
   */
  private static volatile Throwable bootFailure = null;

  /**
   * Resolves the URL the tests should target: an explicit {@code E2E_BASE_URL} / {@code
   * -De2e.baseUrl} (staging mode) when provided, otherwise the ephemeral local frontend.
   *
   * @return the base URL of the system under test
   */
  public String baseUrl() {
    String external = externalBaseUrl();
    return external != null ? external : EPHEMERAL_BASE_URL;
  }

  /**
   * Reports whether this extension manages the Docker lifecycle, i.e. no external base URL was set.
   *
   * @return {@code true} when an ephemeral local stack is being managed, {@code false} for an
   *     external target
   */
  public boolean managesStack() {
    return externalBaseUrl() == null;
  }

  /**
   * Starts the ephemeral stack on the first invocation and registers its teardown for the end of
   * the test plan; a no-op in external mode and on later invocations.
   *
   * @param context the JUnit extension context whose root store owns the teardown hook
   * @throws Exception if bootstrap or {@code docker compose up} fails
   */
  @Override
  public void beforeAll(ExtensionContext context) throws Exception {
    if (!managesStack() || started) {
      return;
    }
    synchronized (E2eStackExtension.class) {
      if (started) {
        return;
      }
      if (bootFailure != null) {
        throw new IllegalStateException(
            "E2E stack bring-up already failed for an earlier test class; not retrying it",
            bootFailure);
      }
      try {
        bringUpAndSeed(context);
      } catch (Exception bringUpFailure) {
        bootFailure = bringUpFailure;
        throw bringUpFailure;
      }
    }
  }

  /**
   * Starts the ephemeral stack once: stages realm and keystore, pulls and starts compose, registers
   * teardown, verifies the served build, and seeds the catalog data every picker needs.
   *
   * @param context the JUnit extension context whose root store owns the teardown hook
   * @throws Exception if bootstrap or {@code docker compose up} fails
   */
  private void bringUpAndSeed(ExtensionContext context) throws Exception {
    Path root = repoRoot();
    stageRealm(root);
    if (prebuilt()) {
      requirePrebuiltImages(root);
    }
    requireFrontendPortFree(root);
    prePullImages(root);
    composeUp(root);
    context
        .getRoot()
        .getStore(ExtensionContext.Namespace.GLOBAL)
        .put("e2e-docker-stack", (AutoCloseable) () -> composeDown(root));
    ServedBuildCheck.assertServesThisCheckout(
        EPHEMERAL_BASE_URL, root, BackendSeeder.trustingTestCa());
    BackendSeeder seeder = new BackendSeeder();
    seeder.seedCatalog();
    seeder.ensureRefineryMaterial(E2E_ADMIN_USER, E2E_ADMIN_PASSWORD, PICKER_MATERIAL_IMPORT);
    seeder.ensureRefineryMaterial(E2E_ADMIN_USER, E2E_ADMIN_PASSWORD, PICKER_MATERIAL_REFINERY);
    seeder.ensureRefineryMaterialWithRefinedOutput(
        E2E_ADMIN_USER,
        E2E_ADMIN_PASSWORD,
        PICKER_MATERIAL_KEYBOARD_RAW,
        PICKER_MATERIAL_KEYBOARD_REFINED);
    seeder.setSquadronProfitEligible(E2E_ADMIN_USER, E2E_ADMIN_PASSWORD, IRIDIUM_SQUADRON_ID, true);
    try {
      String ingredientMaterialId =
          seeder.ensureJobOrderMaterial(
              E2E_ADMIN_USER, E2E_ADMIN_PASSWORD, "E2E Blueprint Ingredient");
      seeder.seedOrderableItem("E2E Orderable Widget", ingredientMaterialId);
    } catch (RuntimeException seedFailure) {
      System.out.printf(
          "[E2E] orderable-item seeding failed (item-order flow will be skipped/failing): %s%n",
          seedFailure.getMessage());
    }
    started = true;
  }

  /**
   * Reports whether the images were built before this JVM started ({@code -De2e.prebuilt=true}).
   *
   * @return {@code true} when the stack is to be started with {@code --no-build}
   */
  static boolean prebuilt() {
    return Boolean.getBoolean("e2e.prebuilt");
  }

  /**
   * Fails unless every image in {@link #BUILT_IMAGES} is in the local Docker store.
   *
   * @param root the repository root, used as the working directory of the {@code docker} calls
   * @throws Exception if an image is absent (the message names it and the CI job that builds it)
   */
  private void requirePrebuiltImages(Path root) throws Exception {
    for (String image : BUILT_IMAGES) {
      try {
        runProcess(
            root,
            "prebuilt-image-check",
            List.of("docker", "image", "inspect", "--format", "{{.Id}}", image),
            Map.of(),
            Duration.ofMinutes(1));
      } catch (IllegalStateException missing) {
        throw new IllegalStateException(
            "e2e.prebuilt=true but "
                + image
                + " is not in the local Docker store; e2e.yml's build-stack job builds it and each"
                + " matrix cell loads it with `docker load`. Run without -Pe2e.prebuilt to build it"
                + " here instead.",
            missing);
      }
    }
  }

  /**
   * The tag this run's backend and frontend images carry: the fixed {@link #IMAGE_TAG} in prebuilt
   * mode, otherwise one derived from the checkout's path, so parallel checkouts never share an
   * image.
   *
   * @return the value handed to compose as {@code IRI_BASETOOL_VERSION}
   */
  static String imageTag() {
    return prebuilt() ? IMAGE_TAG : ServedBuildCheck.localImageTag(repoRoot());
  }

  /**
   * Fails fast, naming the other stack, when a container already publishes the frontend port — the
   * ports and subnets are fixed, so only one ephemeral stack can run on a machine at a time.
   *
   * @param root the repository root, used as the working directory of the {@code docker} call
   * @throws Exception if the check cannot be run or the port is taken
   */
  private void requireFrontendPortFree(Path root) throws Exception {
    Path out = Paths.get("build", "e2e", "port-check.log").toAbsolutePath();
    runProcess(
        root,
        "port-check",
        List.of("docker", "ps", "--filter", "publish=18081", "--format", "{{.Names}}"),
        Map.of(),
        Duration.ofMinutes(1));
    ServedBuildCheck.assertPortFree(Files.readString(out), 18081);
  }

  /**
   * Returns the configured external base URL; {@code E2E_BASE_URL} takes precedence over {@code
   * -De2e.baseUrl}.
   *
   * @return the external base URL, or {@code null} to manage an ephemeral stack
   */
  private static String externalBaseUrl() {
    String env = System.getenv("E2E_BASE_URL");
    if (env != null && !env.isBlank()) {
      return env;
    }
    String prop = System.getProperty("e2e.baseUrl");
    return prop != null && !prop.isBlank() ? prop : null;
  }

  /**
   * Copies the synthetic e2e realm to {@code <repoRoot>/realm-export.json} for Keycloak's {@code
   * --import-realm}.
   *
   * @param root the repository root containing the compose files
   * @throws IOException if the realm resource is missing or cannot be written
   */
  private void stageRealm(Path root) throws IOException {
    try (InputStream in = E2eStackExtension.class.getResourceAsStream("/realm-export.e2e.json")) {
      if (in == null) {
        throw new IllegalStateException(
            "realm-export.e2e.json not found on the e2e classpath; expected under"
                + " frontend/src/e2e/resources");
      }
      Files.copy(in, root.resolve("realm-export.json"), StandardCopyOption.REPLACE_EXISTING);
    }
  }

  /**
   * Builds (or, in {@link #prebuilt()} mode, reuses) the images and starts the dev-profile stack
   * until healthy, retrying up to {@link #COMPOSE_UP_ATTEMPTS} times with teardown between
   * attempts.
   *
   * @param root the repository root the compose files live in
   * @throws Exception if every {@code docker compose up} attempt exits non-zero or times out
   */
  private void composeUp(Path root) throws Exception {
    String buildFlag = prebuilt() ? "--no-build" : "--build";
    Exception lastFailure = null;
    for (int attempt = 1; attempt <= COMPOSE_UP_ATTEMPTS; attempt++) {
      try {
        runProcess(
            root,
            "compose-up",
            composeCommand("up", "-d", buildFlag, "--wait", "--wait-timeout", "360"),
            throwawayEnv(),
            UP_TIMEOUT);
        return;
      } catch (Exception up) {
        lastFailure = up;
        captureComposeLogs(root);
        if (attempt < COMPOSE_UP_ATTEMPTS) {
          System.out.printf(
              "[E2E] compose up failed (attempt %d of %d); tearing down and retrying.%n%s%n",
              attempt, COMPOSE_UP_ATTEMPTS, up.getMessage());
          composeDown(root);
          sleepBackoff(attempt);
        }
      }
    }
    throw lastFailure;
  }

  /**
   * Pulls the external service images, retrying up to {@link #PULL_ATTEMPTS} times with growing
   * back-off.
   *
   * @param root the repository root the compose files live in
   * @throws Exception if every pull attempt exits non-zero or times out
   */
  private void prePullImages(Path root) throws Exception {
    Exception lastFailure = null;
    for (int attempt = 1; attempt <= PULL_ATTEMPTS; attempt++) {
      try {
        runProcess(root, "compose-pull", composeCommand("pull"), throwawayEnv(), PULL_TIMEOUT);
        return;
      } catch (Exception pull) {
        lastFailure = pull;
        if (attempt < PULL_ATTEMPTS) {
          System.out.printf(
              "[E2E] image pull failed (attempt %d of %d); retrying.%n%s%n",
              attempt, PULL_ATTEMPTS, pull.getMessage());
          sleepBackoff(attempt);
        }
      }
    }
    throw lastFailure;
  }

  /**
   * Sleeps {@link #RETRY_BACKOFF} times {@code attempt}.
   *
   * @param attempt the 1-based number of the attempt that just failed
   * @throws InterruptedException if the thread is interrupted while sleeping
   */
  private static void sleepBackoff(int attempt) throws InterruptedException {
    Duration wait = RETRY_BACKOFF.multipliedBy(attempt);
    System.out.printf("[E2E] waiting %ds before next attempt.%n", wait.toSeconds());
    Thread.sleep(wait.toMillis());
  }

  /**
   * Best-effort dump of the stack's container logs to {@code build/e2e/compose-logs.log} for
   * post-mortem diagnostics when {@link #composeUp} fails. Never throws — the original bring-up
   * failure is the one that should propagate.
   *
   * @param root the repository root the compose files live in
   */
  private void captureComposeLogs(Path root) {
    try {
      runProcess(
          root,
          "compose-logs",
          composeCommand("logs", "--no-color", "--tail", "300"),
          throwawayEnv(),
          Duration.ofMinutes(2));
    } catch (Exception ignored) {
      System.out.println("[E2E] could not capture compose logs: " + ignored.getMessage());
    }
  }

  /**
   * Captures the backend and frontend container logs (for post-mortem diagnostics), then tears the
   * stack down and removes its named volumes. Best-effort: a failure here is logged but does not
   * fail the build (the test outcome has already been decided).
   *
   * @param root the repository root the compose files live in
   */
  private void composeDown(Path root) {
    captureServiceLog(root, "backend-dev", "backend");
    captureServiceLog(root, "frontend-dev", "frontend");
    try {
      runProcess(
          root,
          "compose-down",
          composeCommand("down", "--volumes", "--remove-orphans"),
          throwawayEnv(),
          DOWN_TIMEOUT);
    } catch (Exception e) {
      System.out.println("[E2E] stack teardown failed (ignored): " + e.getMessage());
    }
  }

  /**
   * Writes one service container's full log to {@code build/e2e/<label>.log} before teardown; never
   * throws.
   *
   * @param root the repository root the compose files live in
   * @param service the dev-profile compose service key to read logs from (e.g. {@code backend-dev})
   * @param label the {@code build/e2e/<label>.log} file-name stem to write the captured log under
   */
  private void captureServiceLog(Path root, String service, String label) {
    try {
      java.util.ArrayList<String> cmd = new java.util.ArrayList<>(List.of("docker", "compose"));
      for (String f : COMPOSE_FILES) {
        cmd.add("-f");
        cmd.add(f);
      }
      cmd.add("--profile");
      cmd.add("dev");
      cmd.addAll(List.of("logs", "--no-color", "--no-log-prefix", service));
      runProcess(root, label, cmd, throwawayEnv(), Duration.ofMinutes(2));
    } catch (Exception ignored) {
      System.out.println("[E2E] could not capture " + label + " log: " + ignored.getMessage());
    }
  }

  /**
   * Builds a {@code docker compose -f ... --profile dev <verb> ...} command line; {@code up} and
   * {@code logs} get all services, {@code pull} only {@link #PULLED_SERVICES}.
   *
   * @param verbAndArgs the compose verb followed by its flags (e.g. {@code "up","-d","--build"})
   * @return the full argument vector to hand to {@link ProcessBuilder}
   */
  private List<String> composeCommand(String... verbAndArgs) {
    java.util.ArrayList<String> cmd = new java.util.ArrayList<>(List.of("docker", "compose"));
    for (String f : COMPOSE_FILES) {
      cmd.add("-f");
      cmd.add(f);
    }
    cmd.add("--profile");
    cmd.add("dev");
    cmd.addAll(List.of(verbAndArgs));
    if ("up".equals(verbAndArgs[0]) || "logs".equals(verbAndArgs[0])) {
      cmd.addAll(SERVICES);
    } else if ("pull".equals(verbAndArgs[0])) {
      cmd.addAll(PULLED_SERVICES);
    }
    return cmd;
  }

  /**
   * Returns the throwaway environment for compose's {@code ${VAR}} placeholders, passed to the
   * subprocess rather than written to disk; {@code IRI_KEYSTORE_HOST_PATH} is never set.
   *
   * @return the environment variable map for the compose subprocess
   */
  private Map<String, String> throwawayEnv() {
    Map<String, String> env = new LinkedHashMap<>();
    env.put("POSTGRES_DB", "krt_basetool_e2e");
    env.put("POSTGRES_USER", "basetool_e2e");
    env.put("POSTGRES_PASSWORD", "basetool-e2e-pw-do-not-use-in-prod");
    env.put("KC_POSTGRES_DB", "keycloak_e2e");
    env.put("KC_POSTGRES_USER", "keycloak_e2e");
    env.put("KC_POSTGRES_PASSWORD", "keycloak-e2e-pw-do-not-use-in-prod");
    env.put("KC_BOOTSTRAP_ADMIN_USERNAME", "admin");
    env.put("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin-e2e-pw-do-not-use-in-prod");
    env.put("KEYCLOAK_ADMIN_CLIENT_SECRET", "e2e-client-secret-do-not-use-in-prod");
    env.put("KEYCLOAK_FRONTEND_CLIENT_SECRET", FRONTEND_CLIENT_SECRET);
    env.put("REDIS_PASSWORD", RedisAclTemplate.E2E_PASSWORDS.get("REDIS_PASSWORD"));
    env.put("REDIS_FRONTEND_USERNAME", RedisAclTemplate.FRONTEND_USER);
    env.put(
        "REDIS_FRONTEND_PASSWORD", RedisAclTemplate.E2E_PASSWORDS.get("REDIS_FRONTEND_PASSWORD"));
    env.put("REDIS_BACKEND_USERNAME", RedisAclTemplate.BACKEND_USER);
    env.put("REDIS_BACKEND_PASSWORD", RedisAclTemplate.E2E_PASSWORDS.get("REDIS_BACKEND_PASSWORD"));
    env.put("REDIS_INGEST_USERNAME", RedisAclTemplate.INGEST_USER);
    env.put("REDIS_INGEST_PASSWORD", RedisAclTemplate.E2E_PASSWORDS.get("REDIS_INGEST_PASSWORD"));
    env.put("SERVER_SSL_KEY_STORE_PASSWORD", KEYSTORE_PW);
    env.put("IRI_BASETOOL_VERSION", imageTag());
    env.put("COMPOSE_PROJECT_NAME", ServedBuildCheck.composeProjectName(repoRoot()));
    env.put("IRI_BACKEND_EXPECTED_AUDIENCES", EXPECTED_AUDIENCE);
    return env;
  }

  /**
   * Walks up from the working directory to the directory containing {@code docker-compose.yml}.
   *
   * @return the repository root path
   */
  private static Path repoRoot() {
    Path start = Paths.get("").toAbsolutePath();
    for (Path p = start; p != null; p = p.getParent()) {
      if (Files.exists(p.resolve("docker-compose.yml"))) {
        return p;
      }
    }
    throw new IllegalStateException("docker-compose.yml not found walking up from " + start);
  }

  /**
   * Runs an external process, streaming its combined output to {@code <workingDir>/build/e2e/
   * <label>.log}, and fails with the log tail if it exits non-zero or exceeds {@code timeout}.
   *
   * @param workingDir the process working directory
   * @param label short name used for the per-process log file and error messages
   * @param command the argument vector
   * @param extraEnv environment variables to add on top of the inherited environment
   * @param timeout how long to wait before treating the process as hung
   * @throws Exception if the process fails to start, times out, or exits non-zero
   */
  private void runProcess(
      Path workingDir,
      String label,
      List<String> command,
      Map<String, String> extraEnv,
      Duration timeout)
      throws Exception {
    Path logDir = Paths.get("build", "e2e").toAbsolutePath();
    Files.createDirectories(logDir);
    Path log = logDir.resolve(label + ".log");
    System.out.printf("[E2E] %s: %s%n", label, String.join(" ", command));
    ProcessBuilder pb =
        new ProcessBuilder(command)
            .directory(workingDir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile());
    pb.environment().putAll(extraEnv);
    Process process = pb.start();
    if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IllegalStateException(
          label + " timed out after " + timeout.toMinutes() + " min; see " + log);
    }
    int exit = process.exitValue();
    if (exit != 0) {
      throw new IllegalStateException(
          label + " failed (exit " + exit + "). Last log lines:\n" + tail(log, 25));
    }
  }

  /**
   * Reads the last {@code maxLines} lines of a log file for inclusion in a failure message.
   *
   * @param log the log file
   * @param maxLines how many trailing lines to return
   * @return the trailing lines joined by newlines, or a diagnostic note if the file is unreadable
   */
  private static String tail(Path log, int maxLines) {
    try {
      List<String> lines = Files.readAllLines(log);
      return String.join("\n", lines.subList(Math.max(0, lines.size() - maxLines), lines.size()));
    } catch (IOException e) {
      return "(could not read " + log + ": " + e.getMessage() + ")";
    }
  }
}

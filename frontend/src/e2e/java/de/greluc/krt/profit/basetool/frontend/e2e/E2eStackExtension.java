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
 * JUnit 5 extension that provisions the full Basetool stack (Postgres x2, Keycloak, Redis, backend,
 * frontend) for end-to-end tests and tears it down once at the end of the test run.
 *
 * <p>It drives the {@code docker compose} CLI directly rather than Testcontainers' {@code
 * ComposeContainer}: the Phase-0 spike (see {@code docs/e2e-test/README.md}) established that
 * {@code ComposeContainer} cannot express this stack's combination of {@code --profile dev}, four
 * stacked {@code -f} files, fixed published ports, and the {@code !override} isolation tags. The
 * exact compose invocation here mirrors the documented local test-stack flow plus the {@code
 * docker-compose.e2e.yml} isolation override.
 *
 * <p><b>Target-agnostic.</b> If the {@code E2E_BASE_URL} environment variable (or {@code
 * -De2e.baseUrl}) is set, the extension does <i>not</i> manage Docker at all — the tests run
 * against that already-running deployment (e.g. a staging environment). Otherwise it provisions an
 * ephemeral local stack and exposes {@value #EPHEMERAL_BASE_URL}.
 *
 * <p><b>Lifecycle.</b> Register once via a {@code @RegisterExtension static} field. The stack is
 * brought up exactly once on the first {@link #beforeAll} and stopped exactly once when the whole
 * JUnit test plan finishes, via an {@link AutoCloseable} stored in the root context.
 *
 * <p>All credentials baked in here are throwaway values that must match {@code
 * realm-export.e2e.json} (the {@code backend-service} client secret) and the committed test
 * keystore in {@code docker/test-tls/} (ADR-0139) — never reuse them anywhere, never substitute
 * production values.
 */
public final class E2eStackExtension implements BeforeAllCallback {

  /** External origin the ephemeral frontend is reachable at (HTTPS, self-signed). */
  static final String EPHEMERAL_BASE_URL = "https://localhost:18081";

  /**
   * Password of the committed test keystore in {@code docker/test-tls/} (ADR-0139), which the
   * compose stack mounts at {@code /run/secrets/keystore.p12}. It is fed to compose as {@code
   * SERVER_SSL_KEY_STORE_PASSWORD} below and read by {@link BackendSeeder} to build its trust store
   * from that certificate. The value is not a secret and is fixed by the committed file: this stack
   * no longer generates a keystore of its own, because {@code docker-compose.test.yml} pins the
   * same password per service and a locally generated store would fail to open against it.
   */
  static final String KEYSTORE_PW = "basetool-test";

  /** Local image tag the compose build override tags the freshly built images with. */
  private static final String IMAGE_TAG = "e2e-local";

  /**
   * The JWT {@code aud} value the E2E stack's backend enforces (audit L-1, REQ-SEC-024). Fed to the
   * compose stack as {@code IRI_BACKEND_EXPECTED_AUDIENCES}, which the {@code x-backend} anchor
   * maps onto {@code APP_SECURITY_JWT_EXPECTED_AUDIENCES} → {@code
   * app.security.jwt.expected-audiences}.
   *
   * <p>Enforcement is ON here on purpose: the knob stays empty (off) in the deployed prod {@code
   * .env} until an operator flips it, so without this the enforced code path — the custom {@code
   * resourceServerJwtDecoder} and its {@code aud} validator — would never run against a real
   * Keycloak token anywhere, and the first execution ever would be in production, where a missing
   * audience rejects every token and takes the whole app down. Running the whole suite against it
   * turns that one-way prod change into a rehearsed one.
   *
   * <p>The value MUST match the {@code aud-basetool-backend} audience mapper on the {@code
   * basetool-frontend} client in {@code realm-export.e2e.json} — every E2E token, browser-flow and
   * {@link BackendSeeder} ROPC alike, is minted by that client. {@code
   * E2eAudienceEnforcementParityTest} pins the two together so a realm edit that drops the mapper
   * fails with that message instead of a 401 on every test.
   */
  static final String EXPECTED_AUDIENCE = "basetool-backend";

  /** Canonical IRIDIUM Squadron id, opted into Job-Order processing during bootstrap. */
  private static final String IRIDIUM_SQUADRON_ID = "00000000-0000-0000-0000-000000000001";

  /**
   * Throwaway admin username from {@code realm-export.e2e.json}, used once during bootstrap to opt
   * the IRIDIUM Squadron into Job-Order processing. Never reuse; never substitute production
   * values.
   */
  private static final String E2E_ADMIN_USER = "test-admin";

  /** Throwaway admin password matching {@link #E2E_ADMIN_USER} in {@code realm-export.e2e.json}. */
  private static final String E2E_ADMIN_PASSWORD = "test-admin-pw";

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
   * How many times to attempt the registry pull of the external (non-built) images before giving
   * up. The pull is split out from {@code up --build} because a registry blip (e.g. a {@code
   * quay.io} 502 while fetching the Keycloak image) is transient and cheap to retry on its own,
   * whereas re-running the whole {@code up --build} just to re-pull would rebuild the images first.
   * Several attempts with a growing back-off ride out a multi-minute registry outage.
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

  /** The dev-profile services the E2E stack needs (npm is intentionally excluded). */
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
   * {@link #IMAGE_TAG}, so {@code docker compose pull} of them would fail against the registry.
   */
  private static final List<String> PULLED_SERVICES =
      List.of("db-backend-dev", "db-keycloak-dev", "keycloak-dev", "redis-dev");

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
   * Reports whether this extension is responsible for the Docker lifecycle. {@code false} when an
   * external base URL was supplied (staging), in which case browser-side workarounds tied to the
   * local stack (e.g. the {@code host.docker.internal} resolver remap) should be skipped.
   *
   * @return {@code true} when an ephemeral local stack is being managed, {@code false} for an
   *     external target
   */
  public boolean managesStack() {
    return externalBaseUrl() == null;
  }

  /**
   * Brings the ephemeral stack up on the first invocation (no-op in external/staging mode, and a
   * no-op on subsequent invocations from other test classes). Registers the one-time teardown on
   * the JUnit root store so it runs after the entire test plan.
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
        // The stack already failed to come up for an earlier test class. Re-running the
        // multi-minute compose bring-up for every remaining class would just re-fail identically
        // and
        // burn the whole job timeout (~45 min) with no new signal — the mode that masked a real
        // startup crash behind a "cancelled" e2e run. Fail fast with the original cause instead, so
        // every class reports the actual reason in seconds and the job ends promptly.
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
   * Performs the one-time ephemeral-stack bring-up: stages the realm/keystore, pulls + {@code up}s
   * the compose stack, seeds the UEX-owned catalog + profit-eligibility + an orderable item, marks
   * the stack started, and registers the one-time teardown on the JUnit root store. Extracted from
   * {@link #beforeAll} so the caller can remember a failure and fail the remaining classes fast.
   *
   * @param context the JUnit extension context whose root store owns the teardown hook
   * @throws Exception if bootstrap or {@code docker compose up} fails
   */
  private void bringUpAndSeed(ExtensionContext context) throws Exception {
    Path root = repoRoot();
    stageRealm(root);
    prePullImages(root);
    composeUp(root);
    // Seed UEX-owned catalog reference data (refinery-hosting location, ship type, refining
    // method) the admin REST API cannot create on a fresh DB — unblocks the Refinery/Hangar
    // flows.
    BackendSeeder seeder = new BackendSeeder();
    seeder.seedCatalog();
    // Opt the canonical IRIDIUM Squadron into Job-Order processing exactly once, before any test
    // page warms the frontend's 10-minute squadrons-catalog cache. Only profit-eligible org units
    // may be a job order's responsible (processing) unit (V128); without this the create form's
    // responsible picker stays empty and every order-create / handover flow 400s. Seeding it here
    // (not per test class) guarantees the cache never pins a stale not-eligible snapshot.
    seeder.setSquadronProfitEligible(E2E_ADMIN_USER, E2E_ADMIN_PASSWORD, IRIDIUM_SQUADRON_ID, true);
    // Seed one orderable item (a game_item + an active blueprint with a resolved RESOURCE
    // ingredient) so the item-order create form's *frontend-cached* item picker is never empty.
    // Done here — before any test navigates to /orders/create and warms that 10-minute cache —
    // for the same reason the profit-eligibility seeding above runs at bootstrap. Non-fatal: only
    // the anonymous item-order flow (UC-12) depends on it, so a seed hiccup must not sink the
    // whole suite's bring-up.
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
    context
        .getRoot()
        .getStore(ExtensionContext.Namespace.GLOBAL)
        .put("e2e-docker-stack", (AutoCloseable) () -> composeDown(root));
  }

  /**
   * Returns the explicitly configured external base URL, or {@code null} when none was supplied
   * (the signal to manage an ephemeral stack). {@code E2E_BASE_URL} (environment) takes precedence
   * over {@code -De2e.baseUrl} (system property).
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
   * Copies the checked-in synthetic realm from the e2e classpath to {@code <repoRoot>/
   * realm-export.json}, which the base compose file bind-mounts into Keycloak for {@code
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
   * Builds the images from the current source and brings the dev-profile stack up, blocking until
   * every service reports healthy. Retries up to {@link #COMPOSE_UP_ATTEMPTS} times, tearing down
   * between attempts, so a transient image-build flake (e.g. a Maven Central 5xx while downloading
   * Gradle dependencies) does not fail the whole run.
   *
   * @param root the repository root the compose files live in
   * @throws Exception if every {@code docker compose up} attempt exits non-zero or times out
   */
  private void composeUp(Path root) throws Exception {
    Exception lastFailure = null;
    for (int attempt = 1; attempt <= COMPOSE_UP_ATTEMPTS; attempt++) {
      try {
        runProcess(
            root,
            "compose-up",
            composeCommand("up", "-d", "--build", "--wait", "--wait-timeout", "360"),
            throwawayEnv(),
            UP_TIMEOUT);
        return;
      } catch (Exception up) {
        lastFailure = up;
        // Dump the container logs into build/e2e so the CI artifact shows *why* — `up --wait`
        // itself
        // only reports "dependency failed to start", not the failing container's own output.
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
   * Pulls the external (non-built) service images up front, retrying up to {@link #PULL_ATTEMPTS}
   * times with a growing back-off. Splitting the registry pull out of {@code up --build --wait}
   * means a transient registry fault (e.g. a {@code quay.io} 502 on the Keycloak image) is retried
   * cheaply on its own; once the images are cached in the local daemon the subsequent {@code up
   * --build} reuses them instead of pulling again under the same flaky window.
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
   * Sleeps a back-off proportional to the just-failed attempt number ({@link #RETRY_BACKOFF} times
   * {@code attempt}) so successive retries wait progressively longer, riding out a multi-minute
   * registry or Maven Central outage rather than hammering it back-to-back.
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
   * Best-effort dump of one dev-profile service container's full log to {@code
   * build/e2e/<label>.log} just before teardown, so a CI artifact preserves how that container
   * handled every request — most usefully the access-log line (HTTP status + duration) for the
   * operation a failing test was driving, which the browser-side artifacts cannot show. Un-tailed
   * so a mid-run request is never truncated away; never throws (the test outcome is already
   * decided).
   *
   * <p>The service must be named by its compose <em>service key</em> (e.g. {@code backend-dev}, not
   * the network alias {@code backend} nor the prod-profile {@code backend} service): {@code docker
   * compose logs} resolves service keys, and the prod-profile twins have no running container in
   * the dev-profile e2e stack, so naming them would yield an empty log.
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
   * Assembles a {@code docker compose -f ... --profile dev <verb> ...} command line for the given
   * verb and trailing arguments.
   *
   * <p>{@code up} / {@code logs} get the full service list appended; {@code pull} gets only the
   * external, registry-sourced services ({@link #PULLED_SERVICES}).
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
    // Scope `up` and `logs` to the explicit service list (npm is intentionally excluded); scope
    // `pull` to only the external, registry-sourced images (the built services have no pullable
    // tag).
    if ("up".equals(verbAndArgs[0]) || "logs".equals(verbAndArgs[0])) {
      cmd.addAll(SERVICES);
    } else if ("pull".equals(verbAndArgs[0])) {
      cmd.addAll(PULLED_SERVICES);
    }
    return cmd;
  }

  /**
   * The throwaway environment compose substitutes its {@code ${VAR}} placeholders from. Passed via
   * the subprocess environment instead of an {@code --env-file}, so no credentials file is written
   * to disk. {@code KEYCLOAK_ADMIN_CLIENT_SECRET} must match the {@code backend-service} secret in
   * {@code realm-export.e2e.json}; {@code SERVER_SSL_KEY_STORE_PASSWORD} must match the committed
   * test keystore the compose files mount by a hardcoded path (ADR-0139). {@code
   * IRI_KEYSTORE_HOST_PATH} is deliberately NOT set: it selects the production keystore, and no
   * compose file in this stack reads it any more.
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
    env.put("REDIS_PASSWORD", "redis-e2e-pw-do-not-use-in-prod");
    env.put("SERVER_SSL_KEY_STORE_PASSWORD", KEYSTORE_PW);
    env.put("IRI_BASETOOL_VERSION", IMAGE_TAG);
    // Audit L-1 / REQ-SEC-024: exercise the enforced `aud` path. See EXPECTED_AUDIENCE — the
    // e2e realm stamps this audience, so turning the knob on here rehearses the prod flip.
    env.put("IRI_BACKEND_EXPECTED_AUDIENCES", EXPECTED_AUDIENCE);
    return env;
  }

  /**
   * Walks up from the working directory until the directory containing {@code docker-compose.yml}
   * is found (the test runs with the {@code frontend} module as its working directory).
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

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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.service.AuthHelperService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * An admin's undo of one exchange client's writes for every member since a point in time
 * (REQ-XCH-034, ADR-0227): it suspends the client through the registry first, then undoes member by
 * member in the background with the member's own undo semantics, and keeps the run's progress and
 * what it left alone.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeBulkUndoService {

  /** How many skipped entries a run's detail lists at most. */
  static final int SKIPPED_SHOWN = 200;

  private final ExchangeRegistryService registryService;
  private final ExchangeClientRepository clientRepository;
  private final ExchangeInstallationRepository installationRepository;
  private final ExchangeJournalRepository journalRepository;
  private final ExchangeBulkUndoRunRepository runRepository;
  private final ExchangeBulkUndoSkipRepository skipRepository;
  private final ExchangeBulkUndoRunner runner;
  private final ExchangeBulkUndoStep step;
  private final ExchangeEntryLabels entryLabels;
  private final UserRepository userRepository;
  private final AuditRecorder auditRecorder;
  private final AuthHelperService authHelperService;
  private final ExchangeChangeRetentionProperties retention;
  private final PlatformTransactionManager transactionManager;
  private final Clock clock = Clock.systemUTC();

  /**
   * Shows what a bulk undo would reach, writing nothing.
   *
   * @param registryId the registry id of the client
   * @param request the scope
   * @return the members and entries in scope
   * @throws NotFoundException when the client or the installation is unknown
   * @throws BadRequestException when the span starts in the future
   */
  @Transactional(readOnly = true)
  public @NotNull ExchangeBulkUndoPreviewDto preview(
      @NotNull UUID registryId, @NotNull ExchangeBulkUndoRequest request) {
    ExchangeClient client = registryService.getClient(registryId);
    Scope scope = scope(client, request);
    int members =
        journalRepository
            .findUndoableMembers(
                client.getClientId(), scope.since(), scope.installationKey(), scope.resourceName())
            .size();
    long entries =
        journalRepository.countUndoable(
            client.getClientId(), scope.since(), scope.installationKey(), scope.resourceName());
    return new ExchangeBulkUndoPreviewDto(
        scope.since(), members, entries, client.getStatus() == ExchangeClientStatus.ACTIVE);
  }

  /**
   * Lists the client's installations with writes that are not undone since a point in time, most
   * writes first, for limiting a bulk undo to one of them.
   *
   * @param registryId the registry id of the client
   * @param since the start of the span; clamped to the retention
   * @return at most 500 installations
   * @throws NotFoundException when the client is unknown
   */
  @Transactional(readOnly = true)
  public @NotNull List<ExchangeBulkUndoInstallationDto> installations(
      @NotNull UUID registryId, @NotNull Instant since) {
    ExchangeClient client = registryService.getClient(registryId);
    Instant from = clamp(since);
    Map<String, ExchangeInstallation> byKey = new HashMap<>();
    for (ExchangeInstallation installation :
        installationRepository.findAllOfClientWithUser(client.getId())) {
      byKey.put(
          installation.getUser().getId() + "|" + installation.getKeyThumbprint(), installation);
    }
    List<ExchangeBulkUndoInstallationDto> rows = new ArrayList<>();
    for (ExchangeJournalRepository.InstallationWrites writes :
        journalRepository.countUndoableByInstallation(client.getClientId(), from)) {
      ExchangeInstallation installation =
          byKey.get(writes.getUserId() + "|" + writes.getInstallationKey());
      if (installation == null) {
        continue;
      }
      rows.add(
          new ExchangeBulkUndoInstallationDto(
              installation.getId(),
              installation.getUser().getEffectiveName(),
              installation.getLastSeenAt(),
              installation.getRevokedAt() != null,
              writes.getEntries()));
    }
    return rows;
  }

  /**
   * Suspends the client through the registry unless it already is, records the run and starts it in
   * the background.
   *
   * @param registryId the registry id of the client
   * @param request the scope
   * @param admin the authentication of the admin, which the run acts under
   * @return the started run
   * @throws NotFoundException when the client or the installation is unknown
   * @throws BadRequestException when the span starts in the future
   * @throws BusinessConflictException when a bulk undo of the client is already running, or when
   *     the bulk undo executor refuses the run, which is then ended {@code FAILED} with the client
   *     left suspended
   */
  public @NotNull ExchangeBulkUndoRunDto start(
      @NotNull UUID registryId,
      @NotNull ExchangeBulkUndoRequest request,
      @NotNull Authentication admin) {
    ExchangeClient client = registryService.getClient(registryId);
    Scope scope = scope(client, request);
    if (runRepository.existsByExchangeClientIdAndStatus(
        client.getId(), ExchangeBulkUndoStatus.RUNNING)) {
      throw new BusinessConflictException("A bulk undo of this client is already running");
    }
    if (client.getStatus() == ExchangeClientStatus.ACTIVE) {
      client = registryService.suspendClient(client.getId(), client.getVersion());
    }
    UUID adminId = authHelperService.currentUserId().orElse(null);
    ExchangeClient suspended = client;
    ExchangeBulkUndoRun run;
    try {
      run =
          new TransactionTemplate(transactionManager)
              .execute(_ -> create(suspended, scope, adminId));
    } catch (DataIntegrityViolationException e) {
      throw new BusinessConflictException("A bulk undo of this client is already running", e);
    }
    if (run == null) {
      throw new IllegalStateException("The bulk undo run was not created");
    }
    try {
      runner.run(run.getId(), admin);
    } catch (TaskRejectedException e) {
      log.warn("Bulk undo run {} was refused by its executor and is marked failed", run.getId());
      runner.rejected(run.getId());
      throw new BusinessConflictException("error.exchange.bulkUndo.queueFull", e);
    }
    return toDto(
        run, suspended.getDisplayName(), names(adminId == null ? Set.of() : Set.of(adminId)));
  }

  /**
   * Lists the most recent runs, newest first.
   *
   * @return at most twenty runs
   */
  @Transactional(readOnly = true)
  public @NotNull List<ExchangeBulkUndoRunDto> recentRuns() {
    List<ExchangeBulkUndoRun> runs = runRepository.findTop20ByOrderByStartedAtDesc();
    Map<UUID, String> clientNames = new HashMap<>();
    for (ExchangeClient client :
        clientRepository.findAllById(
            runs.stream().map(ExchangeBulkUndoRun::getExchangeClientId).distinct().toList())) {
      clientNames.put(client.getId(), client.getDisplayName());
    }
    Set<UUID> admins = new HashSet<>();
    runs.forEach(
        r -> {
          if (r.getRequestedBy() != null) {
            admins.add(r.getRequestedBy());
          }
        });
    Map<UUID, String> adminNames = names(admins);
    return runs.stream()
        .map(r -> toDto(r, clientNames.get(r.getExchangeClientId()), adminNames))
        .toList();
  }

  /**
   * Loads one run with the first entries it left alone, failed members first.
   *
   * @param runId the run
   * @return the run and its skipped entries
   * @throws NotFoundException when the run is unknown
   */
  @Transactional(readOnly = true)
  public @NotNull ExchangeBulkUndoRunDetailDto run(@NotNull UUID runId) {
    ExchangeBulkUndoRun run =
        Entities.require(runRepository.findById(runId), "Bulk undo run not found");
    String clientName =
        clientRepository
            .findById(run.getExchangeClientId())
            .map(ExchangeClient::getDisplayName)
            .orElse(run.getClientId());
    List<ExchangeBulkUndoSkip> skips =
        skipRepository.findPage(runId, PageRequest.of(0, SKIPPED_SHOWN));
    Set<UUID> people = new HashSet<>();
    skips.forEach(s -> people.add(s.getUserId()));
    if (run.getRequestedBy() != null) {
      people.add(run.getRequestedBy());
    }
    Map<UUID, String> names = names(people);
    List<UUID> entryIds =
        skips.stream()
            .map(ExchangeBulkUndoSkip::getJournalEntryId)
            .filter(id -> id != null)
            .toList();
    List<ExchangeJournalEntry> entries = journalRepository.findAllById(entryIds);
    Map<UUID, String> labels = entryLabels.label(entries);
    List<ExchangeBulkUndoRunDetailDto.SkippedEntry> skipped =
        skips.stream()
            .map(
                s ->
                    new ExchangeBulkUndoRunDetailDto.SkippedEntry(
                        names.get(s.getUserId()),
                        s.getResource() == null ? null : s.getResource().name(),
                        s.getJournalEntryId() == null ? null : labels.get(s.getJournalEntryId()),
                        s.getReason()))
            .toList();
    return new ExchangeBulkUndoRunDetailDto(
        toDto(run, clientName, names), skipped, skipRepository.countByRunId(runId));
  }

  /**
   * Marks the runs a restart cut short as failed, so a new run can start and the admin sees why the
   * old one stopped.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void failInterruptedRuns() {
    for (ExchangeBulkUndoRun run : runRepository.findAllByStatus(ExchangeBulkUndoStatus.RUNNING)) {
      if (step.finish(run.getId(), ExchangeBulkUndoStatus.FAILED, true)) {
        log.warn("Bulk undo run {} was interrupted by a restart and is marked failed", run.getId());
      }
    }
  }

  /**
   * Deletes the runs that ended before a cutoff, with the entries they left alone; the nightly
   * exchange retention calls it with its own cutoff.
   *
   * @param cutoff the oldest end still kept
   * @return the number of runs deleted
   */
  @Transactional
  public int purgeFinishedBefore(@NotNull Instant cutoff) {
    return runRepository.deleteFinishedBefore(cutoff);
  }

  /**
   * Records a run and audits its start, inside the caller's transaction.
   *
   * @param client the suspended client
   * @param scope the scope
   * @param adminId the starting admin, or {@code null}
   * @return the saved run
   */
  private @NotNull ExchangeBulkUndoRun create(
      @NotNull ExchangeClient client, @NotNull Scope scope, @Nullable UUID adminId) {
    int members =
        journalRepository
            .findUndoableMembers(
                client.getClientId(), scope.since(), scope.installationKey(), scope.resourceName())
            .size();
    ExchangeBulkUndoRun run =
        runRepository.saveAndFlush(
            ExchangeBulkUndoRun.builder()
                .id(UUID.randomUUID())
                .exchangeClientId(client.getId())
                .clientId(client.getClientId())
                .installationId(scope.installationId())
                .installationKey(scope.installationKey())
                .resource(scope.resource())
                .since(scope.since())
                .requestedBy(adminId)
                .status(ExchangeBulkUndoStatus.RUNNING)
                .membersTotal(members)
                .startedAt(clock.instant())
                .build());
    auditRecorder.record(
        AuditEventType.EXCHANGE_BULK_UNDO_STARTED,
        client.getId(),
        client.getClientId(),
        null,
        AuditDetails.of("run", run.getId())
            .with("since", scope.since())
            .with("resource", scope.resource() == null ? "ALL" : scope.resource().name())
            .with("installation", scope.installationId())
            .with("members", members));
    return run;
  }

  /**
   * Reads and checks a request's scope.
   *
   * @param client the client
   * @param request the request
   * @return the scope with the span clamped to the retention
   */
  private @NotNull Scope scope(
      @NotNull ExchangeClient client, @NotNull ExchangeBulkUndoRequest request) {
    if (request.since().isAfter(clock.instant())) {
      throw new BadRequestException("The span must not start in the future");
    }
    ExchangeResource resource =
        request.resource() == null ? null : ExchangeResource.valueOf(request.resource());
    String installationKey = null;
    if (request.installationId() != null) {
      ExchangeInstallation installation =
          Entities.require(
              installationRepository
                  .findById(request.installationId())
                  .filter(i -> i.getClient().getId().equals(client.getId())),
              "Installation not found");
      installationKey = installation.getKeyThumbprint();
    }
    return new Scope(clamp(request.since()), request.installationId(), installationKey, resource);
  }

  /**
   * Clamps the start of a span to the journal's retention.
   *
   * @param since the requested start
   * @return the start the journal can still reach
   */
  private @NotNull Instant clamp(@NotNull Instant since) {
    Instant floor = clock.instant().minus(retention.maxAge());
    return since.isBefore(floor) ? floor : since;
  }

  /**
   * Loads display names in one query.
   *
   * @param ids the users
   * @return the names by id; a deleted user is missing
   */
  private @NotNull Map<UUID, String> names(@NotNull Collection<UUID> ids) {
    Map<UUID, String> names = new HashMap<>();
    if (ids.isEmpty()) {
      return names;
    }
    for (User user : userRepository.findAllById(ids)) {
      names.put(user.getId(), user.getEffectiveName());
    }
    return names;
  }

  /**
   * Maps a run for the admin page.
   *
   * @param run the run
   * @param clientName the client's display name
   * @param names display names by user id
   * @return the run
   */
  private static @NotNull ExchangeBulkUndoRunDto toDto(
      @NotNull ExchangeBulkUndoRun run,
      @Nullable String clientName,
      @NotNull Map<UUID, String> names) {
    return new ExchangeBulkUndoRunDto(
        run.getId(),
        run.getExchangeClientId(),
        run.getClientId(),
        clientName == null ? run.getClientId() : clientName,
        run.getStatus().name(),
        run.getSince(),
        run.getInstallationId(),
        run.getResource() == null ? null : run.getResource().name(),
        run.getRequestedBy() == null ? null : names.get(run.getRequestedBy()),
        run.getMembersTotal(),
        run.getMembersDone(),
        run.getMembersFailed(),
        run.getRestored(),
        run.getSkipped(),
        run.getStartedAt(),
        run.getFinishedAt());
  }

  /**
   * A bulk undo's scope.
   *
   * @param since the start of the span, clamped
   * @param installationId the one installation, or {@code null}
   * @param installationKey its key thumbprint, or {@code null}
   * @param resource the one resource, or {@code null}
   */
  private record Scope(
      @NotNull Instant since,
      @Nullable UUID installationId,
      @Nullable String installationKey,
      @Nullable ExchangeResource resource) {

    /**
     * Returns the resource's name for the native queries.
     *
     * @return the name, or {@code null} for every resource
     */
    @Nullable
    String resourceName() {
      return resource == null ? null : resource.name();
    }
  }
}

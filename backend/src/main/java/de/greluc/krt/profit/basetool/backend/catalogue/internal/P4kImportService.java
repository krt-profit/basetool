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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kRejectionReason;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kBuild;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCatalogue;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCatalogueFormatException;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCatalogueHead;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCatalogueReader;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kChangeNumber;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCompression;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kContentTooLargeException;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKindCompleteness;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The P4K import of catalogue version 2 (REQ-P4K-003…010, ADR-0335): it reads a stored upload,
 * hands it to the reconcilers ({@link P4kReconcilers}), sweeps the complete kinds and, on APPLY,
 * records the run, its counts per kind, its change report and its {@code CATALOGUE} audit event
 * (REQ-P4K-046).
 *
 * <ul>
 *   <li>{@link #previewImport(byte[])} computes everything and writes nothing.
 *   <li>{@link #applyImport(byte[], P4kApplyRequest)} writes it in one transaction, refusing an
 *       older Build than the last applied run.
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class P4kImportService {

  private final P4kCatalogueReader reader;
  private final P4kImportProperties properties;
  private final P4kReconcilers reconcilers;
  private final P4kSweeper sweeper;
  private final P4kProductKeyReview productKeyReview;
  private final P4kImportRunRepository runRepository;
  private final P4kImportRecorder recorder;
  private final P4kReviewCaseRepository reviewCaseRepository;
  private final P4kRejections rejections;
  private final SyncReportService syncReportService;
  private final AuditRecorder auditRecorder;
  private final P4kCatalogueRevision revision;

  /**
   * What an APPLY needs besides the catalogue.
   *
   * @param jobId the APPLY job
   * @param createdBy the administrator who uploaded the catalogue
   * @param createItemsAndShips whether unmatched playable items and ships are created
   * @param overrideMassGuard whether a kind is swept even when the sweep would stamp more of it
   *     than the mass guard allows (REQ-P4K-012)
   */
  public record P4kApplyRequest(
      @NotNull UUID jobId,
      @NotNull UUID createdBy,
      boolean createItemsAndShips,
      boolean overrideMassGuard) {}

  /**
   * What a preview computed: its result and the change report an APPLY would write.
   *
   * @param result the result with {@code dryRun = true}
   * @param changes the report rows in the order found, without a run
   */
  public record P4kPreview(
      @NotNull P4kImportResultDto result, @NotNull @Unmodifiable List<P4kImportChange> changes) {}

  /**
   * Previews an import: everything an APPLY with "create new items and ships" ticked and without
   * the mass-guard override would do, its change report included, without writing anything.
   *
   * @param payload the stored upload
   * @return the result with {@code dryRun = true} and no run, and the report rows
   * @throws BadRequestException when the payload is no readable version-2 LIVE catalogue, or its
   *     Build is older than that of the last applied run, which an APPLY would refuse
   */
  @NotNull
  public P4kPreview previewImport(byte @NotNull [] payload) {
    P4kCatalogue catalogue = read(payload);
    P4kBuild build = requireBuild(catalogue.head());
    refuseOlderBuild(build);
    P4kImportContext context =
        P4kImportContext.forPreview(
            catalogue, Instant.now(), reviewCaseRepository.findAdminClosedKeys());
    int ingredientsResolved = reconcile(payload, catalogue, context);
    sweepAndFollow(catalogue, context, false);
    return new P4kPreview(
        result(catalogue, build, context, ingredientsResolved), context.getReport().changes());
  }

  /**
   * Applies an import in one transaction: refuses an older Build, records the run, reconciles every
   * kind, stamps the matched rows with the run, sweeps the complete kinds, moves the personal
   * blueprints onto the produced-item GUIDs (REQ-P4K-029) and stores the change report.
   *
   * @param payload the stored upload
   * @param request the job, the uploader and the admin's choices
   * @return the result with the run id
   * @throws BadRequestException when the payload is unreadable or its Build is older than that of
   *     the last applied run
   */
  @Transactional
  @NotNull
  public P4kImportResultDto applyImport(
      byte @NotNull [] payload, @NotNull P4kApplyRequest request) {
    P4kCatalogue catalogue = read(payload);
    P4kBuild build = requireBuild(catalogue.head());
    long changeNumber = refuseOlderBuild(build);
    Instant now = Instant.now();
    P4kImportRun run =
        runRepository.save(newRun(catalogue.head(), build, changeNumber, request, now));
    run.setItemClassNullShare(P4kItemClassShare.of(catalogue.items()));
    P4kImportContext context =
        P4kImportContext.forApply(
            catalogue,
            now,
            run.getId(),
            request.createItemsAndShips(),
            reviewCaseRepository.findAdminClosedKeys());
    int ingredientsResolved = reconcile(payload, catalogue, context);
    List<P4kKind> overridden = sweepAndFollow(catalogue, context, request.overrideMassGuard());
    run.setKinds(kinds(catalogue.head(), context.getReport()));
    recorder.record(run.getId(), context.getReport());
    P4kImportResultDto result = result(catalogue, build, context, ingredientsResolved);
    overridden.forEach(kind -> auditOverride(run, context.getReport().sweepResult(kind)));
    syncReportService.logP4kRunSummary(run.getId(), summary(result, context.getReport()));
    auditRecorder.record(
        AuditEventType.CATALOGUE_P4K_APPLIED,
        run.getId(),
        run.getBuildVersion(),
        null,
        auditDetails(run, result)
            .with("adminCategoriesRetired", context.getAdminCategoriesRetired()));
    syncReportService.pruneRuns(SyncSourceSystem.P4K);
    revision.changed();
    log.info("P4K import applied (run {}): {}", run.getId(), summary(result, context.getReport()));
    return result;
  }

  /**
   * Sweeps the complete kinds, then hands the blueprints' produced-item GUIDs to the modules keyed
   * by them (REQ-P4K-029).
   *
   * @param catalogue the catalogue
   * @param context the run
   * @param overrideMassGuard whether a kind is swept past the mass guard
   * @return the kinds swept only because of the override
   */
  @NotNull
  private List<P4kKind> sweepAndFollow(
      @NotNull P4kCatalogue catalogue,
      @NotNull P4kImportContext context,
      boolean overrideMassGuard) {
    List<P4kKind> overridden = sweeper.sweep(catalogue, context, overrideMassGuard);
    productKeyReview.follow(context, context.getNow());
    return overridden;
  }

  /**
   * Reconciles every kind of a catalogue, streaming its loadouts from the stored upload again.
   *
   * @param payload the stored upload, streamed again for the loadouts
   * @param catalogue the catalogue
   * @param context the run
   * @return the ingredient lines the blueprints resolved
   */
  private int reconcile(
      byte @NotNull [] payload,
      @NotNull P4kCatalogue catalogue,
      @NotNull P4kImportContext context) {
    return reconcilers.reconcile(
        catalogue,
        action ->
            read(
                payload,
                in -> {
                  reader.readLoadouts(in, action);
                  return null;
                }),
        context);
  }

  /**
   * Reads a stored upload.
   *
   * @param payload the stored upload, compressed
   * @return the catalogue
   * @throws BadRequestException when it cannot be read or passes the decompressed limit
   */
  @NotNull
  private P4kCatalogue read(byte @NotNull [] payload) {
    return Objects.requireNonNull(read(payload, reader::read));
  }

  /**
   * Hands the decompressed content of a stored upload to a reading step.
   *
   * @param payload the stored upload, compressed
   * @param step reads the decompressed content
   * @param <T> what the step returns
   * @return the step's result
   * @throws BadRequestException when it cannot be read or passes the decompressed limit
   */
  @Nullable
  private <T> T read(byte @NotNull [] payload, @NotNull Function<InputStream, T> step) {
    try (InputStream in =
        P4kCompression.openDecompressed(payload, properties.decompressedLimitBytes())) {
      return step.apply(in);
    } catch (P4kCatalogueFormatException e) {
      throw new BadRequestException("The stored catalogue is not readable catalogue JSON.", e);
    } catch (P4kContentTooLargeException e) {
      throw new BadRequestException("The stored catalogue exceeds the decompressed limit.", e);
    } catch (IOException e) {
      throw new UncheckedIOException("Reading an in-memory catalogue failed.", e);
    }
  }

  /**
   * Returns the Build of a catalogue that passes the gate's head rules.
   *
   * @param head the head
   * @return the Build
   * @throws BadRequestException when the head breaks a gate rule
   */
  @NotNull
  private static P4kBuild requireBuild(@NotNull P4kCatalogueHead head) {
    P4kRejectionReason refusal = P4kUploadGate.headRefusal(head);
    P4kBuild build = head.build();
    if (refusal != null || build == null) {
      throw new BadRequestException("The stored catalogue is no version-2 LIVE catalogue.");
    }
    return build;
  }

  /**
   * Refuses a Build whose change number is lower than that of the last applied run (REQ-P4K-004).
   *
   * @param build the catalogue's Build, past the gate's head rules
   * @return the Build's change number
   * @throws BadRequestException when the Build is older; the refusal is logged and counted
   */
  private long refuseOlderBuild(@NotNull P4kBuild build) {
    long changeNumber = Objects.requireNonNull(P4kChangeNumber.parse(build.changeNumber()));
    Long highest = runRepository.findHighestChangeNumber().orElse(null);
    if (highest != null && changeNumber < highest) {
      rejections.record(P4kRejectionReason.OLDER_BUILD);
      throw new BadRequestException(
          "The catalogue's Build (change "
              + changeNumber
              + ") is older than the last applied one (change "
              + highest
              + ").");
    }
    return changeNumber;
  }

  /**
   * Builds the run of an APPLY, without its kinds.
   *
   * @param head the catalogue head
   * @param build the Build
   * @param changeNumber the parsed change number
   * @param request the APPLY request
   * @param now the apply time
   * @return the unsaved run
   */
  @NotNull
  private static P4kImportRun newRun(
      @NotNull P4kCatalogueHead head,
      @NotNull P4kBuild build,
      long changeNumber,
      @NotNull P4kApplyRequest request,
      @NotNull Instant now) {
    P4kImportRun run = new P4kImportRun();
    run.setJobId(request.jobId());
    run.setChannel(P4kUploadGate.LIVE);
    run.setBuildBranch(nonNull(build.branch()));
    run.setBuildVersion(nonNull(build.version()));
    run.setChangeNumber(changeNumber);
    run.setBuildId(nonNull(build.buildId()));
    P4kPatchVersion patch = P4kPatchVersion.of(build);
    if (patch != null) {
      run.setPatchVersion(patch.version());
      run.setPatchVersionOrigin(patch.origin());
    }
    if (head.source() != null) {
      run.setToolVersion(head.source().toolVersion());
      run.setExportedAt(instantOrNull(head.source().exportedAt()));
    }
    run.setCreatedBy(request.createdBy());
    run.setAppliedAt(now);
    return run;
  }

  /**
   * Builds the per-kind rows of a run.
   *
   * @param head the catalogue head
   * @param report the run's report
   * @return one row per kind
   */
  @NotNull
  private static List<P4kImportRunKind> kinds(
      @NotNull P4kCatalogueHead head, @NotNull P4kImportReport report) {
    List<P4kImportRunKind> kinds = new ArrayList<>();
    for (P4kKind kind : P4kKind.values()) {
      P4kCompleteness completeness = completeness(head, kind);
      P4kImportResultDto.Counts counts = report.counts(kind, completeness);
      P4kImportResultDto.Sweep sweep = report.sweepResult(kind);
      P4kKindCompleteness entry = head.completeness().get(kind.getCatalogueField());
      P4kImportRunKind row = new P4kImportRunKind();
      row.setKind(kind);
      row.setCompleteness(completeness);
      row.setCompletenessFilter(entry == null ? null : entry.filter());
      row.setMatched(counts.matched());
      row.setUnmatched(counts.unmatched());
      row.setAdded(counts.created());
      row.setChanged(counts.enriched());
      row.setVanished(sweep.vanished());
      row.setReappeared(sweep.reappeared());
      row.setSweep(sweep.outcome());
      kinds.add(row);
    }
    return kinds;
  }

  /**
   * Assembles the result of a run.
   *
   * @param catalogue the catalogue
   * @param build the Build
   * @param context the run
   * @param ingredientsResolved the ingredient lines the blueprints resolved
   * @return the result
   */
  @NotNull
  private static P4kImportResultDto result(
      @NotNull P4kCatalogue catalogue,
      @NotNull P4kBuild build,
      @NotNull P4kImportContext context,
      int ingredientsResolved) {
    P4kCatalogueHead head = catalogue.head();
    P4kImportReport report = context.getReport();
    P4kPatchVersion patch = P4kPatchVersion.of(build);
    P4kImportResultDto.Build buildDto =
        new P4kImportResultDto.Build(
            P4kUploadGate.LIVE,
            nonNull(build.branch()),
            nonNull(build.version()),
            nonNull(build.changeNumber()),
            patch == null ? null : patch.version(),
            patch == null ? null : patch.origin());
    return new P4kImportResultDto(
        !context.isApply(),
        context.isCreateItemsAndShips(),
        buildDto,
        report.counts(P4kKind.MANUFACTURER, completeness(head, P4kKind.MANUFACTURER)),
        report.counts(P4kKind.ITEM, completeness(head, P4kKind.ITEM)),
        report.counts(P4kKind.SHIP, completeness(head, P4kKind.SHIP)),
        report.counts(P4kKind.COMMODITY, completeness(head, P4kKind.COMMODITY)),
        report.counts(P4kKind.BLUEPRINT, completeness(head, P4kKind.BLUEPRINT)),
        report.counts(P4kKind.STARMAP_OBJECT, completeness(head, P4kKind.STARMAP_OBJECT)),
        report.counts(P4kKind.SHOP, completeness(head, P4kKind.SHOP)),
        report.counts(P4kKind.RESOURCE_GROUP, completeness(head, P4kKind.RESOURCE_GROUP)),
        report.counts(P4kKind.BLUEPRINT_CATEGORY, completeness(head, P4kKind.BLUEPRINT_CATEGORY)),
        ingredientsResolved,
        context.getRunId(),
        Arrays.stream(P4kKind.values()).map(report::sweepResult).toList(),
        P4kSweeper.MASS_GUARD_PERCENT);
  }

  /**
   * Records the audit event of a kind swept on the admin's mass-guard override (REQ-P4K-012).
   *
   * @param run the run
   * @param sweep the kind's sweep result
   */
  private void auditOverride(@NotNull P4kImportRun run, @NotNull P4kImportResultDto.Sweep sweep) {
    double share = sweep.known() == 0 ? 0 : 100.0 * sweep.toStamp() / sweep.known();
    auditRecorder.record(
        AuditEventType.CATALOGUE_P4K_MASS_GUARD_OVERRIDDEN,
        run.getId(),
        sweep.kind().name(),
        null,
        AuditDetails.of("kind", sweep.kind().name())
            .with("count", sweep.toStamp())
            .with("sharePercent", String.format(Locale.ROOT, "%.1f", share)));
  }

  /**
   * Returns a kind's completeness.
   *
   * @param head the catalogue head
   * @param kind the kind
   * @return {@code COMPLETE} or {@code PARTIAL}
   */
  @NotNull
  private static P4kCompleteness completeness(
      @NotNull P4kCatalogueHead head, @NotNull P4kKind kind) {
    return head.isComplete(kind) ? P4kCompleteness.COMPLETE : P4kCompleteness.PARTIAL;
  }

  /**
   * Renders the run summary for {@code external_sync_report}.
   *
   * @param result the result
   * @param report the run's report, for the loadout and paint counts
   * @return one line with the counts per kind
   */
  @NotNull
  private static String summary(
      @NotNull P4kImportResultDto result, @NotNull P4kImportReport report) {
    return Stream.of(
                "manufacturers" + counts(result.manufacturers()),
                "items" + counts(result.items()),
                "ships" + counts(result.ships()),
                "commodities" + counts(result.commodities()),
                "blueprints" + counts(result.blueprints()),
                "starmapObjects" + counts(Objects.requireNonNull(result.starmapObjects())),
                "shops" + counts(Objects.requireNonNull(result.shops())),
                "resourceGroups" + counts(result.resourceGroups()),
                "blueprintCategories"
                    + counts(Objects.requireNonNull(result.blueprintCategories())))
            .collect(Collectors.joining(" ", "P4K catalogue import — ", ""))
        + "; ingredientsResolved="
        + result.ingredientsResolved()
        + "; loadoutShips="
        + report.loadoutShips()
        + "; loadoutPorts="
        + report.loadoutPorts()
        + "; paintLinks="
        + report.paintLinks()
        + "; createItemsAndShips="
        + result.createItemsAndShips();
  }

  /**
   * Renders the audit payload of an APPLY: the Build, the run's choice and the counts per kind.
   *
   * @param run the recorded run
   * @param result the result
   * @return the payload
   */
  @NotNull
  private static AuditDetails auditDetails(
      @NotNull P4kImportRun run, @NotNull P4kImportResultDto result) {
    return AuditDetails.of("branch", run.getBuildBranch())
        .with("change", run.getChangeNumber())
        .with("createItemsAndShips", result.createItemsAndShips())
        .with("manufacturers", counts(result.manufacturers()))
        .with("items", counts(result.items()))
        .with("ships", counts(result.ships()))
        .with("commodities", counts(result.commodities()))
        .with("blueprints", counts(result.blueprints()))
        .with("starmapObjects", counts(Objects.requireNonNull(result.starmapObjects())))
        .with("shops", counts(Objects.requireNonNull(result.shops())))
        .with("resourceGroups", counts(result.resourceGroups()))
        .with("blueprintCategories", counts(Objects.requireNonNull(result.blueprintCategories())))
        .with("ingredientsResolved", result.ingredientsResolved());
  }

  /**
   * Formats one kind's counts.
   *
   * @param c the counts
   * @return a compact {@code [m=…,en=…,im=…,cr=…,un=…,rv=…,uv=…]} fragment
   */
  @NotNull
  private static String counts(@NotNull P4kImportResultDto.Counts c) {
    return "[m=%d,en=%d,im=%d,cr=%d,un=%d,rv=%d,uv=%d]"
        .formatted(
            c.matched(),
            c.enriched(),
            c.identityMismatches(),
            c.created(),
            c.unmatched(),
            c.review(),
            c.unknownValues());
  }

  /**
   * Returns a Build text, empty when absent.
   *
   * @param value the text, or {@code null}
   * @return the text, or an empty string
   */
  @NotNull
  private static String nonNull(@Nullable String value) {
    return value == null ? "" : value;
  }

  /**
   * Parses an RFC 3339 time.
   *
   * @param text the time text, or {@code null}
   * @return the instant, or {@code null} when absent or not parseable
   */
  @Nullable
  private static Instant instantOrNull(@Nullable String text) {
    if (text == null) {
      return null;
    }
    try {
      return Instant.parse(text);
    } catch (DateTimeParseException _) {
      return null;
    }
  }
}

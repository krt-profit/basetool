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

package de.greluc.krt.profit.basetool.frontend.catalogue.web;

import static de.greluc.krt.profit.basetool.frontend.kernel.web.BackendErrorResponses.relay;

import de.greluc.krt.profit.basetool.frontend.catalogue.client.CatalogueBackendClient;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.FrequencyTypeDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.FrequencyTypeForm;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.JobTypeDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.JobTypeForm;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CatalogPages;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CatalogPages.CompleteCatalog;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CatalogueCacheEviction;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.ParallelPageLoader;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.kernel.security.Roles;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.SquadronDto;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.SquadronForm;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Controller for the admin mission-data page ({@code /admin/mission-data}), managing the job-type,
 * squadron and frequency-type catalogs with create, update, soft delete and re-activate; the {@code
 * includeInactive*} flags list soft-deleted entries.
 */
@Controller
@UsesLayoutModel
@RequestMapping("/admin/mission-data")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminMissionDataPageController {

  /** Reads and writes the job-type, squadron and frequency-type catalogues. */
  private final CatalogueBackendClient catalogueClient;

  /** Loads the three catalogues in parallel. */
  private final ParallelPageLoader parallelPageLoader;

  /** Clears the catalogue caches after a write. */
  private final CatalogueCacheEviction cacheEviction;

  /**
   * Renders all three complete catalogs, fetched in parallel via {@link ParallelPageLoader}
   * (REQ-ADMIN-001). A failed catalog renders empty with {@code error.admin.mission.data.load}; a
   * catalog hitting the page-walk cap sets its {@code *Truncated} flag (REQ-ADMIN-002).
   *
   * @param includeInactiveJobTypes show soft-deleted job types
   * @param includeInactiveSquadrons show soft-deleted squadrons
   * @param includeInactiveFrequencyTypes show soft-deleted frequency types
   * @param fragment {@code "squadrons-results"}, {@code "jobtypes-results"} or {@code
   *     "freqtypes-results"} to render only that section's table (REQ-FE-002); otherwise the full
   *     page
   * @param model Thymeleaf model populated with the lists, forms and toggles
   * @return the {@code admin/mission-data} view name, or one section's fragment
   */
  @NotNull
  @GetMapping
  public String listData(
      @RequestParam(required = false, defaultValue = "false") boolean includeInactiveJobTypes,
      @RequestParam(required = false, defaultValue = "false") boolean includeInactiveSquadrons,
      @RequestParam(required = false, defaultValue = "false") boolean includeInactiveFrequencyTypes,
      @RequestParam(required = false) String fragment,
      Model model) {
    if (!model.containsAttribute("jobTypeForm")) {
      model.addAttribute("jobTypeForm", new JobTypeForm("", "", "", false, false, 0L));
    }
    if (!model.containsAttribute("squadronForm")) {
      model.addAttribute("squadronForm", new SquadronForm("", "", "", 0L));
    }
    if (!model.containsAttribute("frequencyTypeForm")) {
      model.addAttribute("frequencyTypeForm", new FrequencyTypeForm("", "", 0L));
    }
    model.addAttribute("includeInactiveJobTypes", includeInactiveJobTypes);
    model.addAttribute("includeInactiveSquadrons", includeInactiveSquadrons);
    model.addAttribute("includeInactiveFrequencyTypes", includeInactiveFrequencyTypes);

    AtomicBoolean anyFailure = new AtomicBoolean(false);

    CompletableFuture<CompleteCatalog<JobTypeDto>> jobTypesFuture =
        parallelPageLoader
            .loadAsync(() -> fetchJobTypes(includeInactiveJobTypes))
            .exceptionally(
                e -> {
                  logFragmentLoadFailure("job types", e);
                  anyFailure.set(true);
                  return null;
                });

    CompletableFuture<CompleteCatalog<SquadronDto>> squadronsFuture =
        parallelPageLoader
            .loadAsync(() -> fetchSquadrons(includeInactiveSquadrons))
            .exceptionally(
                e -> {
                  logFragmentLoadFailure("squadrons", e);
                  anyFailure.set(true);
                  return null;
                });

    CompletableFuture<CompleteCatalog<FrequencyTypeDto>> freqsFuture =
        parallelPageLoader
            .loadAsync(() -> fetchFrequencyTypes(includeInactiveFrequencyTypes))
            .exceptionally(
                e -> {
                  logFragmentLoadFailure("frequency types", e);
                  anyFailure.set(true);
                  return null;
                });

    CompletableFuture.allOf(jobTypesFuture, squadronsFuture, freqsFuture).join();

    CompleteCatalog<JobTypeDto> jobTypesCatalog = jobTypesFuture.join();
    CompleteCatalog<SquadronDto> squadronsCatalog = squadronsFuture.join();
    CompleteCatalog<FrequencyTypeDto> freqsCatalog = freqsFuture.join();
    model.addAttribute("jobTypes", jobTypesCatalog == null ? null : jobTypesCatalog.items());
    model.addAttribute("squadrons", squadronsCatalog == null ? null : squadronsCatalog.items());
    if (freqsCatalog != null) {
      model.addAttribute("frequencyTypes", freqsCatalog.items());
    }
    model.addAttribute("jobTypesTruncated", jobTypesCatalog != null && jobTypesCatalog.truncated());
    model.addAttribute(
        "squadronsTruncated", squadronsCatalog != null && squadronsCatalog.truncated());
    model.addAttribute("frequencyTypesTruncated", freqsCatalog != null && freqsCatalog.truncated());
    if (anyFailure.get()) {
      model.addAttribute("error", "error.admin.mission.data.load");
    }
    return switch (fragment == null ? "" : fragment) {
      case "squadrons-results" -> "admin/mission-data :: squadrons-results";
      case "jobtypes-results" -> "admin/mission-data :: jobtypes-results";
      case "freqtypes-results" -> "admin/mission-data :: freqtypes-results";
      default -> "admin/mission-data";
    };
  }

  /**
   * Logs a parallel fragment-load failure (REQ-OBS-001): a {@link BackendServiceException},
   * possibly wrapped in a {@link CompletionException}, at DEBUG since the kernel backend client
   * already logged it; anything else at ERROR.
   *
   * @param what label of the fragment that failed to load (e.g. {@code "job types"})
   * @param e the throwable handed to {@link CompletableFuture#exceptionally}
   */
  private static void logFragmentLoadFailure(String what, Throwable e) {
    Throwable cause = (e instanceof CompletionException && e.getCause() != null) ? e.getCause() : e;
    if (cause instanceof BackendServiceException) {
      log.debug("Error loading {}", what, cause);
    } else {
      log.error("Error loading {}", what, e);
    }
  }

  /**
   * Fetches the complete job-type catalog (REQ-ADMIN-001) as {@link JobTypeDto}s sorted by name,
   * with the truncation flag (REQ-ADMIN-002).
   */
  @NotNull
  private CompleteCatalog<JobTypeDto> fetchJobTypes(boolean includeInactive) {
    CompleteCatalog<JobTypeDto> catalog =
        CatalogPages.fetchAll(page -> catalogueClient.jobTypePage(includeInactive, page));
    List<JobTypeDto> jobTypes =
        catalog.items().stream()
            .map(
                j ->
                    new JobTypeDto(
                        j.id(),
                        j.name(),
                        j.description(),
                        j.archetype(),
                        j.parentId(),
                        Boolean.TRUE.equals(j.active()),
                        Boolean.TRUE.equals(j.isLeadershipRole()),
                        Boolean.TRUE.equals(j.isMissionLead()),
                        versionOrZero(j.version())))
            .collect(Collectors.toCollection(ArrayList::new));
    jobTypes.sort(
        Comparator.comparing(j -> j.name() == null ? "" : j.name(), String.CASE_INSENSITIVE_ORDER));
    return new CompleteCatalog<>(jobTypes, catalog.totalElements(), catalog.truncated());
  }

  /**
   * Fetches the complete squadron catalog (REQ-ADMIN-001) as {@link SquadronDto}s sorted by name,
   * with the truncation flag (REQ-ADMIN-002).
   */
  @NotNull
  private CompleteCatalog<SquadronDto> fetchSquadrons(boolean includeInactive) {
    CompleteCatalog<SquadronDto> catalog =
        CatalogPages.fetchAll(page -> catalogueClient.squadronPage(includeInactive, page));
    List<SquadronDto> squadrons =
        catalog.items().stream()
            .map(
                s ->
                    new SquadronDto(
                        s.id(),
                        s.name(),
                        s.shorthand(),
                        s.description(),
                        Boolean.TRUE.equals(s.active()),
                        Boolean.TRUE.equals(s.isPromotionEnabled()),
                        Boolean.TRUE.equals(s.isProfitEligible()),
                        versionOrZero(s.version())))
            .collect(Collectors.toCollection(ArrayList::new));
    squadrons.sort(
        Comparator.comparing(s -> s.name() == null ? "" : s.name(), String.CASE_INSENSITIVE_ORDER));
    return new CompleteCatalog<>(squadrons, catalog.totalElements(), catalog.truncated());
  }

  /**
   * Fetches the complete frequency-type catalog in its admin order (REQ-ADMIN-001), with the
   * truncation flag (REQ-ADMIN-002).
   */
  private CompleteCatalog<FrequencyTypeDto> fetchFrequencyTypes(boolean includeInactive) {
    return CatalogPages.fetchAll(page -> catalogueClient.frequencyTypePage(includeInactive, page));
  }

  /**
   * Reads an optimistic-lock version, an absent one as zero.
   *
   * @param version the version the backend sent, or {@code null}
   * @return the version, or {@code 0L}
   */
  @NotNull
  private static Long versionOrZero(@Nullable Long version) {
    return version == null ? 0L : version;
  }

  /**
   * Creates a job type. A validation failure re-renders the page with the modal open; a 409 shows
   * the duplicate-name toast.
   *
   * @param form job-type form
   * @param bindingResult validation errors carrier
   * @param model Thymeleaf model for inline re-rendering
   * @param redirectAttributes flash attributes carrier
   * @return the list page on validation failure, otherwise redirect to {@code /admin/mission-data}
   */
  @NotNull
  @PostMapping("/job-types")
  public String createJobType(
      @Valid @ModelAttribute("jobTypeForm") JobTypeForm form,
      BindingResult bindingResult,
      Model model,
      RedirectAttributes redirectAttributes) {
    if (bindingResult.hasErrors()) {
      model.addAttribute("openModal", "jobtype-modal");
      model.addAttribute("modalAction", "/admin/mission-data/job-types");
      return listData(false, false, false, null, model);
    }
    try {
      JobTypeDto body =
          new JobTypeDto(
              null,
              form.name(),
              form.description(),
              form.archetype(),
              null,
              true,
              form.isLeadershipRole(),
              form.isMissionLead(),
              0L);
      catalogueClient.createJobType(body);
      cacheEviction.clearStaticDataCache();
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (BackendServiceException e) {
      log.debug("Create JobType failed", e);
      if (e.getStatusCode() == 409) {
        redirectAttributes.addFlashAttribute("errorToast", "error.duplicate.jobtype");
        return "redirect:/admin/mission-data";
      }
      return "redirect:/admin/mission-data?error=CreateJobTypeFailed";
    } catch (Exception e) {
      log.error("Create JobType failed", e);
      return "redirect:/admin/mission-data?error=CreateJobTypeFailed";
    }
    return "redirect:/admin/mission-data";
  }

  /**
   * Updates a job type, distinguishing an optimistic-lock conflict from a duplicate-name 409 in the
   * toast.
   *
   * @param id job-type id
   * @param form job-type form, carrying the version
   * @param bindingResult validation errors carrier
   * @param model Thymeleaf model for inline re-rendering
   * @param redirectAttributes flash attributes carrier
   * @return the list page on validation failure, otherwise redirect
   */
  @NotNull
  @PostMapping("/job-types/{id}/update")
  public String updateJobType(
      @PathVariable @NotNull UUID id,
      @Valid @ModelAttribute("jobTypeForm") JobTypeForm form,
      BindingResult bindingResult,
      Model model,
      RedirectAttributes redirectAttributes) {
    if (bindingResult.hasErrors()) {
      model.addAttribute("openModal", "jobtype-modal");
      model.addAttribute("modalAction", "/admin/mission-data/job-types/" + id + "/update");
      return listData(false, false, false, null, model);
    }
    try {
      JobTypeDto body =
          new JobTypeDto(
              id,
              form.name(),
              form.description(),
              form.archetype(),
              null,
              true,
              form.isLeadershipRole(),
              form.isMissionLead(),
              form.version());
      catalogueClient.updateJobType(id, body);
      cacheEviction.clearStaticDataCache();
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (BackendServiceException e) {
      log.debug("Update JobType failed", e);
      if (e.getStatusCode() == 409) {
        if ("concurrency-conflict".equals(e.getProblemType())) {
          redirectAttributes.addFlashAttribute("errorToast", "error.concurrency.conflict");
        } else {
          redirectAttributes.addFlashAttribute("errorToast", "error.duplicate.jobtype");
        }
        return "redirect:/admin/mission-data";
      }
      return "redirect:/admin/mission-data?error=UpdateJobTypeFailed";
    } catch (Exception e) {
      log.error("Update JobType failed", e);
      return "redirect:/admin/mission-data?error=UpdateJobTypeFailed";
    }
    return "redirect:/admin/mission-data";
  }

  /**
   * Soft-deletes a job type; a 409 (still referenced by a mission) shows the "in use" toast.
   *
   * @param id job-type id
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /admin/mission-data}, optionally with an error parameter
   */
  @NotNull
  @PostMapping("/job-types/{id}/delete")
  public String deleteJobType(
      @PathVariable @NotNull UUID id, RedirectAttributes redirectAttributes) {
    try {
      catalogueClient.deleteJobType(id);
      cacheEviction.clearStaticDataCache();
      redirectAttributes.addFlashAttribute("successToast", "notification.success.delete");
    } catch (BackendServiceException e) {
      log.debug("Delete JobType failed", e);
      if (e.getStatusCode() == 409) {
        redirectAttributes.addFlashAttribute("errorToast", "error.delete.jobtype.in_use");
        return "redirect:/admin/mission-data";
      }
      return "redirect:/admin/mission-data?error=DeleteJobTypeFailed";
    } catch (Exception e) {
      log.error("Delete JobType failed", e);
      return "redirect:/admin/mission-data?error=DeleteJobTypeFailed";
    }
    return "redirect:/admin/mission-data";
  }

  /**
   * Re-activates a soft-deleted job type; admin only.
   *
   * @param id job-type id
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /admin/mission-data}
   */
  @NotNull
  @PostMapping("/job-types/{id}/activate")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public String activateJobType(
      @PathVariable @NotNull UUID id, RedirectAttributes redirectAttributes) {
    try {
      catalogueClient.activateJobType(id);
      cacheEviction.clearStaticDataCache();
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (BackendServiceException e) {
      log.debug("Activate JobType failed", e);
      return "redirect:/admin/mission-data?error=ActivateJobTypeFailed";
    } catch (Exception e) {
      log.error("Activate JobType failed", e);
      return "redirect:/admin/mission-data?error=ActivateJobTypeFailed";
    }
    return "redirect:/admin/mission-data";
  }

  /**
   * Creates a squadron, handling validation and 409 like {@link #createJobType}.
   *
   * @param form squadron form
   * @param bindingResult validation errors carrier
   * @param model Thymeleaf model for inline re-rendering
   * @param redirectAttributes flash attributes carrier
   * @return the list page on validation failure, otherwise redirect
   */
  @NotNull
  @PostMapping("/squadrons")
  public String createSquadron(
      @Valid @ModelAttribute("squadronForm") SquadronForm form,
      BindingResult bindingResult,
      Model model,
      RedirectAttributes redirectAttributes) {
    if (bindingResult.hasErrors()) {
      model.addAttribute("openModal", "squadron-modal");
      model.addAttribute("modalAction", "/admin/mission-data/squadrons");
      return listData(false, false, false, null, model);
    }
    try {
      SquadronDto body =
          new SquadronDto(
              null, form.name(), form.shorthand(), form.description(), true, true, false, 0L);
      catalogueClient.createSquadron(body);
      cacheEviction.clearStaticDataCache();
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (BackendServiceException e) {
      log.debug("Create Squadron failed", e);
      if (e.getStatusCode() == 409) {
        redirectAttributes.addFlashAttribute("errorToast", "error.duplicate.squadron");
        return "redirect:/admin/mission-data";
      }
      return "redirect:/admin/mission-data?error=CreateSquadronFailed";
    } catch (Exception e) {
      log.error("Create Squadron failed", e);
      return "redirect:/admin/mission-data?error=CreateSquadronFailed";
    }
    return "redirect:/admin/mission-data";
  }

  /**
   * Updates a squadron, handling conflicts like {@link #updateJobType}.
   *
   * @param id squadron id
   * @param form squadron form
   * @param bindingResult validation errors carrier
   * @param model Thymeleaf model for inline re-rendering
   * @param redirectAttributes flash attributes carrier
   * @return the list page on validation failure, otherwise redirect
   */
  @NotNull
  @PostMapping("/squadrons/{id}/update")
  public String updateSquadron(
      @PathVariable @NotNull UUID id,
      @Valid @ModelAttribute("squadronForm") SquadronForm form,
      BindingResult bindingResult,
      Model model,
      RedirectAttributes redirectAttributes) {
    if (bindingResult.hasErrors()) {
      model.addAttribute("openModal", "squadron-modal");
      model.addAttribute("modalAction", "/admin/mission-data/squadrons/" + id + "/update");
      return listData(false, false, false, null, model);
    }
    try {
      SquadronDto body =
          new SquadronDto(
              id,
              form.name(),
              form.shorthand(),
              form.description(),
              true,
              true,
              false,
              form.version());
      catalogueClient.updateSquadron(id, body);
      cacheEviction.clearStaticDataCache();
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (BackendServiceException e) {
      log.debug("Update Squadron failed", e);
      if (e.getStatusCode() == 409) {
        if ("concurrency-conflict".equals(e.getProblemType())) {
          redirectAttributes.addFlashAttribute("errorToast", "error.concurrency.conflict");
        } else {
          redirectAttributes.addFlashAttribute("errorToast", "error.duplicate.squadron");
        }
        return "redirect:/admin/mission-data";
      }
      return "redirect:/admin/mission-data?error=UpdateSquadronFailed";
    } catch (Exception e) {
      log.error("Update Squadron failed", e);
      return "redirect:/admin/mission-data?error=UpdateSquadronFailed";
    }
    return "redirect:/admin/mission-data";
  }

  /**
   * Soft-deletes a squadron. Mirrors {@link #deleteJobType}'s in-use handling for 409.
   *
   * @param id squadron id
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /admin/mission-data}
   */
  @NotNull
  @PostMapping("/squadrons/{id}/delete")
  public String deleteSquadron(
      @PathVariable @NotNull UUID id, RedirectAttributes redirectAttributes) {
    try {
      catalogueClient.deleteSquadron(id);
      cacheEviction.clearStaticDataCache();
      redirectAttributes.addFlashAttribute("successToast", "notification.success.delete");
    } catch (BackendServiceException e) {
      log.debug("Delete Squadron failed", e);
      if (e.getStatusCode() == 409) {
        redirectAttributes.addFlashAttribute("errorToast", "error.delete.squadron.in_use");
        return "redirect:/admin/mission-data";
      }
      return "redirect:/admin/mission-data?error=DeleteSquadronFailed";
    } catch (Exception e) {
      log.error("Delete Squadron failed", e);
      return "redirect:/admin/mission-data?error=DeleteSquadronFailed";
    }
    return "redirect:/admin/mission-data";
  }

  /**
   * Re-activates a soft-deleted squadron. ADMIN-only, mirrors {@link #activateJobType}.
   *
   * @param id squadron id
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /admin/mission-data}
   */
  @NotNull
  @PostMapping("/squadrons/{id}/activate")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public String activateSquadron(
      @PathVariable @NotNull UUID id, RedirectAttributes redirectAttributes) {
    try {
      catalogueClient.activateSquadron(id);
      cacheEviction.clearStaticDataCache();
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (Exception e) {
      log.error("Activate Squadron failed", e);
      return "redirect:/admin/mission-data?error=ActivateSquadronFailed";
    }
    return "redirect:/admin/mission-data";
  }

  /**
   * Creates a frequency type like {@link #createJobType}; the backend appends it to the order.
   *
   * @param form frequency-type form
   * @param bindingResult validation errors carrier
   * @param model Thymeleaf model for inline re-rendering
   * @param redirectAttributes flash attributes carrier
   * @return the list page on validation failure, otherwise redirect
   */
  @NotNull
  @PostMapping("/frequency-types")
  public String createFrequencyType(
      @Valid @ModelAttribute("frequencyTypeForm") FrequencyTypeForm form,
      BindingResult bindingResult,
      Model model,
      RedirectAttributes redirectAttributes) {
    if (bindingResult.hasErrors()) {
      model.addAttribute("openModal", "frequency-type-modal");
      model.addAttribute("modalAction", "/admin/mission-data/frequency-types");
      return listData(false, false, false, null, model);
    }
    try {
      catalogueClient.createFrequencyType(
          new FrequencyTypeDto(null, form.name(), form.description(), true, null, null));
      cacheEviction.clearStaticDataCache();
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (Exception e) {
      log.error("Create FrequencyType failed", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.general");
    }
    return "redirect:/admin/mission-data";
  }

  /**
   * Updates a frequency type, optionally overriding {@code active}; a concurrency-conflict 409
   * shows a dedicated toast.
   *
   * @param id frequency-type id
   * @param form frequency-type form
   * @param active optional active override; defaults to {@code true}
   * @param bindingResult validation errors carrier
   * @param model Thymeleaf model for inline re-rendering
   * @param redirectAttributes flash attributes carrier
   * @return the list page on validation failure, otherwise redirect
   */
  @NotNull
  @PostMapping("/frequency-types/{id}/update")
  public String updateFrequencyType(
      @PathVariable @NotNull UUID id,
      @Valid @ModelAttribute("frequencyTypeForm") FrequencyTypeForm form,
      @RequestParam(required = false) Boolean active,
      BindingResult bindingResult,
      Model model,
      RedirectAttributes redirectAttributes) {
    if (bindingResult.hasErrors()) {
      model.addAttribute("openModal", "frequency-type-modal");
      model.addAttribute("modalAction", "/admin/mission-data/frequency-types/" + id + "/update");
      return listData(false, false, false, null, model);
    }
    try {
      catalogueClient.updateFrequencyType(
          id,
          new FrequencyTypeDto(
              null,
              form.name(),
              form.description(),
              active != null ? active : true,
              null,
              form.version()));
      cacheEviction.clearStaticDataCache();
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (BackendServiceException e) {
      log.debug("Update FrequencyType failed", e);
      if (e.getStatusCode() == 409 && "concurrency-conflict".equals(e.getProblemType())) {
        redirectAttributes.addFlashAttribute("errorToast", "error.concurrency.conflict");
      } else {
        redirectAttributes.addFlashAttribute("errorToast", "error.general");
      }
    } catch (Exception e) {
      log.error("Update FrequencyType failed", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.general");
    }
    return "redirect:/admin/mission-data";
  }

  /**
   * Soft-deletes a frequency type; a 409 (still referenced by a mission) shows the "in use" toast.
   *
   * @param id frequency-type id
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /admin/mission-data}
   */
  @NotNull
  @PostMapping("/frequency-types/{id}/delete")
  public String deleteFrequencyType(
      @PathVariable @NotNull UUID id, RedirectAttributes redirectAttributes) {
    try {
      catalogueClient.deleteFrequencyType(id);
      cacheEviction.clearStaticDataCache();
      redirectAttributes.addFlashAttribute("successToast", "notification.success.delete");
    } catch (BackendServiceException e) {
      log.debug("Delete FrequencyType failed", e);
      if (e.getStatusCode() == 409) {
        redirectAttributes.addFlashAttribute("errorToast", "error.delete.frequency_type.in_use");
        return "redirect:/admin/mission-data";
      }
      redirectAttributes.addFlashAttribute("errorToast", "error.general");
    } catch (Exception e) {
      log.error("Delete FrequencyType failed", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.general");
    }
    return "redirect:/admin/mission-data";
  }

  /**
   * Re-activates a soft-deleted frequency type. Unlike {@link #activateJobType} and {@link
   * #activateSquadron}, not restricted to admins.
   *
   * @param id frequency-type id
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /admin/mission-data}
   */
  @NotNull
  @PostMapping("/frequency-types/{id}/activate")
  public String activateFrequencyType(
      @PathVariable @NotNull UUID id, RedirectAttributes redirectAttributes) {
    try {
      catalogueClient.activateFrequencyType(id);
      cacheEviction.clearStaticDataCache();
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (Exception e) {
      log.error("Activate FrequencyType failed", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.general");
    }
    return "redirect:/admin/mission-data";
  }

  /**
   * AJAX endpoint that persists the new order of frequency types after a drag-and-drop in the admin
   * UI. Backend uses pessimistic locking to serialize concurrent reorders.
   *
   * @param ids frequency-type ids in the desired new order
   * @return 200 on success, 500 on backend failure
   */
  @PostMapping("/frequency-types/reorder")
  @ResponseBody
  public ResponseEntity<Void> reorderFrequencyTypes(@RequestBody List<UUID> ids) {
    try {
      catalogueClient.reorderFrequencyTypes(ids);
      cacheEviction.clearStaticDataCache();
      return ResponseEntity.ok().build();
    } catch (Exception e) {
      log.error("Reorder FrequencyTypes failed", e);
      return ResponseEntity.status(500).build();
    }
  }

  /**
   * In-place twin of {@link #createJobType}.
   *
   * @param form job-type form
   * @param bindingResult validation errors carrier
   * @return {@code 200} on success, {@code 422} on a validation failure, the relayed backend status
   *     on a domain conflict / failure
   */
  @ResponseBody
  @PostMapping(value = "/job-types", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> createJobTypeAjax(
      @Valid @ModelAttribute("jobTypeForm") JobTypeForm form, BindingResult bindingResult) {
    if (bindingResult.hasErrors()) {
      return ResponseEntity.status(422).build();
    }
    return okOrRelay(
        () ->
            catalogueClient.createJobType(
                new JobTypeDto(
                    null,
                    form.name(),
                    form.description(),
                    form.archetype(),
                    null,
                    true,
                    form.isLeadershipRole(),
                    form.isMissionLead(),
                    0L)));
  }

  /**
   * In-place twin of {@link #updateJobType}.
   *
   * @param id job-type id
   * @param form job-type form (carries the version)
   * @param bindingResult validation errors carrier
   * @return {@code 200} on success, {@code 422} on a validation failure, the relayed backend status
   *     on a conflict / failure
   */
  @ResponseBody
  @PostMapping(value = "/job-types/{id}/update", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> updateJobTypeAjax(
      @PathVariable @NotNull UUID id,
      @Valid @ModelAttribute("jobTypeForm") JobTypeForm form,
      BindingResult bindingResult) {
    if (bindingResult.hasErrors()) {
      return ResponseEntity.status(422).build();
    }
    return okOrRelay(
        () ->
            catalogueClient.updateJobType(
                id,
                new JobTypeDto(
                    id,
                    form.name(),
                    form.description(),
                    form.archetype(),
                    null,
                    true,
                    form.isLeadershipRole(),
                    form.isMissionLead(),
                    form.version())));
  }

  /**
   * In-place twin of {@link #deleteJobType}.
   *
   * @param id job-type id
   * @return {@code 200} on success, the relayed backend status on a conflict / failure
   */
  @ResponseBody
  @PostMapping(value = "/job-types/{id}/delete", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> deleteJobTypeAjax(@PathVariable @NotNull UUID id) {
    return okOrRelay(() -> catalogueClient.deleteJobType(id));
  }

  /**
   * In-place twin of {@link #activateJobType}.
   *
   * @param id job-type id
   * @return {@code 200} on success, the relayed backend status on failure
   */
  @ResponseBody
  @PostMapping(value = "/job-types/{id}/activate", headers = "X-Requested-With=XMLHttpRequest")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public ResponseEntity<Object> activateJobTypeAjax(@PathVariable @NotNull UUID id) {
    return okOrRelay(() -> catalogueClient.activateJobType(id));
  }

  /**
   * In-place twin of {@link #createSquadron}.
   *
   * @param form squadron form
   * @param bindingResult validation errors carrier
   * @return {@code 200} on success, {@code 422} on a validation failure, the relayed backend status
   *     on a conflict / failure
   */
  @ResponseBody
  @PostMapping(value = "/squadrons", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> createSquadronAjax(
      @Valid @ModelAttribute("squadronForm") SquadronForm form, BindingResult bindingResult) {
    if (bindingResult.hasErrors()) {
      return ResponseEntity.status(422).build();
    }
    return okOrRelay(
        () ->
            catalogueClient.createSquadron(
                new SquadronDto(
                    null,
                    form.name(),
                    form.shorthand(),
                    form.description(),
                    true,
                    true,
                    false,
                    0L)));
  }

  /**
   * In-place twin of {@link #updateSquadron}.
   *
   * @param id squadron id
   * @param form squadron form
   * @param bindingResult validation errors carrier
   * @return {@code 200} on success, {@code 422} on a validation failure, the relayed backend status
   *     on a conflict / failure
   */
  @ResponseBody
  @PostMapping(value = "/squadrons/{id}/update", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> updateSquadronAjax(
      @PathVariable @NotNull UUID id,
      @Valid @ModelAttribute("squadronForm") SquadronForm form,
      BindingResult bindingResult) {
    if (bindingResult.hasErrors()) {
      return ResponseEntity.status(422).build();
    }
    return okOrRelay(
        () ->
            catalogueClient.updateSquadron(
                id,
                new SquadronDto(
                    id,
                    form.name(),
                    form.shorthand(),
                    form.description(),
                    true,
                    true,
                    false,
                    form.version())));
  }

  /**
   * In-place twin of {@link #deleteSquadron}.
   *
   * @param id squadron id
   * @return {@code 200} on success, the relayed backend status on a conflict / failure
   */
  @ResponseBody
  @PostMapping(value = "/squadrons/{id}/delete", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> deleteSquadronAjax(@PathVariable @NotNull UUID id) {
    return okOrRelay(() -> catalogueClient.deleteSquadron(id));
  }

  /**
   * In-place twin of {@link #activateSquadron}.
   *
   * @param id squadron id
   * @return {@code 200} on success, the relayed backend status on failure
   */
  @ResponseBody
  @PostMapping(value = "/squadrons/{id}/activate", headers = "X-Requested-With=XMLHttpRequest")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public ResponseEntity<Object> activateSquadronAjax(@PathVariable @NotNull UUID id) {
    return okOrRelay(() -> catalogueClient.activateSquadron(id));
  }

  /**
   * In-place twin of {@link #createFrequencyType}.
   *
   * @param form frequency-type form
   * @param bindingResult validation errors carrier
   * @return {@code 200} on success, {@code 422} on a validation failure, the relayed backend status
   *     on failure
   */
  @ResponseBody
  @PostMapping(value = "/frequency-types", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> createFrequencyTypeAjax(
      @Valid @ModelAttribute("frequencyTypeForm") FrequencyTypeForm form,
      BindingResult bindingResult) {
    if (bindingResult.hasErrors()) {
      return ResponseEntity.status(422).build();
    }
    return okOrRelay(
        () ->
            catalogueClient.createFrequencyType(
                new FrequencyTypeDto(null, form.name(), form.description(), true, null, null)));
  }

  /**
   * In-place twin of {@link #updateFrequencyType}.
   *
   * @param id frequency-type id
   * @param form frequency-type form
   * @param active optional active override; defaults to {@code true} when omitted
   * @param bindingResult validation errors carrier
   * @return {@code 200} on success, {@code 422} on a validation failure, the relayed backend status
   *     on a conflict / failure
   */
  @ResponseBody
  @PostMapping(value = "/frequency-types/{id}/update", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> updateFrequencyTypeAjax(
      @PathVariable @NotNull UUID id,
      @Valid @ModelAttribute("frequencyTypeForm") FrequencyTypeForm form,
      @RequestParam(required = false) Boolean active,
      BindingResult bindingResult) {
    if (bindingResult.hasErrors()) {
      return ResponseEntity.status(422).build();
    }
    return okOrRelay(
        () ->
            catalogueClient.updateFrequencyType(
                id,
                new FrequencyTypeDto(
                    null,
                    form.name(),
                    form.description(),
                    active != null ? active : true,
                    null,
                    form.version())));
  }

  /**
   * In-place twin of {@link #deleteFrequencyType}.
   *
   * @param id frequency-type id
   * @return {@code 200} on success, the relayed backend status on a conflict / failure
   */
  @ResponseBody
  @PostMapping(value = "/frequency-types/{id}/delete", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> deleteFrequencyTypeAjax(@PathVariable @NotNull UUID id) {
    return okOrRelay(() -> catalogueClient.deleteFrequencyType(id));
  }

  /**
   * In-place twin of {@link #activateFrequencyType}.
   *
   * @param id frequency-type id
   * @return {@code 200} on success, the relayed backend status on failure
   */
  @ResponseBody
  @PostMapping(
      value = "/frequency-types/{id}/activate",
      headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> activateFrequencyTypeAjax(@PathVariable @NotNull UUID id) {
    return okOrRelay(() -> catalogueClient.activateFrequencyType(id));
  }

  /**
   * Runs a reference-data backend write, clears the static-data cache on success, and maps the
   * outcome to an HTTP status: {@code 200} on success, the relayed backend problem on a {@link
   * BackendServiceException}, {@code 500} otherwise. Shared by every mission-data AJAX twin.
   *
   * @param backendCall the backend mutation to perform
   * @return the mapped {@link ResponseEntity}
   */
  private ResponseEntity<Object> okOrRelay(Runnable backendCall) {
    return relay(
        log,
        "mission-data write (ajax)",
        () -> {
          backendCall.run();
          cacheEviction.clearStaticDataCache();
          return ResponseEntity.ok().build();
        });
  }
}

/*
 * Copyright (C) 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.copybara;

import static com.google.copybara.WorkflowOptions.CHANGE_REQUEST_PARENT_FLAG;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.copybara.Destination.WriterResult;
import com.google.copybara.WorkflowRunHelper.ComputedChanges;
import com.google.copybara.doc.annotations.DocField;
import com.google.copybara.profiler.Profiler.ProfilerTask;
import com.google.copybara.util.console.ProgressPrefixConsole;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * Workflow type to run between origin an destination
 */
public enum WorkflowMode {
  /**
   * Create a single commit in the destination with new tree state.
   */
  @DocField(description = "Create a single commit in the destination with new tree state.")
  SQUASH {
    @Override
    <O extends Revision, D extends Revision> void run(WorkflowRunHelper<O, D> runHelper)
        throws RepoException, IOException, ValidationException {
      ImmutableList<Change<O>> detectedChanges = ImmutableList.of();
      O current = runHelper.getResolvedRef();

      if (isHistorySupported(runHelper)) {
        O lastRev = maybeGetLastRev(runHelper);
        detectedChanges = runHelper.getChanges(lastRev, current);
        if (detectedChanges.isEmpty()) {
          manageNoChangesDetectedForSquash(runHelper, current, lastRev);
        }
      }

      Metadata metadata = new Metadata(
          "Project import generated by Copybara.\n",
          // SQUASH workflows always use the default author
          runHelper.getAuthoring().getDefaultAuthor());

      runHelper.maybeValidateRepoInLastRevState(metadata);

      WorkflowRunHelper<O, D> helperForChanges = runHelper.forChanges(detectedChanges);
      // Remove changes that don't affect origin_files
      detectedChanges = detectedChanges.stream()
          // Don't replace helperForChanges with runHelper since origin_files could
          // be potentially different in the helper for the current change.
          .filter(change -> !helperForChanges.skipChanges(ImmutableList.of(change)))
          .collect(ImmutableList.toImmutableList());

      // Try to use the latest change that affected the origin_files roots instead of the
      // current revision, that could be an unrelated change.
      current = detectedChanges.isEmpty()
          ?current
          :Iterables.getLast(detectedChanges).getRevision();

      if (runHelper.isSquashWithoutHistory()) {
        detectedChanges = ImmutableList.of();
      }

      helperForChanges.migrate(
              current,
              runHelper.getConsole(),
              metadata,
              // Squash notes an Skylark API expect last commit to be the first one.
              new ComputedChanges(detectedChanges.reverse(), ImmutableList.of()),
              /*destinationBaseline=*/null,
              runHelper.getWorkflowIdentity(runHelper.getResolvedRef()));
    }
  },

  /**
   * Import each origin change individually.
   */
  @DocField(description = "Import each origin change individually.")
  ITERATIVE {
    @Override
    <O extends Revision, D extends Revision> void run(WorkflowRunHelper<O, D> runHelper)
        throws RepoException, IOException, ValidationException {
      ImmutableList<Change<O>> changes = runHelper.changesSinceLastImport();
      if (changes.isEmpty()) {
        throw new EmptyChangeException(
            "No new changes to import for resolved ref: " + runHelper.getResolvedRef().asString());
      }
      int changeNumber = 1;

      Iterator<Change<O>> changesIterator = changes.iterator();
      int limit = changes.size();
      if (runHelper.workflowOptions().iterativeLimitChanges < changes.size()) {
        runHelper.getConsole().info(String.format("Importing first %d change(s) out of %d",
            limit, changes.size()));
        limit = runHelper.workflowOptions().iterativeLimitChanges;
      }

      runHelper.maybeValidateRepoInLastRevState(/*metadata=*/null);

      Deque<Change<O>> migrated = new ArrayDeque<>();
      int migratedChanges = 0;
      while (changesIterator.hasNext() && migratedChanges < limit) {
        Change<O> change = changesIterator.next();
        String prefix = String.format(
            "Change %d of %d (%s): ",
            changeNumber, Math.min(changes.size(), limit), change.getRevision().asString());
        WriterResult result;

        try (ProfilerTask ignored = runHelper.profiler().start(change.refAsString())) {
          ImmutableList<Change<O>> current = ImmutableList.of(change);
          WorkflowRunHelper<O, D> currentHelper = runHelper.forChanges(current);
          if (currentHelper.skipChanges(current)) {
            continue;
          }
          result = currentHelper.migrate(
                      change.getRevision(),
                      new ProgressPrefixConsole(prefix, runHelper.getConsole()),
                      new Metadata(change.getMessage(), change.getAuthor()),
                      new ComputedChanges(current, migrated),
                      /*destinationBaseline=*/null,
                      // Use the current change since we might want to create different
                      // reviews in the destination. Will not work if we want to group
                      // all the changes in the same Github PR
                      runHelper.getWorkflowIdentity(change.getRevision()));
          migratedChanges++;
        } catch (EmptyChangeException e) {
          runHelper.getConsole().warnFmt("Migration of origin revision '%s' resulted in an empty"
              + " change in the destination: %s", change.getRevision().asString(), e.getMessage());
          result = WriterResult.OK;
        } catch (ValidationException | RepoException e) {
          runHelper.getConsole().errorFmt("Migration of origin revision '%s' failed with error: %s",
              change.getRevision().asString(), e.getMessage());
          throw e;
        }
        migrated.addFirst(change);

        if (result == WriterResult.PROMPT_TO_CONTINUE && changesIterator.hasNext()) {
          // Use the regular console to log prompt and final message, it will be easier to spot
          if (!runHelper.getConsole()
              .promptConfirmation("Continue importing next change?")) {
            String message = String.format("Iterative workflow aborted by user after: %s", prefix);
            runHelper.getConsole().warn(message);
            throw new ChangeRejectedException(message);
          }
        }
        changeNumber++;
      }
      if (migratedChanges == 0) {
        throw new EmptyChangeException(
            String.format(
                "Iterative workflow produced no changes in the destination for resolved ref: %s",
                runHelper.getResolvedRef().asString()));
      }
      logger.log(Level.INFO,
          String.format("Imported %d change(s) out of %d", migratedChanges, changes.size()));
    }
  },
  @DocField(description = "Import an origin tree state diffed by a common parent"
      + " in destination. This could be a GH Pull Request, a Gerrit Change, etc.")
  CHANGE_REQUEST {
    @Override
    <O extends Revision, D extends Revision> void run(WorkflowRunHelper<O, D> runHelper)
        throws RepoException, IOException, ValidationException {
      final AtomicReference<String> requestParent = new AtomicReference<>(
          runHelper.workflowOptions().changeBaseline);
      ValidationException.checkCondition(runHelper.destinationSupportsPreviousRef(),
          String.format("'%s' is incompatible with destinations that don't support history"
              + " (For example folder.destination)", CHANGE_REQUEST));
      final String originLabelName = runHelper.getDestination().getLabelNameWhenOrigin();
      if (Strings.isNullOrEmpty(requestParent.get())) {
        O resolvedRef = runHelper.getResolvedRef();
        runHelper.getOriginReader().visitChanges(resolvedRef,
            change -> {
              if (!change.getRevision().asString().equals(resolvedRef.asString())
                  && change.getLabels().containsKey(originLabelName)) {
                requestParent.set(change.getLabels().get(originLabelName));
                return ChangeVisitable.VisitResult.TERMINATE;
              }
              return ChangeVisitable.VisitResult.CONTINUE;
            });
      }

      if (Strings.isNullOrEmpty(requestParent.get())) {
        throw new ValidationException(
            "Cannot find matching parent commit in in the destination. Use '"
                + CHANGE_REQUEST_PARENT_FLAG
                + "' flag to force a parent commit to use as baseline in the destination.");
      }
      Change<O> change = runHelper.getOriginReader().change(runHelper.getResolvedRef());
      ComputedChanges changes = new ComputedChanges(ImmutableList.of(change), ImmutableList.of());
      runHelper
          .forChanges(changes.getCurrent())
          .migrate(
              runHelper.getResolvedRef(),
              runHelper.getConsole(),
              new Metadata(change.getMessage(), change.getAuthor()),
              changes,
              requestParent.get(),
              runHelper.getWorkflowIdentity(runHelper.getResolvedRef()));
    }
  };

  private static <O extends Revision, D extends Revision> void manageNoChangesDetectedForSquash(
      WorkflowRunHelper<O, D> runHelper, O current, O lastRev)
      throws ValidationException, RepoException {
    ValidationException.checkCondition(
        lastRev != null || runHelper.isForce(), String.format(
            "Cannot find any change in history up to '%s'. Use %s if you really want to migrate to"
                + " the revision.", current.asString(), GeneralOptions.FORCE));
    runHelper.getConsole().warnFmt(
        "Cannot find any change in history up to '%s'. Trying the migration anyway", current);
    // Check the reverse changes to see if there is a change from current...lastRev.
    if (lastRev == null
        || !current.asString().equals(lastRev.asString())
        && runHelper.getChanges(current, lastRev).isEmpty()) {
      ValidationException.checkCondition(runHelper.isForce(), String.format(
          "Last imported revision '%s' is not an ancestor of the revision currently being"
              + " migrated ('%s'). Use %s if you really want to migrate the reference.",
          lastRev, current.asString(), GeneralOptions.FORCE));
      runHelper.getConsole().warnFmt(
          "Last imported revision '%s' is not an ancestor of the revision currently being"
              + " migrated ('%s')", lastRev, current.asString());
      return;
    }
    if (!runHelper.isForce()) {
      throw new EmptyChangeException(String.format(
          "'%s' has been already migrated. Use %s if you really want to run the migration"
              + " again (For example if the copy.bara.sky file has changed).",
          current.asString(), GeneralOptions.FORCE));
    }
    runHelper.getConsole().warnFmt("'%s' has been already migrated. Migrating anyway"
                                       + " because of %s", lastRev.asString(),
                                   GeneralOptions.FORCE);
  }

  private static boolean isHistorySupported(WorkflowRunHelper<?, ?> helper) {
    return helper.destinationSupportsPreviousRef() && helper.getOriginReader().supportsHistory();
  }

  /**
   * Returns the last rev if possible. If --force is not enabled it will fail if not found.
   */
  @Nullable
  private static <O extends Revision, D extends Revision> O maybeGetLastRev(
      WorkflowRunHelper<O, D> runHelper) throws RepoException, ValidationException {
    try {
      return runHelper.getLastRev();
    } catch (CannotResolveRevisionException e) {
      if (runHelper.isForce()) {
        runHelper.getConsole().warn(String.format(
            "Cannot find last imported revision, but proceeding because of %s flag",
            GeneralOptions.FORCE));
      } else {
        throw new ValidationException(String.format(
            "Cannot find last imported revision. Use %s if you really want to proceed with the"
                + " migration", GeneralOptions.FORCE), e);
      }
      return null;
    }
  }

  private static final Logger logger = Logger.getLogger(WorkflowMode.class.getName());

  abstract <O extends Revision, D extends Revision> void run(
      WorkflowRunHelper<O, D> runHelper) throws RepoException, IOException, ValidationException;
}

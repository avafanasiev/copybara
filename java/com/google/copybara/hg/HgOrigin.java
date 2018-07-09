/*
 * Copyright (C) 2018 Google Inc.
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

package com.google.copybara.hg;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.copybara.Origin.Reader.ChangesResponse.noChanges;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CharMatcher;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.copybara.Change;
import com.google.copybara.ChangeGraph;
import com.google.copybara.GeneralOptions;
import com.google.copybara.Options;
import com.google.copybara.Origin;
import com.google.copybara.Origin.Reader.ChangesResponse.EmptyReason;
import com.google.copybara.authoring.Authoring;
import com.google.copybara.exception.CannotResolveRevisionException;
import com.google.copybara.exception.EmptyChangeException;
import com.google.copybara.exception.RepoException;
import com.google.copybara.exception.ValidationException;
import com.google.copybara.hg.ChangeReader.HgChange;
import com.google.copybara.util.FileUtil;
import com.google.copybara.util.Glob;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * A class for manipulating Hg repositories
 */
public class HgOrigin implements Origin<HgRevision> {

  private final GeneralOptions generalOptions;
  private final HgOptions hgOptions;
  private final String repoUrl;
  private final String branch;

  HgOrigin(GeneralOptions generalOptions, HgOptions hgOptions, String repoUrl, String branch) {
    this.generalOptions = generalOptions;
    this.hgOptions = hgOptions;
    this.repoUrl = CharMatcher.is('/').trimTrailingFrom(checkNotNull(repoUrl));
    this.branch = Preconditions.checkNotNull(branch);
  }

  @VisibleForTesting
  public HgRepository getRepository() throws RepoException, ValidationException {
    return hgOptions.cachedBareRepoForUrl(repoUrl);
  }

  /**
   * Resolves a hg changeset reference to a revision. Pulls revision into repo.
   */
  @Override
  public HgRevision resolve(@Nullable String reference) throws RepoException, ValidationException {
    HgRepository repo = getRepository();

    if (Strings.isNullOrEmpty(reference)) {
      throw new CannotResolveRevisionException("Cannot resolve null or empty reference");
    }

    repo.pullFromRef(repoUrl, reference);
    return repo.identify(reference);
  }

  private static ImmutableList<Change<HgRevision>> asChanges(Collection<HgChange> hgChanges) {
    return hgChanges.stream().map(HgChange::getChange).collect(ImmutableList.toImmutableList());
  }

  static class ReaderImpl implements Reader<HgRevision> {

    private final String repoUrl;
    private final HgOptions hgOptions;
    private final Authoring authoring;
    private final GeneralOptions generalOptions;

    ReaderImpl(String repoUrl, HgOptions hgOptions, Authoring authoring,
        GeneralOptions generalOptions) {
      this.repoUrl = checkNotNull(repoUrl);
      this.hgOptions = hgOptions;
      this.authoring = authoring;
      this.generalOptions = generalOptions;
    }

    protected HgRepository getRepository() throws RepoException, ValidationException {
      return hgOptions.cachedBareRepoForUrl(repoUrl);
    }

    @Override
    public void checkout(HgRevision revision, Path workDir)
        throws RepoException, ValidationException {
      HgRepository repo = getRepository();
      String revId = revision.getGlobalId();
      repo.pullFromRef(repoUrl, revId);
      repo.cleanUpdate(revId);
      try {
        FileUtil.deleteRecursively(workDir);
        repo.archive(workDir.toString()); // update the working directory
      }
      catch (RepoException e) {
        if (e.getMessage().contains("abort: no files match the archive pattern")) {
          throw new ValidationException(e, "The origin repository is empty");
        }
        throw e;
      } catch (IOException e) {
        throw new RepoException("Error checking out " + repoUrl, e);
      }
    }

    @Override
    public ChangesResponse<HgRevision> changes(@Nullable HgRevision fromRef, HgRevision toRef)
      throws RepoException {
      String refRange = String.format("%s::%s",
          fromRef == null ? "" : fromRef.getGlobalId(), toRef.getGlobalId());

      ImmutableList<HgChange> hgChanges;

      try {
        ChangeReader reader = ChangeReader.Builder.forOrigin(getRepository(), authoring,
            generalOptions.console()).build();
        hgChanges = reader.run(refRange);
      } catch (ValidationException e) {
        throw new RepoException(
            String.format("Error getting changes: %s", e.getMessage()), e.getCause());
      }

      if (!hgChanges.isEmpty()) {
        return ChangesResponse.forChanges(toGraph(hgChanges));
      }

      return noChanges(EmptyReason.NO_CHANGES);
    }

    private ChangeGraph<Change<HgRevision>> toGraph(Iterable<HgChange> hgChanges) {
      ChangeGraph.Builder<Change<HgRevision>> builder = ChangeGraph.builder();

      Map<String, Change<HgRevision>> revisionMap = new HashMap<>();

      for(HgChange change : hgChanges) {
        builder.addChange(change.getChange());
        revisionMap.put(change.getChange().getRevision().getGlobalId(), change.getChange());

        for (HgRevision parent : change.getParents()) {
          Change<HgRevision> parentChange = revisionMap.get(parent.getGlobalId());

          if (parentChange != null) {
            builder.addParent(change.getChange(), parentChange);
          }
        }
      }
      return builder.build();
    }

    @Override
    public Change<HgRevision> change(HgRevision ref) throws RepoException, EmptyChangeException {
      ImmutableList<Change<HgRevision>> changes;

      try {
        ChangeReader reader = ChangeReader.Builder
            .forOrigin(getRepository(), authoring, generalOptions.console()).setLimit(1).build();
        changes = asChanges(reader.run(ref.getGlobalId()));
      }
      catch (ValidationException e){
        throw new RepoException(String.format("Error getting change: %s", e.getMessage()));
      }

      if (changes.isEmpty()) {
        throw new EmptyChangeException(
            String.format("%s reference cannot be found", ref.asString()));
      }

      Change<HgRevision> rev = changes.get(0);

      return new Change<>(ref, rev.getAuthor(), rev.getMessage(), rev.getDateTime(),
          rev.getLabels(), rev.getChangeFiles());
    }

    @Override
    public void visitChanges(HgRevision start, ChangesVisitor visitor) {
      throw new UnsupportedOperationException("Not implemented yet");
    }
  }

  @Override
  public Reader<HgRevision> newReader(Glob originFiles, Authoring authoring) {
    return new ReaderImpl(repoUrl, hgOptions, authoring, generalOptions);
  }

  @Override
  public String getLabelName() {
    return String.format("HgOrigin{url = %s}", repoUrl);
  }

  /**
   * Builds a new {@link HgOrigin}
   */
  static HgOrigin newHgOrigin(Options options, String url, String branch) {
    return new HgOrigin(options.get(GeneralOptions.class), options.get(HgOptions.class), url,
        branch);
  }

}

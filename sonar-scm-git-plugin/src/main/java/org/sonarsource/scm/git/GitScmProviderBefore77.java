/*
 * SonarQube :: Plugins :: SCM :: Git
 * Copyright (C) 2014-2019 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonarsource.scm.git;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.annotation.CheckForNull;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.sonar.api.batch.scm.BlameCommand;
import org.sonar.api.batch.scm.ScmProvider;
import org.sonar.api.utils.MessageException;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;

public class GitScmProviderBefore77 extends ScmProvider {

  private static final Logger LOG = Loggers.get(GitScmProviderBefore77.class);

  private final JGitBlameCommand jgitBlameCommand;
  private final AnalysisWarningsWrapper analysisWarnings;

  public GitScmProviderBefore77(JGitBlameCommand jgitBlameCommand, AnalysisWarningsWrapper analysisWarnings) {
    this.jgitBlameCommand = jgitBlameCommand;
    this.analysisWarnings = analysisWarnings;
  }

  @Override
  public String key() {
    return "git";
  }

  @Override
  public boolean supports(File baseDir) {
    RepositoryBuilder builder = new RepositoryBuilder().findGitDir(baseDir);
    return builder.getGitDir() != null;
  }

  @Override
  public BlameCommand blameCommand() {
    return this.jgitBlameCommand;
  }

  @CheckForNull
  @Override
  public Set<Path> branchChangedFiles(String targetBranchName, Path rootBaseDir) {
    try (Repository repo = buildRepo(rootBaseDir)) {
      Ref targetRef = resolveTargetRef(targetBranchName, repo);
      if (targetRef == null) {
        return null;
      }

      if (!isDiffAlgoValid(repo.getConfig())) {
        LOG.warn("The diff algorithm configured in git is not supported. "
          + "No information regarding changes in the branch will be collected, which can lead to unexpected results.");
        return null;
      }

      try (Git git = newGit(repo)) {
        return git.diff().setShowNameAndStatusOnly(true)
          .setOldTree(prepareTreeParser(repo, targetRef))
          .setNewTree(prepareNewTree(repo))
          .call().stream()
          .filter(diffEntry -> diffEntry.getChangeType() == DiffEntry.ChangeType.ADD || diffEntry.getChangeType() == DiffEntry.ChangeType.MODIFY)
          .map(diffEntry -> repo.getWorkTree().toPath().resolve(diffEntry.getNewPath()))
          .collect(Collectors.toSet());
      }
    } catch (IOException | GitAPIException e) {
      LOG.warn(e.getMessage(), e);
    }
    return null;
  }

  @CheckForNull
  @Override
  public Map<Path, Set<Integer>> branchChangedLines(String targetBranchName, Path projectBaseDir, Set<Path> changedFiles) {
    try (Repository repo = buildRepo(projectBaseDir)) {
      Ref targetRef = resolveTargetRef(targetBranchName, repo);
      if (targetRef == null) {
        return null;
      }

      if (!isDiffAlgoValid(repo.getConfig())) {
        return null;
      }

      Map<Path, Set<Integer>> changedLines = new HashMap<>();

      try (Git git = newGit(repo)) {
        for (Path path : changedFiles) {
          ChangedLinesComputer computer = new ChangedLinesComputer();
          Path repoRootDir = repo.getDirectory().toPath().getParent();

          try {
            List<DiffEntry> diffEntries = git.diff()
              .setOutputStream(computer.receiver())
              .setOldTree(prepareTreeParser(repo, targetRef))
              .setPathFilter(PathFilter.create(toGitPath(repoRootDir.relativize(path).toString())))
              .call();

            diffEntries
              .stream()
              .filter(diffEntry -> diffEntry.getChangeType() == DiffEntry.ChangeType.ADD
                || diffEntry.getChangeType() == DiffEntry.ChangeType.MODIFY)
              .forEach(diffEntry -> changedLines.put(path, computer.changedLines()));
          } catch (Exception e) {
            LOG.warn("Failed to get changed lines from git for file " + path, e);
          }
        }
      }
      return changedLines;
    } catch (Exception e) {
      LOG.warn("Failed to get changed lines from git", e);
    }
    return null;
  }

  private static String toGitPath(String path) {
    return path.replaceAll(Pattern.quote(File.separator), "/");
  }

  @CheckForNull
  private Ref resolveTargetRef(String targetBranchName, Repository repo) throws IOException {
    Ref targetRef = repo.exactRef("refs/heads/" + targetBranchName);
    if (targetRef == null) {
      targetRef = repo.exactRef("refs/remotes/origin/" + targetBranchName);
    }
    if (targetRef == null) {
      LOG.warn("Could not find ref: {} in refs/heads or refs/remotes/origin", targetBranchName);
      analysisWarnings.addUnique(String.format("Could not find ref '%s' in refs/heads or refs/remotes/origin. "
        + "You may see unexpected issues and changes. "
        + "Please make sure to fetch this ref before pull request analysis.", targetBranchName));
      return null;
    }
    return targetRef;
  }

  @Override
  public Path relativePathFromScmRoot(Path path) {
    RepositoryBuilder builder = getVerifiedRepositoryBuilder(path);
    return builder.getGitDir().toPath().getParent().relativize(path);
  }

  @Override
  public String revisionId(Path path) {
    RepositoryBuilder builder = getVerifiedRepositoryBuilder(path);
    try {
      ObjectId obj = getHead(builder.build()).getObjectId();
      if (obj == null) {
        // can happen on fresh, empty repos
        return null;
      }
      return obj.getName();
    } catch (IOException e) {
      throw new IllegalStateException("I/O error while getting revision ID for path: " + path, e);
    }
  }

  private boolean isDiffAlgoValid(Config cfg) {
    try {
      DiffAlgorithm.getAlgorithm(cfg.getEnum(
        ConfigConstants.CONFIG_DIFF_SECTION, null,
        ConfigConstants.CONFIG_KEY_ALGORITHM,
        DiffAlgorithm.SupportedAlgorithm.HISTOGRAM));
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static AbstractTreeIterator prepareNewTree(Repository repo) throws IOException {
    CanonicalTreeParser treeParser = new CanonicalTreeParser();
    try (ObjectReader objectReader = repo.newObjectReader()) {
      treeParser.reset(objectReader, repo.parseCommit(getHead(repo).getObjectId()).getTree());
    }
    return treeParser;
  }

  private static Ref getHead(Repository repo) throws IOException {
    return repo.exactRef("HEAD");
  }

  private AbstractTreeIterator prepareTreeParser(Repository repo, Ref targetRef) throws IOException {
    try (RevWalk walk = newRevWalk(repo)) {
      walk.markStart(walk.parseCommit(targetRef.getObjectId()));
      walk.markStart(walk.parseCommit(getHead(repo).getObjectId()));
      walk.setRevFilter(RevFilter.MERGE_BASE);
      RevCommit base = walk.parseCommit(walk.next());
      LOG.debug("Merge base sha1: {}", base.getName());
      CanonicalTreeParser treeParser = new CanonicalTreeParser();
      try (ObjectReader objectReader = repo.newObjectReader()) {
        treeParser.reset(objectReader, base.getTree());
      }

      walk.dispose();

      return treeParser;
    }
  }

  Git newGit(Repository repo) {
    return new Git(repo);
  }

  RevWalk newRevWalk(Repository repo) {
    return new RevWalk(repo);
  }

  Repository buildRepo(Path basedir) throws IOException {
    return getVerifiedRepositoryBuilder(basedir).build();
  }

  static RepositoryBuilder getVerifiedRepositoryBuilder(Path basedir) {
    RepositoryBuilder builder = new RepositoryBuilder()
      .findGitDir(basedir.toFile())
      .setMustExist(true);

    if (builder.getGitDir() == null) {
      throw MessageException.of("Not inside a Git work tree: " + basedir);
    }
    return builder;
  }
}

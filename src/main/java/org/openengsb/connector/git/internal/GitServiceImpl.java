/**
 * Licensed to the Austrian Association for Software Tool Integration (AASTI)
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. The AASTI licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.openengsb.connector.git.internal;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LogCommand;
import org.eclipse.jgit.api.RmCommand;
import org.eclipse.jgit.api.TagCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.NoFilepatternException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheCheckout;
import org.eclipse.jgit.errors.UnmergedPathException;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.openengsb.connector.git.domain.GitCommitRef;
import org.openengsb.connector.git.domain.GitTagRef;
import org.openengsb.core.api.AliveState;
import org.openengsb.core.common.AbstractOpenEngSBConnectorService;
import org.openengsb.domain.scm.CommitRef;
import org.openengsb.domain.scm.ScmDomain;
import org.openengsb.domain.scm.ScmException;
import org.openengsb.domain.scm.TagRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterators;

public class GitServiceImpl extends AbstractOpenEngSBConnectorService implements ScmDomain {
    private static final Logger LOGGER = LoggerFactory.getLogger(GitServiceImpl.class);

    private String remoteLocation;
    private File localWorkspace;
    private String watchBranch;
    private FileRepository repository;
    private boolean submodulesHack;

    public GitServiceImpl(String instanceId) {
        super(instanceId);
    }

    @Override
    public AliveState getAliveState() {
        return AliveState.OFFLINE;
    }

    private void submoduleHack(boolean initial) throws Exception {
        LinkedList<String> commands = new LinkedList<String>();

        LOGGER.error("JGit exception caught, activating the submodule hack");

        String s = File.separator;
        File lock = new File(localWorkspace + s + ".git" + s + "index.lock");
        lock.delete();

        commands.add("git");
        commands.add("reset");
        commands.add("--hard");
        if (!initial) {
            commands.add("origin/" + watchBranch);
        }

        ProcessBuilder builder = new ProcessBuilder(commands);
        builder.directory(localWorkspace);
        int exit = builder.start().waitFor();
        LOGGER.debug("Git command exited with status " + exit);
    }

    @Override
    public List<CommitRef> update() {
        List<CommitRef> commits = new ArrayList<CommitRef>();
        try {
            if (repository == null) {
                prepareWorkspace();
                initRepository();
            }
            Git git = new Git(repository);
            AnyObjectId oldHead = repository.resolve(Constants.HEAD);
            if (oldHead == null) {
                LOGGER.debug("Local repository is empty. Fetching remote repository.");
                FetchResult fetchResult = doRemoteUpdate();
                if (fetchResult.getTrackingRefUpdate(Constants.R_REMOTES + "origin/" + watchBranch) == null) {
                    LOGGER.debug("Nothing to fetch from remote repository.");
                    return null;
                }
                try {
                    doCheckout(fetchResult);
                } catch (Exception e2) {
                    if (!submodulesHack) {
                        throw e2;
                    }
                    LOGGER.error("submodule hack caught exception", e2);
                    submoduleHack(true);
                }
            } else {
                LOGGER.debug("Local repository exists. Pulling remote repository.");
                try {
                    git.pull().call();
                } catch (Exception e2) {
                    if (!submodulesHack) {
                        throw e2;
                    }
                    submoduleHack(false);
                    LOGGER.error("submodule hack caught exception", e2);
                    repository.scanForRepoChanges();
                }
            }
            AnyObjectId newHead = repository.resolve(Constants.HEAD);
            if (newHead == null) {
                LOGGER.debug("New HEAD of local repository doesnt exist.");
                return null;
            }
            if (newHead != oldHead) {
                LogCommand logCommand = git.log();
                if (oldHead == null) {
                    LOGGER.debug("Retrieving revisions from HEAD [{}] on", newHead.name());
                    logCommand.add(newHead);
                } else {
                    LOGGER.debug("Retrieving revisions in range [{}, {}]", newHead.name(), oldHead.name());
                    logCommand.addRange(oldHead, newHead);
                }
                Iterable<RevCommit> revisions = logCommand.call();
                for (RevCommit revision : revisions) {
                    commits.add(new GitCommitRef(revision));
                }
            }
        } catch (Exception e) {
            throw new ScmException(e);
        }
        return commits;
    }

    /**
     * Checks if the {@code localWorkspace} is set and creates the relevant directories if necessary.
     */
    private void prepareWorkspace() {
        if (localWorkspace == null) {
            throw new ScmException("Local workspace not set.");
        }
        if (!localWorkspace.isDirectory()) {
            tryCreateLocalWorkspace();
        }
    }

    /**
     * Creates the directories necessary for the workspace.
     */
    private void tryCreateLocalWorkspace() {
        if (!localWorkspace.exists()) {
            localWorkspace.mkdirs();
        }
        if (!localWorkspace.exists()) {
            throw new ScmException("Local workspace directory '" + localWorkspace
                    + "' does not exist and cannot be created.");
        }
        if (!localWorkspace.isDirectory()) {
            throw new ScmException("Local workspace directory '" + localWorkspace + "' is not a valid directory.");
        }
    }

    /**
     * Initializes the {@link FileRepository} or creates a new own if it does not exist.
     */
    private void initRepository() throws IOException {
        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        builder.setWorkTree(localWorkspace);
        repository = builder.build();
        if (!new File(localWorkspace, ".git").isDirectory()) {
            repository.create();
            repository.getConfig().setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*");
            repository.getConfig().setString("remote", "origin", "url", remoteLocation);
            repository.getConfig().setString("branch", watchBranch, "remote", "origin");
            repository.getConfig().setString("branch", watchBranch, "merge", "refs/heads/" + watchBranch);
            repository.getConfig().save();
        }
    }

    protected void doCheckout(FetchResult fetchResult) throws IOException {
        final Ref head = fetchResult.getAdvertisedRef(Constants.R_HEADS + watchBranch);
        final RevWalk rw = new RevWalk(repository);
        final RevCommit mapCommit;
        try {
            LOGGER.debug("Mapping received reference to respective commit.");
            mapCommit = rw.parseCommit(head.getObjectId());
        } finally {
            rw.release();
        }

        final RefUpdate u;

        boolean detached = !head.getName().startsWith(Constants.R_HEADS);
        LOGGER.debug("Updating HEAD reference to revision [{}]", mapCommit.getId().name());
        u = repository.updateRef(Constants.HEAD, detached);
        u.setNewObjectId(mapCommit.getId());
        u.forceUpdate();

        DirCacheCheckout dirCacheCheckout = new DirCacheCheckout(repository, null, repository.lockDirCache(), mapCommit
                .getTree());
        dirCacheCheckout.setFailOnConflict(true);
        boolean checkoutResult = dirCacheCheckout.checkout();
        LOGGER.debug("Checked out new repository revision to working directory");
        if (!checkoutResult) {
            throw new IOException("Internal error occured on checking out files");
        }
    }

    protected FetchResult doRemoteUpdate() throws IOException {
        List<RemoteConfig> remoteConfig = null;
        try {
            LOGGER.debug("Fetching remote configurations from repository configuration");
            remoteConfig = RemoteConfig.getAllRemoteConfigs(repository.getConfig());
        } catch (URISyntaxException e) {
            throw new ScmException(e);
        }

        LOGGER.debug("Opening transport to {}", remoteConfig.get(0).getName());
        Transport transport = Transport.open(repository, remoteConfig.get(0));
        try {
            LOGGER.debug("Fetching content from remote repository");
            return transport.fetch(NullProgressMonitor.INSTANCE, null);
        } finally {
            if (transport != null) {
                transport.close();
            }
        }
    }

    @Override
    public byte[] export() {
        initializeIfEmpty();
        return packLocalWorkspaceToByteArray();
    }

    private byte[] packLocalWorkspaceToByteArray() {
        LOGGER.debug("Exporting repository to archive");
        ByteArrayOutputStream byteArrayOutput = new ByteArrayOutputStream();
        ZipArchiveOutputStream zos = new ZipArchiveOutputStream(byteArrayOutput);
        try {
            packRepository(localWorkspace, zos);
        } catch (IOException e) {
            throw new ScmException("could not pack repository", e);
        } finally {
            IOUtils.closeQuietly(zos);
        }
        try {
            return byteArrayOutput.toByteArray();
        } finally {
            IOUtils.closeQuietly(byteArrayOutput);
        }
    }

    private void initializeIfEmpty() {
        if (repository == null) {
            try {
                initRepository();
            } catch (IOException e) {
                throw new ScmException(e);
            }
        }
    }

    @Override
    public byte[] export(CommitRef ref) {
        RevWalk rw = null;
        try {
            if (repository == null) {
                initRepository();
            }

            LOGGER.debug("Resolving HEAD and reference [{}]", ref.getStringRepresentation());
            AnyObjectId headId = repository.resolve(Constants.HEAD);
            AnyObjectId refId = repository.resolve(ref.getStringRepresentation());
            if (headId == null || refId == null) {
                throw new ScmException("HEAD or reference [" + ref.getStringRepresentation() + "] doesn't exist.");
            }
            rw = new RevWalk(repository);
            RevCommit head = rw.parseCommit(headId);
            RevCommit commit = rw.parseCommit(refId);

            LOGGER.debug("Checking out working copy of revision");
            checkoutIndex(commit);

            byte[] result = packLocalWorkspaceToByteArray();

            LOGGER.debug("Checking out working copy of former HEAD revision");
            checkoutIndex(head);
            return result;
        } catch (IOException e) {
            throw new ScmException(e);
        } finally {
            if (rw != null) {
                rw.release();
            }
        }
    }

    /**
     * Packs the files and directories of a passed {@link File} to a passed {@link ArchiveOutputStream}.
     *
     * @throws IOException
     */
    private void packRepository(File source, ArchiveOutputStream aos) throws IOException {
        int bufferSize = 2048;
        byte[] readBuffer = new byte[bufferSize];
        int bytesIn = 0;
        File[] files = source.listFiles(new FileFilter() {
            @Override
            public boolean accept(File pathname) {
                return !pathname.getName().equals(Constants.DOT_GIT);
            }
        });
        for (File file : files) {
            if (file.isDirectory()) {
                ArchiveEntry ae = aos.createArchiveEntry(file, getRelativePath(file.getAbsolutePath()));
                aos.putArchiveEntry(ae);
                aos.closeArchiveEntry();
                packRepository(file, aos);
            } else {
                FileInputStream fis = new FileInputStream(file);
                ArchiveEntry ae = aos.createArchiveEntry(file, getRelativePath(file.getAbsolutePath()));
                aos.putArchiveEntry(ae);
                while ((bytesIn = fis.read(readBuffer)) != -1) {
                    aos.write(readBuffer, 0, bytesIn);
                }
                aos.closeArchiveEntry();
                fis.close();
            }
        }
    }

    private void checkoutIndex(RevCommit commit) {
        DirCache dc = null;
        try {
            dc = repository.lockDirCache();
            DirCacheCheckout checkout = new DirCacheCheckout(repository, dc, commit.getTree());
            checkout.setFailOnConflict(false);
            checkout.checkout();
        } catch (IOException e) {
            throw new ScmException(e);
        } finally {
            if (dc != null) {
                dc.unlock();
            }
        }
    }

    public void setRemoteLocation(String remoteLocation) {
        // https://bugs.eclipse.org/bugs/show_bug.cgi?id=323571
        if (remoteLocation.startsWith("file:/") && !remoteLocation.startsWith("file:///")) {
            remoteLocation = remoteLocation.replace("file:/", "file:///");
        }
        this.remoteLocation = remoteLocation;
        if (repository != null) {
            try {
                repository.getConfig().setString("remote", "origin", "url", remoteLocation);
                repository.getConfig().save();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public void setLocalWorkspace(String localWorkspace) {
        String realWorkspace = localWorkspace;
        if (!new File(realWorkspace).isAbsolute()) {
            realWorkspace = System.getProperty("karaf.data") + realWorkspace;
        }
        this.localWorkspace = new File(realWorkspace);
    }

    public void setWatchBranch(String watchBranch) {
        this.watchBranch = watchBranch;
    }

    public FileRepository getRepository() {
        if (repository == null) {
            prepareWorkspace();
            try {
                initRepository();
            } catch (IOException e) {
                throw new ScmException(e);
            }
        }
        return repository;
    }

    @Override
    public boolean exists(String arg0) {
        try {
            AnyObjectId id = repository.resolve(Constants.HEAD);
            RevCommit commit = new RevWalk(repository).parseCommit(id);
            LOGGER.debug("Looking up file {} in HEAD revision", arg0);
            TreeWalk treeWalk = TreeWalk.forPath(repository, arg0, new AnyObjectId[]{ commit.getTree() });
            if (treeWalk == null) {
                return false;
            }
            ObjectId objectId = treeWalk.getObjectId(treeWalk.getTreeCount() - 1);
            LOGGER.debug("File {} received commit id {} at commit", arg0, objectId.name());
            return !objectId.equals(ObjectId.zeroId());
        } catch (Exception e) {
            throw new ScmException(e);
        }
    }

    @Override
    public byte[] get(String file) {
        initializeIfEmpty();
        TreeWalk treeWalk;
        try {
            AnyObjectId id = repository.resolve(Constants.HEAD);
            RevCommit commit = new RevWalk(repository).parseCommit(id);
            LOGGER.debug("Looking up file {} in HEAD revision", file);
            treeWalk = TreeWalk.forPath(repository, file, new AnyObjectId[]{ commit.getTree() });
        } catch (IOException e) {
            throw new ScmException("Error while walking repository tree", e);
        }
        if (treeWalk == null) {
            return null;
        }
        ObjectId objectId = treeWalk.getObjectId(treeWalk.getTreeCount() - 1);
        if (objectId == ObjectId.zeroId()) {
            LOGGER.debug("File {} couldn't be found in HEAD revision", file);
            return null;
        }
        return packObjectIdToByteArray(objectId);
    }

    private byte[] packObjectIdToByteArray(ObjectId objectId) {
        LOGGER.debug("Creating file from saved repository content");
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try {
            os.write(repository.open(objectId).getCachedBytes());
        } catch (IOException e) {
            throw new ScmException("Error reading file", e);
        } finally {
            IOUtils.closeQuietly(os);
        }
        return os.toByteArray();
    }

    @Override
    public boolean exists(String arg0, CommitRef arg1) {
        try {
            AnyObjectId id = repository.resolve(arg1.getStringRepresentation());
            RevCommit commit = new RevWalk(repository).parseCommit(id);
            LOGGER.debug("Looking up file {} in revision {}", arg0, arg1.getStringRepresentation());
            TreeWalk treeWalk = TreeWalk.forPath(repository, arg0, new AnyObjectId[]{ commit.getTree() });
            if (treeWalk == null) {
                return false;
            }
            ObjectId objectId = treeWalk.getObjectId(treeWalk.getTreeCount() - 1);
            LOGGER.debug("File {} received commit id {} at commit", arg0, objectId.name());
            return !objectId.equals(ObjectId.zeroId());
        } catch (Exception e) {
            throw new ScmException(e);
        }
    }

    @Override
    public byte[] get(String file, CommitRef ref) {
        initializeIfEmpty();
        Preconditions.checkNotNull(ref.getStringRepresentation());

        TreeWalk treeWalk;
        try {
            AnyObjectId id = repository.resolve(ref.getStringRepresentation());
            RevCommit commit = new RevWalk(repository).parseCommit(id);
            LOGGER.debug("Looking up file {} in revision {}", file, ref.getStringRepresentation());
            treeWalk = TreeWalk.forPath(repository, file, new AnyObjectId[]{ commit.getTree() });
        } catch (IOException e) {
            throw new ScmException("Error while walking repository tree", e);
        }
        if (treeWalk == null) {
            return null;
        }
        ObjectId objectId = treeWalk.getObjectId(treeWalk.getTreeCount() - 1);
        if (objectId == ObjectId.zeroId()) {
            return null;
        }
        return packObjectIdToByteArray(objectId);
    }

    @Override
    public CommitRef getHead() {
        try {
            if (repository == null) {
                initRepository();
            }
            AnyObjectId id = repository.resolve(Constants.HEAD);
            RevCommit commit = new RevWalk(repository).parseCommit(id);
            LOGGER.debug("Resolved HEAD to commit {}", commit.getId().name());
            return new GitCommitRef(commit);
        } catch (IOException e) {
            if (repository != null) {
                repository.close();
                repository = null;
            }
            throw new ScmException(e);
        }
    }

    @Override
    public CommitRef add(String comment, String path, byte[] content) {
        File file = new File(path);
        Preconditions.checkArgument(!file.isAbsolute(), "must not commit to absolute paths");
        if (repository == null) {
            prepareWorkspace();
            try {
                initRepository();
            } catch (IOException e) {
                if (repository != null) {
                    repository.close();
                }
                throw new ScmException(e);
            }
        }

        Git git = new Git(repository);
        AddCommand add = git.add();
        safelyUpdateFileContent(path, content);

        LOGGER.debug("Adding file {} in working directory to repository", path);
        add.addFilepattern(path);

        try {
            add.call();
        } catch (NoFilepatternException e) {
            throw new ScmException("add-command failed", e);
        }
        LOGGER.debug("Committing added files with comment '{}'", comment);
        try {
            return new GitCommitRef(git.commit().setMessage(comment).call());
        } catch (GitAPIException e) {
            throw new ScmException("Error while committing to git-repo", e);
        } catch (UnmergedPathException e) {
            throw new ScmException(e);
        }

    }

    @Override
    public CommitRef add(String comment, Map<String, byte[]> files) {
        if (repository == null) {
            prepareWorkspace();
            try {
                initRepository();
            } catch (IOException e) {
                if (repository != null) {
                    repository.close();
                }
                throw new ScmException(e);
            }
        }
        boolean allRelative = Iterators.all(files.keySet().iterator(), new Predicate<String>() {
            @Override
            public boolean apply(String path) {
                return !new File(path).isAbsolute();
            }

        });
        Preconditions.checkArgument(allRelative, "must not commit to absolute paths");

        Git git = new Git(repository);
        AddCommand add = git.add();
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            String path = entry.getKey();
            byte[] content = entry.getValue();
            safelyUpdateFileContent(path, content);
            LOGGER.debug("Adding file {} in working directory to repository", path);
            add.addFilepattern(path);
        }

        try {
            add.call();
        } catch (NoFilepatternException e) {
            throw new ScmException("add-command failed", e);
        }
        LOGGER.debug("Committing added files with comment '{}'", comment);
        try {
            return new GitCommitRef(git.commit().setMessage(comment).call());
        } catch (GitAPIException e) {
            throw new ScmException("Error while committing to git-repo", e);
        } catch (UnmergedPathException e) {
            throw new ScmException(e);
        }
    }

    private void safelyUpdateFileContent(String path, byte[] content) {
        File repoFile = new File(localWorkspace, path);
        File backupFile = null;

        try {
            File newFile = File.createTempFile("openengsb-connector-git", "new");
            FileUtils.writeByteArrayToFile(newFile, content);
            if (repoFile.exists()) {
                backupFile = File.createTempFile("openengsb-connector-git", "old");
                backupFile.delete();
                FileUtils.moveFile(repoFile, backupFile);
                try {
                    FileUtils.moveFile(newFile, repoFile);
                } catch (IOException e) {
                    FileUtils.moveFile(backupFile, repoFile);
                }
                FileUtils.deleteQuietly(backupFile);
                backupFile = null;
            } else {
                FileUtils.moveFile(newFile, repoFile);
            }
        } catch (IOException e) {
            throw new ScmException("error while creating or updating file", e);
        }

    }

    /**
     * Returns the relative path of an absolute {@code filePath} in comparison to the working directory of the
     * repository.
     */
    private String getRelativePath(String filePath) {
        final String repoPath = repository.getWorkTree().getAbsolutePath();
        if (filePath.startsWith(repoPath)) {
            return filePath.substring(repoPath.length() + 1);
        } else {
            throw new ScmException("File is not in working directory.");
        }
    }

    @Override
    public CommitRef remove(String comment, String... file) {
        if (file.length == 0) {
            LOGGER.debug("No files to add in list");
            return null;
        }
        if (repository == null) {
            prepareWorkspace();
            try {
                initRepository();
            } catch (IOException e) {
                if (repository != null) {
                    repository.close();
                    repository = null;
                }
                throw new ScmException(e);
            }
        }

        Git git = new Git(repository);
        RmCommand rm = git.rm();
        try {
            for (String toCommit : file) {
                File file2 = new File(localWorkspace, toCommit);
                if (!file2.exists()) {
                    throw new ScmException("File " + toCommit + " is not a valid file to commit.");
                }
                LOGGER.debug("Removing file {} in working directory from repository", toCommit);
                rm.addFilepattern(toCommit);
            }

            rm.call();
            LOGGER.debug("Committing removed files with comment '{}'", comment);
            return new GitCommitRef(git.commit().setMessage(comment).call());
        } catch (Exception e) {
            throw new ScmException(e);
        }
    }

    @Override
    public TagRef tagRepo(String tagName) {
        try {
            if (repository == null) {
                initRepository();
            }
            TagCommand tag = new Git(repository).tag();
            LOGGER.debug("Tagging HEAD with name '{}'", tagName);
            return new GitTagRef(tag.setName(tagName).call());
        } catch (Exception e) {
            throw new ScmException(e);
        }
    }

    @Override
    public TagRef tagRepo(String tagName, CommitRef ref) {
        try {
            if (repository == null) {
                initRepository();
            }
            AnyObjectId commitRef = repository.resolve(ref.getStringRepresentation());
            if (commitRef == null) {
                LOGGER.debug("Couldnt resolve reference {} in repository", ref.getStringRepresentation());
                return null;
            }
            RevWalk walk = new RevWalk(repository);
            RevCommit revCommit = walk.parseCommit(commitRef);
            TagCommand tag = new Git(repository).tag();
            tag.setName(tagName).setObjectId(revCommit);
            LOGGER.debug("Tagging revision {} with name '{}'", ref.getStringRepresentation(), tagName);
            return new GitTagRef(tag.call());
        } catch (Exception e) {
            throw new ScmException(e);
        }
    }

    @Override
    public CommitRef getCommitRefForTag(TagRef ref) {
        try {
            if (repository == null) {
                initRepository();
            }
            AnyObjectId tagRef = repository.resolve(ref.getStringRepresentation());
            if (tagRef == null) {
                LOGGER.debug("Couldnt resolve reference {} in repository", ref.getStringRepresentation());
                return null;
            }
            RevWalk walk = new RevWalk(repository);
            RevTag revTag = walk.parseTag(tagRef);
            CommitRef commitRef = null;
            if (revTag.getObject() instanceof RevCommit) {
                commitRef = new GitCommitRef((RevCommit) revTag.getObject());
                LOGGER.debug("Resolved reference {} to commit {}", ref.getStringRepresentation(),
                        commitRef.getStringRepresentation());
            }
            return commitRef;
        } catch (IOException e) {
            throw new ScmException(e);
        }
    }

    public void setSubmodulesHack(String string) {
        submodulesHack = new Boolean(string).booleanValue();
    }
}

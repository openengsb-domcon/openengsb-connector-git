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

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TagCommand;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.Test;
import org.openengsb.connector.git.domain.GitCommitRef;
import org.openengsb.connector.git.domain.GitTagRef;
import org.openengsb.domain.scm.CommitRef;
import org.openengsb.domain.scm.ScmException;
import org.openengsb.domain.scm.TagRef;

public class GitServiceImplTest extends AbstractGitServiceImpl {

    @Test
    public void updateWithEmptyWorkspace_shouldCloneRemoteRepository() throws Exception {
        service.startPoller();
        List<CommitRef> commits = service.update();
        assertThat(commits.size(), is(1));
        ObjectId remote = service.getRepository().resolve("refs/remotes/origin/master");
        assertThat(remote, notNullValue());
        assertThat(remote, is(remoteRepository.resolve("refs/heads/master")));
        assertThat(commits.get(0).getStringRepresentation(),
            is(service.getRepository().resolve(Constants.HEAD).name()));
    }

    @Test
    public void updateAgainFromSameRepoState_shouldReturnFalseFromPoll() {
        service.startPoller();
        List<CommitRef> updateOne = service.update();
        assertThat(updateOne.size(), is(1));
        List<CommitRef> updateTwo = service.update();
        assertThat(updateTwo.size(), is(0));
    }

    @Test
    public void update_shouldPullChangesIntoLocalBranch() {
        service.startPoller();
        List<CommitRef> updateOne = service.update();
        assertThat(updateOne.size(), is(1));
        assertThat(new File(localDirectory, "testfile").isFile(), is(true));
    }

    @Test
    public void updateFromUpdatedRemote_shouldUpdateLocal() throws Exception {
        service.startPoller();
        List<CommitRef> updateOne = service.update();
        assertThat(updateOne.size(), is(1));
        Git git = new Git(remoteRepository);
        RepositoryFixture.addFile(git, "second");
        RepositoryFixture.commit(git, "second commit");
        assertThat(new File(localDirectory, "second").isFile(), is(false));
        List<CommitRef> updateTwo = service.update();
        assertThat(updateTwo.size(), is(1));
        assertThat(new File(localDirectory, "second").isFile(), is(true));
        List<CommitRef> updateThree = service.update();
        assertThat(updateThree.size(), is(0));
    }

    @Test
    public void updateWithNoExistingWatchBranch_shouldReturnFalse() {
        service.setWatchBranch("unknown");
        service.startPoller();
        List<CommitRef> updateOne = service.update();
        assertThat(updateOne, nullValue());
    }

    @Test
    public void exportRepository_shouldReturnZipFileWithRepoEntries() throws Exception {
        service.startPoller();

        String dir = "testDirectory";
        String file = "myTestFile";
        File parent = new File(localDirectory, dir);
        parent.mkdirs();
        File child = new File(parent, file);
        FileWriter fw = new FileWriter(child);
        fw.write(file + "\n");
        fw.close();

        String pattern = dir + "/" + file;
        Git git = new Git(remoteRepository);
        git.add().addFilepattern(pattern).call();
        git.commit().setMessage("My msg").call();
        service.update();

        byte[] export = service.export();
        ZipFile zipFile = createZipFileFromByteArray(export);
        assertThat(zipFile.getEntry("testfile").getName(), is("testfile"));
        assertThat(zipFile.getEntry(dir + "/").getName(), is(dir + "/"));
        assertThat(zipFile.getEntry(dir + File.separator + file).getName(), is(dir + File.separator + file));
    }

    private ZipFile createZipFileFromByteArray(byte[] export) throws IOException, ZipException {
        File tmpFile = File.createTempFile("git-repo-export-test", "zip");
        FileUtils.writeByteArrayToFile(tmpFile, export);
        ZipFile zipFile = new ZipFile(tmpFile);
        return zipFile;
    }

    public void exportRepositoryByRef_shouldReturnZipFileWithRepoEntries() throws Exception {
        service.startPoller();

        String dir = "testDirectory";
        String file = "myTestFile";
        File parent = new File(localDirectory, dir);
        parent.mkdirs();
        File child = new File(parent, file);
        FileWriter fw = new FileWriter(child);
        fw.write(file + "\n");
        fw.close();

        String pattern = dir + "/" + file;
        Git git = new Git(remoteRepository);
        git.add().addFilepattern(pattern).call();
        git.commit().setMessage("My msg").call();

        AnyObjectId headId = remoteRepository.resolve(Constants.HEAD);
        RevWalk rw = new RevWalk(remoteRepository);
        RevCommit head = rw.parseCommit(headId);
        rw.release();
        service.update();

        ZipFile zipFile = createZipFileFromByteArray(service.export(new GitCommitRef(head)));
        assertThat(zipFile.getEntry("testfile").getName(), is("testfile"));
        assertThat(zipFile.getEntry(dir + "/").getName(), is(dir + "/"));
        assertThat(zipFile.getEntry(dir + "\\" + file).getName(), is(dir + "\\" + file));
    }

    @Test
    public void getFileFromHeadCommit_shouldReturnFileWithCorrectContent() throws Exception {
        service.startPoller();

        String fileName = "myFile";
        Git git = new Git(remoteRepository);
        RepositoryFixture.addFile(git, fileName);
        RepositoryFixture.commit(git, "Commited my file");
        service.update();

        byte[] file = service.get(fileName);
        List<String> lines = IOUtils.readLines(new ByteArrayInputStream(file));
        assertThat(lines.get(0), is(fileName));
    }

    @Test
    public void getFileFromCommitByRef_shouldReturnFileWithCorrectContent() throws Exception {
        service.startPoller();
        String fileName = "myFile";

        AnyObjectId head = remoteRepository.resolve(Constants.HEAD);
        RevWalk rw = new RevWalk(remoteRepository);
        RevCommit headCommit = rw.parseCommit(head);
        rw.release();
        Git git = new Git(remoteRepository);
        RepositoryFixture.addFile(git, fileName);
        RepositoryFixture.commit(git, "Commited my file");
        service.update();

        byte[] file = service.get("testfile", new GitCommitRef(headCommit));
        List<String> lines = IOUtils.readLines(new ByteArrayInputStream(file));
        assertThat(lines.get(0), is("testfile"));
    }

    @Test(expected = NullPointerException.class)
    public void getFileFromCommitByNonExistingRef_shouldThrowSCMException() throws Exception {
        service.startPoller();
        byte bytecontent[] = service.get("testfile", new GitCommitRef(null));
        String content = new String(bytecontent);
        assertThat(content, is("testfile"));
    }

    @Test
    public void addFile_shouldReturnHeadReference() throws IOException {
        service.startPoller();
        byte[] content = "testfile".getBytes();
        CommitRef commitRef = service.add("testcomment", "testfile", content);
        assertThat(commitRef, notNullValue());
        localRepository = service.getRepository();
        assertThat(commitRef.getStringRepresentation(), is(localRepository.resolve(Constants.HEAD).name()));
    }

    @Test
    public void removeFileFromRepository_shouldReturnNewCommitRefAndDeleteFile() throws Exception {
        service.startPoller();

        AnyObjectId headId = remoteRepository.resolve(Constants.HEAD);
        RevWalk rw = new RevWalk(remoteRepository);
        RevCommit head = rw.parseCommit(headId);
        rw.release();
        service.update();

        CommitRef ref = service.remove("remove", "testfile");
        assertThat(head.name(), not(ref.getStringRepresentation()));

        File removed = new File(localDirectory, "testfile");
        assertThat(removed.exists(), is(false));
    }

    @Test
    public void removeDirectoryFromRepository_shouldReturnNewCommitRefAndDeleteFiles() throws Exception {
        service.startPoller();

        String dir = "testDirectory";
        String file = "testFile";
        File parent = new File(remoteDirectory, dir);
        parent.mkdirs();
        File child = new File(parent, file);
        FileWriter fw = new FileWriter(child);
        fw.write(file + "\n");
        fw.close();
        assertThat(child.exists(), is(true));

        Git git = new Git(remoteRepository);
        git.add().addFilepattern(dir + "/" + file).call();
        git.commit().setMessage("comment").call();

        AnyObjectId headId = remoteRepository.resolve(Constants.HEAD);
        RevWalk rw = new RevWalk(remoteRepository);
        RevCommit head = rw.parseCommit(headId);
        rw.release();
        service.update();

        CommitRef ref = service.remove("remove", dir);
        assertThat(head.name(), not(ref.getStringRepresentation()));

        File removed = new File(localDirectory, dir + "/" + file);
        assertThat(removed.exists(), is(false));
    }

    @Test
    public void existsFilenameInHeadCommit_shouldReturnTrue() throws IOException {
        service.startPoller();
        service.add("testcomment", "commitOne", "1".getBytes());
        service.add("testcomment", "file2", "2".getBytes());
        assertThat(service.exists("commitOne"), is(true));
    }

    @Test
    public void existsFilenameInReferencedCommit_shouldReturnTrue() throws IOException {
        service.startPoller();
        CommitRef commitRef = service.add("testcomment", "commitOne", "1".getBytes());
        service.add("testcomment", "file2", "2".getBytes());
        assertThat(service.exists("commitOne", commitRef), is(true));
    }

    @Test
    public void existsFilenameOfNotExistingFile_shouldReturnFalse() throws IOException {
        service.startPoller();
        service.add("testcomment", "commitOne", "1".getBytes());
        service.add("testcomment", "commitTwo", "2".getBytes());
        assertThat(service.exists("commitThree"), is(false));
    }

    @Test
    public void existsFilenameInPriorCommitToFilecommit_shouldReturnFalse() throws IOException {
        service.startPoller();
        CommitRef commitRef = service.add("testcomment", "commitOne", "1".getBytes());
        service.add("testcomment", "file2", "2".getBytes());
        assertThat(service.exists("file2", commitRef), is(false));
    }

    @Test
    public void tagHeadWithName_shouldReturnTagRefWithName() throws Exception {
        /* Don't start the poller in this test, it doesn't configure a proper remote */
        localRepository = RepositoryFixture.createRepository(localDirectory);
        String tagName = "newTag";
        TagRef tag = service.tagRepo(tagName);
        assertThat(tag, notNullValue());
        assertThat(tagName, is(tag.getTagName()));
        AnyObjectId tagId = localRepository.resolve(tagName);
        assertThat(tagId.name(), is(tag.getStringRepresentation()));
        RevTag revTag = new RevWalk(localRepository).parseTag(tagId);
        AnyObjectId head = localRepository.resolve(Constants.HEAD);
        assertThat(revTag.getObject().name(), is(head.name()));
    }

    @Test(expected = ScmException.class)
    public void tagHeadAgainWithSameName_shouldThrowSCMException() throws Exception {
        /* Don't start the poller in this test, it doesn't configure a proper remote */
        localRepository = RepositoryFixture.createRepository(localDirectory);
        String tagName = "newTag";
        TagRef tag = service.tagRepo(tagName);
        assertThat(tag, notNullValue());
        assertThat(tagName, is(tag.getTagName()));
        service.tagRepo(tagName);
    }

    @Test(expected = ScmException.class)
    public void tagEmptyRepoWithName_shouldThrowSCMException() throws Exception {
        /* FIXME: This test needs its own empty local repo, don't start the poller */
        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        localRepository = builder.setWorkTree(localDirectory).build();
        localRepository.create();
        service.tagRepo("newTag");
    }

    @Test
    public void tagCommitRefWithName_shouldReturnTagRefWithName() throws Exception {
        /* Don't start the poller in this test, it doesn't configure a proper remote */
        localRepository = RepositoryFixture.createRepository(localDirectory);
        RevWalk walk = new RevWalk(localRepository);
        RevCommit head = walk.lookupCommit(localRepository.resolve(Constants.HEAD));
        CommitRef ref = new GitCommitRef(head);
        String tagName = "newTag";
        TagRef tag = service.tagRepo(tagName, ref);
        assertThat(tag, notNullValue());
        assertThat(tagName, is(tag.getTagName()));
        AnyObjectId tagId = localRepository.resolve(tagName);
        assertThat(tagId.name(), is(tag.getStringRepresentation()));
        RevTag revTag = new RevWalk(localRepository).parseTag(tagId);
        assertThat(revTag.getObject().name(), is(head.name()));
    }

    @Test
    public void getCommitRefForTagRef_shouldReturnTaggedCommitRef() throws Exception {
        /* Don't start the poller in this test, it doesn't configure a proper remote */
        localRepository = RepositoryFixture.createRepository(localDirectory);
        RevWalk walk = new RevWalk(localRepository);
        RevCommit head = walk.lookupCommit(localRepository.resolve(Constants.HEAD));
        String tagName = "newTag";
        TagCommand tagCommand = new Git(localRepository).tag();
        TagRef tag = new GitTagRef(tagCommand.setName(tagName).call());
        assertThat(tag, notNullValue());
        assertThat(tagName, is(tag.getTagName()));
        CommitRef commitRef = service.getCommitRefForTag(tag);
        assertThat(head.name(), is(commitRef.getStringRepresentation()));
    }

    @Test
    public void changeRemoteLocation_ShouldChangeRemoteLocation() {
        service.startPoller();
        service.setRemoteLocation("testLoc");
        assertThat(service.getRepository().getConfig().getString("remote", "origin", "url"), is("testLoc"));
    }
}

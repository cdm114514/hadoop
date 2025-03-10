/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.server.namenode;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.server.common.Storage;
import org.apache.hadoop.hdfs.server.common.Storage.StorageDirectory;
import org.apache.hadoop.hdfs.server.common.StorageInfo;

import com.google.common.base.Preconditions;
import org.apache.hadoop.io.IOUtils;

public abstract class NNUpgradeUtil
{

    private static final Log LOG = LogFactory.getLog(NNUpgradeUtil.class);

    /**
     * Return true if this storage dir can roll back to the previous storage
     * state, false otherwise. The NN will refuse to run the rollback operation
     * unless at least one JM or fsimage storage directory can roll back.
     *
     * @param storage             the storage info for the current state
     * @param prevStorage         the storage info for the previous (unupgraded) state
     * @param targetLayoutVersion the layout version we intend to roll back to
     * @return true if this JM can roll back, false otherwise.
     * @throws IOException in the event of error
     */
    static boolean canRollBack(StorageDirectory sd, StorageInfo storage, StorageInfo prevStorage, int targetLayoutVersion) throws IOException
    {
        File prevDir = sd.getPreviousDir();
        if (!prevDir.exists())
        {  // use current directory then
            LOG.info("Storage directory " + sd.getRoot() + " does not contain previous fs state.");
            // read and verify consistency with other directories
            storage.readProperties(sd);
            return false;
        }

        // read and verify consistency of the prev dir
        prevStorage.readPreviousVersionProperties(sd);

        if (prevStorage.getLayoutVersion() != targetLayoutVersion)
        {
            throw new IOException("Cannot rollback to storage version " + prevStorage.getLayoutVersion() + " using this version of the NameNode, which uses storage version " + targetLayoutVersion + ". " + "Please use the previous version of HDFS to perform the rollback.");
        }

        return true;
    }

    /**
     * Finalize the upgrade. The previous dir, if any, will be renamed and
     * removed. After this is completed, rollback is no longer allowed.
     *
     * @param sd the storage directory to finalize
     * @throws IOException in the event of error
     */
    static void doFinalize(StorageDirectory sd) throws IOException
    {
        File prevDir = sd.getPreviousDir();
        if (!prevDir.exists())
        { // already discarded
            LOG.info("Directory " + prevDir + " does not exist.");
            LOG.info("Finalize upgrade for " + sd.getRoot() + " is not required.");
            return;
        }
        LOG.info("Finalizing upgrade of storage directory " + sd.getRoot());
        Preconditions.checkState(sd.getCurrentDir().exists(), "Current directory must exist.");
        final File tmpDir = sd.getFinalizedTmp();
        // rename previous to tmp and remove
        NNStorage.rename(prevDir, tmpDir);
        NNStorage.deleteDir(tmpDir);
        LOG.info("Finalize upgrade for " + sd.getRoot() + " is complete.");
    }

    /**
     * Perform any steps that must succeed across all storage dirs/JournalManagers
     * involved in an upgrade before proceeding onto the actual upgrade stage. If
     * a call to any JM's or local storage dir's doPreUpgrade method fails, then
     * doUpgrade will not be called for any JM. The existing current dir is
     * renamed to previous.tmp, and then a new, empty current dir is created.
     *
     * @param conf configuration for creating {@link EditLogFileOutputStream}
     * @param sd   the storage directory to perform the pre-upgrade procedure.
     * @throws IOException in the event of error
     */
    static void doPreUpgrade(Configuration conf, StorageDirectory sd) throws IOException
    {
        LOG.info("Starting upgrade of storage directory " + sd.getRoot());

        // rename current to tmp
        renameCurToTmp(sd);

        final File curDir = sd.getCurrentDir();
        final File tmpDir = sd.getPreviousTmp();
        List<String> fileNameList = IOUtils.listDirectory(tmpDir, new FilenameFilter()
        {
            @Override
            public boolean accept(File dir, String name)
            {
                return dir.equals(tmpDir) && name.startsWith(NNStorage.NameNodeFile.EDITS.getName());
            }
        });

        for (String s : fileNameList)
        {
            File prevFile = new File(tmpDir, s);
            File newFile = new File(curDir, prevFile.getName());
            Files.createLink(newFile.toPath(), prevFile.toPath());
        }
    }

    /**
     * Rename the existing current dir to previous.tmp, and create a new empty
     * current dir.
     */
    public static void renameCurToTmp(StorageDirectory sd) throws IOException
    {
        File curDir = sd.getCurrentDir();
        File prevDir = sd.getPreviousDir();
        final File tmpDir = sd.getPreviousTmp();

        Preconditions.checkState(curDir.exists(), "Current directory must exist for preupgrade.");
        Preconditions.checkState(!prevDir.exists(), "Previous directory must not exist for preupgrade.");
        Preconditions.checkState(!tmpDir.exists(), "Previous.tmp directory must not exist for preupgrade." + "Consider restarting for recovery.");

        // rename current to tmp
        NNStorage.rename(curDir, tmpDir);

        if (!curDir.mkdir())
        {
            throw new IOException("Cannot create directory " + curDir);
        }
    }

    /**
     * Perform the upgrade of the storage dir to the given storage info. The new
     * storage info is written into the current directory, and the previous.tmp
     * directory is renamed to previous.
     *
     * @param sd      the storage directory to upgrade
     * @param storage info about the new upgraded versions.
     * @throws IOException in the event of error
     */
    public static void doUpgrade(StorageDirectory sd, Storage storage) throws IOException
    {
        LOG.info("Performing upgrade of storage directory " + sd.getRoot());
        try
        {
            // Write the version file, since saveFsImage only makes the
            // fsimage_<txid>, and the directory is otherwise empty.
            storage.writeProperties(sd);

            File prevDir = sd.getPreviousDir();
            File tmpDir = sd.getPreviousTmp();
            Preconditions.checkState(!prevDir.exists(), "previous directory must not exist for upgrade.");
            Preconditions.checkState(tmpDir.exists(), "previous.tmp directory must exist for upgrade.");

            // rename tmp to previous
            NNStorage.rename(tmpDir, prevDir);
        } catch (IOException ioe)
        {
            LOG.error("Unable to rename temp to previous for " + sd.getRoot(), ioe);
            throw ioe;
        }
    }

    /**
     * Perform rollback of the storage dir to the previous state. The existing
     * current dir is removed, and the previous dir is renamed to current.
     *
     * @param sd the storage directory to roll back.
     * @throws IOException in the event of error
     */
    static void doRollBack(StorageDirectory sd) throws IOException
    {
        File prevDir = sd.getPreviousDir();
        if (!prevDir.exists())
        {
            return;
        }

        File tmpDir = sd.getRemovedTmp();
        Preconditions.checkState(!tmpDir.exists(), "removed.tmp directory must not exist for rollback." + "Consider restarting for recovery.");
        // rename current to tmp
        File curDir = sd.getCurrentDir();
        Preconditions.checkState(curDir.exists(), "Current directory must exist for rollback.");

        NNStorage.rename(curDir, tmpDir);
        // rename previous to current
        NNStorage.rename(prevDir, curDir);

        // delete tmp dir
        NNStorage.deleteDir(tmpDir);
        LOG.info("Rollback of " + sd.getRoot() + " is complete.");
    }

}

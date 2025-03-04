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
package org.apache.hadoop.hdfs.server.namenode.snapshot;

import java.io.DataOutput;
import java.io.IOException;
import java.util.List;

import org.apache.hadoop.hdfs.server.blockmanagement.BlockStoragePolicySuite;
import org.apache.hadoop.hdfs.server.namenode.INode;
import org.apache.hadoop.hdfs.server.namenode.INode.BlocksMapUpdateInfo;
import org.apache.hadoop.hdfs.server.namenode.INodeAttributes;
import org.apache.hadoop.hdfs.server.namenode.QuotaCounts;
import org.apache.hadoop.hdfs.server.namenode.snapshot.SnapshotFSImageFormat.ReferenceMap;

import com.google.common.base.Preconditions;

/**
 * The difference of an inode between in two snapshots.
 * {@link AbstractINodeDiffList} maintains a list of snapshot diffs,
 * <pre>
 *   d_1 -> d_2 -> ... -> d_n -> null,
 * </pre>
 * where -> denotes the {@link AbstractINodeDiff#posteriorDiff} reference. The
 * current directory state is stored in the field of {@link INode}.
 * The snapshot state can be obtained by applying the diffs one-by-one in
 * reversed chronological order.  Let s_1, s_2, ..., s_n be the corresponding
 * snapshots.  Then,
 * <pre>
 *   s_n                     = (current state) - d_n;
 *   s_{n-1} = s_n - d_{n-1} = (current state) - d_n - d_{n-1};
 *   ...
 *   s_k     = s_{k+1} - d_k = (current state) - d_n - d_{n-1} - ... - d_k.
 * </pre>
 */
abstract class AbstractINodeDiff<N extends INode, A extends INodeAttributes, D extends AbstractINodeDiff<N, A, D>> implements Comparable<Integer>
{

    /**
     * The id of the corresponding snapshot.
     */
    private int snapshotId;
    /**
     * The snapshot inode data.  It is null when there is no change.
     */
    A snapshotINode;
    /**
     * Posterior diff is the diff happened after this diff.
     * The posterior diff should be first applied to obtain the posterior
     * snapshot and then apply this diff in order to obtain this snapshot.
     * If the posterior diff is null, the posterior state is the current state.
     */
    private D posteriorDiff;

    AbstractINodeDiff(int snapshotId, A snapshotINode, D posteriorDiff)
    {
        this.snapshotId = snapshotId;
        this.snapshotINode = snapshotINode;
        this.posteriorDiff = posteriorDiff;
    }

    /**
     * Compare diffs with snapshot ID.
     */
    @Override
    public final int compareTo(final Integer that)
    {
        return Snapshot.ID_INTEGER_COMPARATOR.compare(this.snapshotId, that);
    }

    /**
     * @return the snapshot object of this diff.
     */
    public final int getSnapshotId()
    {
        return snapshotId;
    }

    final void setSnapshotId(int snapshot)
    {
        this.snapshotId = snapshot;
    }

    /**
     * @return the posterior diff.
     */
    final D getPosterior()
    {
        return posteriorDiff;
    }

    final void setPosterior(D posterior)
    {
        posteriorDiff = posterior;
    }

    /**
     * Save the INode state to the snapshot if it is not done already.
     */
    void saveSnapshotCopy(A snapshotCopy)
    {
        Preconditions.checkState(snapshotINode == null, "Expected snapshotINode to be null");
        snapshotINode = snapshotCopy;
    }

    /**
     * @return the inode corresponding to the snapshot.
     */
    A getSnapshotINode()
    {
        // get from this diff, then the posterior diff
        // and then null for the current inode
        for (AbstractINodeDiff<N, A, D> d = this; ; d = d.posteriorDiff)
        {
            if (d.snapshotINode != null)
            {
                return d.snapshotINode;
            } else if (d.posteriorDiff == null)
            {
                return null;
            }
        }
    }

    /**
     * Combine the posterior diff and collect blocks for deletion.
     */
    abstract QuotaCounts combinePosteriorAndCollectBlocks(final BlockStoragePolicySuite bsps, final N currentINode, final D posterior, final BlocksMapUpdateInfo collectedBlocks, final List<INode> removedINodes);

    /**
     * Delete and clear self.
     *
     * @param bsps            The block storage policy suite used to retrieve storage policy
     * @param currentINode    The inode where the deletion happens.
     * @param collectedBlocks Used to collect blocks for deletion.
     * @param removedINodes   INodes removed
     * @return quota usage delta
     */
    abstract QuotaCounts destroyDiffAndCollectBlocks(final BlockStoragePolicySuite bsps, final N currentINode, final BlocksMapUpdateInfo collectedBlocks, final List<INode> removedINodes);

    @Override
    public String toString()
    {
        return getClass().getSimpleName() + ": " + this.getSnapshotId() + " (post=" + (posteriorDiff == null ? null : posteriorDiff.getSnapshotId()) + ")";
    }

    void writeSnapshot(DataOutput out) throws IOException
    {
        out.writeInt(snapshotId);
    }

    abstract void write(DataOutput out, ReferenceMap referenceMap) throws IOException;
}

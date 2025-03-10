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
package org.apache.hadoop.hdfs.server.datanode.fsdataset.impl;

import java.io.File;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.google.common.collect.Lists;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.StorageType;
import org.apache.hadoop.hdfs.server.datanode.fsdataset.FsVolumeReference;
import org.apache.hadoop.hdfs.server.datanode.fsdataset.FsVolumeSpi;
import org.apache.hadoop.hdfs.server.datanode.fsdataset.VolumeChoosingPolicy;
import org.apache.hadoop.hdfs.server.datanode.BlockScanner;
import org.apache.hadoop.hdfs.server.protocol.DatanodeStorage;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.util.DiskChecker.DiskErrorException;
import org.apache.hadoop.util.Time;

class FsVolumeList
{
    private final AtomicReference<FsVolumeImpl[]> volumes = new AtomicReference<>(new FsVolumeImpl[0]);
    // Tracks volume failures, sorted by volume path.
    private final Map<String, VolumeFailureInfo> volumeFailureInfos = Collections.synchronizedMap(new TreeMap<String, VolumeFailureInfo>());
    private Object checkDirsMutex = new Object();

    private final VolumeChoosingPolicy<FsVolumeImpl> blockChooser;
    private final BlockScanner blockScanner;

    FsVolumeList(List<VolumeFailureInfo> initialVolumeFailureInfos, BlockScanner blockScanner, VolumeChoosingPolicy<FsVolumeImpl> blockChooser)
    {
        this.blockChooser = blockChooser;
        this.blockScanner = blockScanner;
        for (VolumeFailureInfo volumeFailureInfo : initialVolumeFailureInfos)
        {
            volumeFailureInfos.put(volumeFailureInfo.getFailedStorageLocation(), volumeFailureInfo);
        }
    }

    /**
     * Return an immutable list view of all the volumes.
     */
    List<FsVolumeImpl> getVolumes()
    {
        return Collections.unmodifiableList(Arrays.asList(volumes.get()));
    }

    private FsVolumeReference chooseVolume(List<FsVolumeImpl> list, long blockSize) throws IOException
    {
        while (true)
        {
            FsVolumeImpl volume = blockChooser.chooseVolume(list, blockSize);
            try
            {
                return volume.obtainReference();
            } catch (ClosedChannelException e)
            {
                FsDatasetImpl.LOG.warn("Chosen a closed volume: " + volume);
                // blockChooser.chooseVolume returns DiskOutOfSpaceException when the list
                // is empty, indicating that all volumes are closed.
                list.remove(volume);
            }
        }
    }

    /**
     * Get next volume.
     *
     * @param blockSize   free space needed on the volume
     * @param storageType the desired {@link StorageType}
     * @return next volume to store the block in.
     */
    FsVolumeReference getNextVolume(StorageType storageType, long blockSize) throws IOException
    {
        // Get a snapshot of currently available volumes.
        final FsVolumeImpl[] curVolumes = volumes.get();
        final List<FsVolumeImpl> list = new ArrayList<>(curVolumes.length);
        for (FsVolumeImpl v : curVolumes)
        {
            if (v.getStorageType() == storageType)
            {
                list.add(v);
            }
        }
        return chooseVolume(list, blockSize);
    }

    /**
     * Get next volume.
     *
     * @param blockSize free space needed on the volume
     * @return next volume to store the block in.
     */
    FsVolumeReference getNextTransientVolume(long blockSize) throws IOException
    {
        // Get a snapshot of currently available volumes.
        final List<FsVolumeImpl> curVolumes = getVolumes();
        final List<FsVolumeImpl> list = new ArrayList<>(curVolumes.size());
        for (FsVolumeImpl v : curVolumes)
        {
            if (v.isTransientStorage())
            {
                list.add(v);
            }
        }
        return chooseVolume(list, blockSize);
    }

    long getDfsUsed() throws IOException
    {
        long dfsUsed = 0L;
        for (FsVolumeImpl v : volumes.get())
        {
            try (FsVolumeReference ref = v.obtainReference())
            {
                dfsUsed += v.getDfsUsed();
            } catch (ClosedChannelException e)
            {
                // ignore.
            }
        }
        return dfsUsed;
    }

    long getBlockPoolUsed(String bpid) throws IOException
    {
        long dfsUsed = 0L;
        for (FsVolumeImpl v : volumes.get())
        {
            try (FsVolumeReference ref = v.obtainReference())
            {
                dfsUsed += v.getBlockPoolUsed(bpid);
            } catch (ClosedChannelException e)
            {
                // ignore.
            }
        }
        return dfsUsed;
    }

    long getCapacity()
    {
        long capacity = 0L;
        for (FsVolumeImpl v : volumes.get())
        {
            try (FsVolumeReference ref = v.obtainReference())
            {
                capacity += v.getCapacity();
            } catch (IOException e)
            {
                // ignore.
            }
        }
        return capacity;
    }

    long getRemaining() throws IOException
    {
        long remaining = 0L;
        for (FsVolumeSpi vol : volumes.get())
        {
            try (FsVolumeReference ref = vol.obtainReference())
            {
                remaining += vol.getAvailable();
            } catch (ClosedChannelException e)
            {
                // ignore
            }
        }
        return remaining;
    }

    void getAllVolumesMap(final String bpid, final ReplicaMap volumeMap, final RamDiskReplicaTracker ramDiskReplicaMap) throws IOException
    {
        long totalStartTime = Time.monotonicNow();
        final List<IOException> exceptions = Collections.synchronizedList(new ArrayList<IOException>());
        List<Thread> replicaAddingThreads = new ArrayList<Thread>();
        for (final FsVolumeImpl v : volumes.get())
        {
            Thread t = new Thread()
            {
                public void run()
                {
                    try (FsVolumeReference ref = v.obtainReference())
                    {
                        FsDatasetImpl.LOG.info("Adding replicas to map for block pool " + bpid + " on volume " + v + "...");
                        long startTime = Time.monotonicNow();
                        v.getVolumeMap(bpid, volumeMap, ramDiskReplicaMap);
                        long timeTaken = Time.monotonicNow() - startTime;
                        FsDatasetImpl.LOG.info("Time to add replicas to map for block pool" + " " + bpid + " on volume " + v + ": " + timeTaken + "ms");
                    } catch (ClosedChannelException e)
                    {
                        FsDatasetImpl.LOG.info("The volume " + v + " is closed while " + "addng replicas, ignored.");
                    } catch (IOException ioe)
                    {
                        FsDatasetImpl.LOG.info("Caught exception while adding replicas " + "from " + v + ". Will throw later.", ioe);
                        exceptions.add(ioe);
                    }
                }
            };
            replicaAddingThreads.add(t);
            t.start();
        }
        for (Thread t : replicaAddingThreads)
        {
            try
            {
                t.join();
            } catch (InterruptedException ie)
            {
                throw new IOException(ie);
            }
        }
        if (!exceptions.isEmpty())
        {
            throw exceptions.get(0);
        }
        long totalTimeTaken = Time.monotonicNow() - totalStartTime;
        FsDatasetImpl.LOG.info("Total time to add all replicas to map: " + totalTimeTaken + "ms");
    }

    /**
     * Calls {@link FsVolumeImpl#checkDirs()} on each volume.
     * <p>
     * Use checkDirsMutext to allow only one instance of checkDirs() call
     *
     * @return list of all the failed volumes.
     */
    Set<File> checkDirs()
    {
        synchronized (checkDirsMutex)
        {
            Set<File> failedVols = null;

            // Make a copy of volumes for performing modification
            final List<FsVolumeImpl> volumeList = getVolumes();

            for (Iterator<FsVolumeImpl> i = volumeList.iterator(); i.hasNext(); )
            {
                final FsVolumeImpl fsv = i.next();
                try (FsVolumeReference ref = fsv.obtainReference())
                {
                    fsv.checkDirs();
                } catch (DiskErrorException e)
                {
                    FsDatasetImpl.LOG.warn("Removing failed volume " + fsv + ": ", e);
                    if (failedVols == null)
                    {
                        failedVols = new HashSet<>(1);
                    }
                    failedVols.add(new File(fsv.getBasePath()).getAbsoluteFile());
                    addVolumeFailureInfo(fsv);
                    removeVolume(fsv);
                } catch (ClosedChannelException e)
                {
                    FsDatasetImpl.LOG.debug("Caught exception when obtaining " + "reference count on closed volume", e);
                } catch (IOException e)
                {
                    FsDatasetImpl.LOG.error("Unexpected IOException", e);
                }
            }

            if (failedVols != null && failedVols.size() > 0)
            {
                FsDatasetImpl.LOG.warn("Completed checkDirs. Found " + failedVols.size() + " failure volumes.");
            }

            return failedVols;
        }
    }

    @Override
    public String toString()
    {
        return Arrays.toString(volumes.get());
    }

    /**
     * Dynamically add new volumes to the existing volumes that this DN manages.
     *
     * @param ref a reference to the new FsVolumeImpl instance.
     */
    void addVolume(FsVolumeReference ref)
    {
        while (true)
        {
            final FsVolumeImpl[] curVolumes = volumes.get();
            final List<FsVolumeImpl> volumeList = Lists.newArrayList(curVolumes);
            volumeList.add((FsVolumeImpl) ref.getVolume());
            if (volumes.compareAndSet(curVolumes, volumeList.toArray(new FsVolumeImpl[volumeList.size()])))
            {
                break;
            } else
            {
                if (FsDatasetImpl.LOG.isDebugEnabled())
                {
                    FsDatasetImpl.LOG.debug("The volume list has been changed concurrently, " + "retry to remove volume: " + ref.getVolume().getStorageID());
                }
            }
        }
        if (blockScanner != null)
        {
            blockScanner.addVolumeScanner(ref);
        } else
        {
            // If the volume is not put into a volume scanner, it does not need to
            // hold the reference.
            IOUtils.cleanup(FsDatasetImpl.LOG, ref);
        }
        // If the volume is used to replace a failed volume, it needs to reset the
        // volume failure info for this volume.
        removeVolumeFailureInfo(new File(ref.getVolume().getBasePath()));
        FsDatasetImpl.LOG.info("Added new volume: " + ref.getVolume().getStorageID());
    }

    /**
     * Dynamically remove a volume in the list.
     *
     * @param target the volume instance to be removed.
     */
    private void removeVolume(FsVolumeImpl target)
    {
        while (true)
        {
            final FsVolumeImpl[] curVolumes = volumes.get();
            final List<FsVolumeImpl> volumeList = Lists.newArrayList(curVolumes);
            if (volumeList.remove(target))
            {
                if (volumes.compareAndSet(curVolumes, volumeList.toArray(new FsVolumeImpl[volumeList.size()])))
                {
                    if (blockScanner != null)
                    {
                        blockScanner.removeVolumeScanner(target);
                    }
                    try
                    {
                        target.closeAndWait();
                    } catch (IOException e)
                    {
                        FsDatasetImpl.LOG.warn("Error occurs when waiting volume to close: " + target, e);
                    }
                    target.shutdown();
                    FsDatasetImpl.LOG.info("Removed volume: " + target);
                    break;
                } else
                {
                    if (FsDatasetImpl.LOG.isDebugEnabled())
                    {
                        FsDatasetImpl.LOG.debug("The volume list has been changed concurrently, " + "retry to remove volume: " + target);
                    }
                }
            } else
            {
                if (FsDatasetImpl.LOG.isDebugEnabled())
                {
                    FsDatasetImpl.LOG.debug("Volume " + target + " does not exist or is removed by others.");
                }
                break;
            }
        }
    }

    /**
     * Dynamically remove volume in the list.
     *
     * @param volume       the volume to be removed.
     * @param clearFailure set true to remove failure info for this volume.
     */
    void removeVolume(File volume, boolean clearFailure)
    {
        // Make a copy of volumes to remove one volume.
        final FsVolumeImpl[] curVolumes = volumes.get();
        final List<FsVolumeImpl> volumeList = Lists.newArrayList(curVolumes);
        for (Iterator<FsVolumeImpl> it = volumeList.iterator(); it.hasNext(); )
        {
            FsVolumeImpl fsVolume = it.next();
            String basePath, targetPath;
            basePath = new File(fsVolume.getBasePath()).getAbsolutePath();
            targetPath = volume.getAbsolutePath();
            if (basePath.equals(targetPath))
            {
                // Make sure the removed volume is the one in the curVolumes.
                removeVolume(fsVolume);
            }
        }
        if (clearFailure)
        {
            removeVolumeFailureInfo(volume);
        }
    }

    VolumeFailureInfo[] getVolumeFailureInfos()
    {
        Collection<VolumeFailureInfo> infos = volumeFailureInfos.values();
        return infos.toArray(new VolumeFailureInfo[infos.size()]);
    }

    void addVolumeFailureInfo(VolumeFailureInfo volumeFailureInfo)
    {
        volumeFailureInfos.put(volumeFailureInfo.getFailedStorageLocation(), volumeFailureInfo);
    }

    private void addVolumeFailureInfo(FsVolumeImpl vol)
    {
        addVolumeFailureInfo(new VolumeFailureInfo(new File(vol.getBasePath()).getAbsolutePath(), Time.now(), vol.getCapacity()));
    }

    private void removeVolumeFailureInfo(File vol)
    {
        volumeFailureInfos.remove(vol.getAbsolutePath());
    }

    void addBlockPool(final String bpid, final Configuration conf) throws IOException
    {
        long totalStartTime = Time.monotonicNow();

        final List<IOException> exceptions = Collections.synchronizedList(new ArrayList<IOException>());
        List<Thread> blockPoolAddingThreads = new ArrayList<Thread>();
        for (final FsVolumeImpl v : volumes.get())
        {
            Thread t = new Thread()
            {
                public void run()
                {
                    try (FsVolumeReference ref = v.obtainReference())
                    {
                        FsDatasetImpl.LOG.info("Scanning block pool " + bpid + " on volume " + v + "...");
                        long startTime = Time.monotonicNow();
                        v.addBlockPool(bpid, conf);
                        long timeTaken = Time.monotonicNow() - startTime;
                        FsDatasetImpl.LOG.info("Time taken to scan block pool " + bpid + " on " + v + ": " + timeTaken + "ms");
                    } catch (ClosedChannelException e)
                    {
                        // ignore.
                    } catch (IOException ioe)
                    {
                        FsDatasetImpl.LOG.info("Caught exception while scanning " + v + ". Will throw later.", ioe);
                        exceptions.add(ioe);
                    }
                }
            };
            blockPoolAddingThreads.add(t);
            t.start();
        }
        for (Thread t : blockPoolAddingThreads)
        {
            try
            {
                t.join();
            } catch (InterruptedException ie)
            {
                throw new IOException(ie);
            }
        }
        if (!exceptions.isEmpty())
        {
            throw exceptions.get(0);
        }

        long totalTimeTaken = Time.monotonicNow() - totalStartTime;
        FsDatasetImpl.LOG.info("Total time to scan all replicas for block pool " + bpid + ": " + totalTimeTaken + "ms");
    }

    void removeBlockPool(String bpid)
    {
        for (FsVolumeImpl v : volumes.get())
        {
            v.shutdownBlockPool(bpid);
        }
    }

    void shutdown()
    {
        for (FsVolumeImpl volume : volumes.get())
        {
            if (volume != null)
            {
                volume.shutdown();
            }
        }
    }
}

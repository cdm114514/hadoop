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

package org.apache.hadoop.hdfs.server.protocol;

/**
 * The context of the block report.
 * <p>
 * This is a set of fields that the Datanode sends to provide context about a
 * block report RPC.  The context includes a unique 64-bit ID which
 * identifies the block report as a whole.  It also includes the total number
 * of RPCs which this block report is split into, and the index into that
 * total for the current RPC.
 */
public class BlockReportContext
{
    private final int totalRpcs;
    private final int curRpc;
    private final long reportId;

    public BlockReportContext(int totalRpcs, int curRpc, long reportId)
    {
        this.totalRpcs = totalRpcs;
        this.curRpc = curRpc;
        this.reportId = reportId;
    }

    public int getTotalRpcs()
    {
        return totalRpcs;
    }

    public int getCurRpc()
    {
        return curRpc;
    }

    public long getReportId()
    {
        return reportId;
    }
}

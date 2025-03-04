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
package org.apache.hadoop.hdfs.server.common;

import java.io.File;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.hdfs.server.namenode.JournalManager;

/**
 * Interface which implementations of {@link JournalManager} can use to report
 * errors on underlying storage directories. This avoids a circular dependency
 * between journal managers and the storage which instantiates them.
 */
@InterfaceAudience.Private
public interface StorageErrorReporter
{

    /**
     * Indicate that some error occurred on the given file.
     *
     * @param f the file which had an error.
     */
    public void reportErrorOnFile(File f);
}

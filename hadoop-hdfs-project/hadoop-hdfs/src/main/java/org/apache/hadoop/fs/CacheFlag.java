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
package org.apache.hadoop.fs;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;

/**
 * Specifies semantics for CacheDirective operations. Multiple flags can
 * be combined in an EnumSet.
 */
@InterfaceAudience.Public
@InterfaceStability.Evolving
public enum CacheFlag
{

    /**
     * Ignore cache pool resource limits when performing this operation.
     */
    FORCE((short) 0x01);
    private final short mode;

    private CacheFlag(short mode)
    {
        this.mode = mode;
    }

    short getMode()
    {
        return mode;
    }
}

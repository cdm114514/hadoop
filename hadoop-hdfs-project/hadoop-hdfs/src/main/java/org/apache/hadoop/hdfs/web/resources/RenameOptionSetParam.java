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
package org.apache.hadoop.hdfs.web.resources;

import org.apache.hadoop.fs.Options;

/**
 * Rename option set parameter.
 */
public class RenameOptionSetParam extends EnumSetParam<Options.Rename>
{
    /**
     * Parameter name.
     */
    public static final String NAME = "renameoptions";
    /**
     * Default parameter value.
     */
    public static final String DEFAULT = "";

    private static final Domain<Options.Rename> DOMAIN = new Domain<Options.Rename>(NAME, Options.Rename.class);

    /**
     * Constructor.
     *
     * @param options rename options.
     */
    public RenameOptionSetParam(final Options.Rename... options)
    {
        super(DOMAIN, toEnumSet(Options.Rename.class, options));
    }

    /**
     * Constructor.
     *
     * @param str a string representation of the parameter value.
     */
    public RenameOptionSetParam(final String str)
    {
        super(DOMAIN, DOMAIN.parse(str));
    }

    @Override
    public String getName()
    {
        return NAME;
    }
}
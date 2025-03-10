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

import java.util.Arrays;

import org.apache.hadoop.util.StringUtils;

abstract class EnumParam<E extends Enum<E>> extends Param<E, EnumParam.Domain<E>>
{
    EnumParam(final Domain<E> domain, final E value)
    {
        super(domain, value);
    }

    /**
     * The domain of the parameter.
     */
    static final class Domain<E extends Enum<E>> extends Param.Domain<E>
    {
        private final Class<E> enumClass;

        Domain(String name, final Class<E> enumClass)
        {
            super(name);
            this.enumClass = enumClass;
        }

        @Override
        public final String getDomain()
        {
            return Arrays.asList(enumClass.getEnumConstants()).toString();
        }

        @Override
        final E parse(final String str)
        {
            return Enum.valueOf(enumClass, StringUtils.toUpperCase(str));
        }
    }
}

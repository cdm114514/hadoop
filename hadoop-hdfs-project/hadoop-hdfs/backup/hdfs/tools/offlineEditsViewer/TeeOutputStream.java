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
package org.apache.hadoop.hdfs.tools.offlineEditsViewer;

import java.io.IOException;
import java.io.OutputStream;

/**
 * A TeeOutputStream writes its output to multiple output streams.
 */
public class TeeOutputStream extends OutputStream
{
    private final OutputStream[] outs;

    public TeeOutputStream(OutputStream outs[])
    {
        this.outs = outs;
    }

    @Override
    public void write(int c) throws IOException
    {
        for (OutputStream o : outs)
        {
            o.write(c);
        }
    }

    @Override
    public void write(byte[] b) throws IOException
    {
        for (OutputStream o : outs)
        {
            o.write(b);
        }
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException
    {
        for (OutputStream o : outs)
        {
            o.write(b, off, len);
        }
    }

    @Override
    public void close() throws IOException
    {
        for (OutputStream o : outs)
        {
            o.close();
        }
    }

    @Override
    public void flush() throws IOException
    {
        for (OutputStream o : outs)
        {
            o.flush();
        }
    }
}

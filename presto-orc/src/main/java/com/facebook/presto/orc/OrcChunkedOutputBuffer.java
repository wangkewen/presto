/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.orc;

import io.airlift.slice.SliceOutput;

public interface OrcChunkedOutputBuffer
{
    void writeTo(SliceOutput outputStream);

    void reset();

    int size();

    long getRetainedSize();

    // need to be called before writing
    void ensureAvailable(int minLength, int length);

    void writeHeader(int value);

    void writeBytes(byte[] source, int sourceIndex, int length);

    String toString();
}

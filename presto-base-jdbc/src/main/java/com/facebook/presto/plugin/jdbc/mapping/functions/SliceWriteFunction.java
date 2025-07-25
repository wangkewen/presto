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
package com.facebook.presto.plugin.jdbc.mapping.functions;

import com.facebook.presto.plugin.jdbc.mapping.WriteFunction;
import io.airlift.slice.Slice;

import java.sql.PreparedStatement;
import java.sql.SQLException;

public interface SliceWriteFunction
        extends WriteFunction
{
    default Class<?> getJavaType()
    {
        return Slice.class;
    }

    void set(PreparedStatement statement, int index, Slice value) throws SQLException;
}

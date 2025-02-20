/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.jdbc.proto.event;

import java.util.ArrayList;
import java.util.List;
import org.apache.ignite.internal.client.proto.ClientMessagePacker;
import org.apache.ignite.internal.client.proto.ClientMessageUnpacker;
import org.apache.ignite.internal.jdbc.proto.ClientMessage;
import org.apache.ignite.internal.tostring.S;
import org.apache.ignite.internal.util.CollectionUtils;

/**
 * JDBC batch execute request.
 */
public class BatchExecuteRequest implements ClientMessage {
    /** Schema name. */
    private String schemaName;

    /** Sql queries. */
    private List<String> queries;

    /**
     * Default constructor.
     */
    public BatchExecuteRequest() {
    }

    /**
     * Constructor.
     *
     * @param schemaName Schema name.
     * @param queries    Queries.
     */
    public BatchExecuteRequest(String schemaName, List<String> queries) {
        assert !CollectionUtils.nullOrEmpty(queries);

        this.schemaName = schemaName;
        this.queries = queries;
    }

    /**
     * Get the schema name.
     *
     * @return Schema name.
     */
    public String schemaName() {
        return schemaName;
    }

    /**
     * Get the queries.
     *
     * @return Queries.
     */
    public List<String> queries() {
        return queries;
    }

    /** {@inheritDoc} */
    @Override
    public void writeBinary(ClientMessagePacker packer) {
        ClientMessageUtils.writeStringNullable(packer, schemaName);

        packer.packArrayHeader(queries.size());

        for (String q : queries) {
            packer.packString(q);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void readBinary(ClientMessageUnpacker unpacker) {
        schemaName = ClientMessageUtils.readStringNullable(unpacker);

        int n = unpacker.unpackArrayHeader();

        queries = new ArrayList<>(n);

        for (int i = 0; i < n; ++i) {
            queries.add(unpacker.unpackString());
        }
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        return S.toString(BatchExecuteRequest.class, this);
    }
}

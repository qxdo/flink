/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.util;

import org.apache.flink.runtime.io.network.partition.ResultPartitionType;
import org.apache.flink.runtime.jobgraph.DistributionPattern;
import org.apache.flink.runtime.jobgraph.IntermediateDataSetID;
import org.apache.flink.runtime.jobgraph.JobEdge;
import org.apache.flink.runtime.jobgraph.JobVertex;

/**
 * Utility class for providing overloaded methods to connect a new dataset as input to a given job
 * vertex.
 */
public class JobVertexConnectionUtils {

    public static JobEdge connectNewDataSetAsInput(
            JobVertex currentJobVertex,
            JobVertex input,
            DistributionPattern distPattern,
            ResultPartitionType partitionType) {
        return connectNewDataSetAsInput(
                currentJobVertex, input, distPattern, partitionType, false, false);
    }

    public static JobEdge connectNewDataSetAsInput(
            JobVertex currentJobVertex,
            JobVertex input,
            DistributionPattern distPattern,
            ResultPartitionType partitionType,
            boolean isBroadcast,
            boolean isForward) {
        JobEdge jobEdge =
                currentJobVertex.connectNewDataSetAsInput(
                        input,
                        distPattern,
                        partitionType,
                        new IntermediateDataSetID(),
                        isBroadcast,
                        isForward);
        jobEdge.getSource().increaseNumJobEdgesToCreate();
        return jobEdge;
    }

    public static JobEdge connectNewDataSetAsInput(
            JobVertex currentJobVertex,
            JobVertex input,
            DistributionPattern distPattern,
            ResultPartitionType partitionType,
            IntermediateDataSetID intermediateDataSetId,
            boolean isBroadcast) {
        JobEdge jobEdge =
                currentJobVertex.connectNewDataSetAsInput(
                        input,
                        distPattern,
                        partitionType,
                        intermediateDataSetId,
                        isBroadcast,
                        false);
        jobEdge.getSource().increaseNumJobEdgesToCreate();
        return jobEdge;
    }
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.msq.querykit;

import org.apache.druid.frame.key.ClusterBy;
import org.apache.druid.msq.kernel.GlobalSortMaxCountShuffleSpec;
import org.apache.druid.msq.kernel.GlobalSortTargetSizeShuffleSpec;
import org.apache.druid.msq.kernel.MixShuffleSpec;
import org.apache.druid.msq.kernel.ShuffleSpec;

/**
 * Static factory methods for common implementations of {@link ShuffleSpecFactory}.
 */
public class ShuffleSpecFactories
{
  private ShuffleSpecFactories()
  {
    // No instantiation.
  }

  /**
   * Factory that produces a single output partition, which may or may not be sorted.
   */
  public static ShuffleSpecFactory singlePartition()
  {
    return singlePartitionWithLimit(ShuffleSpec.UNLIMITED);
  }

  /**
   * Factory that produces a single output partition, which may or may not be sorted.
   *
   * @param limitHint limit that can be applied during shuffling. May not actually be applied; this is just an
   *                  optional optimization. See {@link ShuffleSpec#limitHint()}.
   */
  public static ShuffleSpecFactory singlePartitionWithLimit(final long limitHint)
  {
    return (clusterBy, aggregate) -> {
      if (clusterBy.sortable() && !clusterBy.isEmpty()) {
        return new GlobalSortMaxCountShuffleSpec(clusterBy, 1, aggregate, limitHint);
      } else {
        return MixShuffleSpec.instance();
      }
    };
  }

  /**
   * Factory that produces a particular number of output partitions.
   */
  public static ShuffleSpecFactory globalSortWithMaxPartitionCount(final int partitions)
  {
    return (clusterBy, aggregate) ->
        new GlobalSortMaxCountShuffleSpec(clusterBy, partitions, aggregate, ShuffleSpec.UNLIMITED);
  }

  /**
   * Factory that produces globally sorted partitions of a target size, using the {@link ClusterBy} to partition
   * rows across partitions.
   *
   * Produces {@link MixShuffleSpec}, ignoring the target size, if the provided {@link ClusterBy} is empty.
   */
  public static ShuffleSpecFactory getGlobalSortWithTargetSize(int targetSize)
  {
    return (clusterBy, aggregate) -> {
      if (clusterBy.isEmpty()) {
        // Cannot partition or sort meaningfully because there are no cluster-by keys. Generate a MixShuffleSpec
        // so everything goes into a single partition.
        return MixShuffleSpec.instance();
      } else {
        return new GlobalSortTargetSizeShuffleSpec(clusterBy, targetSize, aggregate);
      }
    };
  }
}

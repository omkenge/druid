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

package org.apache.druid.indexing.common.task;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import org.apache.druid.client.indexing.ClientCompactionTaskGranularitySpec;
import org.apache.druid.data.input.InputSplit;
import org.apache.druid.data.input.SegmentsSplitHintSpec;
import org.apache.druid.data.input.impl.AggregateProjectionSpec;
import org.apache.druid.data.input.impl.CsvInputFormat;
import org.apache.druid.data.input.impl.DimensionSchema;
import org.apache.druid.data.input.impl.DimensionsSpec;
import org.apache.druid.data.input.impl.LocalInputSource;
import org.apache.druid.data.input.impl.LongDimensionSchema;
import org.apache.druid.data.input.impl.StringDimensionSchema;
import org.apache.druid.data.input.impl.TimestampSpec;
import org.apache.druid.indexer.TaskState;
import org.apache.druid.indexer.TaskStatus;
import org.apache.druid.indexer.granularity.UniformGranularitySpec;
import org.apache.druid.indexer.partitions.DimensionRangePartitionsSpec;
import org.apache.druid.indexer.partitions.DynamicPartitionsSpec;
import org.apache.druid.indexer.partitions.HashedPartitionsSpec;
import org.apache.druid.indexer.partitions.PartitionsSpec;
import org.apache.druid.indexer.partitions.SingleDimensionPartitionsSpec;
import org.apache.druid.indexer.report.IngestionStatsAndErrors;
import org.apache.druid.indexing.common.LockGranularity;
import org.apache.druid.indexing.common.TaskToolbox;
import org.apache.druid.indexing.common.actions.TaskActionClient;
import org.apache.druid.indexing.common.task.CompactionTask.Builder;
import org.apache.druid.indexing.common.task.batch.parallel.AbstractParallelIndexSupervisorTaskTest;
import org.apache.druid.indexing.common.task.batch.parallel.ParallelIndexIOConfig;
import org.apache.druid.indexing.common.task.batch.parallel.ParallelIndexIngestionSpec;
import org.apache.druid.indexing.common.task.batch.parallel.ParallelIndexSupervisorTask;
import org.apache.druid.indexing.common.task.batch.parallel.ParallelIndexTuningConfig;
import org.apache.druid.indexing.input.DruidInputSource;
import org.apache.druid.indexing.input.WindowedSegmentId;
import org.apache.druid.java.util.common.Intervals;
import org.apache.druid.java.util.common.granularity.Granularities;
import org.apache.druid.query.aggregation.AggregatorFactory;
import org.apache.druid.query.aggregation.CountAggregatorFactory;
import org.apache.druid.query.aggregation.LongSumAggregatorFactory;
import org.apache.druid.query.filter.SelectorDimFilter;
import org.apache.druid.segment.DataSegmentsWithSchemas;
import org.apache.druid.segment.SegmentUtils;
import org.apache.druid.segment.VirtualColumns;
import org.apache.druid.segment.indexing.DataSchema;
import org.apache.druid.segment.loading.NoopSegmentCacheManager;
import org.apache.druid.segment.transform.CompactionTransformSpec;
import org.apache.druid.server.security.Action;
import org.apache.druid.server.security.Resource;
import org.apache.druid.server.security.ResourceAction;
import org.apache.druid.server.security.ResourceType;
import org.apache.druid.timeline.CompactionState;
import org.apache.druid.timeline.DataSegment;
import org.apache.druid.timeline.partition.DimensionRangeShardSpec;
import org.apache.druid.timeline.partition.HashBasedNumberedShardSpec;
import org.apache.druid.timeline.partition.NumberedOverwriteShardSpec;
import org.apache.druid.timeline.partition.NumberedShardSpec;
import org.apache.druid.timeline.partition.PartitionIds;
import org.apache.druid.timeline.partition.ShardSpec;
import org.apache.druid.timeline.partition.SingleDimensionShardSpec;
import org.joda.time.Interval;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import javax.annotation.Nullable;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

@RunWith(Parameterized.class)
public class CompactionTaskParallelRunTest extends AbstractParallelIndexSupervisorTaskTest
{
  @Parameterized.Parameters(name = "{0}")
  public static Iterable<Object[]> constructorFeeder()
  {
    return ImmutableList.of(
        new Object[]{LockGranularity.TIME_CHUNK},
        new Object[]{LockGranularity.SEGMENT}
    );
  }

  private static final String DATA_SOURCE = "test";
  private static final Interval INTERVAL_TO_INDEX = Intervals.of("2014-01-01/2014-01-02");

  private static final AggregateProjectionSpec PROJECTION_SPEC = new AggregateProjectionSpec(
      "projection1",
      VirtualColumns.create(
          Granularities.toVirtualColumn(
              Granularities.HOUR,
              Granularities.GRANULARITY_VIRTUAL_COLUMN_NAME
          )
      ),
      ImmutableList.of(
          new LongDimensionSchema(Granularities.GRANULARITY_VIRTUAL_COLUMN_NAME),
          new StringDimensionSchema("dim", DimensionSchema.MultiValueHandling.ARRAY, null)
      ),
      new AggregatorFactory[]{
          new LongSumAggregatorFactory("val", "val")
      }
  );

  private final LockGranularity lockGranularity;

  // Whether the currently-running test case is allowed to fetch segments in the primary logic of the CompactionTask.
  // Verified by a special SegmentCacheManager in createTaskToolbox.
  private boolean allowSegmentFetchesByCompactionTask = false;
  private File inputDir;

  public CompactionTaskParallelRunTest(LockGranularity lockGranularity)
  {
    super(DEFAULT_TRANSIENT_TASK_FAILURE_RATE, DEFAULT_TRANSIENT_API_FAILURE_RATE);
    this.lockGranularity = lockGranularity;
  }

  @Before
  public void setup() throws IOException
  {
    getObjectMapper().registerSubtypes(ParallelIndexTuningConfig.class, DruidInputSource.class);
    getObjectMapper().registerSubtypes(CompactionTask.CompactionTuningConfig.class, DruidInputSource.class);

    inputDir = temporaryFolder.newFolder();
    final File tmpFile = File.createTempFile("druid", "index", inputDir);

    try (BufferedWriter writer = Files.newWriter(tmpFile, StandardCharsets.UTF_8)) {
      writer.write("2014-01-01T00:00:10Z,a,1\n");
      writer.write("2014-01-01T00:00:10Z,b,2\n");
      writer.write("2014-01-01T00:00:10Z,c,3\n");
      writer.write("2014-01-01T01:00:20Z,a,1\n");
      writer.write("2014-01-01T01:00:20Z,b,2\n");
      writer.write("2014-01-01T01:00:20Z,c,3\n");
      writer.write("2014-01-01T02:00:30Z,a,1\n");
      writer.write("2014-01-01T02:00:30Z,b,2\n");
      writer.write("2014-01-01T02:00:30Z,c,3\n");
    }
  }

  @Test
  public void testRunParallelWithDynamicPartitioningMatchCompactionState()
  {
    allowSegmentFetchesByCompactionTask = true;
    runIndexTask(null, true);

    final Builder builder = new Builder(
        DATA_SOURCE,
        getSegmentCacheManagerFactory()
    );
    final CompactionTask compactionTask = builder
        .inputSpec(new CompactionIntervalSpec(INTERVAL_TO_INDEX, null))
        .tuningConfig(AbstractParallelIndexSupervisorTaskTest.DEFAULT_TUNING_CONFIG_FOR_PARALLEL_INDEXING)
        .build();

    final DataSegmentsWithSchemas dataSegmentsWithSchemas = runTask(compactionTask);
    verifySchema(dataSegmentsWithSchemas);
    final Set<DataSegment> compactedSegments = dataSegmentsWithSchemas.getSegments();

    for (DataSegment segment : compactedSegments) {
      Assert.assertSame(
          lockGranularity == LockGranularity.TIME_CHUNK ? NumberedShardSpec.class : NumberedOverwriteShardSpec.class,
          segment.getShardSpec().getClass()
      );
      // Expect compaction state to exist as store compaction state by default
      LongSumAggregatorFactory expectedLongSumMetric = new LongSumAggregatorFactory("val", "val");
      CompactionState expectedState = new CompactionState(
          new DynamicPartitionsSpec(null, Long.MAX_VALUE),
          new DimensionsSpec(
              ImmutableList.of(
                  new StringDimensionSchema("ts", DimensionSchema.MultiValueHandling.ARRAY, null),
                  new StringDimensionSchema("dim", DimensionSchema.MultiValueHandling.ARRAY, null)
              )
          ),
          ImmutableList.of(expectedLongSumMetric),
          null,
          compactionTask.getTuningConfig().getIndexSpec(),
          new UniformGranularitySpec(
              Granularities.HOUR,
              Granularities.MINUTE,
              true,
              ImmutableList.of(segment.getInterval())
          ),
          null
      );
      Assert.assertEquals("Compaction state for " + segment.getId(), expectedState, segment.getLastCompactionState());
    }
  }

  @Test
  public void testRunParallelWithHashPartitioningMatchCompactionState() throws Exception
  {
    allowSegmentFetchesByCompactionTask = true;

    // Hash partitioning is not supported with segment lock yet
    Assume.assumeFalse(lockGranularity == LockGranularity.SEGMENT);
    runIndexTask(null, true);

    final Builder builder = new Builder(
        DATA_SOURCE,
        getSegmentCacheManagerFactory()
    );
    final CompactionTask compactionTask = builder
        .inputSpec(new CompactionIntervalSpec(INTERVAL_TO_INDEX, null))
        .tuningConfig(newTuningConfig(new HashedPartitionsSpec(null, 3, null), 2, true))
        .build();

    final DataSegmentsWithSchemas dataSegmentsWithSchemas = runTask(compactionTask);
    verifySchema(dataSegmentsWithSchemas);
    final Set<DataSegment> compactedSegments = dataSegmentsWithSchemas.getSegments();
    for (DataSegment segment : compactedSegments) {
      // Expect compaction state to exist as store compaction state by default
      LongSumAggregatorFactory expectedLongSumMetric = new LongSumAggregatorFactory("val", "val");
      Assert.assertSame(HashBasedNumberedShardSpec.class, segment.getShardSpec().getClass());
      CompactionState expectedState = new CompactionState(
          new HashedPartitionsSpec(null, 3, null),
          new DimensionsSpec(
              ImmutableList.of(
                  new StringDimensionSchema("ts", DimensionSchema.MultiValueHandling.ARRAY, null),
                  new StringDimensionSchema("dim", DimensionSchema.MultiValueHandling.ARRAY, null)
              )
          ),
          ImmutableList.of(expectedLongSumMetric),
          null,
          compactionTask.getTuningConfig().getIndexSpec(),
          new UniformGranularitySpec(
              Granularities.HOUR,
              Granularities.MINUTE,
              true,
              ImmutableList.of(segment.getInterval())
          ),
          null
      );
      Assert.assertEquals("Compaction state for " + segment.getId(), expectedState, segment.getLastCompactionState());
    }

    List<IngestionStatsAndErrors> reports = getIngestionReports();
    Assert.assertEquals(reports.size(), 3); // since three index tasks are run by single compaction task

    // this test reads 3 segments and publishes 6 segments
    Assert.assertEquals(
        3,
        reports.stream().mapToLong(IngestionStatsAndErrors::getSegmentsRead).sum()
    );
    Assert.assertEquals(
        6,
        reports.stream()
               .mapToLong(IngestionStatsAndErrors::getSegmentsPublished)
               .sum()
    );
  }

  @Test
  public void testRunParallelWithRangePartitioning() throws Exception
  {
    allowSegmentFetchesByCompactionTask = true;

    // Range partitioning is not supported with segment lock yet
    Assume.assumeFalse(lockGranularity == LockGranularity.SEGMENT);
    runIndexTask(null, true);

    final Builder builder = new Builder(
        DATA_SOURCE,
        getSegmentCacheManagerFactory()
    );
    final CompactionTask compactionTask = builder
        .inputSpec(new CompactionIntervalSpec(INTERVAL_TO_INDEX, null))
        .tuningConfig(newTuningConfig(new SingleDimensionPartitionsSpec(7, null, "dim", false), 2, true))
        .build();

    final DataSegmentsWithSchemas dataSegmentsWithSchemas = runTask(compactionTask);
    verifySchema(dataSegmentsWithSchemas);
    final Set<DataSegment> compactedSegments = dataSegmentsWithSchemas.getSegments();
    for (DataSegment segment : compactedSegments) {
      // Expect compaction state to exist as store compaction state by default
      LongSumAggregatorFactory expectedLongSumMetric = new LongSumAggregatorFactory("val", "val");
      Assert.assertSame(SingleDimensionShardSpec.class, segment.getShardSpec().getClass());
      CompactionState expectedState = new CompactionState(
          new SingleDimensionPartitionsSpec(7, null, "dim", false),
          new DimensionsSpec(
              ImmutableList.of(
                  new StringDimensionSchema("ts", DimensionSchema.MultiValueHandling.ARRAY, null),
                  new StringDimensionSchema("dim", DimensionSchema.MultiValueHandling.ARRAY, null)
              )
          ),
          ImmutableList.of(expectedLongSumMetric),
          null,
          compactionTask.getTuningConfig().getIndexSpec(),
          new UniformGranularitySpec(
              Granularities.HOUR,
              Granularities.MINUTE,
              true,
              ImmutableList.of(segment.getInterval())
          ),
          null
      );
      Assert.assertEquals("Compaction state for " + segment.getId(), expectedState, segment.getLastCompactionState());
    }
  }

  @Test
  public void testRunParallelWithRangePartitioningAndNoUpfrontSegmentFetching() throws Exception
  {
    allowSegmentFetchesByCompactionTask = false;

    // Range partitioning is not supported with segment lock yet
    Assume.assumeFalse(lockGranularity == LockGranularity.SEGMENT);
    runIndexTask(null, true);

    final Builder builder = new Builder(
        DATA_SOURCE,
        getSegmentCacheManagerFactory()
    );

    final CompactionTask compactionTask = builder
        .inputSpec(new CompactionIntervalSpec(INTERVAL_TO_INDEX, null))
        .tuningConfig(newTuningConfig(new SingleDimensionPartitionsSpec(7, null, "dim", false), 2, true))
        .dimensionsSpec(new DimensionsSpec(DimensionsSpec.getDefaultSchemas(ImmutableList.of("ts", "dim"))))
        .metricsSpec(new AggregatorFactory[]{new LongSumAggregatorFactory("val", "val")})
        .granularitySpec(
            new ClientCompactionTaskGranularitySpec(
                Granularities.HOUR,
                Granularities.MINUTE,
                true
            )
        )
        .build();

    final DataSegmentsWithSchemas dataSegmentsWithSchemas = runTask(compactionTask);
    verifySchema(dataSegmentsWithSchemas);
    final Set<DataSegment> compactedSegments = dataSegmentsWithSchemas.getSegments();
    for (DataSegment segment : compactedSegments) {
      // Expect compaction state to exist as store compaction state by default
      LongSumAggregatorFactory expectedLongSumMetric = new LongSumAggregatorFactory("val", "val");
      Assert.assertSame(SingleDimensionShardSpec.class, segment.getShardSpec().getClass());
      CompactionState expectedState = new CompactionState(
          new SingleDimensionPartitionsSpec(7, null, "dim", false),
          new DimensionsSpec(DimensionsSpec.getDefaultSchemas(ImmutableList.of("ts", "dim"))),
          ImmutableList.of(expectedLongSumMetric),
          null,
          compactionTask.getTuningConfig().getIndexSpec(),
          new UniformGranularitySpec(
              Granularities.HOUR,
              Granularities.MINUTE,
              true,

              // Umbrella interval for all segments, since CompactionTasks generated a single granularitySpec.
              ImmutableList.of(Intervals.of("2014-01-01/2014-01-01T03:00:00"))
          ),
          null
      );
      Assert.assertEquals("Compaction state for " + segment.getId(), expectedState, segment.getLastCompactionState());
    }
  }

  @Test
  public void testRunParallelWithMultiDimensionRangePartitioning() throws Exception
  {
    allowSegmentFetchesByCompactionTask = true;

    // Range partitioning is not supported with segment lock yet
    Assume.assumeFalse(lockGranularity == LockGranularity.SEGMENT);
    runIndexTask(null, true);

    final Builder builder = new Builder(
        DATA_SOURCE,
        getSegmentCacheManagerFactory()
    );
    final CompactionTask compactionTask = builder
        .inputSpec(new CompactionIntervalSpec(INTERVAL_TO_INDEX, null))
        .tuningConfig(newTuningConfig(
            new DimensionRangePartitionsSpec(7, null, Arrays.asList("dim1", "dim2"), false),
            2,
            true
        )).build();

    final DataSegmentsWithSchemas dataSegmentsWithSchemas = runTask(compactionTask);
    verifySchema(dataSegmentsWithSchemas);
    final Set<DataSegment> compactedSegments = dataSegmentsWithSchemas.getSegments();
    for (DataSegment segment : compactedSegments) {
      // Expect compaction state to exist as store compaction state by default
      LongSumAggregatorFactory expectedLongSumMetric = new LongSumAggregatorFactory("val", "val");
      Assert.assertSame(DimensionRangeShardSpec.class, segment.getShardSpec().getClass());
      CompactionState expectedState = new CompactionState(
          new DimensionRangePartitionsSpec(7, null, Arrays.asList("dim1", "dim2"), false),
          new DimensionsSpec(
              ImmutableList.of(
                  new StringDimensionSchema("ts", DimensionSchema.MultiValueHandling.ARRAY, null),
                  new StringDimensionSchema("dim", DimensionSchema.MultiValueHandling.ARRAY, null)
              )
          ),
          ImmutableList.of(expectedLongSumMetric),
          null,
          compactionTask.getTuningConfig().getIndexSpec(),
          new UniformGranularitySpec(
              Granularities.HOUR,
              Granularities.MINUTE,
              true,
              ImmutableList.of(segment.getInterval())
          ),
          null
      );
      Assert.assertEquals("Compaction state for " + segment.getId(), expectedState, segment.getLastCompactionState());
    }
  }

  @Test
  public void testRunParallelWithRangePartitioningWithSingleTask() throws Exception
  {
    allowSegmentFetchesByCompactionTask = true;

    // Range partitioning is not supported with segment lock yet
    Assume.assumeFalse(lockGranularity == LockGranularity.SEGMENT);
    runIndexTask(null, true);

    final Builder builder = new Builder(
        DATA_SOURCE,
        getSegmentCacheManagerFactory()
    );
    final CompactionTask compactionTask = builder
        .inputSpec(new CompactionIntervalSpec(INTERVAL_TO_INDEX, null))
        .tuningConfig(newTuningConfig(new SingleDimensionPartitionsSpec(7, null, "dim", false), 1, true))
        .build();

    final DataSegmentsWithSchemas dataSegmentsWithSchemas = runTask(compactionTask);
    verifySchema(dataSegmentsWithSchemas);
    final Set<DataSegment> compactedSegments = dataSegmentsWithSchemas.getSegments();
    for (DataSegment segment : compactedSegments) {
      // Expect compaction state to exist as store compaction state by default
      LongSumAggregatorFactory expectedLongSumMetric = new LongSumAggregatorFactory("val", "val");
      Assert.assertSame(SingleDimensionShardSpec.class, segment.getShardSpec().getClass());
      CompactionState expectedState = new CompactionState(
          new SingleDimensionPartitionsSpec(7, null, "dim", false),
          new DimensionsSpec(
              ImmutableList.of(
                  new StringDimensionSchema("ts", DimensionSchema.MultiValueHandling.ARRAY, null),
                  new StringDimensionSchema("dim", DimensionSchema.MultiValueHandling.ARRAY, null)
              )
          ),
          ImmutableList.of(expectedLongSumMetric),
          null,
          compactionTask.getTuningConfig().getIndexSpec(),
          new UniformGranularitySpec(
              Granularities.HOUR,
              Granularities.MINUTE,
              true,
              ImmutableList.of(segment.getInterval())
          ),
          null
      );
      Assert.assertEquals("Compaction state for " + segment.getId(), expectedState, segment.getLastCompactionState());
    }
  }

  @Test
  public void testRunParallelWithMultiDimensionRangePartitioningWithSingleTask() throws Exception
  {
    allowSegmentFetchesByCompactionTask = true;

    // Range partitioning is not supported with segment lock yet
    Assume.assumeFalse(lockGranularity == LockGranularity.SEGMENT);
    runIndexTask(null, true);

    final Builder builder = new Builder(
        DATA_SOURCE,
        getSegmentCacheManagerFactory()
    );
    final CompactionTask compactionTask = builder
        .inputSpec(new CompactionIntervalSpec(INTERVAL_TO_INDEX, null))
        .tuningConfig(newTuningConfig(
            new DimensionRangePartitionsSpec(7, null, Arrays.asList("dim1", "dim2"), false),
            1,
            true
        )).build();

    final DataSegmentsWithSchemas dataSegmentsWithSchemas = runTask(compactionTask);
    verifySchema(dataSegmentsWithSchemas);
    final Set<DataSegment> compactedSegments = dataSegmentsWithSchemas.getSegments();
    for (DataSegment segment : compactedSegments) {
      // Expect compaction state to exist as store compaction state by default
      LongSumAggregatorFactory expectedLongSumMetric = new LongSumAggregatorFactory("val", "val");
      Assert.assertSame(DimensionRangeShardSpec.class, segment.getShardSpec().getClass());
      CompactionState expectedState = new CompactionState(
          new DimensionRangePartitionsSpec(7, null, Arrays.asList("dim1", "dim2"), false),
          new DimensionsSpec(
              ImmutableList.of(
                  new StringDimensionSchema("ts", DimensionSchema.MultiValueHandling.ARRAY, null),
                  new StringDimensionSchema("dim", DimensionSchema.MultiValueHandling.ARRAY, null)
              )
          ),
          ImmutableList.of(expectedLongSumMetric),
          null,
          compactionTask.getTuningConfig().getIndexSpec(),
          new UniformGranularitySpec(
              Granularities.HOUR,
              Granularities.MINUTE,
              true,
              ImmutableList.of(segment.getInterval())
          ),
          null
      );
      Assert.assertEquals("Compaction state for " + segment.getId(), expectedState, segment.getLastCompactionState());
    }
  }

  @Test
  public void testRunCompactionStateNotStoreIfContextSetToFalse()
  {
    allowSegmentFetchesByCompactionTask = true;
    runIndexTask(null, true);

    final Builder builder = new Builder(
        DATA_SOURCE,
        getSegmentCacheManagerFactory()
    );
    final CompactionTask compactionTask = builder
        .inputSpec(new CompactionIntervalSpec(INTERVAL_TO_INDEX, null))
        .tuningConfig(AbstractParallelIndexSupervisorTaskTest.DEFAULT_TUNING_CONFIG_FOR_PARALLEL_INDEXING)
        .context(ImmutableMap.of(Tasks.STORE_COMPACTION_STATE_KEY, false))
        .build();

    final DataSegmentsWithSchemas dataSegmentsWithSchemas = runTask(compactionTask);
    verifySchema(dataSegmentsWithSchemas);
    final Set<DataSegment> compactedSegments = dataSegmentsWithSchemas.getSegments();

    for (DataSegment segment : compactedSegments) {
      Assert.assertSame(
          lockGranularity == LockGranularity.TIME_CHUNK ? NumberedShardSpec.class : NumberedOverwriteShardSpec.class,
          segment.getShardSpec().getClass()
      );
      // Expect compaction state to exist as store compaction state by default
      Assert.assertEquals(null, segment.getLastCompactionState());
    }
  }

  @Test
  public void testRunCompactionWithFilterShouldStoreInState() throws Exception
  {
    allowSegmentFetchesByCompactionTask = true;
    runIndexTask(null, true);

    final Builder builder = new Builder(
        DATA_SOURCE,
        getSegmentCacheManagerFactory()
    );
    final CompactionTask compactionTask = builder
        .inputSpec(new CompactionIntervalSpec(INTERVAL_TO_INDEX, null))
        .tuningConfig(AbstractParallelIndexSupervisorTaskTest.DEFAULT_TUNING_CONFIG_FOR_PARALLEL_INDEXING)
        .transformSpec(new CompactionTransformSpec(new SelectorDimFilter("dim", "a", null)))
        .build();

    final DataSegmentsWithSchemas dataSegmentsWithSchemas = runTask(compactionTask);
    verifySchema(dataSegmentsWithSchemas);
    final Set<DataSegment> compactedSegments = dataSegmentsWithSchemas.getSegments();

    Assert.assertEquals(3, compactedSegments.size());

    for (DataSegment segment : compactedSegments) {
      Assert.assertSame(
          lockGranularity == LockGranularity.TIME_CHUNK ? NumberedShardSpec.class : NumberedOverwriteShardSpec.class,
          segment.getShardSpec().getClass()
      );
      LongSumAggregatorFactory expectedLongSumMetric = new LongSumAggregatorFactory("val", "val");
      CompactionState expectedState = new CompactionState(
          new DynamicPartitionsSpec(null, Long.MAX_VALUE),
          new DimensionsSpec(
              ImmutableList.of(
                  new StringDimensionSchema("ts", DimensionSchema.MultiValueHandling.ARRAY, null),
                  new StringDimensionSchema("dim", DimensionSchema.MultiValueHandling.ARRAY, null)
              )
          ),
          ImmutableList.of(expectedLongSumMetric),
          compactionTask.getTransformSpec(),
          compactionTask.getTuningConfig().getIndexSpec(),
          new UniformGranularitySpec(
              Granularities.HOUR,
              Granularities.MINUTE,
              true,
              ImmutableList.of(segment.getInterval())
          ),
          null
      );
      Assert.assertEquals("Compaction state for " + segment.getId(), expectedState, segment.getLastCompactionState());
    }
  }

  @Test
  public void testRunCompactionWithNewMetricsShouldStoreInState() throws Exception
  {
    allowSegmentFetchesByCompactionTask = true;
    runIndexTask(null, true);

    final Builder builder = new Builder(
        DATA_SOURCE,
        getSegmentCacheManagerFactory()
    );
    final CompactionTask compactionTask = builder
        .inputSpec(new CompactionIntervalSpec(INTERVAL_TO_INDEX, null))
        .tuningConfig(AbstractParallelIndexSupervisorTaskTest.DEFAULT_TUNING_CONFIG_FOR_PARALLEL_INDEXING)
        .metricsSpec(new AggregatorFactory[]{
            new CountAggregatorFactory("cnt"),
            new LongSumAggregatorFactory("val", "val")
        })
        .build();

    final DataSegmentsWithSchemas dataSegmentsWithSchemas = runTask(compactionTask);
    verifySchema(dataSegmentsWithSchemas);
    final Set<DataSegment> compactedSegments = dataSegmentsWithSchemas.getSegments();

    Assert.assertEquals(3, compactedSegments.size());

    for (DataSegment segment : compactedSegments) {
      Assert.assertSame(
          lockGranularity == LockGranularity.TIME_CHUNK ? NumberedShardSpec.class : NumberedOverwriteShardSpec.class,
          segment.getShardSpec().getClass()
      );
      CountAggregatorFactory expectedCountMetric = new CountAggregatorFactory("cnt");
      LongSumAggregatorFactory expectedLongSumMetric = new LongSumAggregatorFactory("val", "val");
      CompactionState expectedState = new CompactionState(
          new DynamicPartitionsSpec(null, Long.MAX_VALUE),
          new DimensionsSpec(
              ImmutableList.of(
                  new StringDimensionSchema("ts", DimensionSchema.MultiValueHandling.ARRAY, null),
                  new StringDimensionSchema("dim", DimensionSchema.MultiValueHandling.ARRAY, null)
              )
          ),
          ImmutableList.of(expectedCountMetric, expectedLongSumMetric),
          compactionTask.getTransformSpec(),
          compactionTask.getTuningConfig().getIndexSpec(),
          new UniformGranularitySpec(
              Granularities.HOUR,
              Granularities.MINUTE,
              true,
              ImmutableList.of(segment.getInterval())
          ),
          null
      );
      Assert.assertEquals("Compaction state for " + segment.getId(), expectedState, segment.getLastCompactionState());
    }
  }

  @Test
  public void testCompactHashAndDynamicPartitionedSegments()
  {
    allowSegmentFetchesByCompactionTask = true;
    runIndexTask(new HashedPartitionsSpec(null, 2, null), false);
    runIndexTask(null, true);
    final Builder builder = new Builder(
        DATA_SOURCE,
        getSegmentCacheManagerFactory()
    );
    final CompactionTask compactionTask = builder
        .inputSpec(new CompactionIntervalSpec(INTERVAL_TO_INDEX, null))
        .tuningConfig(AbstractParallelIndexSupervisorTaskTest.DEFAULT_TUNING_CONFIG_FOR_PARALLEL_INDEXING)
        .build();

    final DataSegmentsWithSchemas dataSegmentsWithSchemas = runTask(compactionTask);
    verifySchema(dataSegmentsWithSchemas);
    final Set<DataSegment> compactedSegments = dataSegmentsWithSchemas.getSegments();

    final Map<Interval, List<DataSegment>> intervalToSegments = SegmentUtils.groupSegmentsByInterval(
        compactedSegments
    );
    Assert.assertEquals(3, intervalToSegments.size());
    Assert.assertEquals(
        ImmutableSet.of(
            Intervals.of("2014-01-01T00/PT1H"),
            Intervals.of("2014-01-01T01/PT1H"),
            Intervals.of("2014-01-01T02/PT1H")
        ),
        intervalToSegments.keySet()
    );
    for (Entry<Interval, List<DataSegment>> entry : intervalToSegments.entrySet()) {
      final List<DataSegment> segmentsInInterval = entry.getValue();
      Assert.assertEquals(1, segmentsInInterval.size());
      final ShardSpec shardSpec = segmentsInInterval.get(0).getShardSpec();
      if (lockGranularity == LockGranularity.TIME_CHUNK) {
        Assert.assertSame(NumberedShardSpec.class, shardSpec.getClass());
        final NumberedShardSpec numberedShardSpec = (NumberedShardSpec) shardSpec;
        Assert.assertEquals(0, numberedShardSpec.getPartitionNum());
        Assert.assertEquals(1, numberedShardSpec.getNumCorePartitions());
      } else {
        Assert.assertSame(NumberedOverwriteShardSpec.class, shardSpec.getClass());
        final NumberedOverwriteShardSpec numberedShardSpec = (NumberedOverwriteShardSpec) shardSpec;
        Assert.assertEquals(PartitionIds.NON_ROOT_GEN_START_PARTITION_ID, numberedShardSpec.getPartitionNum());
        Assert.assertEquals(1, numberedShardSpec.getAtomicUpdateGroupSize());
      }
    }
  }

  @Test
  public void testCompactRangeAndDynamicPartitionedSegments()
  {
    allowSegmentFetchesByCompactionTask = true;
    runIndexTask(new SingleDimensionPartitionsSpec(2, null, "dim", false), false);
    runIndexTask(null, true);
    final Builder builder = new Builder(
        DATA_SOURCE,
        getSegmentCacheManagerFactory()
    );
    final CompactionTask compactionTask = builder
        .inputSpec(new CompactionIntervalSpec(INTERVAL_TO_INDEX, null))
        .tuningConfig(AbstractParallelIndexSupervisorTaskTest.DEFAULT_TUNING_CONFIG_FOR_PARALLEL_INDEXING)
        .build();

    final DataSegmentsWithSchemas dataSegmentsWithSchemas = runTask(compactionTask);
    verifySchema(dataSegmentsWithSchemas);
    final Set<DataSegment> compactedSegments = dataSegmentsWithSchemas.getSegments();

    final Map<Interval, List<DataSegment>> intervalToSegments = SegmentUtils.groupSegmentsByInterval(
        compactedSegments
    );
    Assert.assertEquals(3, intervalToSegments.size());
    Assert.assertEquals(
        ImmutableSet.of(
            Intervals.of("2014-01-01T00/PT1H"),
            Intervals.of("2014-01-01T01/PT1H"),
            Intervals.of("2014-01-01T02/PT1H")
        ),
        intervalToSegments.keySet()
    );
    for (Entry<Interval, List<DataSegment>> entry : intervalToSegments.entrySet()) {
      final List<DataSegment> segmentsInInterval = entry.getValue();
      Assert.assertEquals(1, segmentsInInterval.size());
      final ShardSpec shardSpec = segmentsInInterval.get(0).getShardSpec();
      if (lockGranularity == LockGranularity.TIME_CHUNK) {
        Assert.assertSame(NumberedShardSpec.class, shardSpec.getClass());
        final NumberedShardSpec numberedShardSpec = (NumberedShardSpec) shardSpec;
        Assert.assertEquals(0, numberedShardSpec.getPartitionNum());
        Assert.assertEquals(1, numberedShardSpec.getNumCorePartitions());
      } else {
        Assert.assertSame(NumberedOverwriteShardSpec.class, shardSpec.getClass());
        final NumberedOverwriteShardSpec numberedShardSpec = (NumberedOverwriteShardSpec) shardSpec;
        Assert.assertEquals(PartitionIds.NON_ROOT_GEN_START_PARTITION_ID, numberedShardSpec.getPartitionNum());
        Assert.assertEquals(1, numberedShardSpec.getAtomicUpdateGroupSize());
      }
    }
  }

  @Test
  public void testDruidInputSourceCreateSplitsWithIndividualSplits() throws Exception
  {
    allowSegmentFetchesByCompactionTask = true;
    runIndexTask(null, true);

    List<InputSplit<List<WindowedSegmentId>>> splits = Lists.newArrayList(
        DruidInputSource.createSplits(
            null,
            getCoordinatorClient(),
            DATA_SOURCE,
            INTERVAL_TO_INDEX,
            new SegmentsSplitHintSpec(null, 1)
        )
    );

    List<DataSegment> segments = new ArrayList<>(
        getCoordinatorClient().fetchUsedSegments(
            DATA_SOURCE,
            ImmutableList.of(INTERVAL_TO_INDEX)
        ).get()
    );

    Set<String> segmentIdsFromSplits = new HashSet<>();
    Set<String> segmentIdsFromCoordinator = new HashSet<>();
    Assert.assertEquals(segments.size(), splits.size());
    for (int i = 0; i < segments.size(); i++) {
      segmentIdsFromCoordinator.add(segments.get(i).getId().toString());
      segmentIdsFromSplits.add(splits.get(i).get().get(0).getSegmentId());
    }
    Assert.assertEquals(segmentIdsFromCoordinator, segmentIdsFromSplits);
  }

  @Test
  public void testCompactionDropSegmentsOfInputIntervalIfDropFlagIsSet() throws Exception
  {
    allowSegmentFetchesByCompactionTask = true;
    runIndexTask(null, true);

    Collection<DataSegment> usedSegments = getCoordinatorClient().fetchUsedSegments(
        DATA_SOURCE,
        ImmutableList.of(INTERVAL_TO_INDEX)
    ).get();
    Assert.assertEquals(3, usedSegments.size());
    for (DataSegment segment : usedSegments) {
      Assert.assertTrue(Granularities.HOUR.isAligned(segment.getInterval()));
    }

    final Builder builder = new Builder(
        DATA_SOURCE,
        getSegmentCacheManagerFactory()
    );
    final CompactionTask compactionTask = builder
        // Set the dropExisting flag to true in the IOConfig of the compaction task
        .inputSpec(new CompactionIntervalSpec(INTERVAL_TO_INDEX, null), true)
        .tuningConfig(AbstractParallelIndexSupervisorTaskTest.DEFAULT_TUNING_CONFIG_FOR_PARALLEL_INDEXING)
        .granularitySpec(new ClientCompactionTaskGranularitySpec(Granularities.MINUTE, null, null))
        .build();

    final DataSegmentsWithSchemas dataSegmentsWithSchemas = runTask(compactionTask);
    verifySchema(dataSegmentsWithSchemas);

    usedSegments = getCoordinatorClient().fetchUsedSegments(
        DATA_SOURCE,
        ImmutableList.of(INTERVAL_TO_INDEX)
    ).get();
    // All the HOUR segments got covered by tombstones even if we do not have all MINUTES segments fully covering the 3 HOURS interval.
    // In fact, we only have 3 minutes of data out of the 3 hours interval.
    Assert.assertEquals(180, usedSegments.size());
    int tombstonesCount = 0;
    for (DataSegment segment : usedSegments) {
      Assert.assertTrue(Granularities.MINUTE.isAligned(segment.getInterval()));
      if (segment.isTombstone()) {
        tombstonesCount++;
      }
    }
    Assert.assertEquals(177, tombstonesCount);
  }

  @Test
  public void testCompactionDoesNotDropSegmentsIfDropFlagNotSet() throws Exception
  {
    allowSegmentFetchesByCompactionTask = true;
    runIndexTask(null, true);

    Collection<DataSegment> usedSegments = getCoordinatorClient().fetchUsedSegments(
        DATA_SOURCE,
        ImmutableList.of(INTERVAL_TO_INDEX)
    ).get();
    Assert.assertEquals(3, usedSegments.size());
    for (DataSegment segment : usedSegments) {
      Assert.assertTrue(Granularities.HOUR.isAligned(segment.getInterval()));
    }

    final Builder builder = new Builder(
        DATA_SOURCE,
        getSegmentCacheManagerFactory()
    );
    final CompactionTask compactionTask = builder
        .inputSpec(new CompactionIntervalSpec(INTERVAL_TO_INDEX, null))
        .tuningConfig(AbstractParallelIndexSupervisorTaskTest.DEFAULT_TUNING_CONFIG_FOR_PARALLEL_INDEXING)
        .granularitySpec(new ClientCompactionTaskGranularitySpec(Granularities.MINUTE, null, null))
        .build();

    final DataSegmentsWithSchemas dataSegmentsWithSchemas = runTask(compactionTask);
    verifySchema(dataSegmentsWithSchemas);

    usedSegments = getCoordinatorClient().fetchUsedSegments(
        DATA_SOURCE,
        ImmutableList.of(INTERVAL_TO_INDEX)
    ).get();
    // All the HOUR segments did not get dropped since MINUTES segments did not fully covering the 3 HOURS interval.
    Assert.assertEquals(6, usedSegments.size());
    int hourSegmentCount = 0;
    int minuteSegmentCount = 0;
    for (DataSegment segment : usedSegments) {
      if (Granularities.MINUTE.isAligned(segment.getInterval())) {
        minuteSegmentCount++;
      }
      if (Granularities.MINUTE.isAligned(segment.getInterval())) {
        hourSegmentCount++;
      }
    }
    Assert.assertEquals(3, hourSegmentCount);
    Assert.assertEquals(3, minuteSegmentCount);
  }



  @Test
  public void testRunParallelWithProjections()
  {
    allowSegmentFetchesByCompactionTask = true;
    runIndexTaskWithProjections(null, true);

    final Builder builder = new Builder(
        DATA_SOURCE,
        getSegmentCacheManagerFactory()
    );
    final CompactionTask compactionTask = builder
        .inputSpec(new CompactionIntervalSpec(INTERVAL_TO_INDEX, null))
        .tuningConfig(AbstractParallelIndexSupervisorTaskTest.DEFAULT_TUNING_CONFIG_FOR_PARALLEL_INDEXING)
        .build();

    final DataSegmentsWithSchemas dataSegmentsWithSchemas = runTask(compactionTask);
    verifySchema(dataSegmentsWithSchemas);
    final Set<DataSegment> compactedSegments = dataSegmentsWithSchemas.getSegments();

    for (DataSegment segment : compactedSegments) {
      Assert.assertSame(
          lockGranularity == LockGranularity.TIME_CHUNK ? NumberedShardSpec.class : NumberedOverwriteShardSpec.class,
          segment.getShardSpec().getClass()
      );
      // Expect compaction state to exist as store compaction state by default
      CompactionState expectedState = new CompactionState(
          new DynamicPartitionsSpec(null, Long.MAX_VALUE),
          new DimensionsSpec(
              ImmutableList.of(
                  new StringDimensionSchema("ts", DimensionSchema.MultiValueHandling.ARRAY, null),
                  new StringDimensionSchema("dim", DimensionSchema.MultiValueHandling.ARRAY, null),
                  new LongDimensionSchema("val")
              )
          ),
          Collections.emptyList(),
          null,
          compactionTask.getTuningConfig().getIndexSpec(),
          new UniformGranularitySpec(
              Granularities.HOUR,
              Granularities.MINUTE,
              true,
              ImmutableList.of(segment.getInterval())
          ),
          ImmutableList.of(PROJECTION_SPEC)
      );
      Assert.assertEquals("Compaction state for " + segment.getId(), expectedState, segment.getLastCompactionState());
    }
  }

  @Test
  public void testRunParallelAddProjections()
  {
    allowSegmentFetchesByCompactionTask = true;
    runIndexTaskWithProjections(null, true);

    final Builder builder = new Builder(
        DATA_SOURCE,
        getSegmentCacheManagerFactory()
    );
    final AggregateProjectionSpec addProjection = new AggregateProjectionSpec(
        "projection2",
        VirtualColumns.EMPTY,
        null,
        new AggregatorFactory[]{
            new LongSumAggregatorFactory("val", "val")
        }
    );
    final CompactionTask compactionTask = builder
        .inputSpec(new CompactionIntervalSpec(INTERVAL_TO_INDEX, null))
        .tuningConfig(AbstractParallelIndexSupervisorTaskTest.DEFAULT_TUNING_CONFIG_FOR_PARALLEL_INDEXING)
        .projections(
            ImmutableList.of(
                PROJECTION_SPEC,
                addProjection
            )
        )
        .build();

    final DataSegmentsWithSchemas dataSegmentsWithSchemas = runTask(compactionTask);
    verifySchema(dataSegmentsWithSchemas);
    final Set<DataSegment> compactedSegments = dataSegmentsWithSchemas.getSegments();

    for (DataSegment segment : compactedSegments) {
      Assert.assertSame(
          lockGranularity == LockGranularity.TIME_CHUNK ? NumberedShardSpec.class : NumberedOverwriteShardSpec.class,
          segment.getShardSpec().getClass()
      );
      // Expect compaction state to exist as store compaction state by default
      CompactionState expectedState = new CompactionState(
          new DynamicPartitionsSpec(null, Long.MAX_VALUE),
          new DimensionsSpec(
              ImmutableList.of(
                  new StringDimensionSchema("ts", DimensionSchema.MultiValueHandling.ARRAY, null),
                  new StringDimensionSchema("dim", DimensionSchema.MultiValueHandling.ARRAY, null),
                  new LongDimensionSchema("val")
              )
          ),
          Collections.emptyList(),
          null,
          compactionTask.getTuningConfig().getIndexSpec(),
          new UniformGranularitySpec(
              Granularities.HOUR,
              Granularities.MINUTE,
              true,
              ImmutableList.of(segment.getInterval())
          ),
          ImmutableList.of(PROJECTION_SPEC, addProjection)
      );
      Assert.assertEquals("Compaction state for " + segment.getId(), expectedState, segment.getLastCompactionState());
    }
  }

  @Override
  protected TaskToolbox createTaskToolbox(Task task, TaskActionClient actionClient) throws IOException
  {
    final TaskToolbox baseToolbox = super.createTaskToolbox(task, actionClient);
    if (allowSegmentFetchesByCompactionTask) {
      return baseToolbox;
    } else {
      return new TaskToolbox.Builder(baseToolbox)
          .segmentCacheManager(new NoopSegmentCacheManager())
          .build();
    }
  }

  private void runIndexTask(@Nullable PartitionsSpec partitionsSpec, boolean appendToExisting)
  {
    ParallelIndexIOConfig ioConfig = new ParallelIndexIOConfig(
        new LocalInputSource(inputDir, "druid*"),
        new CsvInputFormat(
            Arrays.asList("ts", "dim", "val"),
            "|",
            null,
            false,
            0,
            null
        ),
        appendToExisting,
        null
    );
    ParallelIndexTuningConfig tuningConfig = newTuningConfig(partitionsSpec, 2, !appendToExisting);
    ParallelIndexSupervisorTask indexTask = new ParallelIndexSupervisorTask(
        null,
        null,
        null,
        new ParallelIndexIngestionSpec(
            DataSchema.builder()
                      .withDataSource(DATA_SOURCE)
                      .withTimestamp(new TimestampSpec("ts", "auto", null))
                      .withDimensions(DimensionsSpec.getDefaultSchemas(Arrays.asList("ts", "dim")))
                      .withAggregators(new LongSumAggregatorFactory("val", "val"))
                      .withGranularity(
                          new UniformGranularitySpec(
                              Granularities.HOUR,
                              Granularities.MINUTE,
                              ImmutableList.of(INTERVAL_TO_INDEX)
                          )
                      )
                      .build(),
            ioConfig,
            tuningConfig
        ),
        null
    );

    Assert.assertEquals(
        Collections.singleton(
            new ResourceAction(
                new Resource(
                    LocalInputSource.TYPE_KEY,
                    ResourceType.EXTERNAL
                ), Action.READ
            )),
        indexTask.getInputSourceResources()
    );

    final DataSegmentsWithSchemas dataSegmentsWithSchemas = runTask(indexTask);
    verifySchema(dataSegmentsWithSchemas);
  }

  private void runIndexTaskWithProjections(@Nullable PartitionsSpec partitionsSpec, boolean appendToExisting)
  {
    ParallelIndexIOConfig ioConfig = new ParallelIndexIOConfig(
        new LocalInputSource(inputDir, "druid*"),
        new CsvInputFormat(
            Arrays.asList("ts", "dim", "val"),
            "|",
            null,
            false,
            0,
            null
        ),
        appendToExisting,
        null
    );
    ParallelIndexTuningConfig tuningConfig = newTuningConfig(partitionsSpec, 2, !appendToExisting);
    ParallelIndexSupervisorTask indexTask = new ParallelIndexSupervisorTask(
        null,
        null,
        null,
        new ParallelIndexIngestionSpec(
            DataSchema.builder()
                      .withDataSource(DATA_SOURCE)
                      .withTimestamp(new TimestampSpec("ts", "auto", null))
                      .withDimensions(
                          DimensionsSpec.builder()
                                        .setDimensions(
                                            ImmutableList.of(
                                                new StringDimensionSchema("ts"),
                                                new StringDimensionSchema("dim"),
                                                new LongDimensionSchema("val")
                                            )
                                        )
                                        .build()
                      )
                      .withProjections(
                          ImmutableList.of(PROJECTION_SPEC)
                      )
                      .withGranularity(
                          new UniformGranularitySpec(
                              Granularities.HOUR,
                              Granularities.MINUTE,
                              ImmutableList.of(INTERVAL_TO_INDEX)
                          )
                      )
                      .build(),
            ioConfig,
            tuningConfig
        ),
        null
    );

    Assert.assertEquals(
        Collections.singleton(
            new ResourceAction(
                new Resource(
                    LocalInputSource.TYPE_KEY,
                    ResourceType.EXTERNAL
                ), Action.READ
            )),
        indexTask.getInputSourceResources()
    );

    final DataSegmentsWithSchemas dataSegmentsWithSchemas = runTask(indexTask);
    verifySchema(dataSegmentsWithSchemas);
  }

  private DataSegmentsWithSchemas runTask(Task task)
  {
    task.addToContext(Tasks.FORCE_TIME_CHUNK_LOCK_KEY, lockGranularity == LockGranularity.TIME_CHUNK);
    TaskStatus status = getIndexingServiceClient().runAndWait(task);
    Assert.assertEquals(status.toString(), TaskState.SUCCESS, status.getStatusCode());
    return getIndexingServiceClient().getSegmentAndSchemas(task);
  }
}

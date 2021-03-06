/**
 * Copyright Ambud Sharma
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * 		http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.srotya.sidewinder.core.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import com.google.gson.Gson;
import com.srotya.sidewinder.core.filters.ComplexTagFilter;
import com.srotya.sidewinder.core.filters.ComplexTagFilter.ComplexFilterType;
import com.srotya.sidewinder.core.filters.SimpleTagFilter;
import com.srotya.sidewinder.core.filters.SimpleTagFilter.FilterType;
import com.srotya.sidewinder.core.filters.TagFilter;
import com.srotya.sidewinder.core.rpc.Point;
import com.srotya.sidewinder.core.rpc.Tag;
import com.srotya.sidewinder.core.storage.disk.DiskMalloc;
import com.srotya.sidewinder.core.storage.disk.DiskStorageEngine;
import com.srotya.sidewinder.core.storage.mem.MemStorageEngine;
import com.srotya.sidewinder.core.utils.BackgrounThreadFactory;
import com.srotya.sidewinder.core.utils.InvalidFilterException;
import com.srotya.sidewinder.core.utils.MiscUtils;
import com.srotya.sidewinder.core.utils.TimeUtils;

/**
 * Unit tests for {@link DiskStorageEngine}
 * 
 * @author ambud
 */
@RunWith(Parameterized.class)
public class TestStorageEngine {

	public static ScheduledExecutorService bgTasks;
	private Map<String, String> conf;
	@Parameter
	public Class<StorageEngine> clazz;
	private StorageEngine engine;

	@BeforeClass
	public static void beforeClass() {
		bgTasks = Executors.newScheduledThreadPool(1, new BackgrounThreadFactory("te1"));
	}

	@SuppressWarnings("rawtypes")
	@Parameters(name = "{index}: Storage Engine Impl Class: {0}")
	public static Collection classes() {
		List<Object[]> implementations = new ArrayList<>();
		implementations.add(new Object[] { DiskStorageEngine.class });
		implementations.add(new Object[] { MemStorageEngine.class });
		return implementations;
	}

	@AfterClass
	public static void afterClass() {
		bgTasks.shutdown();
	}

	@Before
	public void before() throws InstantiationException, IllegalAccessException, IOException {
		conf = new HashMap<>();
		MiscUtils.delete(new File("target/se-common-test/"));
		conf.put("data.dir", "target/se-common-test/data");
		conf.put("index.dir", "target/se-common-test/index");
		engine = clazz.newInstance();
	}

	@After
	public void after() throws IOException {
		engine.shutdown();
		MiscUtils.delete(new File("target/se-common-test/"));
	}

	@Test
	public void testUpdateTimeSeriesRetention() throws IOException {
		engine.configure(conf, bgTasks);
		engine.getOrCreateMeasurement("db1", "m1");
		engine.updateDefaultTimeSeriesRetentionPolicy("db1", 10);
		assertEquals(10, engine.getDbMetadataMap().get("db1").getRetentionHours());
		Measurement m = engine.getOrCreateMeasurement("db1", "m1");
		int buckets = m.getRetentionBuckets().get();
		engine.updateDefaultTimeSeriesRetentionPolicy("db1", 30);
		engine.updateTimeSeriesRetentionPolicy("db1", 30);
		engine.updateTimeSeriesRetentionPolicy("db1", "m1", 40);
		assertTrue(buckets != m.getRetentionBuckets().get());
	}

	@Test
	public void testMetadataOperations() throws Exception {
		engine.configure(conf, bgTasks);
		Measurement m = engine.getOrCreateMeasurement("db1", "m1");
		m.getOrCreateSeries(Arrays.asList(Tag.newBuilder().setTagKey("t").setTagValue("1").build()), false);

		assertEquals(1, engine.getAllMeasurementsForDb("db1").size());
		assertEquals(1, engine.getTagKeysForMeasurement("db1", "m1").size());
		try {
			engine.getAllMeasurementsForDb("db2");
			fail("Exception must be thrown");
		} catch (ItemNotFoundException e) {
		}
		try {
			engine.getTagKeysForMeasurement("db1", "m2");
			fail("Exception must be thrown");
		} catch (ItemNotFoundException e) {
		}
		assertEquals(1, engine.getTagsForMeasurement("db1", "m1").size());
	}

	@Test
	public void testMeasurementsLike() throws Exception {
		engine.configure(conf, bgTasks);
		Measurement m = engine.getOrCreateMeasurement("db1", "m1");
		m.getOrCreateSeries(Arrays.asList(Tag.newBuilder().setTagKey("t").setTagValue("1").build()), false);
		m = engine.getOrCreateMeasurement("db1", "t1");
		m.getOrCreateSeries(Arrays.asList(Tag.newBuilder().setTagKey("t").setTagValue("1").build()), false);

		Set<String> measurementsLike = engine.getMeasurementsLike("db1", "m.*");
		assertEquals(1, measurementsLike.size());
		assertEquals(2, engine.getAllMeasurementsForDb("db1").size());
	}

	@Test
	public void testConcurrentOperations() throws Exception {
		engine.configure(conf, bgTasks);
		final long ts = System.currentTimeMillis();
		ExecutorService es = Executors.newFixedThreadPool(2, new BackgrounThreadFactory("wr1"));
		String measurementName = "mmm2";
		String valueFieldName = "v1";
		String dbName = "db9";
		List<Tag> tagd = Arrays.asList(Tag.newBuilder().setTagKey("h").setTagValue("1").build());
		for (int k = 0; k < 2; k++) {
			final int p = k;
			es.submit(() -> {
				long t = ts + p;
				for (int i = 0; i < 100; i++) {
					Point dp = MiscUtils.buildDataPoint(dbName, measurementName, valueFieldName, tagd, t + i * 1000, i);
					try {
						engine.writeDataPointLocked(dp, false);
					} catch (Exception e) {
						e.printStackTrace();
						throw new RuntimeException(e);
					}
				}
				System.err.println("Completed writes:" + 100 + " data points");
			});
		}
		es.shutdown();
		es.awaitTermination(100, TimeUnit.SECONDS);
		assertEquals(1, engine.getAllMeasurementsForDb(dbName).size());
		assertEquals(1, engine.getMeasurementMap().size());
		try {
			Series timeSeries = engine.getTimeSeries(dbName, measurementName, valueFieldName, tagd);
			assertNotNull(timeSeries);
		} catch (ItemNotFoundException e) {
			fail("Time series must exist");
		}
		List<SeriesOutput> queryDataPoints = engine.queryDataPoints(dbName, measurementName, valueFieldName, ts,
				ts + 220 * 1000, null);
		assertEquals(1, queryDataPoints.size());
		SeriesOutput next = queryDataPoints.iterator().next();
		assertEquals(200, next.getDataPoints().size());
	}

	@Test
	public void testConfigureTimeBuckets() throws ItemNotFoundException, IOException {
		long ts = System.currentTimeMillis();
		conf.put(StorageEngine.DEFAULT_BUCKET_SIZE, String.valueOf(4096 * 10));
		List<Tag> tagd = Arrays.asList(Tag.newBuilder().setTagKey("t").setTagValue("e").build());
		try {
			engine.configure(conf, bgTasks);
		} catch (IOException e) {
			fail("No IOException should be thrown");
		}
		try {
			for (int i = 0; i < 10; i++) {
				engine.writeDataPointLocked(
						MiscUtils.buildDataPoint("test", "ss", "value", tagd, ts + (i * 4096 * 1000), 2.2), false);
			}
		} catch (Exception e) {
			e.printStackTrace();
			fail("Engine is initialized, no IO Exception should be thrown:" + e.getMessage());
		}
		List<SeriesOutput> queryDataPoints = engine.queryDataPoints("test", "ss", "value", ts,
				ts + (4096 * 100 * 1000) + 1, new SimpleTagFilter(FilterType.EQUALS, "t", "e"));
		assertTrue(queryDataPoints.size() >= 1);
	}

	@Test
	public void testConfigure() throws IOException {
		List<Tag> tagd = Arrays.asList(Tag.newBuilder().setTagKey("t").setTagValue("e").build());
		try {
			engine.writeDataPointLocked(
					MiscUtils.buildDataPoint("test", "ss", "value", tagd, System.currentTimeMillis(), 2.2), false);
			fail("Engine not initialized, shouldn't be able to write a datapoint");
		} catch (Exception e) {
		}

		try {
			// FileUtils.forceDelete(new File("target/db2/"));
			MiscUtils.delete(new File("targer/db2/"));
			HashMap<String, String> map = new HashMap<>();
			map.put("index.dir", "target/db2/index");
			map.put("data.dir", "target/db2/data");
			map.put("default.series.retention.hours", "32");
			engine.configure(map, bgTasks);
		} catch (IOException e) {
			fail("No IOException should be thrown");
		}
		try {
			engine.writeDataPointLocked(
					MiscUtils.buildDataPoint("test", "ss", "value", tagd, System.currentTimeMillis(), 2.2), false);
			String md = new String(Files.readAllBytes(new File("target/db2/data/test/.md").toPath()),
					Charset.forName("utf8"));
			DBMetadata metadata = new Gson().fromJson(md, DBMetadata.class);
			assertEquals(32, metadata.getRetentionHours());
		} catch (Exception e) {
			e.printStackTrace();
			fail("Engine is initialized, no IO Exception should be thrown:" + e.getMessage());
		}
		engine.shutdown();
	}

	@Test
	public void testQueryDataPoints() throws IOException, ItemNotFoundException {
		List<Tag> tagd = Arrays.asList(Tag.newBuilder().setTagKey("test").setTagValue("e").build());

		MiscUtils.delete(new File("target/db15/"));
		HashMap<String, String> map = new HashMap<>();
		map.put("index.dir", "target/db15/index");
		map.put("data.dir", "target/db15/data");
		engine.configure(map, bgTasks);
		long ts = System.currentTimeMillis();
		Map<String, Measurement> db = engine.getOrCreateDatabase("test3", 24, map);
		assertEquals(0, db.size());
		engine.writeDataPointLocked(MiscUtils.buildDataPoint("test3", "cpu", "value", tagd, ts, 1), false);
		engine.writeDataPointLocked(MiscUtils.buildDataPoint("test3", "cpu", "value", tagd, ts + (400 * 60000), 4),
				false);
		assertEquals(1, engine.getOrCreateMeasurement("test3", "cpu").getSeriesKeys().size());
		List<SeriesOutput> queryDataPoints = null;
		try {
			queryDataPoints = engine.queryDataPoints("test3", "cpu", "value", ts, ts + (400 * 60000), null, null);
		} catch (Exception e) {

		}
		try {
			engine.queryDataPoints("test123", "cpu", "value", ts, ts + (400 * 60000), null, null);
		} catch (ItemNotFoundException e) {
		}
		try {
			engine.queryDataPoints("test3", "123cpu", "value", ts, ts + (400 * 60000), null, null);
		} catch (ItemNotFoundException e) {
		}
		assertTrue(!engine.isMeasurementFieldFP("test3", "cpu", "value"));
		try {
			engine.isMeasurementFieldFP("test3", "test", "test");
			fail("Measurement should not exist");
		} catch (Exception e) {
		}
		assertEquals(2, queryDataPoints.iterator().next().getDataPoints().size());
		assertEquals(ts, queryDataPoints.iterator().next().getDataPoints().get(0).getTimestamp());
		assertEquals(ts + (400 * 60000), queryDataPoints.iterator().next().getDataPoints().get(1).getTimestamp());
		try {
			engine.dropDatabase("test3");
		} catch (Exception e) {
		}
		assertEquals(0, engine.getOrCreateMeasurement("test3", "cpu").getSeriesKeys().size());
		engine.shutdown();
	}

	@Test
	public void testGarbageCollector() throws Exception {
		conf.put(StorageEngine.GC_DELAY, "1");
		conf.put(StorageEngine.GC_FREQUENCY, "10");
		conf.put(StorageEngine.DEFAULT_BUCKET_SIZE, "4096");
		conf.put(DiskMalloc.CONF_MEASUREMENT_BUF_INCREMENT_SIZE, "4096");
		conf.put(DiskMalloc.CONF_MEASUREMENT_FILE_INCREMENT, "10240");
		conf.put(DiskMalloc.CONF_MEASUREMENT_FILE_MAX, String.valueOf(1024 * 100));
		conf.put(StorageEngine.RETENTION_HOURS, "28");
		engine.configure(conf, bgTasks);
		long base = 1497720452566L;
		long ts = base;
		List<Tag> tagd = Arrays.asList(Tag.newBuilder().setTagKey("test").setTagValue("1").build());
		for (int i = 320; i >= 0; i--) {
			engine.writeDataPointLocked(
					MiscUtils.buildDataPoint("test", "cpu2", "value", tagd, base - (3600_000 * i), 2L), false);
		}
		engine.getMeasurementMap().get("test").get("cpu2").collectGarbage(null);
		List<SeriesOutput> queryDataPoints = engine.queryDataPoints("test", "cpu2", "value", ts - (3600_000 * 320), ts,
				null, null);
		assertEquals(27, queryDataPoints.iterator().next().getDataPoints().size());
		assertTrue(!engine.isMeasurementFieldFP("test", "cpu2", "value"));
	}

	@Test
	public void testGetMeasurementsLike() throws Exception {
		List<Tag> tagd = Arrays.asList(Tag.newBuilder().setTagKey("test").setTagValue("e").build());
		engine.configure(conf, bgTasks);
		engine.writeDataPointLocked(
				MiscUtils.buildDataPoint("test", "cpu", "value", tagd, System.currentTimeMillis(), 2L), false);
		engine.writeDataPointLocked(
				MiscUtils.buildDataPoint("test", "mem", "value", tagd, System.currentTimeMillis() + 10, 3L), false);
		engine.writeDataPointLocked(
				MiscUtils.buildDataPoint("test", "netm", "value", tagd, System.currentTimeMillis() + 20, 5L), false);
		Set<String> result = engine.getMeasurementsLike("test", " ");
		assertEquals(3, result.size());

		result = engine.getMeasurementsLike("test", "c.*");
		assertEquals(1, result.size());

		result = engine.getMeasurementsLike("test", ".*m.*");
		assertEquals(2, result.size());
		engine.shutdown();
	}

	// @Test
	// public void testSeriesToDataPointConversion() throws IOException {
	// List<DataPoint> points = new ArrayList<>();
	// long headerTimestamp = System.currentTimeMillis();
	// ByteBuffer buf = ByteBuffer.allocate(100);
	// TimeWriter timeSeries = new ByzantineTimestampWriter();
	// timeSeries.configure(buf, true, 1);
	// timeSeries.setHeaderTimestamp(headerTimestamp);
	// timeSeries.add(headerTimestamp);
	// Series.seriesToDataPoints(Arrays.asList("test=1"), points, timeSeries, null,
	// null, false);
	// assertEquals(1, points.size());
	// points.clear();
	//
	// Predicate timepredicate = new BetweenPredicate(Long.MAX_VALUE,
	// Long.MAX_VALUE);
	// Series.seriesToDataPoints(Arrays.asList("test=1"), points, timeSeries,
	// timepredicate, null, false);
	// assertEquals(0, points.size());
	// }

	@Test
	public void testSeriesBucketLookups() throws IOException, ItemNotFoundException {
		engine.configure(conf, bgTasks);
		engine.startup();
		String dbName = "test1";
		String measurementName = "cpu";
		List<Tag> tagd = Arrays.asList(Tag.newBuilder().setTagKey("test").setTagValue("1").build());

		long ts = 1483923600000L;
		System.out.println("Base timestamp=" + new Date(ts));

		for (int i = 0; i < 100; i++) {
			engine.writeDataPointLocked(
					MiscUtils.buildDataPoint(dbName, measurementName, "value", tagd, ts + (i * 60000), 2.2), false);
		}
		long endTs = ts + 99 * 60000;

		// validate all points are returned with a full range query
		List<SeriesOutput> points = engine.queryDataPoints(dbName, measurementName, "value", ts, endTs,
				new SimpleTagFilter(FilterType.EQUALS, "test", "1"));
		assertEquals(ts, points.iterator().next().getDataPoints().get(0).getTimestamp());
		assertEquals(endTs, points.iterator().next().getDataPoints()
				.get(points.iterator().next().getDataPoints().size() - 1).getTimestamp());

		// validate ts-1 yields the same result
		points = engine.queryDataPoints(dbName, measurementName, "value", ts - 1, endTs,
				new SimpleTagFilter(FilterType.EQUALS, "test", "1"));
		assertEquals(ts, points.iterator().next().getDataPoints().get(0).getTimestamp());
		System.out.println("Value count:" + points.iterator().next().getDataPoints().size());
		assertEquals(endTs, points.iterator().next().getDataPoints()
				.get(points.iterator().next().getDataPoints().size() - 1).getTimestamp());

		// validate ts+1 yields correct result
		points = engine.queryDataPoints(dbName, measurementName, "value", ts + 1, endTs,
				new SimpleTagFilter(FilterType.EQUALS, "test", "1"));
		assertEquals(ts + 60000, points.iterator().next().getDataPoints().get(0).getTimestamp());
		assertEquals(endTs, points.iterator().next().getDataPoints()
				.get(points.iterator().next().getDataPoints().size() - 1).getTimestamp());

		// validate that points have been written to 2 different buckets
		assertTrue(TimeUtils.getTimeBucket(TimeUnit.MILLISECONDS, ts, 4096) != TimeUtils
				.getTimeBucket(TimeUnit.MILLISECONDS, endTs, 4096));
		// calculate base timestamp for the second bucket
		long baseTs2 = ((long) TimeUtils.getTimeBucket(TimeUnit.MILLISECONDS, endTs, 4096)) * 1000;
		System.out.println("Bucket2 base timestamp=" + new Date(baseTs2));

		// validate random seek with deliberate time offset
		points = engine.queryDataPoints(dbName, measurementName, "value", ts, baseTs2,
				new SimpleTagFilter(FilterType.EQUALS, "test", "1"));
		assertEquals("Invalid first entry:" + new Date(points.iterator().next().getDataPoints().get(0).getTimestamp()),
				ts, points.iterator().next().getDataPoints().get(0).getTimestamp());
		assertEquals("Invalid first entry:" + (baseTs2 - ts), (baseTs2 / 60000) * 60000, points.iterator().next()
				.getDataPoints().get(points.iterator().next().getDataPoints().size() - 1).getTimestamp());

		points = engine.queryDataPoints(dbName, measurementName, "value", baseTs2, endTs,
				new SimpleTagFilter(FilterType.EQUALS, "test", "1"));
		assertEquals("Invalid first entry:" + new Date(points.iterator().next().getDataPoints().get(0).getTimestamp()),
				(baseTs2 - ts), (baseTs2 / 60000) * 60000,
				points.iterator().next().getDataPoints().get(0).getTimestamp());
		assertEquals("Invalid first entry:" + endTs, endTs, points.iterator().next().getDataPoints()
				.get(points.iterator().next().getDataPoints().size() - 1).getTimestamp());

		// validate correct results when time range is incorrectly swapped i.e.
		// end time is smaller than start time
		points = engine.queryDataPoints(dbName, measurementName, "value", endTs - 1, baseTs2,
				new SimpleTagFilter(FilterType.EQUALS, "test", "1"));
		assertEquals("Invalid first entry:" + new Date(points.iterator().next().getDataPoints().get(0).getTimestamp()),
				(baseTs2 - ts), (baseTs2 / 60000) * 60000,
				points.iterator().next().getDataPoints().get(0).getTimestamp());
		assertEquals("Invalid first entry:" + endTs, endTs - 60000, points.iterator().next().getDataPoints()
				.get(points.iterator().next().getDataPoints().size() - 1).getTimestamp());
		engine.shutdown();
	}

	@Test
	public void testBaseTimeSeriesWrites() throws Exception {
		engine.configure(conf, bgTasks);
		engine.startup();

		final List<Tag> tagd = Arrays.asList(Tag.newBuilder().setTagKey("p").setTagValue("1").build());
		final long ts1 = System.currentTimeMillis();
		ExecutorService es = Executors.newCachedThreadPool();
		for (int k = 0; k < 10; k++) {
			final int p = k;
			es.submit(() -> {
				long ts = System.currentTimeMillis();
				for (int i = 0; i < 1000; i++) {
					try {
						engine.writeDataPointLocked(
								MiscUtils.buildDataPoint("test", "helo" + p, "value", tagd, ts + i * 60, ts + i),
								false);
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			});
		}
		es.shutdown();
		es.awaitTermination(10, TimeUnit.SECONDS);

		System.out.println("Write time:" + (System.currentTimeMillis() - ts1) + "\tms");
	}

	@Test
	public void testAddAndReaderDataPoints() throws Exception {
		File file = new File("target/db8/");
		if (file.exists()) {
			MiscUtils.delete(file);
		}
		engine.configure(conf, bgTasks);
		long curr = 1497720452566L;

		String dbName = "test";
		String measurementName = "cpu";
		String valueFieldName = "value";
		try {
			engine.writeDataPointLocked(
					MiscUtils.buildDataPoint(dbName, measurementName, valueFieldName, null, curr, 2.2 * 0), false);
			fail("Must reject the above datapoint due to missing tags");
		} catch (Exception e) {
		}
		List<Tag> tagd = Arrays.asList(Tag.newBuilder().setTagKey(dbName).setTagValue("1").build());
		for (int i = 1; i <= 3; i++) {
			engine.writeDataPointLocked(
					MiscUtils.buildDataPoint(dbName, measurementName, valueFieldName, tagd, curr + i, 2.2 * i), false);
		}
		assertEquals(1, engine.getAllMeasurementsForDb(dbName).size());
		Map<ByteString, FieldReaderIterator[]> queryReaders = engine.queryReaders(dbName, measurementName,
				Arrays.asList(valueFieldName), false, curr, curr + 3);
		int count = 0;
		for (Entry<ByteString, FieldReaderIterator[]> entry : queryReaders.entrySet()) {
			assertEquals(2, entry.getValue().length);
			while (true) {
				try {
					long[] extracted = FieldReaderIterator.extracted(entry.getValue());
					assertEquals(2.2 * (count + 1), Double.longBitsToDouble(extracted[0]), 0.01);
					count++;
				} catch (RejectException e) {
					break;
				}
			}
		}
		assertEquals(3, count);
		assertTrue(engine.checkIfExists(dbName, measurementName));
		try {
			engine.checkIfExists(dbName + "1");
		} catch (Exception e) {
		}
		engine.dropMeasurement(dbName, measurementName);
		assertEquals(0, engine.getAllMeasurementsForDb(dbName).size());
		engine.shutdown();
	}

	@Test
	public void testTagFiltering() throws Exception {
		engine.configure(conf, bgTasks);
		long curr = 1497720452566L;
		String dbName = "test";
		String measurementName = "cpu";
		String valueFieldName = "value";

		for (int i = 1; i <= 3; i++) {
			List<Tag> tagd = Arrays.asList(Tag.newBuilder().setTagKey("p").setTagValue(String.valueOf(i)).build(),
					Tag.newBuilder().setTagKey("k").setTagValue(String.valueOf(i + 7)).build());
			engine.writeDataPointLocked(
					MiscUtils.buildDataPoint(dbName, measurementName, valueFieldName, tagd, curr, 2 * i), false);
		}

		for (int i = 1; i <= 3; i++) {
			List<Tag> tagd = Arrays.asList(Tag.newBuilder().setTagKey("p").setTagValue(String.valueOf(i)).build(),
					Tag.newBuilder().setTagKey("k").setTagValue(String.valueOf(i + 12)).build());
			engine.writeDataPointLocked(
					MiscUtils.buildDataPoint(dbName, measurementName, valueFieldName + "2", tagd, curr, 2 * i), false);
		}
		Set<String> tags = engine.getTagKeysForMeasurement(dbName, measurementName);
		System.out.println("Tags:" + tags);
		assertEquals(2, tags.size());
		// Set<String> series = engine.getSeriesIdsWhereTags(dbName, measurementName,
		// Arrays.asList("p=" + String.valueOf(1)));
		// assertEquals(2, series.size());

		TagFilter tagFilterTree = new ComplexTagFilter(ComplexFilterType.OR, Arrays.asList(
				new SimpleTagFilter(FilterType.EQUALS, "p", "1"), new SimpleTagFilter(FilterType.EQUALS, "p", "2")));
		Set<String> series = engine.getTagFilteredRowKeys(dbName, measurementName, tagFilterTree);
		assertEquals(4, series.size());

		System.out.println(engine.getTagKeysForMeasurement(dbName, measurementName));
		tagFilterTree = new ComplexTagFilter(ComplexFilterType.AND, Arrays.asList(
				new SimpleTagFilter(FilterType.EQUALS, "p", "1"), new SimpleTagFilter(FilterType.EQUALS, "k", "8")));
		series = engine.getTagFilteredRowKeys(dbName, measurementName, tagFilterTree);
		System.out.println("Series::" + series);
		assertEquals(1, series.size());
		engine.shutdown();
	}

	@Test
	public void testAddAndReadDataPointsWithTagFilters() throws Exception {
		engine.configure(conf, bgTasks);
		long curr = 1497720452566L;
		String dbName = "test";
		String measurementName = "cpu";
		String valueFieldName = "value";
		String tag = "host123123";

		for (int i = 1; i <= 3; i++) {
			List<Tag> tagd = Arrays.asList(Tag.newBuilder().setTagKey(tag).setTagValue(String.valueOf(i)).build(),
					Tag.newBuilder().setTagKey(tag).setTagValue(String.valueOf(i + 1)).build());
			engine.writeDataPointLocked(
					MiscUtils.buildDataPoint(dbName, measurementName, valueFieldName, tagd, curr + i, 2 * i), false);
		}
		assertEquals(1, engine.getAllMeasurementsForDb(dbName).size());

		SimpleTagFilter filter1 = new SimpleTagFilter(FilterType.EQUALS, "host123123", "1");
		SimpleTagFilter filter2 = new SimpleTagFilter(FilterType.EQUALS, "host123123", "2");

		List<SeriesOutput> queryDataPoints = engine.queryDataPoints(dbName, measurementName, valueFieldName, curr,
				curr + 3, new ComplexTagFilter(ComplexFilterType.OR, Arrays.asList(filter1, filter2)), null, null);
		assertEquals(2, queryDataPoints.size());
		int i = 1;
		assertEquals(1, queryDataPoints.iterator().next().getDataPoints().size());
		queryDataPoints.sort(new Comparator<SeriesOutput>() {

			@Override
			public int compare(SeriesOutput o1, SeriesOutput o2) {
				return o1.getTags().toString().compareTo(o2.getTags().toString());
			}
		});
		for (SeriesOutput list : queryDataPoints) {
			for (DataPoint dataPoint : list.getDataPoints()) {
				assertEquals(curr + i, dataPoint.getTimestamp());
				i++;
			}
		}
		Set<String> tags = engine.getTagKeysForMeasurement(dbName, measurementName);
		assertEquals(new HashSet<>(Arrays.asList(tag)), tags);
		assertEquals(new HashSet<>(Arrays.asList("1", "2", "3", "4")),
				engine.getTagValuesForMeasurement(dbName, measurementName, tag));
		Set<String> fieldsForMeasurement = engine.getFieldsForMeasurement(dbName, measurementName);
		assertEquals(new HashSet<>(Arrays.asList(valueFieldName, Series.TS)), fieldsForMeasurement);

		try {
			engine.getTagKeysForMeasurement(dbName + "1", measurementName);
			fail("This measurement should not exist");
		} catch (Exception e) {
		}

		try {
			engine.getTagKeysForMeasurement(dbName, measurementName + "1");
			fail("This measurement should not exist");
		} catch (Exception e) {
		}

		try {
			engine.getFieldsForMeasurement(dbName + "1", measurementName);
			fail("This measurement should not exist");
		} catch (Exception e) {
		}

		try {
			engine.getFieldsForMeasurement(dbName, measurementName + "1");
			fail("This measurement should not exist");
		} catch (Exception e) {
		}
		engine.shutdown();
	}

	public static Point build(String dbName, String measurementName, String valueFieldName, List<Tag> tags, long ts,
			long v) {
		return Point.newBuilder().setDbName(dbName).setMeasurementName(measurementName).addAllTags(tags)
				.setTimestamp(ts).addValueFieldName(valueFieldName).addValue(v).addFp(false).build();
	}

	public static Point build(String dbName, String measurementName, String valueFieldName, List<Tag> tags, long ts,
			double v) {
		return Point.newBuilder().setDbName(dbName).setMeasurementName(measurementName).addAllTags(tags)
				.setTimestamp(ts).addValueFieldName(valueFieldName).addValue(Double.doubleToLongBits(v)).addFp(true)
				.build();
	}

	@Test
	public void testAddAndReadDataPoints() throws Exception {
		engine.configure(conf, bgTasks);
		long curr = System.currentTimeMillis();

		String dbName = "test";
		String measurementName = "cpu";
		String valueFieldName = "value";
		try {
			engine.writeDataPointLocked(build(dbName, measurementName, valueFieldName, null, curr, 2 * 0), false);
			fail("Must reject the above datapoint due to missing tags");
		} catch (Exception e) {
		}
		Tag tag = Tag.newBuilder().setTagKey("host").setTagValue("123123").build();
		for (int i = 1; i <= 3; i++) {
			engine.writeDataPointLocked(MiscUtils.buildDataPoint(dbName, measurementName, valueFieldName,
					Arrays.asList(tag), curr + i, 2 * i), false);
		}
		assertEquals(1, engine.getAllMeasurementsForDb(dbName).size());
		List<SeriesOutput> queryDataPoints = engine.queryDataPoints(dbName, measurementName, valueFieldName, curr,
				curr + 3, null);
		assertEquals(1, queryDataPoints.size());
		int i = 1;
		assertEquals(3, queryDataPoints.iterator().next().getDataPoints().size());
		List<List<DataPoint>> output = new ArrayList<>();
		for (SeriesOutput series : queryDataPoints) {
			output.add(series.getDataPoints());
		}
		for (List<DataPoint> list : output) {
			for (DataPoint dataPoint : list) {
				assertEquals(curr + i, dataPoint.getTimestamp());
				i++;
			}
		}
		Set<String> tags = engine.getTagKeysForMeasurement(dbName, measurementName);
		assertEquals(new HashSet<>(Arrays.asList("host")), tags);
		Set<String> fieldsForMeasurement = engine.getFieldsForMeasurement(dbName, measurementName);
		assertEquals(new HashSet<>(Arrays.asList(valueFieldName, Series.TS)), fieldsForMeasurement);

		try {
			engine.getTagKeysForMeasurement(dbName + "1", measurementName);
			fail("This measurement should not exist");
		} catch (Exception e) {
		}

		try {
			engine.getTagKeysForMeasurement(dbName, measurementName + "1");
			fail("This measurement should not exist");
		} catch (Exception e) {
		}

		try {
			engine.getFieldsForMeasurement(dbName + "1", measurementName);
			fail("This measurement should not exist");
		} catch (Exception e) {
		}

		try {
			engine.getFieldsForMeasurement(dbName, measurementName + "1");
			fail("This measurement should not exist");
		} catch (Exception e) {
		}
		engine.shutdown();
	}

	@Test
	public void testCompaction() throws IOException, InterruptedException {
		conf.put("default.bucket.size", "409600");
		conf.put("use.query.pool", "false");
		conf.put("compaction.delay", "1");
		conf.put("compaction.frequency", "1");
		engine.configure(conf, bgTasks);
		final long curr = 1497720652566L;

		String dbName = "test";
		String measurementName = "cpu";
		String valueFieldName = "value";
		List<Tag> tags = Arrays.asList(Tag.newBuilder().setTagKey("host").setTagValue("123123").build());
		for (int i = 1; i <= 10000; i++) {
			engine.writeDataPointLocked(
					MiscUtils.buildDataPoint(dbName, measurementName, Arrays.asList(valueFieldName), tags,
							curr + i * 1000, Arrays.asList(Double.doubleToLongBits(i * 1.1)), Arrays.asList(true)),
					false);
		}

		long ts = System.nanoTime();
		List<SeriesOutput> queryDataPoints = engine.queryDataPoints(dbName, measurementName, valueFieldName,
				curr - 1000, curr + 10000 * 1000 + 1, null, null);
		ts = System.nanoTime() - ts;
		System.out.println("Before compaction:" + ts / 1000 + "us");
		assertEquals(1, queryDataPoints.size());
		assertEquals(10000, queryDataPoints.iterator().next().getDataPoints().size());
		List<DataPoint> dataPoints = queryDataPoints.iterator().next().getDataPoints();
		for (int i = 1; i <= 10000; i++) {
			DataPoint dp = dataPoints.get(i - 1);
			assertEquals("Bad ts:" + i, curr + i * 1000, dp.getTimestamp());
			assertEquals(dp.getValue(), i * 1.1, 0.001);
		}
		Measurement m = engine.getOrCreateMeasurement(dbName, measurementName);
		Series series = m.getOrCreateSeries(tags, false);
		SortedMap<Integer, Map<String, Field>> bucketRawMap = series.getBucketMap();
		assertEquals(1, bucketRawMap.size());
		int size = (int) MiscUtils.bucketCounter(series);
		assertTrue(size > 2);
		Thread.sleep(4000);
		ts = System.nanoTime();
		queryDataPoints = engine.queryDataPoints(dbName, measurementName, valueFieldName, curr - 1,
				curr + 20000 * 1000 + 1, null, null);
		ts = System.nanoTime() - ts;
		System.out.println("After compaction:" + ts / 1000 + "us");
		bucketRawMap = series.getBucketMap();
		assertEquals(2, bucketRawMap.values().iterator().next().size());
		assertEquals(10000, queryDataPoints.iterator().next().getDataPoints().size());
		dataPoints = queryDataPoints.iterator().next().getDataPoints();
		for (int i = 1; i <= 10000; i++) {
			DataPoint dp = dataPoints.get(i - 1);
			assertEquals("Bad ts:" + i, curr + i * 1000, dp.getTimestamp());
			assertEquals(dp.getValue(), i * 1.1, 0.001);
		}
	}

	@SuppressWarnings("unchecked")
	@Test
	public void testCompactionThreadSafety() throws IOException, InterruptedException {
		conf.put("default.bucket.size", "409600");
		conf.put("use.query.pool", "false");
		engine.configure(conf, bgTasks);
		final long curr = 1497720652566L;

		String dbName = "test";
		String measurementName = "cpu";
		String valueFieldName = "value";
		List<Tag> tags = Arrays.asList(Tag.newBuilder().setTagKey("host").setTagValue("123123").build());
		for (int i = 1; i <= 10000; i++) {
			engine.writeDataPointLocked(
					MiscUtils.buildDataPoint(dbName, measurementName, Arrays.asList(valueFieldName), tags,
							curr + i * 1000, Arrays.asList(Double.doubleToLongBits(i * 1.1)), Arrays.asList(true)),
					false);
		}

		long ts = System.nanoTime();
		List<SeriesOutput> queryDataPoints = engine.queryDataPoints(dbName, measurementName, valueFieldName,
				curr - 1000, curr + 10000 * 1000 + 1, null, null);
		ts = System.nanoTime() - ts;
		System.out.println("Before compaction:" + ts / 1000 + "us");
		assertEquals(1, queryDataPoints.size());
		assertEquals(10000, queryDataPoints.iterator().next().getDataPoints().size());
		List<DataPoint> dataPoints = queryDataPoints.iterator().next().getDataPoints();
		for (int i = 1; i <= 10000; i++) {
			DataPoint dp = dataPoints.get(i - 1);
			assertEquals("Bad ts:" + i, curr + i * 1000, dp.getTimestamp());
			assertEquals(dp.getValue(), i * 1.1, 0.001);
		}
		Measurement m = engine.getOrCreateMeasurement(dbName, measurementName);
		Series series = m.getOrCreateSeries(tags, false);
		SortedMap<Integer, Map<String, Field>> bucketRawMap = series.getBucketMap();
		assertEquals(1, bucketRawMap.size());
		int size = (int) MiscUtils.bucketCounter(series);
		assertTrue(size > 2);
		final AtomicBoolean bool = new AtomicBoolean(false);
		bgTasks.execute(() -> {
			while (!bool.get()) {
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
					break;
				}
			}
			try {
				series.addPoint(Point.newBuilder().setTimestamp(curr + 1000 * 10001).addValueFieldName("vf1")
						.addFp(true).addValue(Double.doubleToLongBits(1.11)).build(), m);
				bool.set(false);
			} catch (IOException e) {
				e.printStackTrace();
				return;
			}
		});
		series.compact(m, l -> {
			bool.set(true);
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			if (!bool.get()) {
				throw new RuntimeException("Synchronized block failed");
			}
		});
		Thread.sleep(100);
		assertTrue(!bool.get());
	}

	@Test
	public void testWriteIndexAndQuery() throws IOException, InvalidFilterException {
		final long curr = 1497720652566L;
		conf.put("default.bucket.size", "409600");
		conf.put("use.query.pool", "false");
		engine.configure(conf, bgTasks);
		Point dp = Point.newBuilder().setDbName("db1").setMeasurementName("cpu").setTimestamp(curr)
				.addValueFieldName("user").addValue(20).addFp(false).addValueFieldName("system").addValue(11)
				.addFp(false).addTags(Tag.newBuilder().setTagKey("host").setTagValue("test1.xyz.com").build()).build();
		engine.writeDataPointLocked(dp, true);

		dp = Point.newBuilder().setDbName("db1").setMeasurementName("cpu").setTimestamp(curr).addValueFieldName("user")
				.addValue(24).addFp(false).addValueFieldName("system").addValue(12).addFp(false)
				.addTags(Tag.newBuilder().setTagKey("host").setTagValue("test2.xyz.com").build()).build();
		engine.writeDataPointLocked(dp, true);

		dp = Point.newBuilder().setDbName("db1").setMeasurementName("cpu").setTimestamp(curr).addValueFieldName("user")
				.addValue(21).addFp(false).addValueFieldName("system").addValue(10).addFp(false)
				.addTags(Tag.newBuilder().setTagKey("host").setTagValue("test3.xyz.com").build()).build();
		engine.writeDataPointLocked(dp, true);

		TagFilter filter = MiscUtils.buildTagFilter("host=test1.xyz.com");
		List<SeriesOutput> series = engine.queryDataPoints("db1", "cpu", "system", curr - 1, curr + 10, filter);
		assertEquals(1, series.size());
		assertEquals(1, series.get(0).getDataPoints().size());
		assertEquals(curr, series.get(0).getDataPoints().get(0).getTimestamp());
		assertEquals(11, series.get(0).getDataPoints().get(0).getLongValue());

		series = engine.queryDataPoints("db1", "cpu", ".*", curr - 1, curr + 10, filter);
		assertEquals("Error:" + series, 2, series.size());
		assertEquals(1, series.get(0).getDataPoints().size());
		assertEquals(1, series.get(1).getDataPoints().size());
		assertEquals(curr, series.get(0).getDataPoints().get(0).getTimestamp());
		assertEquals(curr, series.get(1).getDataPoints().get(0).getTimestamp());
		assertEquals(11, series.get(0).getDataPoints().get(0).getLongValue());
		assertEquals(20, series.get(1).getDataPoints().get(0).getLongValue());

		dp = Point.newBuilder().setDbName("db1").setMeasurementName("cpu").setTimestamp(curr + 1000)
				.addValueFieldName("user").addValue(20).addFp(false).addValueFieldName("system").addValue(11)
				.addFp(false).addTags(Tag.newBuilder().setTagKey("host").setTagValue("test1.xyz.com").build()).build();
		engine.writeDataPointLocked(dp, true);

		dp = Point.newBuilder().setDbName("db1").setMeasurementName("cpu").setTimestamp(curr + 1000)
				.addValueFieldName("user").addValue(24).addFp(false).addValueFieldName("system").addValue(12)
				.addFp(false).addTags(Tag.newBuilder().setTagKey("host").setTagValue("test2.xyz.com").build()).build();
		engine.writeDataPointLocked(dp, true);

		dp = Point.newBuilder().setDbName("db1").setMeasurementName("cpu").setTimestamp(curr + 1000)
				.addValueFieldName("user").addValue(21).addFp(false).addValueFieldName("system").addValue(10)
				.addFp(false).addTags(Tag.newBuilder().setTagKey("host").setTagValue("test3.xyz.com").build()).build();
		engine.writeDataPointLocked(dp, true);

		series = engine.queryDataPoints("db1", "cpu", ".*", curr, curr + 10, filter);
		assertEquals(2, series.size());
		assertEquals(1, series.get(0).getDataPoints().size());
		assertEquals(1, series.get(1).getDataPoints().size());
		assertEquals(curr, series.get(0).getDataPoints().get(0).getTimestamp());
		assertEquals(curr, series.get(1).getDataPoints().get(0).getTimestamp());
		assertEquals(11, series.get(0).getDataPoints().get(0).getLongValue());
		assertEquals(20, series.get(1).getDataPoints().get(0).getLongValue());

		series = engine.queryDataPoints("db1", "cpu", ".*", curr, curr + 1001, filter);
		assertEquals(2, series.size());
		assertEquals(2, series.get(0).getDataPoints().size());
		assertEquals(2, series.get(1).getDataPoints().size());
		assertEquals(curr, series.get(0).getDataPoints().get(0).getTimestamp());
		assertEquals(curr, series.get(1).getDataPoints().get(0).getTimestamp());
		assertEquals(11, series.get(0).getDataPoints().get(0).getLongValue());
		assertEquals(20, series.get(1).getDataPoints().get(0).getLongValue());

		assertEquals(curr + 1000, series.get(0).getDataPoints().get(1).getTimestamp());
		assertEquals(curr + 1000, series.get(1).getDataPoints().get(1).getTimestamp());
		assertEquals(11, series.get(0).getDataPoints().get(1).getLongValue());
		assertEquals(20, series.get(1).getDataPoints().get(1).getLongValue());

		filter = MiscUtils.buildTagFilter("host=test1.xyz.com|host=test2.xyz.com");
		series = engine.queryDataPoints("db1", "cpu", "system", curr - 1, curr + 10, filter);
		assertEquals(2, series.size());
		assertEquals(1, series.get(0).getDataPoints().size());
		assertEquals(1, series.get(1).getDataPoints().size());
		assertEquals(curr, series.get(0).getDataPoints().get(0).getTimestamp());
		assertEquals(11, series.get(0).getDataPoints().get(0).getLongValue());
		assertEquals(curr, series.get(1).getDataPoints().get(0).getTimestamp());
		assertEquals(12, series.get(1).getDataPoints().get(0).getLongValue());

		// test bad queries

		// tag filter contains a space therefore it
		filter = MiscUtils.buildTagFilter("host=test1.xyz.com host=test2.xyz.com");
		series = engine.queryDataPoints("db1", "cpu", "system", curr - 1, curr + 10, filter);
		assertEquals(0, series.size());

		try {
			filter = MiscUtils.buildTagFilter("host332&=test1");
			series = engine.queryDataPoints("db1", "cpu", "system", curr - 1, curr + 10, filter);
			fail("Bad tag filter can't succeed the query");
		} catch (Exception e) {
		}

		filter = MiscUtils.buildTagFilter("host~.*.xyz.com");
		series = engine.queryDataPoints("db1", "cpu", "system", curr - 1, curr + 10, filter);
		assertEquals(3, series.size());
	}
}

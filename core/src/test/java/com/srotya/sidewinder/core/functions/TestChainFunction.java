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
package com.srotya.sidewinder.core.functions;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.srotya.sidewinder.core.functions.BasicWindowedFunctions.WindowedMean;
import com.srotya.sidewinder.core.rpc.Tag;
import com.srotya.sidewinder.core.storage.DataPoint;
import com.srotya.sidewinder.core.storage.SeriesOutput;

public class TestChainFunction {

	@Test
	public void testSingleFunction() throws Exception {
		SeriesOutput series = new SeriesOutput("cpu", "test",
				Arrays.asList(Tag.newBuilder().setTagKey("t").setTagValue("1").build(),
						Tag.newBuilder().setTagKey("t").setTagValue("2").build()));
		List<DataPoint> dps = new ArrayList<>();
		long baseTs = 1486617103629L;
		for (int i = 0; i < 4; i++) {
			dps.add(new DataPoint(baseTs + 30_000 * i, 1));
		}
		series.setDataPoints(dps);
		series.setFp(false);
		List<SeriesOutput> seriesList = Arrays.asList(series);

		ChainFunction cf = new ChainFunction();
		ReducingWindowedAggregator rwa = new WindowedMean();
		rwa.init(new Object[] { 70, "smean" });
		cf.init(new Function[] { rwa });

		List<SeriesOutput> apply = cf.apply(seriesList);
		List<DataPoint> result = apply.get(0).getDataPoints();
		assertEquals(2, result.size());
	}

	@Test
	public void testTwoFunctions() throws Exception {
		SeriesOutput series = new SeriesOutput("cpu", "test", Arrays.asList(Tag.newBuilder().setTagKey("t").setTagValue("1").build(),
				Tag.newBuilder().setTagKey("t").setTagValue("2").build()));
		List<DataPoint> dps = new ArrayList<>();
		long baseTs = 1486617103629L;
		for (int i = 0; i < 4; i++) {
			dps.add(new DataPoint(baseTs + 30_000 * i, 1));
		}
		series.setDataPoints(dps);
		series.setFp(false);
		List<SeriesOutput> seriesList = Arrays.asList(series);

		ChainFunction cf = new ChainFunction();
		ReducingWindowedAggregator rwa = new WindowedMean();
		rwa.init(new Object[] { 70, "smean" });
		ReducingWindowedAggregator rwa2 = new WindowedMean();
		rwa2.init(new Object[] { 200, "smean" });
		cf.init(new Function[] { rwa, rwa2 });

		List<SeriesOutput> apply = cf.apply(seriesList);
		List<DataPoint> result = apply.get(0).getDataPoints();
		assertEquals(1, result.size());
		assertEquals(1, result.get(0).getLongValue());
	}

}

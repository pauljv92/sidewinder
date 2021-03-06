/**
s * Copyright Ambud Sharma
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
package com.srotya.sidewinder.core.api;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;

import org.junit.Test;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.srotya.sidewinder.core.rpc.Point;
import com.srotya.sidewinder.core.rpc.Tag;
import com.srotya.sidewinder.core.rpc.Point.Builder;
import com.srotya.sidewinder.core.storage.mem.MemStorageEngine;

/**
 * @author ambud
 */
public class TestDatabaseOpsApi {

	@Test
	public void testQuerySeries() throws IOException {
		MemStorageEngine engine = new MemStorageEngine();
		engine.configure(new HashMap<>(), null);
		engine.startup();

		Builder dp = Point.newBuilder();
		dp.setDbName("test1");
		dp.addFp(false);
		dp.addValue(1L);
		dp.setMeasurementName("cpu");
		dp.setTimestamp(System.currentTimeMillis());
		dp.addAllTags(Arrays.asList(Tag.newBuilder().setTagKey("host").setTagValue("1").build(),
				Tag.newBuilder().setTagKey("vm").setTagValue("1").build()));
		dp.addValueFieldName("value");
		engine.writeDataPointLocked(dp.build(), false);

		DatabaseOpsApi api = new DatabaseOpsApi(engine);
		String querySeries = api.querySeries("test1",
				"2000-12-10T10:10:10<cpu.value.host=1|te=2&vm=1<2020-12-10T10:10:10");
		JsonArray results = new Gson().fromJson(querySeries, JsonArray.class);
		assertEquals(1, results.size());

		querySeries = api.querySeries("test1", "2000-12-10T10:10:10<cpu.value.host=2<2020-12-10T10:10:10");
		results = new Gson().fromJson(querySeries, JsonArray.class);
		assertEquals(0, results.size());

		querySeries = api.querySeries("test1", "2000-12-10T10:10:10<cpu.value.vm=1<2020-12-10T10:10:10");
		results = new Gson().fromJson(querySeries, JsonArray.class);
		assertEquals(1, results.size());

		System.out.println(results);
	}

}

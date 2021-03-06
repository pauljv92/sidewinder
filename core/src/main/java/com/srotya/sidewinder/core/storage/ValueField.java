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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.srotya.sidewinder.core.monitoring.MetricsRegistryService;
import com.srotya.sidewinder.core.predicates.Predicate;
import com.srotya.sidewinder.core.storage.compression.CompressionFactory;
import com.srotya.sidewinder.core.storage.compression.Reader;
import com.srotya.sidewinder.core.storage.compression.RollOverException;
import com.srotya.sidewinder.core.storage.compression.ValueWriter;
import com.srotya.sidewinder.core.storage.compression.Writer;

/**
 * Persistent version of {@link ValueField}. Persistence is provided via keeping
 * series buckets in a text file whenever a new series is added. Series buckets
 * are added not that frequently therefore using a text format is fairly
 * reasonable. This system is prone to instant million file appends at the mark
 * of series bucket time boundary i.e. all series will have bucket changes at
 * the same time however depending on the frequency of writes, this may not be
 * an issue.
 * 
 * @author ambud
 */
public class ValueField implements Field {

	private static final int START_OFFSET = 2;
	private static final Logger logger = Logger.getLogger(ValueField.class.getName());
	private List<ValueWriter> writerList;
	private LinkedByteString fieldId;
	public static double compactionRatio = 1.0;
	public static Class<ValueWriter> compressionClass = CompressionFactory.getValueClassByName("byzantine");
	public static Class<ValueWriter> compactionClass = CompressionFactory.getValueClassByName("gorilla");
	private int tsBucket;

	/**
	 * @param measurement
	 * @param fieldId
	 * @param tsBucket
	 * @param conf
	 * @throws IOException
	 */
	public ValueField(Measurement measurement, LinkedByteString fieldId, int tsBucket, Map<String, String> conf)
			throws IOException {
		writerList = Collections.synchronizedList(new ArrayList<>(4));
		this.fieldId = fieldId;
		this.tsBucket = tsBucket;
		checkAndEnableMethodProfiling();
	}

	private void checkAndEnableMethodProfiling() {
		if (StorageEngine.ENABLE_METHOD_METRICS && MetricsRegistryService.getInstance() != null) {
			logger.finest(() -> "Enabling method metrics for:" + fieldId);
		}
	}

	private ValueWriter getOrCreateValueWriter(Measurement measurement) throws IOException {
		ValueWriter ans = null;
		if (writerList.isEmpty()) {
			ans = createNewWriter(measurement, tsBucket, writerList);
		} else {
			ans = writerList.get(writerList.size() - 1);
		}
		if (ans.isFull()) {
			final ValueWriter ansTmp = ans;
			logger.fine(() -> "Requesting new writer for:" + fieldId + " bucketcount:" + writerList.size() + " pos:"
					+ ansTmp.getPosition());
			ans = createNewWriter(measurement, tsBucket, writerList);
		}
		if (StorageEngine.ENABLE_METHOD_METRICS) {
		}
		return ans;
	}

	private ValueWriter createNewWriter(Measurement measurement, int tsBucket, List<ValueWriter> list)
			throws IOException {
		if (StorageEngine.ENABLE_METHOD_METRICS) {
			// ctx = timerCreateWriter.time();
		}
		BufferObject bufPair = measurement.getMalloc().createNewBuffer(fieldId, tsBucket);
		bufPair.getBuf().put((byte) CompressionFactory.getIdByValueClass(compressionClass));
		bufPair.getBuf().put((byte) list.size());
		ValueWriter writer;
		writer = getWriterInstance(compressionClass);
		writer.setBufferId(bufPair.getBufferId());
		// first byte is used to store compression codec type
		writer.configure(bufPair.getBuf(), true, START_OFFSET);
		list.add(writer);
		logger.fine(() -> "Created new writer for:" + tsBucket + " buckectInfo:" + bufPair.getBufferId());
		if (StorageEngine.ENABLE_METHOD_METRICS) {
			// ctx.stop();
		}
		return writer;
	}

	private ValueWriter getWriterInstance(Class<ValueWriter> compressionClass) {
		try {
			ValueWriter writer = compressionClass.newInstance();
			return writer;
		} catch (InstantiationException | IllegalAccessException e) {
			// should never happen unless the constructors are hidden
			throw new RuntimeException(e);
		}
	}

	/**
	 * Function to check and recover existing bucket map, if one exists.
	 * 
	 * @param bufferEntries
	 * @throws IOException
	 */
	public void loadBucketMap(Measurement measurement, List<BufferObject> bufferEntries) throws IOException {
		logger.fine(() -> "Scanning buffer for:" + fieldId);
		for (BufferObject entry : bufferEntries) {
			ByteBuffer duplicate = entry.getBuf();
			duplicate.rewind();
			ByteBuffer slice = duplicate.slice();
			int codecId = (int) slice.get();
			// int listIndex = (int) slice.get();
			Class<ValueWriter> classById = CompressionFactory.getValueClassById(codecId);
			ValueWriter writer = getWriterInstance(classById);
			if (entry.getBufferId() == null) {
				throw new IOException("Buffer id can't be read:" + " series:" + getFieldId());
			}
			LinkedByteString repairedBufferId = measurement.getMalloc().repairBufferId(fieldId, entry.getBufferId());
			logger.fine(() -> "Loading bucketmap:" + fieldId + "\t" + tsBucket + "bufferid:" + entry.getBufferId());
			writer.setBufferId(repairedBufferId);
			writer.configure(slice, false, START_OFFSET);
			writerList.add(writer);
			logger.fine(() -> "Loaded bucketmap:" + fieldId + "\t" + " bufferid:" + entry.getBufferId());
		}
		sortBucketMap();
	}

	private void sortBucketMap() throws IOException {
		Collections.sort(writerList, new Comparator<ValueWriter>() {

			@Override
			public int compare(ValueWriter o1, ValueWriter o2) {
				return Integer.compare((int) o1.getRawBytes().get(1), (int) o2.getRawBytes().get(1));
			}
		});
		for (int i = 0; i < writerList.size() - 1; i++) {
			ValueWriter writer = writerList.get(i);
			try {
				writer.makeReadOnly(true);
			} catch (Exception e) {
				throw new IOException(e);
			}
		}
	}

	/**
	 * Extract list of readers for the supplied time range and value predicate.
	 * 
	 * Each {@link DataPoint} has the appendFieldValue and appendTags set in it.
	 * 
	 * @param predicate
	 * @param readLock
	 * @return
	 * @throws IOException
	 */
	public FieldReaderIterator queryReader(Predicate predicate, Lock readLock) throws IOException {
		List<Reader> readers = new ArrayList<>();
		readLock.lock();
		for (ValueWriter writer : writerList) {
			readers.add(Field.getReader(writer, predicate));
		}
		readLock.unlock();
		return new FieldReaderIterator().addReader(readers);
	}

	public void addDataPoint(Measurement measurement, long value) throws IOException {
		ValueWriter timeseriesBucket = getOrCreateValueWriter(measurement);
		try {
			timeseriesBucket.add(value);
		} catch (RollOverException e) {
			addDataPoint(measurement, value);
		} catch (NullPointerException e) {
			logger.log(Level.SEVERE, "\n\nNPE occurred for add datapoint operation\n\n", e);
		}
	}

	public List<ValueWriter> getRawWriterList() {
		return writerList;
	}

	/**
	 * @return the seriesId
	 */
	public LinkedByteString getFieldId() {
		return fieldId;
	}

	/**
	 * @param fieldId
	 *            the seriesId to set
	 */
	public void setFieldId(LinkedByteString fieldId) {
		this.fieldId = fieldId;
	}

	@Override
	public String toString() {
		return "Field [writerList=" + writerList + ", fieldId=" + fieldId + ", measurement=" + ", tsBucket=" + tsBucket
				+ "]";
	}

	public void close() throws IOException {
		// TODO close series
	}

	/**
	 * Compacts old Writers into one for every single time bucket, this insures the
	 * buffers are compacted as well as provides an opportunity to use a higher
	 * compression rate algorithm for the bucket. All Writers but the last are
	 * read-only therefore performing operations on them does not impact.
	 * 
	 * @param functions
	 * @return returns null if nothing to compact or empty list if all compaction
	 *         attempts fail
	 * @throws IOException
	 */
	@SafeVarargs
	public final List<Writer> compact(Measurement measurement, Lock writeLock,
			Consumer<List<? extends Writer>>... functions) throws IOException {
		if (StorageEngine.ENABLE_METHOD_METRICS) {
			// ctx = timerCompaction.time();
		}
		// this loop only executes if there are any candidate buffers in the set
		// buckets should be moved out of the compaction set once they are
		// compacted
		// size check is to avoid unnecessary calls and exit fast
		if (writerList.size() <= 1) {
			return null;
		}
		List<Writer> compactedWriter = new ArrayList<>();
		int id = CompressionFactory.getIdByValueClass(compactionClass);
		int listSize = writerList.size() - 1;
		int pointCount = writerList.subList(0, listSize).stream().mapToInt(s -> s.getCount()).sum();
		int total = writerList.subList(0, listSize).stream().mapToInt(s -> s.getPosition()).sum();
		if (total == 0) {
			logger.warning("Ignoring bucket for compaction, not enough bytes. THIS BUG SHOULD BE INVESTIGATED");
			return null;
		}
		ValueWriter writer = getWriterInstance(compactionClass);
		int compactedPoints = 0;
		double bufSize = total * compactionRatio;
		logger.finer("Allocating buffer:" + total + " Vs. " + pointCount * 16 + " max compacted buffer:" + bufSize);
		logger.finer("Getting sublist from:" + 0 + " to:" + (writerList.size() - 1));
		ByteBuffer buf = ByteBuffer.allocateDirect((int) bufSize);
		buf.put((byte) id);
		// since this buffer will be the first one
		buf.put(1, (byte) 0);
		writer.configure(buf, true, START_OFFSET);
		ValueWriter input = writerList.get(0);
		// read the header timestamp
		// read all but the last writer and insert into new temp writer
		try {
			for (int i = 0; i < writerList.size() - 1; i++) {
				input = writerList.get(i);
				Reader reader = input.getReader();
				for (int k = 0; k < reader.getCount(); k++) {
					long pair = reader.read();
					writer.add(pair);
					compactedPoints++;
				}
			}
			writer.makeReadOnly(false);
		} catch (RollOverException e) {
			logger.warning("Buffer filled up; bad compression ratio; not compacting");
			return null;
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Compaction failed due to unknown exception", e);
			return null;
		}
		// get the raw compressed bytes
		ByteBuffer rawBytes = writer.getRawBytes();
		// limit how much data needs to be read from the buffer
		rawBytes.limit(rawBytes.position());
		// convert buffer length request to size of 2
		int size = rawBytes.limit() + 1;
		if (size % 2 != 0) {
			size++;
		}
		rawBytes.rewind();
		// create buffer in measurement
		BufferObject newBuf = measurement.getMalloc().createNewBuffer(fieldId, tsBucket, size);
		logger.fine("Compacted buffer size:" + size + " vs " + total + " countp:" + listSize + " field:" + fieldId);
		LinkedByteString bufferId = newBuf.getBufferId();
		buf = newBuf.getBuf();
		writer = getWriterInstance(compactionClass);
		buf.put(rawBytes);
		writer.setBufferId(bufferId);
		writer.configure(buf, false, START_OFFSET);
		writer.makeReadOnly(false);

		writeLock.lock();
		if (functions != null) {
			for (Consumer<List<? extends Writer>> function : functions) {
				function.accept(writerList);
			}
		}
		size = listSize - 1;
		logger.finest("Compaction debug size differences size:" + size + " listSize:" + listSize + " curr:"
				+ writerList.size());
		for (int i = size; i >= 0; i--) {
			compactedWriter.add(writerList.remove(i));
		}
		writerList.add(0, writer);
		for (int i = 0; i < writerList.size(); i++) {
			writerList.get(i).getRawBytes().put(1, (byte) i);
		}
		logger.fine("Total points:" + compactedPoints + ", original pair count:" + writer.getReader().getCount()
				+ " compression ratio:" + rawBytes.position() + " original:" + total + " newlistlength:"
				+ writerList.size());
		writeLock.unlock();

		if (StorageEngine.ENABLE_METHOD_METRICS) {
			// ctx.stop();
		}
		return compactedWriter;
	}

	/**
	 * Method to help fix bucket writers directly
	 * 
	 * @param measurement
	 * @param bufList
	 * @throws IOException
	 * @throws InstantiationException
	 * @throws IllegalAccessException
	 */
	public void replaceFirstBuckets(Measurement measurement, List<byte[]> bufList)
			throws IOException, InstantiationException, IllegalAccessException {
		synchronized (writerList) {
			// insert writers to list
			List<String> cleanupList = insertOrOverwriteWriters(measurement, bufList, writerList.size() == 0,
					writerList, tsBucket);
			measurement.getMalloc().cleanupBufferIds(new HashSet<>(cleanupList));
		}
	}

	private List<String> insertOrOverwriteWriters(Measurement measurement, List<byte[]> bufList, boolean wasEmpty,
			List<ValueWriter> list, Integer tsBucket)
			throws IOException, InstantiationException, IllegalAccessException {
		List<String> garbageCollectWriters = new ArrayList<>();
		if (!wasEmpty) {
			if (bufList.size() >= list.size()) {
				throw new IllegalArgumentException(
						"Buffer can't be replaced since local buffers are smaller than the replacing buffers");
			}
		}
		for (int i = 0; i < bufList.size(); i++) {
			if (!wasEmpty) {
				ValueWriter removedWriter = list.remove(i);
				garbageCollectWriters.add(removedWriter.getBufferId().toString());
			}
			byte[] bs = bufList.get(i);
			BufferObject bufPair = measurement.getMalloc().createNewBuffer(fieldId, tsBucket, bs.length);
			ByteBuffer buf = bufPair.getBuf();
			buf.put(bs);
			buf.rewind();
			ValueWriter writer = CompressionFactory.getValueClassById(buf.get(0)).newInstance();
			writer.setBufferId(bufPair.getBufferId());
			writer.configure(bufPair.getBuf(), false, START_OFFSET);
			list.add(i, writer);
		}
		return garbageCollectWriters;
	}

	@Override
	public int getWriterCount() {
		return writerList.size();
	}

	@Override
	public List<? extends Writer> getWriters() {
		return writerList;
	}

}

/**
 * Copyright 2017 Ambud Sharma
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
package com.srotya.sidewinder.core.storage.compression.byzantine;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.srotya.sidewinder.core.storage.DataPoint;
import com.srotya.sidewinder.core.storage.LinkedByteString;
import com.srotya.sidewinder.core.storage.compression.Codec;
import com.srotya.sidewinder.core.storage.compression.Writer;

/**
 * A simple delta-of-delta timeseries compression with XOR value compression
 * 
 * @author ambud
 */
@Codec(id = 1, name = "byzantine")
public class ByzantineWriter implements Writer {

	private Lock read;
	private Lock write;
	private long prevTs;
	private long tsDelta;
	private int count;
	private ByteBuffer buf;
	private long prevValue;
	private boolean readOnly;
	private volatile boolean full;
	private int startOffset;
	private LinkedByteString bufferId;

	public ByzantineWriter() {
	}

	/**
	 * For unit testing only
	 * 
	 * @param headerTimestamp
	 * @param buf
	 */
	protected ByzantineWriter(long headerTimestamp, byte[] buf) {
		this();
		this.buf = ByteBuffer.allocateDirect(buf.length);
		setHeaderTimestamp(headerTimestamp);
		ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
		read = lock.readLock();
		write = lock.writeLock();
	}

	@Override
	public void configure(ByteBuffer buf, boolean isNew, int startOffset, boolean isLocking)
			throws IOException {
		this.startOffset = startOffset;
		if (isLocking) {
			ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
			read = lock.readLock();
			write = lock.writeLock();
		} else {
			read = new NoLock();
			write = new NoLock();
		}
		this.buf = buf;
		this.buf.position(startOffset);
		if (isNew) {
			this.buf.putInt(0);
		} else {
			forwardCursorToEnd();
		}
	}

	private void forwardCursorToEnd() throws IOException {
		ByzantineReader reader = new ByzantineReader(buf, startOffset);
		count = reader.getPairCount();
		for (int i = 0; i < count; i++) {
			reader.readPair();
		}
		tsDelta = reader.getDelta();
		prevTs = reader.getPrevTs();
		prevValue = reader.getPrevValue();
	}

	@Override
	public void write(DataPoint dp) throws IOException {
		try {
			write.lock();
			writeDataPoint(dp.getTimestamp(), dp.getLongValue());
		} finally {
			write.unlock();
		}
	}

	@Override
	public void write(List<DataPoint> dps) throws IOException {
		write.lock();
		try {
			for (Iterator<DataPoint> itr = dps.iterator(); itr.hasNext();) {
				DataPoint dp = itr.next();
				writeDataPoint(dp.getTimestamp(), dp.getLongValue());
			}
		} finally {
			write.unlock();
		}
	}

	/**
	 * 
	 * @param dp
	 * @throws IOException
	 */
	private void writeDataPoint(long timestamp, long value) throws IOException {
		if (readOnly) {
			throw WRITE_REJECT_EXCEPTION;
		}
		checkAndExpandBuffer();
		compressAndWriteTimestamp(buf, timestamp);
		compressAndWriteValue(buf, value);
		count++;
		updateCount();
	}

	private void compressAndWriteValue(ByteBuffer tBuf, long value) {
		long xor = prevValue ^ value;
		if (xor == 0) {
			tBuf.put((byte) 0);
		} else if (xor >= Byte.MIN_VALUE && xor <= Byte.MAX_VALUE) {
			tBuf.put((byte) 1);
			tBuf.put((byte) xor);
		} else if (xor >= Short.MIN_VALUE && xor <= Short.MAX_VALUE) {
			tBuf.put((byte) 2);
			tBuf.putShort((short) xor);
		} else if (xor >= Integer.MIN_VALUE && xor <= Integer.MAX_VALUE) {
			tBuf.put((byte) 3);
			tBuf.putInt((int) xor);
		} else {
			tBuf.put((byte) 4);
			tBuf.putLong(xor);
		}
		prevValue = value;
	}

	private void checkAndExpandBuffer() throws IOException {
		if (buf.remaining() < 20 || buf.isReadOnly()) {
			full = true;
			throw BUF_ROLLOVER_EXCEPTION;
		}
	}

	private void compressAndWriteTimestamp(ByteBuffer tBuf, long timestamp) {
		long ts = timestamp;
		long newDelta = (ts - prevTs);
		int deltaOfDelta = (int) (newDelta - tsDelta);
		if (deltaOfDelta == 0) {
			tBuf.put((byte) 0);
		} else if (deltaOfDelta >= Byte.MIN_VALUE && deltaOfDelta <= Byte.MAX_VALUE) {
			tBuf.put((byte) 1);
			tBuf.put((byte) deltaOfDelta);
		} else if (deltaOfDelta >= Short.MIN_VALUE && deltaOfDelta <= Short.MAX_VALUE) {
			tBuf.put((byte) 2);
			tBuf.putShort((short) deltaOfDelta);
		} else {
			tBuf.put((byte) 3);
			tBuf.putInt(deltaOfDelta);
		}
		prevTs = ts;
		tsDelta = newDelta;
	}

	@Override
	public void addValue(long timestamp, double value) throws IOException {
		addValue(timestamp, Double.doubleToLongBits(value));
	}

	private void updateCount() {
		buf.putInt(startOffset, count);
	}

	public ByzantineReader getReader() throws IOException {
		ByzantineReader reader = null;
		read.lock();
		ByteBuffer rbuf = buf.duplicate();
		rbuf.rewind();
		reader = new ByzantineReader(rbuf, startOffset);
		read.unlock();
		return reader;
	}

	@Override
	public void addValue(long timestamp, long value) throws IOException {
		try {
			write.lock();
			writeDataPoint(timestamp, value);
		} finally {
			write.unlock();
		}
	}

	@Override
	public double getCompressionRatio() {
		double ratio = 0;
		read.lock();
		ratio = ((double) count * Long.BYTES * 2) / buf.position();
		read.unlock();
		return ratio;
	}

	@Override
	public int getPosition() {
		return buf.position();
	}

	@Override
	public void setHeaderTimestamp(long timestamp) {
		if (prevTs == 0) {
			prevTs = timestamp;
			buf.putLong(timestamp);
		}
	}

	@Override
	public long getHeaderTimestamp() {
		return buf.getLong(4 + startOffset);
	}

	/**
	 * @return the read
	 */
	protected Lock getReadLock() {
		return read;
	}

	/**
	 * @return the write
	 */
	protected Lock getWriteLock() {
		return write;
	}

	/**
	 * @return the prevTs
	 */
	protected long getPrevTs() {
		return prevTs;
	}

	/**
	 * @return the delta
	 */
	protected long getDelta() {
		return tsDelta;
	}

	/**
	 * @return the count
	 */
	public int getCount() {
		return count;
	}

	/**
	 * @return the buf
	 */
	protected ByteBuffer getBuf() {
		return buf;
	}

	/**
	 * @return the prevValue
	 */
	protected long getPrevValue() {
		return prevValue;
	}

	@Override
	public ByteBuffer getRawBytes() {
		read.lock();
		ByteBuffer b = buf.duplicate();
		read.unlock();
		return b;
	}

	@Override
	public void bootstrap(ByteBuffer buf) throws IOException {
		write.lock();
		this.buf.rewind();
		buf.rewind();
		if (this.buf.limit() < buf.limit()) {
			throw BUF_ROLLOVER_EXCEPTION;
		}
		this.buf.put(buf);
		this.buf.rewind();
		forwardCursorToEnd();
		write.unlock();
	}

	@Override
	public void setCounter(int count) {
		this.count = count;
	}

	@Override
	public void makeReadOnly() {
		write.lock();
		readOnly = true;
		write.unlock();
	}

	@Override
	public int currentOffset() {
		int offset = 0;
		read.lock();
		offset = buf.position();
		read.unlock();
		return offset;
	}

	@Override
	public boolean isFull() {
		return full;
	}

	/**
	 * @return the bufferId
	 */
	public LinkedByteString getBufferId() {
		return bufferId;
	}

	/**
	 * @param bufferId
	 *            the bufferId to set
	 */
	public void setBufferId(LinkedByteString bufferId) {
		this.bufferId = bufferId;
	}

	@Override
	public boolean isReadOnly() {
		return readOnly;
	}

}

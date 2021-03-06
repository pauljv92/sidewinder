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
package com.srotya.sidewinder.core.utils;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

/**
 * @author ambud
 *
 */
public class ByteUtils {

	public static final Charset DEF_CHARSET = Charset.forName("US-ASCII");
	
	/**
	 * Returns md5 of data
	 * @param data
	 * @return
	 * @throws NoSuchAlgorithmException
	 */
	public static byte[] md5(byte[] data) throws NoSuchAlgorithmException {
		MessageDigest digest = MessageDigest.getInstance("md5");
		return digest.digest(data);
	}

	/**
	 * Returns sha1 hash of data
	 * @param data
	 * @return
	 * @throws NoSuchAlgorithmException
	 */
	public static byte[] sha1(byte[] data) throws NoSuchAlgorithmException {
		MessageDigest digest = MessageDigest.getInstance("sha1");
		return digest.digest(data);
	}

	/**
	 * @param in
	 * @return
	 */
	public static byte[] intToByteMSB(int in) {
		byte[] ou = new byte[4];
		ou[0] = (byte) ((in >> 24) & 0xff);
		ou[1] = (byte) ((in >> 16) & 0xff);
		ou[2] = (byte) ((in >> 8) & 0xff);
		ou[3] = (byte) (in & 0xff);
		return ou;
	}

	public static int bytesToIntMSB(byte[] bytes) {
		int val = 0;
		val |= ((int) bytes[0]) << 24;
		val |= ((int) bytes[1]) << 16;
		val |= ((int) bytes[2]) << 8;
		val |= ((int) bytes[3]);
		return val;
	}

	/**
	 * @param in
	 * @return
	 */
	public static byte[] shortToByteMSB(short in) {
		byte[] ou = new byte[2];
		ou[0] = (byte) ((in << 8) & 0xff);
		ou[1] = (byte) ((in >> 8) & 0xff);
		return ou;
	}

	/**
	 * @param input
	 * @return
	 */
	public static byte[] stringToBytes(String input) {
		byte[] ou = new byte[input.length()];
		for (int i = 0; i < ou.length; i++) {
			ou[i] = (byte) input.charAt(i);
		}
		return ou;
	}

	/**
	 * @param in
	 * @return
	 */
	public static String byteAryToAscii(byte[] in) {
		return new String(in, DEF_CHARSET);
	}

	/**
	 * @param array
	 * @return
	 */
	public static List<String> jsonArrayToStringList(JsonArray array) {
		List<String> ary = new ArrayList<>();
		for (JsonElement jsonElement : array) {
			ary.add(jsonElement.getAsString());
		}
		return ary;
	}

}

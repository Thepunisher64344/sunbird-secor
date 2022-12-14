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
package com.pinterest.secor.common;

import com.pinterest.secor.message.ParsedMessage;
import net.minidev.json.JSONValue;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.StringUtils;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.text.SimpleDateFormat;
import org.apache.commons.lang.text.StrSubstitutor;

/**
 * LogFilePath represents path of a log file.  It contains convenience method for building and
 * decomposing paths.
 *
 * Log file path has the following form:
 *     prefix/topic/partition1/.../partitionN/generation_kafkaPartition_firstMessageOffset
 * where:
 *     prefix is top-level directory for log files.  It can be a local path or an s3 dir,
 *     topic is a kafka topic,
 *     partition1, ..., partitionN is the list of partition names extracted from message content.
 *         E.g., the partition may describe the message date such as dt=2014-01-01,
 *     generation is the consumer version.  It allows up to perform rolling upgrades of
 *         non-compatible Secor releases,
 *     kafkaPartition is the kafka partition of the topic,
 *     firstMessageOffset is the offset of the first message in a batch of files committed
 *         atomically.
 *
 * @author Pawel Garbacki (pawel@pinterest.com)
 */
public class LogFilePath {
    private final String mPrefix;
    private final String mTopic;
    private final String[] mMessageIdentifier;
    private final String[] mPartitions;
    private final int mGeneration;
    private final int[] mKafkaPartitions;
    private final long[] mOffsets;
    private final String mExtension;
    private MessageDigest messageDigest;
    private String mOutputFilePattern;
    private SimpleDateFormat timeFormat = new SimpleDateFormat("HH-mm");
    private SimpleDateFormat dateFormat = new SimpleDateFormat("YYYYMMdd");


    public LogFilePath(String prefix, String topic, String[] partitions, int generation,
                       int[] kafkaPartitions, long[] offsets, String extension, String[] message_channel_identifier) {
        assert kafkaPartitions != null & kafkaPartitions.length >= 1
                : "Wrong kafkaParttions: " + Arrays.toString(kafkaPartitions);
        assert offsets != null & offsets.length >= 1 : "Wrong offsets: " + Arrays.toString(offsets);
        assert kafkaPartitions.length == offsets.length
                : "Size mismatch partitions: " + Arrays.toString(kafkaPartitions) +
                " offsets: " + Arrays.toString(offsets);
        for (int i = 1; i < kafkaPartitions.length; i++) {
            assert kafkaPartitions[i] == kafkaPartitions[i - 1] + 1
                    : "Non consecutive partitions " + kafkaPartitions[i] +
                    " and " + kafkaPartitions[i-1];
        }
        mPrefix = prefix;
        mTopic = topic;
        mMessageIdentifier = Arrays.copyOf(message_channel_identifier, message_channel_identifier.length);
        mPartitions = Arrays.copyOf(partitions, partitions.length);
        mGeneration = generation;
        mKafkaPartitions = Arrays.copyOf(kafkaPartitions, kafkaPartitions.length);
        mOffsets = Arrays.copyOf(offsets, offsets.length);
        mExtension = extension;

        try {
            messageDigest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Unable to find mdt digest.", e);
        }
    }

    public LogFilePath(String prefix, int generation, long lastCommittedOffset,
                       ParsedMessage message, String extension, String[] message_channel_identifier) {
        this(prefix, message.getTopic(), message.getPartitions(), generation,
                new int[]{message.getKafkaPartition()}, new long[]{lastCommittedOffset},
                extension, message_channel_identifier);
    }

    public LogFilePath(String prefix, String topic, String[] partitions, int generation,
                       int kafkaPartition, long offset, String extension, String[] message_channel_identifier) {
        this(prefix, topic, partitions, generation, new int[]{kafkaPartition},
                new long[]{offset}, extension, message_channel_identifier);
    }

    public LogFilePath(String prefix, String path, String[] message_channel_identifier) {
        assert path.startsWith(prefix): path + ".startsWith(" + prefix + ")";

        mPrefix = prefix;

        int prefixLength = prefix.length();
        if (!prefix.endsWith("/")) {
            prefixLength++;
        }
        String suffix = path.substring(prefixLength);
        String[] pathElements = suffix.split("/");
        // Suffix should contain a topic, at least one partition, and the basename.
        assert pathElements.length >= 3: Arrays.toString(pathElements) + ".length >= 3";

        mTopic = pathElements[0];
        mMessageIdentifier = message_channel_identifier;
        mPartitions = subArray(pathElements, 1, pathElements.length - 2);

        // Parse basename.
        String basename = pathElements[pathElements.length - 1];
        // Remove extension.
        int lastIndexOf = basename.lastIndexOf('.');
        if (lastIndexOf >= 0) {
            mExtension = basename.substring(lastIndexOf, basename.length());
            basename = basename.substring(0, lastIndexOf);
        } else {
            mExtension = "";
        }
        String[] basenameElements = basename.split("_");
        assert basenameElements.length == 4: Integer.toString(basenameElements.length) + " == 4";
        mGeneration = Integer.parseInt(basenameElements[0]);
        mKafkaPartitions = new int[]{Integer.parseInt(basenameElements[1])};
        mOffsets = new long[]{Long.parseLong(basenameElements[2])};

    }

    private static String[] subArray(String[] array, int startIndex, int endIndex) {
        String[] result = new String[endIndex - startIndex + 1];
        for (int i = startIndex; i <= endIndex; ++i) {
            result[i - startIndex] = array[i];
        }
        return result;
    }

    public LogFilePath withPrefix(String prefix, SecorConfig mConfig) {
        return new LogFilePath(prefix, mTopic, mPartitions, mGeneration, mKafkaPartitions, mOffsets, mExtension, mConfig, mMessageIdentifier);
    }

    public LogFilePath(String prefix, String topic, String[] partitions, int generation, int[] kafkaPartition,
                       long[] offset, String extension, SecorConfig config, String[] message_channel_identifier) {

        mPrefix = prefix;
        mTopic = topic;
        mMessageIdentifier = message_channel_identifier;
        mPartitions = partitions;
        mGeneration = generation;
        mKafkaPartitions = kafkaPartition;
        mOffsets = offset;
        mExtension = extension;
        mOutputFilePattern = config.getS3OutputFilePattern();
    }

    public String getLogFileParentDir() {
        ArrayList<String> elements = new ArrayList<String>();
        if (mPrefix != null && mPrefix.length() > 0) {
            elements.add(mPrefix);
        }
        if (mTopic != null && mTopic.length() > 0) {
            elements.add(mTopic);
        }
        return StringUtils.join(elements, "/");
    }

    public String getLogFileDir() {
        ArrayList<String> elements = new ArrayList<String>();
        elements.add(getLogFileParentDir());
        for (String partition : mPartitions) {
            elements.add(partition);
        }
        return StringUtils.join(elements, "/");
    }

    private String getLogFileBasename() {
        ArrayList<String> basenameElements = new ArrayList<String>();
        basenameElements.add(Integer.toString(mGeneration));
        if (mKafkaPartitions.length > 1) {
            String kafkaPartitions = mKafkaPartitions[0] + "-" +
                    mKafkaPartitions[mKafkaPartitions.length - 1];
            basenameElements.add(kafkaPartitions);

            StringBuilder sb = new StringBuilder();
            for (long offset : mOffsets) {
                sb.append(offset);
            }
            try {
                byte[] md5Bytes = messageDigest.digest(sb.toString().getBytes("UTF-8"));
                byte[] encodedBytes = Base64.encodeBase64URLSafe(md5Bytes);
                basenameElements.add(new String(encodedBytes));
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
        } else {
            basenameElements.add(Integer.toString(mKafkaPartitions[0]));
            basenameElements.add(String.format("%020d", mOffsets[0]));
        }
        return StringUtils.join(basenameElements, "_");
    }

    public String getLogFilePath() {
        if (StringUtils.isNotBlank(mOutputFilePattern)) {
            return getLogFilePath(mOutputFilePattern);
        }
        String basename = getLogFileBasename();
        ArrayList<String> pathElements = new ArrayList<String>();
        pathElements.add(getLogFileDir());
        pathElements.add(basename);

        return StringUtils.join(pathElements, "/") + mExtension;
    }

    private String getLogFilePath(String pattern) {

        List<String> pathElements = new ArrayList<String>();
        pathElements.add(mPrefix);
        pathElements.add(StrSubstitutor.replace(pattern, getValueMap(), "{", "}"));
        System.out.println("Path:" + StringUtils.join(pathElements, "/") + mExtension);
        return StringUtils.join(pathElements, "/") + mExtension;
    }

    private Map<String, String> getValueMap() {

        Map<String, String> valueMap = new HashMap<String, String>();
        valueMap.put("randomHex", getRandomHex());
        valueMap.put("partition", mPartitions[0]);
        valueMap.put("topic", mTopic);
        valueMap.put("message_channel_identifier", mMessageIdentifier[0]);
        valueMap.put("generation", mGeneration + "");
        valueMap.put("kafkaPartition", mKafkaPartitions[0] + "");
        valueMap.put("fmOffset", String.format("%020d", mOffsets[0]));
        valueMap.put("currentTimestamp", System.currentTimeMillis() + "");
        valueMap.put("currentTime", timeFormat.format(new Date()));
        valueMap.put("currentDate", dateFormat.format(new Date()));
//        System.out.println("messageing it " + JSONValue.parse(message));


        return valueMap;
    }

    public static String getRandomHex() {

        Random random = new Random();
        return StringUtils.substring(Integer.toHexString(random.nextInt()), 0, 4);
    }

    public String getLogFileCrcPath() {
        String basename = "." + getLogFileBasename() + ".crc";

        ArrayList<String> pathElements = new ArrayList<String>();
        pathElements.add(getLogFileDir());
        pathElements.add(basename);

        return StringUtils.join(pathElements, "/");
    }

    public String getTopic() {
        return mTopic;
    }
    public String[] getMessageIdentifier() {
        return mMessageIdentifier;
    }
    public String[] getPartitions() {
        return mPartitions;
    }

    public int getGeneration() {
        return mGeneration;
    }

    @Deprecated
    public int getKafkaPartition() {
        return mKafkaPartitions[0];
    }

    public int[] getKafkaPartitions() {
        return mKafkaPartitions;
    }

    @Deprecated
    public long getOffset() {
        return mOffsets[0];
    }

    public long[] getOffsets() {
        return mOffsets;
    }

    public String getExtension() {
        return mExtension;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        LogFilePath that = (LogFilePath) o;

        if (mGeneration != that.mGeneration) return false;
        if (!Arrays.equals(mKafkaPartitions, that.mKafkaPartitions)) return false;
        if (!Arrays.equals(mOffsets, that.mOffsets)) return false;
        if (!Arrays.equals(mPartitions, that.mPartitions)) return false;
        if (mPrefix != null ? !mPrefix.equals(that.mPrefix) : that.mPrefix != null) return false;
        if (mMessageIdentifier != null ? !mMessageIdentifier.equals(that.mMessageIdentifier) : that.mMessageIdentifier != null) return false;
        if (mTopic != null ? !mTopic.equals(that.mTopic) : that.mTopic != null) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = mPrefix != null ? mPrefix.hashCode() : 0;
        result = 31 * result + (mTopic != null ? mTopic.hashCode() : 0);
        result = 31 * result + (mMessageIdentifier != null ? mMessageIdentifier.hashCode() : 0);
        result = 31 * result + (mPartitions != null ? Arrays.hashCode(mPartitions) : 0);
        result = 31 * result + mGeneration;
        result = 31 * result + Arrays.hashCode(mKafkaPartitions);
        result = 31 * result + Arrays.hashCode(mOffsets);
        return result;
    }

    @Override
    public String toString() {
        return getLogFilePath();
    }
}
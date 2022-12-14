/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.pinterest.secor.parser;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Map.Entry;

import net.minidev.json.JSONObject;
import net.minidev.json.JSONValue;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.pinterest.secor.common.SecorConfig;
import com.pinterest.secor.message.Message;

/**
 * PatternDateMessageParser extracts timestamp field (specified by
 * 'message.timestamp.name') and the date pattern (specified by
 * 'message.timestamp.input.pattern'). The output file pattern is fetched from
 * the property 'secor.partition.output_dt_format'.
 * 
 * This generic class can even handle the DateMessageParse functionality. For
 * ex: it will generate the same partition when the
 * 'secor.partition.output_dt_format' property is set to "'dt='yyyy-MM-dd"
 * 
 * @see http://docs.oracle.com/javase/6/docs/api/java/text/SimpleDateFormat.html
 * 
 * @author Santhosh Vasabhaktula (santhosh.vasabhaktula@gmail.com)
 * 
 */
public class PatternDateMessageParser extends MessageParser {

	private static final Logger LOG = LoggerFactory.getLogger(PatternDateMessageParser.class);
	protected static final String defaultDate = "1970-01-01";
	protected static final String defaultFormatter = "yyyy-MM-dd";
	private Map<String, String> partitionPrefixMap;
	private SimpleDateFormat outputFormatter;
	private TimeZone messageTimeZone;

	public PatternDateMessageParser(SecorConfig config) {
		super(config);
		messageTimeZone = config.getMessageTimeZone();
		outputFormatter = new SimpleDateFormat(
				StringUtils.defaultIfBlank(mConfig.getPartitionOutputDtFormat(), defaultFormatter));
		outputFormatter.setTimeZone(config.getTimeZone());
		partitionPrefixMap = new HashMap<>();
		String partitionMapping = config.getPartitionPrefixMapping();
		if (null != partitionMapping) {
			JSONObject jsonObject = (JSONObject) JSONValue.parse(partitionMapping);
			for (Entry<String, Object> entry : jsonObject.entrySet()) {
				partitionPrefixMap.put(entry.getKey(), entry.getValue().toString() + "/");
			}
		}
	}

	@Override
	public String[] extractPartitions(Message message) {

		boolean prefixEnabled = mConfig.isPartitionPrefixEnabled();
		String result[] = {prefixEnabled ? partitionPrefixMap.get("DEFAULT") + defaultDate : defaultDate};
		try {
			JSONObject jsonObject = (JSONObject) JSONValue.parse(message.getPayload());
			if (jsonObject != null) {
				Object fieldValue = jsonObject.get(mConfig.getMessageTimestampName());
				if (fieldValue == null)
					fieldValue = jsonObject.get(mConfig.getFallbackMessageTimestampName());
				if (fieldValue == null)
					fieldValue = System.currentTimeMillis();

				Object eventValue = jsonObject.get(mConfig.getPartitionPrefixIdentifier());
				Object inputPattern = mConfig.getMessageTimestampInputPattern();
				if (inputPattern != null) {
					try {
					/*
					SimpleDateFormat outputFormatter = new SimpleDateFormat(
							StringUtils.defaultIfBlank(mConfig.getPartitionOutputDtFormat(), defaultFormatter));
							*/
						Date dateFormat;
						if (fieldValue instanceof Number) {
							dateFormat = new Date(((Number) fieldValue).longValue());
						} else {
							SimpleDateFormat inputFormatter = new SimpleDateFormat(inputPattern.toString());
							inputFormatter.setTimeZone(messageTimeZone);
							dateFormat = inputFormatter.parse(fieldValue.toString());
						}
						result[0] = prefixEnabled ? getPrefix(eventValue.toString()) + outputFormatter.format(dateFormat)
								: outputFormatter.format(dateFormat);
						return result;
					} catch (Exception e) {
						e.printStackTrace();
						LOG.info("PatternDateMessageParser: Exception while parsing date " + e.getMessage());
						LOG.warn("Unable to get path: " + e.getMessage());
					}
				}
			} else {
				LOG.info("PatternDateMessageParser: Unable to parse json object " + jsonObject);
			}
			return result;
		}
		catch (Exception e) {
			e.printStackTrace();
			LOG.info("PatternDateMessageParser: Exception while parsing date " + e.getMessage());
			return result;
		}

	}

	private String getPrefix(String prefixIdentifier) {
		String prefix = partitionPrefixMap.get(prefixIdentifier);
		if (StringUtils.isBlank(prefix))
			prefix = partitionPrefixMap.get("DEFAULT");
		if (null == prefix)
			prefix = "";
		return prefix;
	}

}
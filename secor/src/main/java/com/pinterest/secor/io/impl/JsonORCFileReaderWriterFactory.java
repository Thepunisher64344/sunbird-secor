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
package com.pinterest.secor.io.impl;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.ql.exec.vector.VectorizedRowBatch;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.GzipCodec;
import org.apache.hadoop.io.compress.Lz4Codec;
import org.apache.hadoop.io.compress.SnappyCodec;
import org.apache.orc.CompressionKind;
import org.apache.orc.OrcFile;
import org.apache.orc.Reader;
import org.apache.orc.RecordReader;
import org.apache.orc.TypeDescription;
import org.apache.orc.Writer;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pinterest.secor.common.FileRegistry;
import com.pinterest.secor.common.LogFilePath;
import com.pinterest.secor.common.SecorConfig;
import com.pinterest.secor.io.FileReader;
import com.pinterest.secor.io.FileReaderWriterFactory;
import com.pinterest.secor.io.FileWriter;
import com.pinterest.secor.io.KeyValue;
import com.pinterest.secor.util.ReflectionUtil;
import com.pinterest.secor.util.orc.JsonFieldFiller;
import com.pinterest.secor.util.orc.VectorColumnFiller;
import com.pinterest.secor.util.orc.VectorColumnFiller.JsonConverter;
import com.pinterest.secor.util.orc.schema.ORCSchemaProvider;

/**
 * ORC reader/writer implementation
 *
 * @author Ashish (ashu.impetus@gmail.com)
 *
 */
public class JsonORCFileReaderWriterFactory implements FileReaderWriterFactory {

    private static final Logger LOG = LoggerFactory.getLogger(FileRegistry.class);
    private ORCSchemaProvider schemaProvider;

    public JsonORCFileReaderWriterFactory(SecorConfig config) throws Exception {
        schemaProvider = ReflectionUtil.createORCSchemaProvider(
                config.getORCSchemaProviderClass(), config);
    }

    @Override
    public FileReader BuildFileReader(LogFilePath logFilePath,
                                      CompressionCodec codec) throws Exception {
        return new JsonORCFileReader(logFilePath, codec);
    }

    @Override
    public FileWriter BuildFileWriter(LogFilePath logFilePath,
                                      CompressionCodec codec) throws Exception {
        return new JsonORCFileWriter(logFilePath, codec);
    }

    protected class JsonORCFileReader implements FileReader {

        private int rowIndex = 0;
        private long offset;
        private RecordReader rows;
        private VectorizedRowBatch batch;
        private TypeDescription schema;

        @SuppressWarnings("deprecation")
        public JsonORCFileReader(LogFilePath logFilePath, CompressionCodec codec)
                throws IOException {
            schema = schemaProvider.getSchema(logFilePath.getTopic(),
                    logFilePath);
            Path path = new Path(logFilePath.getLogFilePath());
            Reader reader = OrcFile.createReader(path,
                    OrcFile.readerOptions(new Configuration(true)));
            offset = logFilePath.getOffset();
            rows = reader.rows();
            batch = reader.getSchema().createRowBatch();
            rows.nextBatch(batch);
        }

        @Override
        public KeyValue next() throws IOException {
            boolean endOfBatch = false;
            StringWriter sw = new StringWriter();

            if (rowIndex > batch.size - 1) {
                endOfBatch = !rows.nextBatch(batch);
                rowIndex = 0;
            }

            if (endOfBatch) {
                rows.close();
                return null;
            }

            try {
                JsonFieldFiller.processRow(new JSONWriter(sw), batch, schema,
                        rowIndex);
            } catch (JSONException e) {
                LOG.error("Unable to parse json {}", sw.toString());
                return null;
            }
            rowIndex++;
            return new KeyValue(offset++, sw.toString().getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void close() throws IOException {
            rows.close();
        }
    }

    protected class JsonORCFileWriter implements FileWriter {

        private Gson gson = new Gson();
        private Writer writer;
        private JsonConverter[] converters;
        private VectorizedRowBatch batch;
        private int rowIndex;
        private TypeDescription schema;

        public JsonORCFileWriter(LogFilePath logFilePath, CompressionCodec codec)
                throws IOException {
            Configuration conf = new Configuration();
            Path path = new Path(logFilePath.getLogFilePath());
            schema = schemaProvider.getSchema(logFilePath.getTopic(),
                    logFilePath);
            if (schema == null) {
                String topic = logFilePath.getTopic();
                throw new IllegalArgumentException(
                        String.format("No schema is provided for topic '%s'", topic));
            }
            List<TypeDescription> fieldTypes = schema.getChildren();
            converters = new JsonConverter[fieldTypes.size()];
            for (int c = 0; c < converters.length; ++c) {
                converters[c] = VectorColumnFiller.createConverter(fieldTypes
                        .get(c));
            }

            writer = OrcFile.createWriter(path, OrcFile.writerOptions(conf)
                    .compress(resolveCompression(codec)).setSchema(schema));
            batch = schema.createRowBatch();
        }

        @Override
        public long getLength() throws IOException {
            return writer.getRawDataSize();
        }

        @Override
        public void write(KeyValue keyValue) throws IOException {
            JsonElement jsonElement = gson.fromJson(new String(keyValue.getValue()), JsonElement.class);
            if (jsonElement instanceof JsonObject) {
                writeOne((JsonObject) jsonElement);
            } else if (jsonElement instanceof JsonArray) {
                // save each element in the array as a separate record
                for (JsonElement arrayElement : (JsonArray) jsonElement) {
                    if (arrayElement instanceof JsonObject) {
                        writeOne((JsonObject) arrayElement);
                    } else {
                        throw new IOException("Cannot write " + keyValue + ": unsupported type " + jsonElement);
                    }
                }
            } else {
                throw new IOException("Cannot write " + keyValue + ": unsupported type " + jsonElement);
            }
        }

        private void writeOne(JsonObject object) throws IOException {
            rowIndex = batch.size++;
            VectorColumnFiller.fillRow(rowIndex, converters, schema, batch, object);
            if (batch.size == batch.getMaxSize()) {
                writer.addRowBatch(batch);
                batch.reset();
            }
        }

        @Override
        public void close() throws IOException {
            writer.addRowBatch(batch);
            writer.close();
        }
    }

    /**
     * Used for returning the compression kind used in ORC
     *
     * @param codec
     * @return
     */
    private CompressionKind resolveCompression(CompressionCodec codec) {
        if (codec instanceof Lz4Codec)
            return CompressionKind.LZ4;
        else if (codec instanceof SnappyCodec)
            return CompressionKind.SNAPPY;
            // although GZip and ZLIB are not same thing
            // there is no better named codec for this case,
            // use hadoop Gzip codec to enable ORC ZLIB compression
        else if (codec instanceof GzipCodec)
            return CompressionKind.ZLIB;
        else
            return CompressionKind.NONE;
    }
}
/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hive.llap.io.decode.orc.stream.readers;

import java.io.IOException;

import org.apache.hadoop.hive.llap.io.api.EncodedColumnBatch;
import org.apache.hadoop.hive.llap.io.decode.orc.stream.StreamUtils;
import org.apache.hadoop.hive.ql.io.orc.CompressionCodec;
import org.apache.hadoop.hive.ql.io.orc.InStream;
import org.apache.hadoop.hive.ql.io.orc.OrcProto;
import org.apache.hadoop.hive.ql.io.orc.PositionProvider;
import org.apache.hadoop.hive.ql.io.orc.RecordReaderImpl;

/**
 *
 */
public class DoubleStreamReader extends RecordReaderImpl.DoubleTreeReader {
  private boolean isFileCompressed;
  private OrcProto.RowIndexEntry rowIndex;

  private DoubleStreamReader(int columnId, InStream present,
      InStream data, boolean isFileCompressed,
      OrcProto.RowIndexEntry rowIndex) throws IOException {
    super(columnId, present, data);
    this.rowIndex = rowIndex;

    // position the readers based on the specified row index
    PositionProvider positionProvider = new RecordReaderImpl.PositionProviderImpl(rowIndex);
    seek(positionProvider);
  }

  public void seek(PositionProvider positionProvider) throws IOException {
    super.seek(positionProvider);
  }

  public static class StreamReaderBuilder {
    private String fileName;
    private int columnIndex;
    private EncodedColumnBatch.StreamBuffer presentStream;
    private EncodedColumnBatch.StreamBuffer dataStream;
    private CompressionCodec compressionCodec;
    private int bufferSize;
    private OrcProto.RowIndexEntry rowIndex;

    public StreamReaderBuilder setFileName(String fileName) {
      this.fileName = fileName;
      return this;
    }

    public StreamReaderBuilder setColumnIndex(int columnIndex) {
      this.columnIndex = columnIndex;
      return this;
    }

    public StreamReaderBuilder setPresentStream(EncodedColumnBatch.StreamBuffer presentStream) {
      this.presentStream = presentStream;
      return this;
    }

    public StreamReaderBuilder setDataStream(EncodedColumnBatch.StreamBuffer dataStream) {
      this.dataStream = dataStream;
      return this;
    }

    public StreamReaderBuilder setCompressionCodec(CompressionCodec compressionCodec) {
      this.compressionCodec = compressionCodec;
      return this;
    }

    public StreamReaderBuilder setBufferSize(int bufferSize) {
      this.bufferSize = bufferSize;
      return this;
    }

    public StreamReaderBuilder setRowIndex(OrcProto.RowIndexEntry rowIndex) {
      this.rowIndex = rowIndex;
      return this;
    }

    public DoubleStreamReader build() throws IOException {
      InStream present = null;
      if (presentStream != null) {
        present = StreamUtils
            .createInStream(OrcProto.Stream.Kind.PRESENT.name(), fileName, null, bufferSize,
                presentStream);
      }

      InStream data = null;
      if (dataStream != null) {
        data = StreamUtils
            .createInStream(OrcProto.Stream.Kind.DATA.name(), fileName, null, bufferSize,
                dataStream);
      }

      return new DoubleStreamReader(columnIndex, present, data, compressionCodec != null, rowIndex);
    }
  }

  public static StreamReaderBuilder builder() {
    return new StreamReaderBuilder();
  }
}

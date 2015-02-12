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

package org.apache.hadoop.hive.llap.io.decode;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.llap.Consumer;
import org.apache.hadoop.hive.llap.io.api.EncodedColumnBatch;
import org.apache.hadoop.hive.llap.io.api.impl.ColumnVectorBatch;
import org.apache.hadoop.hive.llap.io.api.orc.OrcBatchKey;
import org.apache.hadoop.hive.llap.io.decode.orc.streams.DoubleStreamReader;
import org.apache.hadoop.hive.llap.io.decode.orc.streams.FloatStreamReader;
import org.apache.hadoop.hive.llap.io.decode.orc.streams.IntStreamReader;
import org.apache.hadoop.hive.llap.io.decode.orc.streams.LongStreamReader;
import org.apache.hadoop.hive.llap.io.decode.orc.streams.ShortStreamReader;
import org.apache.hadoop.hive.llap.io.encoded.EncodedDataProducer;
import org.apache.hadoop.hive.llap.io.encoded.OrcEncodedDataProducer;
import org.apache.hadoop.hive.llap.io.metadata.OrcFileMetadata;
import org.apache.hadoop.hive.llap.io.metadata.OrcMetadataCache;
import org.apache.hadoop.hive.llap.io.metadata.OrcStripeMetadata;
import org.apache.hadoop.hive.ql.exec.vector.ColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.VectorizedRowBatch;
import org.apache.hadoop.hive.ql.io.orc.CompressionCodec;
import org.apache.hadoop.hive.ql.io.orc.OrcProto;
import org.apache.hadoop.hive.ql.io.orc.RecordReaderImpl;

import com.google.common.collect.Lists;

public class OrcColumnVectorProducer extends ColumnVectorProducer<OrcBatchKey> {
  private final OrcEncodedDataProducer edp;
  private final OrcMetadataCache metadataCache;
  private ColumnVectorBatch cvb;
  private boolean skipCorrupt;

  public OrcColumnVectorProducer(
      ExecutorService executor, OrcEncodedDataProducer edp, Configuration conf) {
    super(executor);
    this.edp = edp;
    this.metadataCache = OrcMetadataCache.getInstance();
    this.cvb = null;
    this.skipCorrupt = HiveConf.getBoolVar(conf, HiveConf.ConfVars.HIVE_ORC_SKIP_CORRUPT_DATA);
  }

  @Override
  protected EncodedDataProducer<OrcBatchKey> getEncodedDataProducer() {
    return edp;
  }

  @Override
  protected void decodeBatch(EncodedColumnBatch<OrcBatchKey> batch,
      Consumer<ColumnVectorBatch> downstreamConsumer) {
    String fileName = batch.batchKey.file;
    // OrcEncodedDataProducer should have just loaded cache entries from this file.
    // The default LRU algorithm shouldn't have dropped the entries. To make it
    // safe, untie the code from EDP into separate class and make use of loading cache.
    try {
      OrcFileMetadata fileMetadata = metadataCache.getFileMetadata(fileName);
      OrcBatchKey stripeKey = batch.batchKey.clone();
      // we are interested only in the stripe number. To make sure we get the correct stripe
      // metadata, set row group index to 0. That's how it is cached. See OrcEncodedDataProducer
      stripeKey.rgIx = 0;
      OrcStripeMetadata stripeMetadata = metadataCache.getStripeMetadata(stripeKey);
      if (cvb == null) {
        cvb = new ColumnVectorBatch(batch.columnIxs.length);
      }

      // Get non null row count from root column
      int rgIdx = batch.batchKey.rgIx;
      OrcProto.RowIndexEntry rowIndex = stripeMetadata.getRowIndexes()[0].getEntry(rgIdx);
      long nonNullRowCount = getRowCount(rowIndex);
      int maxBatchesRG = (int) ((nonNullRowCount / VectorizedRowBatch.DEFAULT_SIZE) + 1);
      int batchSize = VectorizedRowBatch.DEFAULT_SIZE;
      int numCols = batch.columnIxs.length;
      RecordReaderImpl.TreeReader[] columnStreams = createTreeReaders(numCols, batch, fileMetadata,
          stripeMetadata);
      for (int i = 0; i < maxBatchesRG; i++) {
        if (i == maxBatchesRG - 1) {
          batchSize = (int) (nonNullRowCount % VectorizedRowBatch.DEFAULT_SIZE);
        }

        for (int idx = 0; idx < batch.columnIxs.length; idx++) {
          cvb.cols[idx] = (ColumnVector) columnStreams[idx].nextVector(null, batchSize);
        }

        // we are done reading a batch, send it to consumer for processing
        downstreamConsumer.consumeData(cvb);
      }
    } catch (IOException ioe) {
      downstreamConsumer.setError(ioe);
    } catch (CloneNotSupportedException e) {
      downstreamConsumer.setError(e);
    }
  }

  private RecordReaderImpl.TreeReader[] createTreeReaders(int numCols,
      EncodedColumnBatch<OrcBatchKey> batch,
      OrcFileMetadata fileMetadata,
      OrcStripeMetadata stripeMetadata) throws IOException {
    String file = batch.batchKey.file;
    RecordReaderImpl.TreeReader[] treeReaders = new RecordReaderImpl.TreeReader[numCols];

    for (int i = 0; i < numCols; i++) {
      int colIx = batch.columnIxs[i];
      int rgIdx = batch.batchKey.rgIx;
      OrcProto.RowIndexEntry rowIndex = stripeMetadata.getRowIndexes()[colIx].getEntry(rgIdx);
      EncodedColumnBatch.StreamBuffer[] streamBuffers = batch.columnData[i];
      OrcProto.Type colType = fileMetadata.getTypes().get(colIx);
      // TODO: EncodedColumnBatch is already decompressed, we don't really need to pass codec.
      // But we need to know if the original data is compressed or not. This is used to skip positions
      // in row index. If the file is originally compressed, then 1st position (compressed offset)
      // in row index should be skipped to get uncompressed offset, else 1st position should not
      // be skipped.
      CompressionCodec codec = fileMetadata.getCompressionCodec();
      int bufferSize = fileMetadata.getCompressionBufferSize();
      OrcProto.ColumnEncoding columnEncoding = stripeMetadata.getEncodings().get(colIx);
      ColumnVector cv = null;

      EncodedColumnBatch.StreamBuffer present = null;
      EncodedColumnBatch.StreamBuffer data = null;
      EncodedColumnBatch.StreamBuffer dictionary = null;
      EncodedColumnBatch.StreamBuffer lengths = null;
      EncodedColumnBatch.StreamBuffer secondary = null;
      for (EncodedColumnBatch.StreamBuffer streamBuffer : streamBuffers) {
        switch(streamBuffer.streamKind) {
          case 0:
            // PRESENT stream
            present = streamBuffer;
            break;
          case 1:
            // DATA stream
            data = streamBuffer;
            break;
          case 2:
            // LENGTH stream
            lengths = streamBuffer;
            break;
          case 3:
            // DICTIONARY_DATA stream
            dictionary = streamBuffer;
            break;
          case 5:
            // SECONDARY stream
            secondary = streamBuffer;
            break;
          default:
            throw new IOException("Unexpected stream kind: " + streamBuffer.streamKind);
        }
      }

      switch (colType.getKind()) {
        case SHORT:
          treeReaders[i] = ShortStreamReader.builder()
              .setFileName(file)
              .setColumnIndex(colIx)
              .setPresentStream(present)
              .setDataStream(data)
              .setCompressionCodec(codec)
              .setBufferSize(bufferSize)
              .setRowIndex(rowIndex)
              .setColumnEncodingKind(columnEncoding.getKind())
              .build();
          break;
        case INT:
          treeReaders[i] = IntStreamReader.builder()
              .setFileName(file)
              .setColumnIndex(colIx)
              .setPresentStream(present)
              .setDataStream(data)
              .setCompressionCodec(codec)
              .setBufferSize(bufferSize)
              .setRowIndex(rowIndex)
              .setColumnEncodingKind(columnEncoding.getKind())
              .build();
          break;
        case LONG:
          treeReaders[i] = LongStreamReader.builder()
              .setFileName(file)
              .setColumnIndex(colIx)
              .setPresentStream(present)
              .setDataStream(data)
              .setCompressionCodec(codec)
              .setBufferSize(bufferSize)
              .setRowIndex(rowIndex)
              .setColumnEncodingKind(columnEncoding.getKind())
              .skipCorrupt(skipCorrupt)
              .build();
          break;
        case FLOAT:
          treeReaders[i] = FloatStreamReader.builder()
              .setFileName(file)
              .setColumnIndex(colIx)
              .setPresentStream(present)
              .setDataStream(data)
              .setCompressionCodec(codec)
              .setBufferSize(bufferSize)
              .setRowIndex(rowIndex)
              .build();
          break;
        case DOUBLE:
          treeReaders[i] = DoubleStreamReader.builder()
              .setFileName(file)
              .setColumnIndex(colIx)
              .setPresentStream(present)
              .setDataStream(data)
              .setCompressionCodec(codec)
              .setBufferSize(bufferSize)
              .setRowIndex(rowIndex)
              .build();
          break;
        case CHAR:
        case VARCHAR:
        case STRING:
//          columnStreams[i] = new StringColumnStream(file, colIx, present, data, dictionary, lengths,
//              columnEncoding, codec, bufferSize, rowIndex);
          break;
        default:
          throw new UnsupportedOperationException("Data type not supported yet! " + colType);
      }
    }
    return treeReaders;
  }

  private List<OrcProto.Stream> getDataStreams(int colIx, List<OrcProto.Stream> streams) {
    List<OrcProto.Stream> result = Lists.newArrayList();
    for (OrcProto.Stream stream : streams) {
      if (stream.getColumn() == colIx) {
        switch (stream.getKind()) {
          case PRESENT:
          case DATA:
          case LENGTH:
          case DICTIONARY_DATA:
          case SECONDARY:
            result.add(stream);
          default:
            // ignore
        }
      }
    }
    return result;
  }

  private long getRowCount(OrcProto.RowIndexEntry rowIndexEntry) {
    return rowIndexEntry.getStatistics().getNumberOfValues();
  }
}

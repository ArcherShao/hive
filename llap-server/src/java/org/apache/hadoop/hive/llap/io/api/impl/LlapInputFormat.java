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


package org.apache.hadoop.hive.llap.io.api.impl;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hive.llap.Consumer;
import org.apache.hadoop.hive.llap.ConsumerFeedback;
import org.apache.hadoop.hive.llap.DebugUtils;
import org.apache.hadoop.hive.llap.io.decode.ColumnVectorProducer;
import org.apache.hadoop.hive.ql.exec.Utilities;
import org.apache.hadoop.hive.ql.exec.vector.VectorizedInputFormatInterface;
import org.apache.hadoop.hive.ql.exec.vector.VectorizedRowBatch;
import org.apache.hadoop.hive.ql.exec.vector.VectorizedRowBatchCtx;
import org.apache.hadoop.hive.ql.io.orc.OrcInputFormat;
import org.apache.hadoop.hive.ql.io.sarg.SearchArgument;
import org.apache.hadoop.hive.ql.io.sarg.SearchArgumentFactory;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.serde2.ColumnProjectionUtils;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapred.FileSplit;
import org.apache.hadoop.mapred.InputFormat;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.RecordReader;
import org.apache.hadoop.mapred.Reporter;

public class LlapInputFormat
  implements InputFormat<NullWritable, VectorizedRowBatch>, VectorizedInputFormatInterface {
  private static final Log LOG = LogFactory.getLog(LlapInputFormat.class);
  private final LlapIoImpl llapIo;
  private InputFormat sourceInputFormat;

  LlapInputFormat(LlapIoImpl llapIo, InputFormat sourceInputFormat) {
    // TODO: right now, we do nothing with source input format, ORC-only in the first cut.
    //       We'd need to plumb it thru and use it to get data to cache/etc.
    assert sourceInputFormat instanceof OrcInputFormat;
    this.llapIo = llapIo;
    this.sourceInputFormat = sourceInputFormat;
  }

  @Override
  public RecordReader<NullWritable, VectorizedRowBatch> getRecordReader(
      InputSplit split, JobConf job, Reporter reporter) throws IOException {
    boolean isVectorMode = Utilities.isVectorMode(job);
    if (!isVectorMode) {
      LOG.error("No llap in non-vectorized mode");
      throw new UnsupportedOperationException("No llap in non-vectorized mode");
    }
    FileSplit fileSplit = (FileSplit)split;
    reporter.setStatus(fileSplit.toString());
    try {
      List<Integer> includedCols = ColumnProjectionUtils.isReadAllColumns(job)
          ? null : ColumnProjectionUtils.getReadColumnIDs(job);
      if (includedCols.isEmpty()) {
        includedCols = null; // Also means read all columns? WTF?
      }
      return new LlapRecordReader(job, fileSplit, includedCols, llapIo.getCvp());
    } catch (Exception ex) {
      throw new IOException(ex);
    }
  }

  @Override
  public InputSplit[] getSplits(JobConf job, int numSplits) throws IOException {
    return sourceInputFormat.getSplits(job, numSplits);
  }

  private static class LlapRecordReader
      implements RecordReader<NullWritable, VectorizedRowBatch>, Consumer<ColumnVectorBatch> {
    private final InputSplit split;
    private final List<Integer> columnIds;
    private final SearchArgument sarg;
    private final String[] columnNames;
    private final ColumnVectorProducer<?> cvp;
    private final VectorizedRowBatchCtx rbCtx;

    private final LinkedList<ColumnVectorBatch> pendingData = new LinkedList<ColumnVectorBatch>();
    private boolean isFirst = true;

    private Throwable pendingError = null;
    /** Vector that is currently being processed by our user. */
    private boolean isDone = false, isClosed = false;
    private ConsumerFeedback<ColumnVectorBatch> feedback;

    public LlapRecordReader(
        JobConf job, FileSplit split, List<Integer> includedCols, ColumnVectorProducer<?> cvp) {
      this.split = split;
      this.columnIds = includedCols;
      this.cvp = cvp;
      this.sarg = SearchArgumentFactory.createFromConf(job);
      this.columnNames = ColumnProjectionUtils.getReadColumnNames(job);
      try {
        rbCtx = new VectorizedRowBatchCtx();
        rbCtx.init(job, split);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public boolean next(NullWritable key, VectorizedRowBatch value) throws IOException {
      try {
        assert value != null;
        if (isClosed) {
          throw new AssertionError("next called after close");
        }
        // Add partition cols if necessary (see VectorizedOrcInputFormat for details).
        if (isFirst) {
          rbCtx.addPartitionColsToBatch(value);
          feedback = cvp.read(split, columnIds, sarg, columnNames, this);
          isFirst = false;
        }
        ColumnVectorBatch cvb = nextCvb();
        if (cvb == null) return false;
        int[] columnMap = rbCtx.getIncludedColumnIndexes();
        if (columnMap.length != cvb.cols.length) {
          throw new RuntimeException("Unexpected number of columns, VRB has " + columnMap.length
              + " included, but the reader returned " + cvb.cols.length);
        }
        // VRB was created from VrbCtx, so we already have pre-allocated column vectors
        for (int i = 0; i < cvb.cols.length; ++i) {
          value.cols[columnMap[i]] = cvb.cols[i]; // TODO: reuse CV objects that are replaced
        }
        value.selectedInUse = false;
        value.size = cvb.size;
      } catch (InterruptedException e) {
        throw new IOException(e);
      } catch (HiveException e) {
        throw new IOException(e);
      }
      return true;
    }

    ColumnVectorBatch nextCvb() throws InterruptedException, IOException {
      // TODO: if some collection is needed, return previous ColumnVectorBatch here
      ColumnVectorBatch current = null;
      synchronized (pendingData) {
        // We are waiting for next block. Either we will get it, or be told we are done.
        boolean doLogBlocking = DebugUtils.isTraceMttEnabled() && isNothingToReport();
        if (doLogBlocking) {
          LlapIoImpl.LOG.info("next will block"); // TODO: separate log objects
        }
        while (isNothingToReport()) {
          pendingData.wait(100);
        }
        if (doLogBlocking) {
          LlapIoImpl.LOG.info("next is unblocked");
        }
        rethrowErrorIfAny();
        current = pendingData.poll();
      }
      if (DebugUtils.isTraceMttEnabled() && current != null) {
        LlapIoImpl.LOG.info("Processing will receive vector " + current);
      }
      return current;
    }

    private boolean isNothingToReport() {
      return !isDone && pendingData.isEmpty() && pendingError == null;
    }

    @Override
    public NullWritable createKey() {
      return NullWritable.get();
    }

    @Override
    public VectorizedRowBatch createValue() {
      try {
        return rbCtx.createVectorizedRowBatch();
      } catch (HiveException e) {
        throw new RuntimeException("Error creating a batch", e);
      }
    }

    @Override
    public long getPos() throws IOException {
      return -1; // Position doesn't make sense for async reader, chunk order is arbitrary.
    }

    @Override
    public void close() throws IOException {
      if (DebugUtils.isTraceEnabled()) {
        LlapIoImpl.LOG.info("close called; closed " + isClosed + ", done " + isDone
            + ", err " + pendingError + ", pending " + pendingData.size());
      }
      feedback.stop();
      rethrowErrorIfAny();
    }

    private void rethrowErrorIfAny() throws IOException {
      if (pendingError == null) return;
      if (pendingError instanceof IOException) {
        throw (IOException)pendingError;
      }
      throw new IOException(pendingError);
    }

    @Override
    public void setDone() {
      if (DebugUtils.isTraceEnabled()) {
        LlapIoImpl.LOG.info("setDone called; cclosed " + isClosed
          + ", done " + isDone + ", err " + pendingError + ", pending " + pendingData.size());
      }
      synchronized (pendingData) {
        isDone = true;
        pendingData.notifyAll();
      }
    }

    @Override
    public void consumeData(ColumnVectorBatch data) {
      if (DebugUtils.isTraceEnabled()) {
        LlapIoImpl.LOG.info("consume called; closed " + isClosed + ", done " + isDone
            + ", err " + pendingError + ", pending " + pendingData.size());
      }
      synchronized (pendingData) {
        if (isClosed) {
          return;
        }
        pendingData.add(data);
        pendingData.notifyAll();
      }
    }

    @Override
    public void setError(Throwable t) {
      LlapIoImpl.LOG.info("setError called; closed " + isClosed
        + ", done " + isDone + ", err " + pendingError + ", pending " + pendingData.size());
      assert t != null;
      synchronized (pendingData) {
        pendingError = t;
        pendingData.notifyAll();
      }
    }

    @Override
    public float getProgress() throws IOException {
      // TODO: plumb progress info thru the reader if we can get metadata from loader first.
      return 0.0f;
    }
  }
}

/*
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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iotdb.db.wal.recover.file;

import org.apache.iotdb.db.engine.modification.ModificationFile;
import org.apache.iotdb.db.engine.storagegroup.TsFileResource;
import org.apache.iotdb.db.metadata.path.PartialPath;
import org.apache.iotdb.db.qp.physical.crud.DeletePlan;
import org.apache.iotdb.db.qp.physical.crud.InsertRowPlan;
import org.apache.iotdb.db.service.IoTDB;
import org.apache.iotdb.db.utils.EnvironmentUtils;
import org.apache.iotdb.db.wal.buffer.WALEntry;
import org.apache.iotdb.db.wal.utils.TsFileUtilsForRecoverTest;
import org.apache.iotdb.tsfile.common.conf.TSFileDescriptor;
import org.apache.iotdb.tsfile.exception.write.WriteProcessException;
import org.apache.iotdb.tsfile.file.metadata.ChunkMetadata;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.file.metadata.enums.TSEncoding;
import org.apache.iotdb.tsfile.read.TsFileSequenceReader;
import org.apache.iotdb.tsfile.read.common.Chunk;
import org.apache.iotdb.tsfile.read.common.Path;
import org.apache.iotdb.tsfile.write.TsFileWriter;
import org.apache.iotdb.tsfile.write.record.TSRecord;
import org.apache.iotdb.tsfile.write.record.datapoint.DoubleDataPoint;
import org.apache.iotdb.tsfile.write.record.datapoint.FloatDataPoint;
import org.apache.iotdb.tsfile.write.record.datapoint.IntDataPoint;
import org.apache.iotdb.tsfile.write.record.datapoint.LongDataPoint;
import org.apache.iotdb.tsfile.write.schema.MeasurementSchema;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class UnsealedTsFileRecoverPerformerTest {
  private static final String SG_NAME = "root.recover_sg";
  private static final String DEVICE1_NAME = SG_NAME.concat(".d1");
  private static final String DEVICE2_NAME = SG_NAME.concat(".d2");
  private static final String FILE_NAME =
      TsFileUtilsForRecoverTest.getTestTsFilePath(SG_NAME, 0, 0, 1);
  private TsFileResource tsFileResource;

  @Before
  public void setUp() throws Exception {
    EnvironmentUtils.cleanDir(new File(FILE_NAME).getParent());
    EnvironmentUtils.envSetUp();
    IoTDB.schemaProcessor.setStorageGroup(new PartialPath(SG_NAME));
    IoTDB.schemaProcessor.createTimeseries(
        new PartialPath(DEVICE1_NAME.concat(".s1")),
        TSDataType.INT32,
        TSEncoding.RLE,
        TSFileDescriptor.getInstance().getConfig().getCompressor(),
        Collections.emptyMap());
    IoTDB.schemaProcessor.createTimeseries(
        new PartialPath(DEVICE1_NAME.concat(".s2")),
        TSDataType.INT64,
        TSEncoding.RLE,
        TSFileDescriptor.getInstance().getConfig().getCompressor(),
        Collections.emptyMap());
    IoTDB.schemaProcessor.createTimeseries(
        new PartialPath(DEVICE2_NAME.concat(".s1")),
        TSDataType.FLOAT,
        TSEncoding.RLE,
        TSFileDescriptor.getInstance().getConfig().getCompressor(),
        Collections.emptyMap());
    IoTDB.schemaProcessor.createTimeseries(
        new PartialPath(DEVICE2_NAME.concat(".s2")),
        TSDataType.DOUBLE,
        TSEncoding.RLE,
        TSFileDescriptor.getInstance().getConfig().getCompressor(),
        Collections.emptyMap());
  }

  @After
  public void tearDown() throws Exception {
    if (tsFileResource != null) {
      tsFileResource.close();
    }
    EnvironmentUtils.cleanDir(new File(FILE_NAME).getParent());
    EnvironmentUtils.cleanEnv();
  }

  @Test
  public void testRedoInsertPlan() throws Exception {
    // generate crashed .tsfile
    File file = new File(FILE_NAME);
    generateCrashedFile(file);
    assertTrue(file.exists());
    assertFalse(new File(FILE_NAME.concat(TsFileResource.RESOURCE_SUFFIX)).exists());
    // generate InsertRowPlan
    long time = 4;
    TSDataType[] dataTypes = new TSDataType[] {TSDataType.FLOAT, TSDataType.DOUBLE};
    String[] columns = new String[] {1 + "", 1.0 + ""};
    InsertRowPlan insertRowPlan =
        new InsertRowPlan(
            new PartialPath(DEVICE2_NAME), time, new String[] {"s1", "s2"}, dataTypes, columns);
    int fakeMemTableId = 1;
    WALEntry walEntry = new WALEntry(fakeMemTableId, insertRowPlan);
    // recover
    tsFileResource = new TsFileResource(file);
    // vsg processor is used to test IdTable, don't test IdTable here
    try (UnsealedTsFileRecoverPerformer recoverPerformer =
        new UnsealedTsFileRecoverPerformer(
            tsFileResource, true, null, performer -> assertFalse(performer.canWrite()))) {
      recoverPerformer.startRecovery();
      assertTrue(recoverPerformer.hasCrashed());
      assertTrue(recoverPerformer.canWrite());
      assertEquals(3, tsFileResource.getEndTime(DEVICE2_NAME));

      recoverPerformer.redoLog(walEntry);

      recoverPerformer.endRecovery();
    }
    // check file content
    TsFileSequenceReader reader = new TsFileSequenceReader(FILE_NAME);
    List<ChunkMetadata> chunkMetadataList =
        reader.getChunkMetadataList(new Path(DEVICE1_NAME, "s1"));
    assertNotNull(chunkMetadataList);
    chunkMetadataList = reader.getChunkMetadataList(new Path(DEVICE1_NAME, "s2"));
    assertNotNull(chunkMetadataList);
    chunkMetadataList = reader.getChunkMetadataList(new Path(DEVICE2_NAME, "s1"));
    assertNotNull(chunkMetadataList);
    chunkMetadataList = reader.getChunkMetadataList(new Path(DEVICE2_NAME, "s2"));
    assertNotNull(chunkMetadataList);
    assertEquals(2, chunkMetadataList.size());
    Chunk chunk = reader.readMemChunk(chunkMetadataList.get(0));
    assertEquals(3, chunk.getChunkStatistic().getEndTime());
    chunk = reader.readMemChunk(chunkMetadataList.get(1));
    assertEquals(4, chunk.getChunkStatistic().getEndTime());
    reader.close();
    // check .resource file in memory
    assertEquals(1, tsFileResource.getStartTime(DEVICE1_NAME));
    assertEquals(2, tsFileResource.getEndTime(DEVICE1_NAME));
    assertEquals(3, tsFileResource.getStartTime(DEVICE2_NAME));
    assertEquals(4, tsFileResource.getEndTime(DEVICE2_NAME));
    // check file existence
    assertTrue(file.exists());
    assertTrue(new File(FILE_NAME.concat(TsFileResource.RESOURCE_SUFFIX)).exists());
  }

  @Test
  public void testRedoDeletePlan() throws Exception {
    // generate crashed .tsfile
    File file = new File(FILE_NAME);
    generateCrashedFile(file);
    assertTrue(file.exists());
    assertFalse(new File(FILE_NAME.concat(TsFileResource.RESOURCE_SUFFIX)).exists());
    assertFalse(new File(FILE_NAME.concat(ModificationFile.FILE_SUFFIX)).exists());
    // generate InsertRowPlan
    DeletePlan deletePlan =
        new DeletePlan(Long.MIN_VALUE, Long.MAX_VALUE, new PartialPath(DEVICE2_NAME));
    int fakeMemTableId = 1;
    WALEntry walEntry = new WALEntry(fakeMemTableId, deletePlan);
    // recover
    tsFileResource = new TsFileResource(file);
    // vsg processor is used to test IdTable, don't test IdTable here
    try (UnsealedTsFileRecoverPerformer recoverPerformer =
        new UnsealedTsFileRecoverPerformer(
            tsFileResource, true, null, performer -> assertFalse(performer.canWrite()))) {
      recoverPerformer.startRecovery();
      assertTrue(recoverPerformer.hasCrashed());
      assertTrue(recoverPerformer.canWrite());
      assertEquals(3, tsFileResource.getEndTime(DEVICE2_NAME));

      recoverPerformer.redoLog(walEntry);

      recoverPerformer.endRecovery();
    }
    // check file content
    TsFileSequenceReader reader = new TsFileSequenceReader(FILE_NAME);
    List<ChunkMetadata> chunkMetadataList =
        reader.getChunkMetadataList(new Path(DEVICE1_NAME, "s1"));
    assertNotNull(chunkMetadataList);
    chunkMetadataList = reader.getChunkMetadataList(new Path(DEVICE1_NAME, "s2"));
    assertNotNull(chunkMetadataList);
    chunkMetadataList = reader.getChunkMetadataList(new Path(DEVICE2_NAME, "s1"));
    assertNotNull(chunkMetadataList);
    chunkMetadataList = reader.getChunkMetadataList(new Path(DEVICE2_NAME, "s2"));
    assertNotNull(chunkMetadataList);
    assertEquals(1, chunkMetadataList.size());
    Chunk chunk = reader.readMemChunk(chunkMetadataList.get(0));
    assertEquals(3, chunk.getChunkStatistic().getEndTime());
    reader.close();
    // check .resource file in memory
    assertEquals(1, tsFileResource.getStartTime(DEVICE1_NAME));
    assertEquals(2, tsFileResource.getEndTime(DEVICE1_NAME));
    assertEquals(3, tsFileResource.getStartTime(DEVICE2_NAME));
    assertEquals(3, tsFileResource.getEndTime(DEVICE2_NAME));
    // check file existence
    assertTrue(file.exists());
    assertTrue(new File(FILE_NAME.concat(TsFileResource.RESOURCE_SUFFIX)).exists());
    assertTrue(new File(FILE_NAME.concat(ModificationFile.FILE_SUFFIX)).exists());
  }

  private void generateCrashedFile(File tsFile) throws IOException, WriteProcessException {
    long truncateSize;
    try (TsFileWriter writer = new TsFileWriter(tsFile)) {
      writer.registerTimeseries(
          new Path(DEVICE1_NAME), new MeasurementSchema("s1", TSDataType.INT32, TSEncoding.RLE));
      writer.registerTimeseries(
          new Path(DEVICE1_NAME), new MeasurementSchema("s2", TSDataType.INT64, TSEncoding.RLE));
      writer.registerTimeseries(
          new Path(DEVICE2_NAME), new MeasurementSchema("s1", TSDataType.FLOAT, TSEncoding.RLE));
      writer.registerTimeseries(
          new Path(DEVICE2_NAME), new MeasurementSchema("s2", TSDataType.DOUBLE, TSEncoding.RLE));
      writer.write(
          new TSRecord(1, DEVICE1_NAME)
              .addTuple(new IntDataPoint("s1", 1))
              .addTuple(new LongDataPoint("s2", 1)));
      writer.write(
          new TSRecord(2, DEVICE1_NAME)
              .addTuple(new IntDataPoint("s1", 2))
              .addTuple(new LongDataPoint("s2", 2)));
      writer.write(
          new TSRecord(3, DEVICE2_NAME)
              .addTuple(new FloatDataPoint("s1", 3))
              .addTuple(new DoubleDataPoint("s2", 3)));
      writer.flushAllChunkGroups();
      try (FileChannel channel = new FileInputStream(tsFile).getChannel()) {
        truncateSize = channel.size();
      }
      writer.write(
          new TSRecord(4, DEVICE2_NAME)
              .addTuple(new FloatDataPoint("s1", 4))
              .addTuple(new DoubleDataPoint("s2", 4)));
      writer.flushAllChunkGroups();
      try (FileChannel channel = new FileInputStream(tsFile).getChannel()) {
        truncateSize = (truncateSize + channel.size()) / 2;
      }
    }
    try (FileChannel channel = new FileOutputStream(tsFile, true).getChannel()) {
      channel.truncate(truncateSize);
    }
  }
}

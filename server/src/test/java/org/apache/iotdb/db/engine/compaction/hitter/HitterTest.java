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

package org.apache.iotdb.db.engine.compaction.hitter;

import org.apache.iotdb.db.engine.compaction.heavyhitter.QueryHeavyHitters;
import org.apache.iotdb.db.engine.compaction.heavyhitter.hitters.HashMapHitter;
import org.apache.iotdb.db.engine.compaction.heavyhitter.hitters.SpaceSavingHitter;
import org.apache.iotdb.db.exception.StorageEngineException;
import org.apache.iotdb.db.exception.metadata.MetadataException;
import org.apache.iotdb.db.metadata.MManager;
import org.apache.iotdb.db.metadata.PartialPath;
import org.apache.iotdb.db.service.IoTDB;
import org.apache.iotdb.db.utils.EnvironmentUtils;
import org.apache.iotdb.tsfile.exception.write.WriteProcessException;
import org.apache.iotdb.tsfile.file.metadata.enums.CompressionType;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.file.metadata.enums.TSEncoding;
import org.apache.iotdb.tsfile.write.schema.MeasurementSchema;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.apache.iotdb.db.conf.IoTDBConstant.PATH_SEPARATOR;

public class HitterTest {

  private MManager mManager = IoTDB.metaManager;
  final String HITTER_TEST_SG = "root.hitter";
  final int maxHitterNum = 10;
  TSEncoding encoding = TSEncoding.PLAIN;
  private MeasurementSchema[] measurementSchemas;
  String[] deviceIds;
  private int deviceNum = 10;
  private int measurementNum = 10;
  List<PartialPath> queryPaths = new ArrayList<>();

  @Before
  public void setUp() throws IOException, WriteProcessException, MetadataException {
    EnvironmentUtils.envSetUp();
    measurementSchemas = new MeasurementSchema[measurementNum];
    for (int i = 0; i < measurementNum; i++) {
      measurementSchemas[i] =
          new MeasurementSchema(
              "sensor" + i, TSDataType.DOUBLE, encoding, CompressionType.UNCOMPRESSED);
    }
    deviceIds = new String[deviceNum];
    for (int i = 0; i < deviceNum; i++) {
      deviceIds[i] = HITTER_TEST_SG + PATH_SEPARATOR + "device" + i;
    }
    mManager.setStorageGroup(new PartialPath(HITTER_TEST_SG));
    for (String device : deviceIds) {
      for (MeasurementSchema measurementSchema : measurementSchemas) {
        PartialPath devicePath = new PartialPath(device);
        mManager.createTimeseries(
            devicePath.concatNode(measurementSchema.getMeasurementId()),
            measurementSchema.getType(),
            measurementSchema.getEncodingType(),
            measurementSchema.getCompressor(),
            Collections.emptyMap());
      }
    }
    // construct query paths
    for (MeasurementSchema measurementSchema : measurementSchemas) {
      PartialPath devicePath =
          new PartialPath(deviceIds[0] + "." + measurementSchema.getMeasurementId());
      queryPaths.add(devicePath);
    }
  }

  @After
  public void tearDown() throws IOException, StorageEngineException {
    EnvironmentUtils.cleanEnv();
  }

  @Test
  public void testHashMapHitter() throws MetadataException {
    QueryHeavyHitters queryHeavyHitters = new HashMapHitter(maxHitterNum);
    List<PartialPath> compSeriesBef =
        queryHeavyHitters.getTopCompactionSeries(new PartialPath(HITTER_TEST_SG));
    Assert.assertEquals(compSeriesBef.size(), 0);
    queryHeavyHitters.acceptQuerySeriesList(queryPaths);
    List<PartialPath> compSeriesAft =
        queryHeavyHitters.getTopCompactionSeries(new PartialPath(HITTER_TEST_SG));
    Assert.assertEquals(compSeriesAft.size(), maxHitterNum);
  }

  @Test
  public void testSpaceSavingHitter() throws MetadataException {
    QueryHeavyHitters queryHeavyHitters = new SpaceSavingHitter(maxHitterNum);
    List<PartialPath> compSeriesBef =
        queryHeavyHitters.getTopCompactionSeries(new PartialPath(HITTER_TEST_SG));
    Assert.assertEquals(compSeriesBef.size(), 0);
    queryHeavyHitters.acceptQuerySeriesList(queryPaths);
    List<PartialPath> compSeriesAft =
        queryHeavyHitters.getTopCompactionSeries(new PartialPath(HITTER_TEST_SG));
    Assert.assertEquals(compSeriesAft.size(), maxHitterNum);
  }
}

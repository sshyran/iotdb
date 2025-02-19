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

package org.apache.iotdb.db.service;

import org.apache.iotdb.commons.cluster.DataNodeLocation;
import org.apache.iotdb.commons.cluster.Endpoint;
import org.apache.iotdb.commons.consensus.SchemaRegionId;
import org.apache.iotdb.commons.partition.RegionReplicaSet;
import org.apache.iotdb.consensus.common.Peer;
import org.apache.iotdb.db.conf.IoTDBConfig;
import org.apache.iotdb.db.conf.IoTDBDescriptor;
import org.apache.iotdb.db.consensus.ConsensusImpl;
import org.apache.iotdb.db.exception.metadata.MetadataException;
import org.apache.iotdb.db.metadata.LocalConfigNode;
import org.apache.iotdb.db.metadata.path.PartialPath;
import org.apache.iotdb.db.mpp.common.PlanFragmentId;
import org.apache.iotdb.db.mpp.sql.analyze.QueryType;
import org.apache.iotdb.db.mpp.sql.planner.plan.FragmentInstance;
import org.apache.iotdb.db.mpp.sql.planner.plan.PlanFragment;
import org.apache.iotdb.db.mpp.sql.planner.plan.node.PlanNodeId;
import org.apache.iotdb.db.mpp.sql.planner.plan.node.metedata.write.CreateTimeSeriesNode;
import org.apache.iotdb.db.service.thrift.impl.InternalServiceImpl;
import org.apache.iotdb.db.utils.EnvironmentUtils;
import org.apache.iotdb.mpp.rpc.thrift.TConsensusGroupId;
import org.apache.iotdb.mpp.rpc.thrift.TFragmentInstance;
import org.apache.iotdb.mpp.rpc.thrift.TSendFragmentInstanceReq;
import org.apache.iotdb.mpp.rpc.thrift.TSendFragmentInstanceResp;
import org.apache.iotdb.tsfile.file.metadata.enums.CompressionType;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.file.metadata.enums.TSEncoding;
import org.apache.iotdb.tsfile.read.filter.GroupByFilter;

import org.apache.ratis.util.FileUtils;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class InternalServiceImplTest {
  private static final IoTDBConfig conf = IoTDBDescriptor.getInstance().getConfig();
  InternalServiceImpl internalServiceImpl;
  LocalConfigNode configNode;

  @Before
  public void setUp() throws Exception {
    IoTDB.configManager.init();
    configNode = LocalConfigNode.getInstance();
    configNode.getBelongedSchemaRegionIdWithAutoCreate(new PartialPath("root.ln"));
    ConsensusImpl.getInstance().start();
    RegionReplicaSet regionReplicaSet = genRegionReplicaSet();
    ConsensusImpl.getInstance()
        .addConsensusGroup(regionReplicaSet.getConsensusGroupId(), genPeerList(regionReplicaSet));
    internalServiceImpl = new InternalServiceImpl();
  }

  @After
  public void tearDown() throws Exception {
    IoTDB.configManager.clear();
    RegionReplicaSet regionReplicaSet = genRegionReplicaSet();
    ConsensusImpl.getInstance().removeConsensusGroup(regionReplicaSet.getConsensusGroupId());
    ConsensusImpl.getInstance().stop();
    EnvironmentUtils.cleanEnv();
    FileUtils.deleteFully(new File("data" + File.separator + "consensus"));
  }

  @Test
  public void createTimeseriesTest() throws MetadataException {
    configNode.getBelongedSchemaRegionIdWithAutoCreate(new PartialPath("root.ln"));
    CreateTimeSeriesNode createTimeSeriesNode =
        new CreateTimeSeriesNode(
            new PlanNodeId("0"),
            new PartialPath("root.ln.wf01.wt01.status"),
            TSDataType.BOOLEAN,
            TSEncoding.PLAIN,
            CompressionType.SNAPPY,
            new HashMap<String, String>() {
              {
                put("MAX_POINT_NUMBER", "3");
              }
            },
            new HashMap<String, String>() {
              {
                put("tag1", "v1");
                put("tag2", "v2");
              }
            },
            new HashMap<String, String>() {
              {
                put("attr1", "a1");
                put("attr2", "a2");
              }
            },
            "meter1");

    RegionReplicaSet regionReplicaSet = genRegionReplicaSet();
    PlanFragment planFragment = new PlanFragment(new PlanFragmentId("2", 3), createTimeSeriesNode);
    FragmentInstance fragmentInstance =
        new FragmentInstance(
            planFragment,
            planFragment.getId().genFragmentInstanceId(),
            new GroupByFilter(1, 2, 3, 4),
            QueryType.WRITE);
    fragmentInstance.setDataRegionAndHost(regionReplicaSet);

    // serialize fragmentInstance
    ByteBuffer byteBuffer = ByteBuffer.allocate(1024);
    fragmentInstance.serializeRequest(byteBuffer);
    byteBuffer.flip();

    // put serialized fragmentInstance to TSendFragmentInstanceReq
    TSendFragmentInstanceReq request = new TSendFragmentInstanceReq();
    TFragmentInstance tFragmentInstance = new TFragmentInstance();
    tFragmentInstance.setBody(byteBuffer);
    request.setFragmentInstance(tFragmentInstance);
    request.setConsensusGroupId(
        new TConsensusGroupId(
            regionReplicaSet.getConsensusGroupId().getId(),
            regionReplicaSet.getConsensusGroupId().getType().toString()));
    request.setQueryType(QueryType.WRITE.toString());

    // Use consensus layer to execute request
    TSendFragmentInstanceResp response = internalServiceImpl.sendFragmentInstance(request);

    Assert.assertTrue(response.accepted);
  }

  private RegionReplicaSet genRegionReplicaSet() {
    List<DataNodeLocation> dataNodeList = new ArrayList<>();
    dataNodeList.add(
        new DataNodeLocation(
            conf.getConsensusPort(), new Endpoint(conf.getInternalIp(), conf.getConsensusPort())));

    // construct fragmentInstance
    SchemaRegionId schemaRegionId = new SchemaRegionId(0);
    return new RegionReplicaSet(schemaRegionId, dataNodeList);
  }

  private List<Peer> genPeerList(RegionReplicaSet regionReplicaSet) {
    List<Peer> peerList = new ArrayList<>();
    for (DataNodeLocation node : regionReplicaSet.getDataNodeList()) {
      peerList.add(new Peer(regionReplicaSet.getConsensusGroupId(), node.getEndPoint()));
    }
    return peerList;
  }
}

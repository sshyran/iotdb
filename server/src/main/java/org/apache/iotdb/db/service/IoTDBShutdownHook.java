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

import org.apache.iotdb.db.engine.compaction.CompactionTaskManager;
import org.apache.iotdb.db.utils.MemUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IoTDBShutdownHook extends Thread {

  private static final Logger logger = LoggerFactory.getLogger(IoTDBShutdownHook.class);

  @Override
  public void run() {
    CompactionTaskManager.getInstance().stop();
    // close rocksdb if possible to avoid lose data
    IoTDB.configManager.clear();
    if (logger.isInfoEnabled()) {
      logger.info(
          "IoTDB exits. Jvm memory usage: {}",
          MemUtils.bytesCntToStr(
              Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()));
    }
  }
}

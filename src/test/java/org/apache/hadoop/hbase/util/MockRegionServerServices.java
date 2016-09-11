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
package org.apache.hadoop.hbase.util;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.hadoop.hbase.HServerInfo;
import org.apache.hadoop.hbase.catalog.CatalogTracker;
import org.apache.hadoop.hbase.ipc.HBaseRpcMetrics;
import org.apache.hadoop.hbase.regionserver.CompactionRequestor;
import org.apache.hadoop.hbase.regionserver.FlushRequester;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.RegionServerServices;
import org.apache.hadoop.hbase.regionserver.wal.HLog;
import org.apache.zookeeper.KeeperException;

/**
 * Basic mock region server services.
 */
public class MockRegionServerServices implements RegionServerServices {
  final Map<String, HRegion> regions = new HashMap<String, HRegion>();
  boolean stopping = false;
  Set<byte[]> rit = new HashSet<byte[]>();

  @Override
  public boolean removeFromOnlineRegions(String encodedRegionName) {
    return this.regions.remove(encodedRegionName) != null;
  }
  
  @Override
  public HRegion getFromOnlineRegions(String encodedRegionName) {
    return this.regions.get(encodedRegionName);
  }
  
  @Override
  public void addToOnlineRegions(HRegion r) {
    this.regions.put(r.getRegionInfo().getEncodedName(), r);
  }
  
  @Override
  public void postOpenDeployTasks(HRegion r, CatalogTracker ct, boolean daughter)
      throws KeeperException, IOException {
  }
  
  @Override
  public boolean isStopping() {
    return this.stopping;
  }
  
  @Override
  public HLog getWAL() {
    return null;
  }
  
  @Override
  public HServerInfo getServerInfo() {
    return null;
  }
  
  @Override
  public HBaseRpcMetrics getRpcMetrics() {
    return null;
  }

  @Override
  public Set<byte[]> getRegionsInTransitionInRS() {
    return rit;
  }

  @Override
  public FlushRequester getFlushRequester() {
    return null;
  }
  
  @Override
  public CompactionRequestor getCompactionRequester() {
    return null;
  }
}

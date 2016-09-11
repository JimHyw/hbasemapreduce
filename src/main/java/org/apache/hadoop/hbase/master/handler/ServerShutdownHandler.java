/**
 * Copyright 2010 The Apache Software Foundation
 *
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
package org.apache.hadoop.hbase.master.handler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HServerInfo;
import org.apache.hadoop.hbase.Server;
import org.apache.hadoop.hbase.catalog.CatalogTracker;
import org.apache.hadoop.hbase.catalog.MetaEditor;
import org.apache.hadoop.hbase.catalog.MetaReader;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.executor.EventHandler;
import org.apache.hadoop.hbase.master.AssignmentManager;
import org.apache.hadoop.hbase.master.AssignmentManager.RegionState;
import org.apache.hadoop.hbase.master.AssignmentManager.RegionsOnDeadServer;
import org.apache.hadoop.hbase.master.DeadServer;
import org.apache.hadoop.hbase.master.MasterServices;
import org.apache.hadoop.hbase.master.ServerManager;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.hbase.util.Writables;
import org.apache.zookeeper.KeeperException;

/**
 * Process server shutdown.
 * Server-to-handle must be already in the deadservers lists.  See
 * {@link ServerManager#expireServer(HServerInfo)}.
 */
public class ServerShutdownHandler extends EventHandler {
  private static final Log LOG = LogFactory.getLog(ServerShutdownHandler.class);
  private final HServerInfo hsi;
  private final Server server;
  private final MasterServices services;
  private final DeadServer deadServers;

  public ServerShutdownHandler(final Server server, final MasterServices services,
      final DeadServer deadServers, final HServerInfo hsi) {
    this(server, services, deadServers, hsi, EventType.M_SERVER_SHUTDOWN);
  }

  ServerShutdownHandler(final Server server, final MasterServices services,
      final DeadServer deadServers, final HServerInfo hsi, EventType type) {
    super(server, type);
    this.hsi = hsi;
    this.server = server;
    this.services = services;
    this.deadServers = deadServers;
    if (!this.deadServers.contains(hsi.getServerName())) {
      LOG.warn(hsi.getServerName() + " is NOT in deadservers; it should be!");
    }
  }

  /**
   * Before assign the ROOT region, ensure it haven't 
   *  been assigned by other place
   * <p>
   * Under some scenarios, the ROOT region can be opened twice, so it seemed online
   * in two regionserver at the same time.
   * If the ROOT region has been assigned, so the operation can be canceled. 
   * @throws InterruptedException
   * @throws IOException
   * @throws KeeperException
   */
  private void verifyAndAssignRoot() 
  throws InterruptedException, IOException, KeeperException {
    long timeout = this.server.getConfiguration().
      getLong("hbase.catalog.verification.timeout", 1000);
    if (!this.server.getCatalogTracker().verifyRootRegionLocation(timeout)) {
      this.services.getAssignmentManager().assignRoot();     
    }
  }
  
  /**
   * Failed many times, shutdown processing
   * @throws IOException
   */
  private void verifyAndAssignRootWithRetries() throws IOException {
    int iTimes = this.server.getConfiguration().getInt(
        "hbase.catalog.verification.retries", 10);

    long waitTime = this.server.getConfiguration().getLong(
        "hbase.catalog.verification.timeout", 1000);

    int iFlag = 0;
    while (true) {
      try {
        verifyAndAssignRoot();
        break;
      } catch (KeeperException e) {
        this.server.abort("In server shutdown processing, assigning root", e);
        throw new IOException("Aborting", e);
      } catch (Exception e) {
        if (iFlag >= iTimes) {
          this.server.abort("verifyAndAssignRoot failed after" + iTimes
              + " times retries, aborting", e);
          throw new IOException("Aborting", e);
        }
        try {
          Thread.sleep(waitTime);
        } catch (InterruptedException e1) {
          LOG.warn("Interrupted when is the thread sleep", e1);
          Thread.currentThread().interrupt();
          throw new IOException("Interrupted", e1);
        }
        iFlag++;
      }
    }
  }
  
  /**
   * @return True if the server we are processing was carrying <code>-ROOT-</code>
   */
  boolean isCarryingRoot() {
    return false;
  }

  /**
   * @return True if the server we are processing was carrying <code>.META.</code>
   */
  boolean isCarryingMeta() {
    return false;
  }

  @Override
  public void process() throws IOException {
    final String serverName = this.hsi.getServerName();

    LOG.info("Splitting logs for " + serverName);
    try {
      this.services.getMasterFileSystem().splitLog(serverName);
    
      // Clean out anything in regions in transition.  Being conservative and
      // doing after log splitting.  Could do some states before -- OPENING?
      // OFFLINE? -- and then others after like CLOSING that depend on log
      // splitting.
      Set<HRegionInfo> regionsFromRegionPlansForServer = new HashSet<HRegionInfo>();
      List<RegionState> regionsInTransition = new ArrayList<RegionState>();
      RegionsOnDeadServer regionsOnDeadServer = this.services
          .getAssignmentManager().processServerShutdown(this.hsi);
      regionsFromRegionPlansForServer = regionsOnDeadServer
          .getRegionsFromRegionPlansForServer();
      regionsInTransition = regionsOnDeadServer.getRegionsInTransition();

      // Assign root and meta if we were carrying them.
      if (isCarryingRoot()) { // -ROOT-
        LOG.info("Server " + serverName + " was carrying ROOT. Trying to assign.");
        verifyAndAssignRootWithRetries();
      }
    
      // Carrying meta?
      if (isCarryingMeta()) {
        LOG.info("Server " + serverName + " was carrying META. Trying to assign.");
        this.services.getAssignmentManager().assignMeta();
      }
    
      // Wait on meta to come online; we need it to progress.
      // TODO: Best way to hold strictly here?  We should build this retry logic
      //       into the MetaReader operations themselves.
      NavigableMap<HRegionInfo, Result> hris = null;
      while (!this.server.isStopped()) {
        try {
          this.server.getCatalogTracker().waitForMeta();
          hris = MetaReader.getServerUserRegions(this.server.getCatalogTracker(),
              this.hsi);
          break;
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("Interrupted", e);
        } catch (IOException ioe) {
          LOG.info("Received exception accessing META during server shutdown of " +
              serverName + ", retrying META read", ioe);
        }
      }
    
      // Skip regions that were in transition unless CLOSING or PENDING_CLOSE
      for (RegionState rit : regionsInTransition) {
        if (!rit.isClosing() && !rit.isPendingClose()) {
          LOG.debug("Removed " + rit.getRegion().getRegionNameAsString() +
            " from list of regions to assign because in RIT");
          hris.remove(rit.getRegion());
        }
      }
    
      LOG.info("Reassigning " + (hris == null? 0: hris.size()) +
        " region(s) that " + serverName +
        " was carrying (skipping " + regionsInTransition.size() +
        " regions(s) that are already in transition)");
    
      // Iterate regions that were on this server and assign them
      if (hris != null) {
        for (Map.Entry<HRegionInfo, Result> e : hris.entrySet()) {
          if (processDeadRegion(e.getKey(), e.getValue(),
              this.services.getAssignmentManager(),
              this.server.getCatalogTracker())) {
            RegionState rit = this.services.getAssignmentManager()
                .isRegionInTransition(e.getKey());
            Pair<HRegionInfo, HServerInfo> p = this.services
                .getAssignmentManager().getAssignment(
                    e.getKey().getEncodedNameAsBytes());

            if (rit != null && !rit.isClosing() && !rit.isPendingClose()
                && !regionsFromRegionPlansForServer.contains(rit.getRegion())) {
              // Skip regions that were in transition unless CLOSING or
              // PENDING_CLOSE
              LOG.info("Skip assigning region " + rit.toString());
            } else if ((p != null) && (p.getSecond() != null)
                && !(p.getSecond().equals(this.hsi))) {
              LOG.debug("Skip assigning region "
                  + e.getKey().getRegionNameAsString()
                  + " because it has been opened in " + p.getSecond());
            } else {
              this.services.getAssignmentManager().assign(e.getKey(), true);
              regionsFromRegionPlansForServer.remove(e.getKey());
            }
          }
        }
      }
      
      int reassignedPlans = 0;
      for (HRegionInfo hri : regionsFromRegionPlansForServer) {
        if (!this.services.getAssignmentManager().isRegionOnline(hri)) {
          this.services.getAssignmentManager().assign(hri, true);
          reassignedPlans++;
        }
      }
      LOG.info(reassignedPlans + " regions which planned to open on "
          + this.hsi.getServerName() + " be re-assigned.");
    } finally {
      this.deadServers.finish(serverName);
    }
    
    LOG.info("Finished processing of shutdown of " + serverName);
  }

  /**
   * Process a dead region from a dead RS.  Checks if the region is disabled
   * or if the region has a partially completed split.
   * @param hri
   * @param result
   * @param assignmentManager
   * @param catalogTracker
   * @return Returns true if specified region should be assigned, false if not.
   * @throws IOException
   */
  public static boolean processDeadRegion(HRegionInfo hri, Result result,
      AssignmentManager assignmentManager, CatalogTracker catalogTracker)
  throws IOException {
    // If table is not disabled but the region is offlined,
    boolean disabled = assignmentManager.getZKTable().isDisabledTable(
        hri.getTableDesc().getNameAsString());
    if (disabled) return false;
    if (hri.isOffline() && hri.isSplit()) {
      LOG.debug("Offlined and split region " + hri.getRegionNameAsString() +
        "; checking daughter presence");
      fixupDaughters(result, assignmentManager, catalogTracker);
      return false;
    }
    return true;
  }

  /**
   * Check that daughter regions are up in .META. and if not, add them.
   * @param hris All regions for this server in meta.
   * @param result The contents of the parent row in .META.
   * @return the number of daughters missing and fixed
   * @throws IOException
   */
  public static int fixupDaughters(final Result result,
      final AssignmentManager assignmentManager,
      final CatalogTracker catalogTracker)
  throws IOException {
    int fixedA = fixupDaughter(result, HConstants.SPLITA_QUALIFIER,
      assignmentManager, catalogTracker);
    int fixedB = fixupDaughter(result, HConstants.SPLITB_QUALIFIER,
      assignmentManager, catalogTracker);
    return fixedA + fixedB;
  }

  /**
   * Check individual daughter is up in .META.; fixup if its not.
   * @param result The contents of the parent row in .META.
   * @param qualifier Which daughter to check for.
   * @return 1 if the daughter is missing and fixed. Otherwise 0
   * @throws IOException
   */
  static int fixupDaughter(final Result result, final byte [] qualifier,
      final AssignmentManager assignmentManager,
      final CatalogTracker catalogTracker)
  throws IOException {
    HRegionInfo daughter = getHRegionInfo(result, qualifier);
    if (daughter == null) return 0;
    if (isDaughterMissing(catalogTracker, daughter)) {
      LOG.info("Fixup; missing daughter " + daughter.getRegionNameAsString());
      MetaEditor.addDaughter(catalogTracker, daughter, null);

      // TODO: Log WARN if the regiondir does not exist in the fs.  If its not
      // there then something wonky about the split -- things will keep going
      // but could be missing references to parent region.

      // And assign it.
      assignmentManager.assign(daughter, true);
      return 1;
    } else {
      LOG.debug("Daughter " + daughter.getRegionNameAsString() + " present");
    }
    return 0;
  }

  /**
   * Interpret the content of the cell at {@link HConstants#CATALOG_FAMILY} and
   * <code>qualifier</code> as an HRegionInfo and return it, or null.
   * @param r Result instance to pull from.
   * @param qualifier Column family qualifier
   * @return An HRegionInfo instance or null.
   * @throws IOException
   */
  private static HRegionInfo getHRegionInfo(final Result r, byte [] qualifier)
  throws IOException {
    byte [] bytes = r.getValue(HConstants.CATALOG_FAMILY, qualifier);
    if (bytes == null || bytes.length <= 0) return null;
    return Writables.getHRegionInfoOrNull(bytes);
  }

  /**
   * Look for presence of the daughter OR of a split of the daughter in .META.
   * Daughter could have been split over on regionserver before a run of the
   * catalogJanitor had chance to clear reference from parent.
   * @param daughter Daughter region to search for.
   * @throws IOException 
   */
  private static boolean isDaughterMissing(final CatalogTracker catalogTracker,
      final HRegionInfo daughter) throws IOException {
    FindDaughterVisitor visitor = new FindDaughterVisitor(daughter);
    // Start the scan at what should be the daughter's row in the .META.
    // We will either 1., find the daughter or some derivative split of the
    // daughter (will have same table name and start row at least but will sort
    // after because has larger regionid -- the regionid is timestamp of region
    // creation), OR, we will not find anything with same table name and start
    // row.  If the latter, then assume daughter missing and do fixup.
    byte [] startrow = daughter.getRegionName();
    MetaReader.fullScan(catalogTracker, visitor, startrow);
    return !visitor.foundDaughter();
  }

  /**
   * Looks for daughter.  Sets a flag if daughter or some progeny of daughter
   * is found up in <code>.META.</code>.
   */
  static class FindDaughterVisitor implements MetaReader.Visitor {
    private final HRegionInfo daughter;
    private boolean found = false;

    FindDaughterVisitor(final HRegionInfo daughter) {
      this.daughter = daughter;
    }

    /**
     * @return True if we found a daughter region during our visiting.
     */
    boolean foundDaughter() {
      return this.found;
    }

    @Override
    public boolean visit(Result r) throws IOException {
      HRegionInfo hri = getHRegionInfo(r, HConstants.REGIONINFO_QUALIFIER);
      if (hri == null) {
        LOG.warn("No serialized HRegionInfo in " + r);
        return true;
      }
      byte [] value = r.getValue(HConstants.CATALOG_FAMILY,
          HConstants.SERVER_QUALIFIER);
      // See if daughter is assigned to some server
      if (value == null) return false;

      // Now see if we have gone beyond the daughter's startrow.
      if (!Bytes.equals(daughter.getTableDesc().getName(),
          hri.getTableDesc().getName())) {
        // We fell into another table.  Stop scanning.
        return false;
      }
      // If our start rows do not compare, move on.
      if (!Bytes.equals(daughter.getStartKey(), hri.getStartKey())) {
        return false;
      }
      // Else, table name and start rows compare.  It means that the daughter
      // or some derivative split of the daughter is up in .META.  Daughter
      // exists.
      this.found = true;
      return false;
    }
  }
}

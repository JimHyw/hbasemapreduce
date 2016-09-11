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
package org.apache.hadoop.hbase.regionserver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.Server;
import org.apache.hadoop.hbase.catalog.MetaEditor;
import org.apache.hadoop.hbase.io.Reference.Range;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.CancelableProgressable;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.hadoop.hbase.util.FSUtils;
import org.apache.hadoop.hbase.util.PairOfSameType;
import org.apache.zookeeper.KeeperException;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

/**
 * Executes region split as a "transaction".  Call {@link #prepare()} to setup
 * the transaction, {@link #execute(Server, RegionServerServices)} to run the
 * transaction and {@link #rollback(OnlineRegions)} to cleanup if execute fails.
 *
 * <p>Here is an example of how you would use this class:
 * <pre>
 *  SplitTransaction st = new SplitTransaction(this.conf, parent, midKey)
 *  if (!st.prepare()) return;
 *  try {
 *    st.execute(server, services);
 *  } catch (IOException ioe) {
 *    try {
 *      st.rollback(server, services);
 *      return;
 *    } catch (RuntimeException e) {
 *      myAbortable.abort("Failed split, abort");
 *    }
 *  }
 * </Pre>
 * <p>This class is not thread safe.  Caller needs ensure split is run by
 * one thread only.
 */
class SplitTransaction {
  private static final Log LOG = LogFactory.getLog(SplitTransaction.class);
  private static final String SPLITDIR = "splits";

  /*
   * Region to split
   */
  private final HRegion parent;
  private HRegionInfo hri_a;
  private HRegionInfo hri_b;
  private Path splitdir;
  private long fileSplitTimeout = 30000;

  /*
   * Row to split around
   */
  private final byte [] splitrow;

  /**
   * Types to add to the transaction journal.
   * Each enum is a step in the split transaction. Used to figure how much
   * we need to rollback.
   */
  enum JournalEntry {
    /**
     * We created the temporary split data directory.
     */
    CREATE_SPLIT_DIR,
    /**
     * Closed the parent region.
     */
    CLOSED_PARENT_REGION,
    /**
     * The parent has been taken out of the server's online regions list.
     */
    OFFLINED_PARENT,
    /**
     * Started in on creation of the first daughter region.
     */
    STARTED_REGION_A_CREATION,
    /**
     * Started in on the creation of the second daughter region.
     */
    STARTED_REGION_B_CREATION,
    /**
     * Point of no return.
     * If we got here, then transaction is not recoverable other than by
     * crashing out the regionserver.
     */
    PONR
  }

  /*
   * Journal of how far the split transaction has progressed.
   */
  private final List<JournalEntry> journal = new ArrayList<JournalEntry>();

  /**
   * Constructor
   * @param services So we can online new regions.  If null, we'll skip onlining
   * (Useful testing).
   * @param c Configuration to use running split
   * @param r Region to split
   * @param splitrow Row to split around
   */
  SplitTransaction(final HRegion r, final byte [] splitrow) {
    this.parent = r;
    this.splitrow = splitrow;
    this.splitdir = getSplitDir(this.parent);
  }

  /**
   * Does checks on split inputs.
   * @return <code>true</code> if the region is splittable else
   * <code>false</code> if it is not (e.g. its already closed, etc.).
   */
  public boolean prepare() {
    if (this.parent.isClosed() || this.parent.isClosing()) return false;
    // Split key can be null if this region is unsplittable; i.e. has refs.
    if (this.splitrow == null) return false;
    HRegionInfo hri = this.parent.getRegionInfo();
    parent.prepareToSplit();
    // Check splitrow.
    byte [] startKey = hri.getStartKey();
    byte [] endKey = hri.getEndKey();
    if (Bytes.equals(startKey, splitrow) ||
        !this.parent.getRegionInfo().containsRow(splitrow)) {
      LOG.info("Split row is not inside region key range or is equal to " +
          "startkey: " + Bytes.toStringBinary(this.splitrow));
      return false;
    }
    long rid = getDaughterRegionIdTimestamp(hri);
    this.hri_a = new HRegionInfo(hri.getTableDesc(), startKey, this.splitrow,
      false, rid);
    this.hri_b = new HRegionInfo(hri.getTableDesc(), this.splitrow, endKey,
      false, rid);
    return true;
  }

  /**
   * Calculate daughter regionid to use.
   * @param hri Parent {@link HRegionInfo}
   * @return Daughter region id (timestamp) to use.
   */
  private static long getDaughterRegionIdTimestamp(final HRegionInfo hri) {
    long rid = EnvironmentEdgeManager.currentTimeMillis();
    // Regionid is timestamp.  Can't be less than that of parent else will insert
    // at wrong location in .META. (See HBASE-710).
    if (rid < hri.getRegionId()) {
      LOG.warn("Clock skew; parent regions id is " + hri.getRegionId() +
        " but current time here is " + rid);
      rid = hri.getRegionId() + 1;
    }
    return rid;
  }

  private static IOException closedByOtherException = new IOException(
      "Failed to close region: already closed by another thread");
  /**
   * Run the transaction.
   * @param server Hosting server instance.  Can be null when testing (won't try
   * and update in zk if a null server)
   * @param services Used to online/offline regions.
   * @throws IOException If thrown, transaction failed. Call {@link #rollback(Server, RegionServerServices)}
   * @return Regions created
   * @see #rollback(Server, RegionServerServices)
   */
  PairOfSameType<HRegion> execute(final Server server,
      final RegionServerServices services)
  throws IOException {
    LOG.info("Starting split of region " + this.parent);
    if ((server != null && server.isStopped()) ||
        (services != null && services.isStopping())) {
      throw new IOException("Server is stopped or stopping");
    }
    assert !this.parent.lock.writeLock().isHeldByCurrentThread() : "Unsafe to hold write lock while performing RPCs";

    // If true, no cluster to write meta edits into.
    boolean testing = server == null? true:
      server.getConfiguration().getBoolean("hbase.testing.nocluster", false);
    this.fileSplitTimeout = testing ? this.fileSplitTimeout :
        server.getConfiguration().getLong(
            "hbase.regionserver.fileSplitTimeout", this.fileSplitTimeout);

    createSplitDir(this.parent.getFilesystem(), this.splitdir);
    this.journal.add(JournalEntry.CREATE_SPLIT_DIR);

    List<StoreFile> hstoreFilesToSplit = null;
    Exception exceptionToThrow = null;
    try{
      hstoreFilesToSplit = this.parent.close(false);
    } catch (Exception e) {
      exceptionToThrow = e;
    }
    if (exceptionToThrow == null && hstoreFilesToSplit == null) {
      // The region was closed by a concurrent thread. We can't continue
      // with the split, instead we must just abandon the split. If we
      // reopen or split this could cause problems because the region has
      // probably already been moved to a different server, or is in the
      // process of moving to a different server.
      exceptionToThrow = closedByOtherException;
    }
    if (exceptionToThrow != closedByOtherException) {
      this.journal.add(JournalEntry.CLOSED_PARENT_REGION);
    }
    if (exceptionToThrow != null) {
      if (exceptionToThrow instanceof IOException)
        throw (IOException) exceptionToThrow;
      throw new IOException(exceptionToThrow);
    }

    if (!testing) {
      services.removeFromOnlineRegions(this.parent.getRegionInfo().getEncodedName());
    }
    this.journal.add(JournalEntry.OFFLINED_PARENT);

    // TODO: If splitStoreFiles were multithreaded would we complete steps in
    // less elapsed time?  St.Ack 20100920
    //
    // splitStoreFiles creates daughter region dirs under the parent splits dir
    // Nothing to unroll here if failure -- clean up of CREATE_SPLIT_DIR will
    // clean this up.
    splitStoreFiles(this.splitdir, hstoreFilesToSplit);

    // Log to the journal that we are creating region A, the first daughter
    // region.  We could fail halfway through.  If we do, we could have left
    // stuff in fs that needs cleanup -- a storefile or two.  Thats why we
    // add entry to journal BEFORE rather than AFTER the change.
    this.journal.add(JournalEntry.STARTED_REGION_A_CREATION);
    HRegion a = createDaughterRegion(this.hri_a, this.parent.flushRequester);

    // Ditto
    this.journal.add(JournalEntry.STARTED_REGION_B_CREATION);
    HRegion b = createDaughterRegion(this.hri_b, this.parent.flushRequester);

    // This is the point of no return.  Adding subsequent edits to .META. as we
    // do below when we do the daughter opens adding each to .META. can fail in
    // various interesting ways the most interesting of which is a timeout
    // BUT the edits all go through (See HBASE-3872).  IF we reach the PONR
    // then subsequent failures need to crash out this regionserver; the
    // server shutdown processing should be able to fix-up the incomplete split.
    // The offlined parent will have the daughters as extra columns.  If
    // we leave the daughter regions in place and do not remove them when we
    // crash out, then they will have their references to the parent in place
    // still and the server shutdown fixup of .META. will point to these
    // regions.
    // We should add PONR JournalEntry before offlineParentInMeta,so even if
    // OfflineParentInMeta timeout,this will cause regionserver exit,and then
    // master ServerShutdownHandler will fix daughter & avoid data loss. See (
    // HBASE-4562).
    this.journal.add(JournalEntry.PONR);

    // Edit parent in meta.  Offlines parent region and adds splita and splitb.
    if (!testing) {
      MetaEditor.offlineParentInMeta(server.getCatalogTracker(),
        this.parent.getRegionInfo(), a.getRegionInfo(), b.getRegionInfo());
    }

    // Open daughters in parallel.
    DaughterOpener aOpener = new DaughterOpener(server, services, a);
    DaughterOpener bOpener = new DaughterOpener(server, services, b);
    aOpener.start();
    bOpener.start();
    try {
      aOpener.join();
      bOpener.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted " + e.getMessage());
    }
    if (aOpener.getException() != null) {
      throw new IOException("Failed " +
        aOpener.getName(), aOpener.getException());
    }
    if (bOpener.getException() != null) {
      throw new IOException("Failed " +
        bOpener.getName(), bOpener.getException());
    }

    // Leaving here, the splitdir with its dross will be in place but since the
    // split was successful, just leave it; it'll be cleaned when parent is
    // deleted and cleaned up.
    return new PairOfSameType<HRegion>(a, b);
  }

  /*
   * Open daughter region in its own thread.
   * If we fail, abort this hosting server.
   */
  class DaughterOpener extends Thread {
    private final RegionServerServices services;
    private final Server server;
    private final HRegion r;
    private Throwable t = null;

    DaughterOpener(final Server s, final RegionServerServices services,
        final HRegion r) {
      super((s ==  null? "null": s.getServerName()) + "-daughterOpener=" +
        r.getRegionInfo().getEncodedName());
      setDaemon(true);
      this.services = services;
      this.server = s;
      this.r = r;
    }

    /**
     * @return Null if open succeeded else exception that causes us fail open.
     * Call it after this thread exits else you may get wrong view on result.
     */
    Throwable getException() {
      return this.t;
    }

    @Override
    public void run() {
      try {
        openDaughterRegion(this.server, this.services, r);
      } catch (Throwable t) {
        this.t = t;
      }
    }
  }

  /**
   * Open daughter regions, add them to online list and update meta.
   * @param server
   * @param services Can be null when testing.
   * @param daughter
   * @throws IOException
   * @throws KeeperException
   */
  void openDaughterRegion(final Server server,
      final RegionServerServices services, final HRegion daughter)
  throws IOException, KeeperException {
    boolean stopping = services != null && services.isStopping();
    boolean stopped = server != null && server.isStopped();
    if (stopped || stopping) {
      MetaEditor.addDaughter(server.getCatalogTracker(),
        daughter.getRegionInfo(), null);
      LOG.info("Not opening daughter " +
        daughter.getRegionInfo().getRegionNameAsString() +
        " because stopping=" + stopping + ", stopped=" + server.isStopped());
      return;
    }
    HRegionInfo hri = daughter.getRegionInfo();
    LoggingProgressable reporter = (server == null)? null:
      new LoggingProgressable(hri, server.getConfiguration());
    HRegion r = daughter.openHRegion(reporter);
    if (services != null) {
      services.postOpenDeployTasks(r, server.getCatalogTracker(), true);
    }
  }

  static class LoggingProgressable implements CancelableProgressable {
    private final HRegionInfo hri;
    private long lastLog = -1;
    private final long interval;

    LoggingProgressable(final HRegionInfo hri, final Configuration c) {
      this.hri = hri;
      this.interval = c.getLong("hbase.regionserver.split.daughter.open.log.interval",
        10000);
    }

    @Override
    public boolean progress() {
      long now = System.currentTimeMillis();
      if (now - lastLog > this.interval) {
        LOG.info("Opening " + this.hri.getRegionNameAsString());
        this.lastLog = now;
      }
      return true;
    }
  }

  private static Path getSplitDir(final HRegion r) {
    return new Path(r.getRegionDir(), SPLITDIR);
  }

  /**
   * @param fs Filesystem to use
   * @param splitdir Directory to store temporary split data in
   * @throws IOException If <code>splitdir</code> already exists or we fail
   * to create it.
   * @see #cleanupSplitDir(FileSystem, Path)
   */
  private static void createSplitDir(final FileSystem fs, final Path splitdir)
  throws IOException {
    if (fs.exists(splitdir)) {
      LOG.info("The " + splitdir
          + " directory exists.  Hence deleting it to recreate it");
      if (!fs.delete(splitdir, true)) {
        throw new IOException("Failed deletion of " + splitdir
            + " before creating them again.");
      }
    }
    if (!fs.mkdirs(splitdir)) throw new IOException("Failed create of " + splitdir);
  }

  private static void cleanupSplitDir(final FileSystem fs, final Path splitdir)
  throws IOException {
    // Splitdir may have been cleaned up by reopen of the parent dir.
    deleteDir(fs, splitdir, false);
  }

  /**
   * @param fs Filesystem to use
   * @param dir Directory to delete
   * @param mustPreExist If true, we'll throw exception if <code>dir</code>
   * does not preexist, else we'll just pass.
   * @throws IOException Thrown if we fail to delete passed <code>dir</code>
   */
  private static void deleteDir(final FileSystem fs, final Path dir,
      final boolean mustPreExist)
  throws IOException {
    if (!fs.exists(dir)) {
      if (mustPreExist) throw new IOException(dir.toString() + " does not exist!");
    } else if (!fs.delete(dir, true)) {
      throw new IOException("Failed delete of " + dir);
    }
  }

  private void splitStoreFiles(final Path splitdir,
    final List<StoreFile> hstoreFilesToSplit)
  throws IOException {
    if (hstoreFilesToSplit == null) {
      // Could be null because close didn't succeed -- for now consider it fatal
      throw new IOException("Close returned empty list of StoreFiles");
    }
    // The following code sets up a thread pool executor with as many slots as
    // there's files to split. It then fires up everything, waits for
    // completion and finally checks for any exception
    int nbFiles = hstoreFilesToSplit.size();
    ThreadFactoryBuilder builder = new ThreadFactoryBuilder();
    builder.setNameFormat("StoreFileSplitter-%1$d");
    ThreadFactory factory = builder.build();
    ThreadPoolExecutor threadPool =
      (ThreadPoolExecutor) Executors.newFixedThreadPool(nbFiles, factory);
    List<Future<Void>> futures = new ArrayList<Future<Void>>(nbFiles);

     // Split each store file.
    for (StoreFile sf: hstoreFilesToSplit) {
      //splitStoreFile(sf, splitdir);
      StoreFileSplitter sfs = new StoreFileSplitter(sf, splitdir);
      futures.add(threadPool.submit(sfs));
    }
    // Shutdown the pool
    threadPool.shutdown();

    // Wait for all the tasks to finish
    try {
      boolean stillRunning = !threadPool.awaitTermination(
          this.fileSplitTimeout, TimeUnit.MILLISECONDS);
      if (stillRunning) {
        threadPool.shutdownNow();
        // wait for the thread to shutdown completely.
        while (!threadPool.isTerminated()) {
          Thread.sleep(50);
        }
        throw new IOException("Took too long to split the" +
            " files and create the references, aborting split");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while waiting for file splitters", e);
    }

    // Look for any exception
    for (Future future : futures) {
      try {
        future.get();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException(
            "Interrupted while trying to get the results of file splitters", e);
      } catch (ExecutionException e) {
        throw new IOException(e);
      }
    }
  }

  private void splitStoreFile(final StoreFile sf, final Path splitdir)
  throws IOException {
    FileSystem fs = this.parent.getFilesystem();
    byte [] family = sf.getFamily();
    String encoded = this.hri_a.getEncodedName();
    Path storedir = Store.getStoreHomedir(splitdir, encoded, family);
    StoreFile.split(fs, storedir, sf, this.splitrow, Range.bottom);
    encoded = this.hri_b.getEncodedName();
    storedir = Store.getStoreHomedir(splitdir, encoded, family);
    StoreFile.split(fs, storedir, sf, this.splitrow, Range.top);
  }

  /**
   * Utility class used to do the file splitting / reference writing
   * in parallel instead of sequentially.
   */
  class StoreFileSplitter implements Callable<Void> {

    private final StoreFile sf;
    private final Path splitdir;

    /**
     * Constructor that takes what it needs to split
     * @param sf which file
     * @param splitdir where the splitting is done
     */
    public StoreFileSplitter(final StoreFile sf, final Path splitdir) {
      this.sf = sf;
      this.splitdir = splitdir;
    }

    public Void call() throws IOException {
      splitStoreFile(sf, splitdir);
      return null;
    }
  }

  /**
   * @param hri Spec. for daughter region to open.
   * @param flusher Flusher this region should use.
   * @return Created daughter HRegion.
   * @throws IOException
   * @see #cleanupDaughterRegion(FileSystem, Path, HRegionInfo)
   */
  HRegion createDaughterRegion(final HRegionInfo hri,
      final FlushRequester flusher)
  throws IOException {
    // Package private so unit tests have access.
    FileSystem fs = this.parent.getFilesystem();
    Path regionDir = getSplitDirForDaughter(this.parent.getFilesystem(),
      this.splitdir, hri);
    HRegion r = HRegion.newHRegion(this.parent.getTableDir(),
      this.parent.getLog(), fs, this.parent.getConf(),
      hri, flusher);
    HRegion.moveInitialFilesIntoPlace(fs, regionDir, r.getRegionDir());
    return r;
  }

  private static void cleanupDaughterRegion(final FileSystem fs,
    final Path tabledir, final String encodedName)
  throws IOException {
    Path regiondir = HRegion.getRegionDir(tabledir, encodedName);
    // Dir may not preexist.
    deleteDir(fs, regiondir, false);
  }

  /*
   * Get the daughter directories in the splits dir.  The splits dir is under
   * the parent regions' directory.
   * @param fs
   * @param splitdir
   * @param hri
   * @return Path to daughter split dir.
   * @throws IOException
   */
  private static Path getSplitDirForDaughter(final FileSystem fs,
      final Path splitdir, final HRegionInfo hri)
  throws IOException {
    return new Path(splitdir, hri.getEncodedName());
  }

  /**
   * @param server Hosting server instance (May be null when testing).
   * @param services
   * @throws IOException If thrown, rollback failed.  Take drastic action.
   * @return True if we successfully rolled back, false if we got to the point
   * of no return and so now need to abort the server to minimize damage.
   */
  public boolean rollback(final Server server, final RegionServerServices services)
  throws IOException {
    boolean result = true;
    FileSystem fs = this.parent.getFilesystem();
    ListIterator<JournalEntry> iterator =
      this.journal.listIterator(this.journal.size());
    // Iterate in reverse.
    while (iterator.hasPrevious()) {
      JournalEntry je = iterator.previous();
      switch(je) {
      case CREATE_SPLIT_DIR:
    	this.parent.writestate.writesEnabled = true;
        cleanupSplitDir(fs, this.splitdir);
        break;

      case CLOSED_PARENT_REGION:
        // So, this returns a seqid but if we just closed and then reopened, we
        // should be ok. On close, we flushed using sequenceid obtained from
        // hosting regionserver so no need to propagate the sequenceid returned
        // out of initialize below up into regionserver as we normally do.
        // TODO: Verify.
        this.parent.initialize();
        break;

      case STARTED_REGION_A_CREATION:
        cleanupDaughterRegion(fs, this.parent.getTableDir(),
          this.hri_a.getEncodedName());
        break;

      case STARTED_REGION_B_CREATION:
        cleanupDaughterRegion(fs, this.parent.getTableDir(),
          this.hri_b.getEncodedName());
        break;

      case OFFLINED_PARENT:
        if (services != null) services.addToOnlineRegions(this.parent);
        break;

      case PONR:
        // We got to the point-of-no-return so we need to just abort. Return
        // immediately.  Do not clean up created daughter regions.  They need
        // to be in place so we don't delete the parent region mistakenly.
        // See HBASE-3872.
        return false;

      default:
        throw new RuntimeException("Unhandled journal entry: " + je);
      }
    }
    return result;
  }

  HRegionInfo getFirstDaughter() {
    return hri_a;
  }

  HRegionInfo getSecondDaughter() {
    return hri_b;
  }

  // For unit testing.
  Path getSplitDir() {
    return this.splitdir;
  }

  /**
   * Clean up any split detritus that may have been left around from previous
   * split attempts.
   * Call this method on initial region deploy.  Cleans up any mess
   * left by previous deploys of passed <code>r</code> region.
   * @param r
   * @throws IOException
   */
  static void cleanupAnySplitDetritus(final HRegion r) throws IOException {
    Path splitdir = getSplitDir(r);
    FileSystem fs = r.getFilesystem();
    if (!fs.exists(splitdir)) return;
    // Look at the splitdir.  It could have the encoded names of the daughter
    // regions we tried to make.  See if the daughter regions actually got made
    // out under the tabledir.  If here under splitdir still, then the split did
    // not complete.  Try and do cleanup.  This code WILL NOT catch the case
    // where we successfully created daughter a but regionserver crashed during
    // the creation of region b.  In this case, there'll be an orphan daughter
    // dir in the filesystem.  TOOD: Fix.
    FileStatus [] daughters = fs.listStatus(splitdir, new FSUtils.DirFilter(fs));
    for (int i = 0; i < daughters.length; i++) {
      cleanupDaughterRegion(fs, r.getTableDir(),
        daughters[i].getPath().getName());
    }
    cleanupSplitDir(r.getFilesystem(), splitdir);
    LOG.info("Cleaned up old failed split transaction detritus: " + splitdir);
  }
}

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
package org.apache.hadoop.hbase.master;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.CompatibilityFactory;
import org.apache.hadoop.hbase.HBaseClassTestRule;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.StartMiniClusterOption;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.RegionInfo;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.master.assignment.AssignmentManager;
import org.apache.hadoop.hbase.master.assignment.ServerState;
import org.apache.hadoop.hbase.master.assignment.ServerStateNode;
import org.apache.hadoop.hbase.master.procedure.ServerCrashProcedure;
import org.apache.hadoop.hbase.procedure2.Procedure;
import org.apache.hadoop.hbase.regionserver.HRegionServer;
import org.apache.hadoop.hbase.test.MetricsAssertHelper;
import org.apache.hadoop.hbase.testclassification.LargeTests;
import org.apache.hadoop.hbase.testclassification.MasterTests;
import org.apache.hadoop.hbase.util.JVMClusterUtil;
import org.apache.zookeeper.KeeperException;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Category({ MasterTests.class, LargeTests.class })
public class TestClusterRestartFailover extends AbstractTestRestartCluster {

  @ClassRule
  public static final HBaseClassTestRule CLASS_RULE =
    HBaseClassTestRule.forClass(TestClusterRestartFailover.class);

  private static final Logger LOG = LoggerFactory.getLogger(TestClusterRestartFailover.class);
  private static final MetricsAssertHelper metricsHelper =
    CompatibilityFactory.getInstance(MetricsAssertHelper.class);

  private static CountDownLatch SCP_LATCH;
  private static ServerName SERVER_FOR_TEST;

  @Override
  protected boolean splitWALCoordinatedByZk() {
    return true;
  }

  private ServerStateNode getServerStateNode(ServerName serverName) {
    return UTIL.getHBaseCluster().getMaster().getAssignmentManager().getRegionStates()
      .getServerNode(serverName);
  }

  /**
   * Test for HBASE-22964
   */
  @Test
  public void test() throws Exception {
    setupCluster();
    setupTable();

    // Find the server which not carry hbase:namespace
    for (JVMClusterUtil.RegionServerThread thread : UTIL.getHBaseCluster()
        .getRegionServerThreads()) {
      if (!thread.getRegionServer().getOnlineTables().contains(TableName.NAMESPACE_TABLE_NAME)) {
        SERVER_FOR_TEST = thread.getRegionServer().getServerName();
        break;
      }
    }
    UTIL.waitFor(60000, () -> getServerStateNode(SERVER_FOR_TEST) != null);
    ServerStateNode serverNode = getServerStateNode(SERVER_FOR_TEST);
    assertNotNull(serverNode);
    assertTrue("serverNode should be ONLINE when cluster runs normally",
        serverNode.isInState(ServerState.ONLINE));

    SCP_LATCH = new CountDownLatch(1);

    // Shutdown cluster and restart
    List<Integer> ports =
        UTIL.getHBaseCluster().getMaster().getServerManager().getOnlineServersList().stream()
            .map(serverName -> serverName.getPort()).collect(Collectors.toList());
    LOG.info("Shutting down cluster");
    UTIL.getHBaseCluster().killAll();
    UTIL.getHBaseCluster().waitUntilShutDown();
    LOG.info("Restarting cluster");
    UTIL.restartHBaseCluster(StartMiniClusterOption.builder().masterClass(HMasterForTest.class)
        .numMasters(1).numRegionServers(3).rsPorts(ports).build());
    UTIL.waitFor(60000, () -> UTIL.getHBaseCluster().getMaster().isInitialized());

    UTIL.waitFor(60000, () -> getServerStateNode(SERVER_FOR_TEST) != null);
    serverNode = getServerStateNode(SERVER_FOR_TEST);
    assertFalse("serverNode should not be ONLINE during SCP processing",
        serverNode.isInState(ServerState.ONLINE));
    Optional<Procedure<?>> procedure = UTIL.getHBaseCluster().getMaster().getProcedures().stream()
        .filter(p -> (p instanceof ServerCrashProcedure) &&
            ((ServerCrashProcedure) p).getServerName().equals(SERVER_FOR_TEST)).findAny();
    assertTrue("Should have one SCP for " + SERVER_FOR_TEST, procedure.isPresent());
    assertTrue("Submit the SCP for the same serverName " + SERVER_FOR_TEST + " which should fail",
      UTIL.getHBaseCluster().getMaster().getServerManager().expireServer(SERVER_FOR_TEST) ==
          Procedure.NO_PROC_ID);

    // Wait the SCP to finish
    SCP_LATCH.countDown();
    UTIL.waitFor(60000, () -> procedure.get().isFinished());

    assertFalse("Even when the SCP is finished, the duplicate SCP should not be scheduled for " +
            SERVER_FOR_TEST,
      UTIL.getHBaseCluster().getMaster().getServerManager().expireServer(SERVER_FOR_TEST) ==
        Procedure.NO_PROC_ID);
    serverNode = UTIL.getHBaseCluster().getMaster().getAssignmentManager().getRegionStates()
        .getServerNode(SERVER_FOR_TEST);
    assertNull("serverNode should be deleted after SCP finished", serverNode);
  }

  private void setupCluster() throws Exception {
    UTIL.startMiniCluster(
        StartMiniClusterOption.builder().masterClass(HMasterForTest.class).numMasters(1)
            .numRegionServers(3).build());
    UTIL.waitFor(60000, () -> UTIL.getMiniHBaseCluster().getMaster().isInitialized());
    // wait for all SCPs finished
    UTIL.waitFor(60000, () -> UTIL.getHBaseCluster().getMaster().getProcedures().stream()
        .noneMatch(p -> p instanceof ServerCrashProcedure));
    UTIL.getHBaseCluster().getMaster().balanceSwitch(false);
  }

  private void setupTable() throws Exception {
    TableName tableName = TABLES[0];
    UTIL.createMultiRegionTable(tableName, FAMILY);
    UTIL.waitTableAvailable(tableName);
    Table table = UTIL.getConnection().getTable(tableName);
    for (int i = 0; i < 100; i++) {
      UTIL.loadTable(table, FAMILY);
    }
  }

  public static final class HMasterForTest extends HMaster {

    public HMasterForTest(Configuration conf) throws IOException, KeeperException {
      super(conf);
    }

    @Override
    protected AssignmentManager createAssignmentManager(MasterServices master) {
      return new AssignmentManagerForTest(master);
    }
  }

  private static final class AssignmentManagerForTest extends AssignmentManager {

    public AssignmentManagerForTest(MasterServices master) {
      super(master);
    }

    @Override
    public List<RegionInfo> getRegionsOnServer(ServerName serverName) {
      List<RegionInfo> regions = super.getRegionsOnServer(serverName);
      // ServerCrashProcedure will call this method, so wait the CountDownLatch here
      if (SCP_LATCH != null && SERVER_FOR_TEST != null && serverName.equals(SERVER_FOR_TEST)) {
        try {
          LOG.info("ServerCrashProcedure wait the CountDownLatch here");
          SCP_LATCH.await();
          LOG.info("Continue the ServerCrashProcedure");
          SCP_LATCH = null;
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }
      return regions;
    }
  }
}

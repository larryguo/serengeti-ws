/***************************************************************************
 * Copyright (c) 2012-2013 VMware, Inc. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ***************************************************************************/
package com.vmware.bdd.service.job;

import org.apache.log4j.Logger;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;

import com.vmware.bdd.entity.ClusterEntity;
import com.vmware.bdd.entity.NetworkEntity;
import com.vmware.bdd.exception.ClusteringServiceException;
import com.vmware.bdd.manager.intf.IExclusiveLockedClusterEntityManager;
import com.vmware.bdd.service.resmgmt.INetworkService;
import com.vmware.bdd.utils.AuAssert;

public class DeleteClusterNodeStep extends TrackableTasklet {
   private static final Logger logger = Logger
         .getLogger(DeleteClusterNodeStep.class);
   private INetworkService networkMgr;
   private IExclusiveLockedClusterEntityManager lockClusterEntityMgr;

   public INetworkService getNetworkMgr() {
      return networkMgr;
   }

   public void setNetworkMgr(INetworkService networkMgr) {
      this.networkMgr = networkMgr;
   }

   public IExclusiveLockedClusterEntityManager getLockClusterEntityMgr() {
      return lockClusterEntityMgr;
   }

   @Autowired
   public void setLockClusterEntityMgr(
         IExclusiveLockedClusterEntityManager lockClusterEntityMgr) {
      this.lockClusterEntityMgr = lockClusterEntityMgr;
   }

   @Override
   public RepeatStatus executeStep(ChunkContext chunkContext,
         JobExecutionStatusHolder jobExecutionStatusHolder) throws Exception {
      String clusterName =
            getJobParameters(chunkContext).getString(
                  JobConstants.CLUSTER_NAME_JOB_PARAM);
      boolean deleted =
            getFromJobExecutionContext(chunkContext,
                  JobConstants.CLUSTER_DELETE_VM_OPERATION_SUCCESS,
                  Boolean.class);
      deleteClusterNodes(chunkContext, clusterName, deleted);
      if (!deleted) {
         // vm deleting is finished, and with error happens, throw exception
         logger.error("Failed to delete nodes.");
         throw ClusteringServiceException.DELETE_CLUSTER_VM_FAILED(clusterName);
      }
      return RepeatStatus.FINISHED;
   }

   private void deleteClusterNodes(ChunkContext chunkContext,
         String clusterName, boolean success) {
      ClusterEntity cluster = getClusterEntityMgr().findByName(clusterName);
      AuAssert.check(cluster != null);
      if (success) {
         releaseIp(cluster);
         lockClusterEntityMgr.getLock(clusterName).lock();
         putIntoJobExecutionContext(chunkContext,
               JobConstants.CLUSTER_EXCLUSIVE_WRITE_LOCKED, true);
         getClusterEntityMgr().delete(cluster);
      }
   }

   private void releaseIp(ClusterEntity cluster) {
      logger.info("Free ip adderss of cluster: " + cluster.getName());
      try {
         for (String networkName : cluster.fetchNetworkNameList()) {
            NetworkEntity networkEntity =
                  networkMgr.getNetworkEntityByName(networkName);
            if (networkEntity.getAllocType() == NetworkEntity.AllocType.IP_POOL) {
               networkMgr.free(networkEntity, cluster.getId());
            }
         }
      } catch (Exception e) {
         logger.error("Ignore failure of free ip address for cluster "
               + cluster.getName(), e);
      }
   }
}

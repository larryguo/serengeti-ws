/***************************************************************************
 * Copyright (c) 2012 VMware, Inc. All Rights Reserved.
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
package com.vmware.bdd.manager;

import java.util.ArrayList;
import java.util.List;

import com.vmware.bdd.apitypes.RackInfo;
import com.vmware.bdd.dal.DAL;
import com.vmware.bdd.entity.PhysicalHostEntity;
import com.vmware.bdd.entity.RackEntity;
import com.vmware.bdd.entity.Saveable;

public class RackInfoManager {

   public void importRackInfo(final List<RackInfo> racksInfo) {
      DAL.inRwTransactionDo(new Saveable<Void>() {
         @Override
         public Void body() throws Exception {
            List<RackEntity> racks = RackEntity.findAllRacks();
            // clean all racks
            for (RackEntity rack : racks) {
               rack.delete();
            }

            for (RackInfo rack : racksInfo) {
               RackEntity.addRack(rack.getName(), rack.getHosts());
            }

            return null;
         }
      });
   }

   public List<RackInfo> exportRackInfo() {
      return DAL.inRoTransactionDo(new Saveable<List<RackInfo>>() {
         @Override
         public List<RackInfo> body() throws Exception {
            List<RackInfo> racksInfo = new ArrayList<RackInfo>();

            List<RackEntity> racks = RackEntity.findAllRacks();
            for (RackEntity rack : racks) {
               List<PhysicalHostEntity> hostEntities = rack.getHosts();
               if (hostEntities != null && !hostEntities.isEmpty()) {
                  List<String> hosts = new ArrayList<String>(hostEntities.size());
                  for (PhysicalHostEntity he : hostEntities) {
                     hosts.add(he.getName());
                  }
                  RackInfo rackInfo = new RackInfo();
                  rackInfo.setName(rack.getName());
                  rackInfo.setHosts(hosts);
                  racksInfo.add(rackInfo);
               }
            }

            return racksInfo;
         }
      });
   }
}
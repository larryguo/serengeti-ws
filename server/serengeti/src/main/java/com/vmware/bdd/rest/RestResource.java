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
package com.vmware.bdd.rest;

import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.NestedRuntimeException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

import com.vmware.bdd.apitypes.BddErrorMessage;
import com.vmware.bdd.apitypes.ClusterCreate;
import com.vmware.bdd.apitypes.ClusterRead;
import com.vmware.bdd.apitypes.DatastoreAdd;
import com.vmware.bdd.apitypes.DatastoreRead;
import com.vmware.bdd.apitypes.DistroRead;
import com.vmware.bdd.apitypes.ElasticityRequestBody;
import com.vmware.bdd.apitypes.FixDiskRequestBody;
import com.vmware.bdd.apitypes.NetworkAdd;
import com.vmware.bdd.apitypes.NetworkRead;
import com.vmware.bdd.apitypes.RackInfo;
import com.vmware.bdd.apitypes.RackInfoList;
import com.vmware.bdd.apitypes.ResourcePoolAdd;
import com.vmware.bdd.apitypes.ResourcePoolRead;
import com.vmware.bdd.apitypes.ResourceScale;
import com.vmware.bdd.apitypes.TaskRead;
import com.vmware.bdd.apitypes.TaskRead.Type;
import com.vmware.bdd.exception.BddException;
import com.vmware.bdd.exception.NetworkException;
import com.vmware.bdd.manager.ClusterManager;
import com.vmware.bdd.manager.DistroManager;
import com.vmware.bdd.manager.JobManager;
import com.vmware.bdd.manager.RackInfoManager;
import com.vmware.bdd.manager.ScaleManager;
import com.vmware.bdd.service.resmgmt.IDatastoreService;
import com.vmware.bdd.service.resmgmt.INetworkService;
import com.vmware.bdd.service.resmgmt.IResourcePoolService;
import com.vmware.bdd.utils.CommonUtil;
import com.vmware.bdd.utils.Constants;
import com.vmware.bdd.utils.IpAddressUtil;

@Controller
public class RestResource {
   private static final Logger logger = Logger.getLogger(RestResource.class);

   @Autowired
   private ClusterManager clusterMgr;
   @Autowired
   private JobManager jobManager;
   @Autowired
   private IResourcePoolService vcRpSvc;
   @Autowired
   private INetworkService networkSvc;
   @Autowired
   private RackInfoManager rackInfoManager;
   @Autowired
   private DistroManager distroManager;
   @Autowired
   private IDatastoreService datastoreSvc;
   @Autowired
   private ScaleManager scaleMgr;

   private static final String ERR_CODE_FILE = "serengeti-errcode.properties";
   private static final int DEFAULT_HTTP_ERROR_CODE = 500;

   /* HTTP status code read from a config file. */
   private static org.apache.commons.configuration.Configuration httpStatusCodes =
         init();

   private static org.apache.commons.configuration.Configuration init() {
      PropertiesConfiguration config = null;
      try {
         config = new PropertiesConfiguration();
         config.setListDelimiter('\0');
         config.load(ERR_CODE_FILE);
      } catch (ConfigurationException ex) {
         // error out if the configuration file is not there
         String message = "Cannot load Serengeti error message file.";
         Logger.getLogger(RestResource.class).fatal(message, ex);
         throw BddException.APP_INIT_ERROR(ex, message);
      }
      return config;
   }

   private static int getHttpErrorCode(String errorId) {
      return httpStatusCodes.getInteger(errorId, DEFAULT_HTTP_ERROR_CODE);
   }

   @RequestMapping(value = "/hello", method = RequestMethod.GET)
   @ResponseStatus(HttpStatus.OK)
   @ResponseBody
   public String getHello() {
      return Constants.VERSION;
   }

   // task API
   @RequestMapping(value = "/tasks", method = RequestMethod.GET, produces = "application/json")
   @ResponseBody
   public List<TaskRead> getTasks() {
      return jobManager.getLatestTaskForExistedClusters();
   }

   @RequestMapping(value = "/task/{taskId}", method = RequestMethod.GET, produces = "application/json")
   @ResponseBody
   public TaskRead getTaskById(@PathVariable long taskId) throws Exception {

      // TODO add exception handling
      TaskRead task = jobManager.getJobExecutionStatus(taskId);
      if (task.getStatus() == TaskRead.Status.COMPLETED) {
         task.setProgress(1.0);
      }
      if (task.getType() == null) {
         task.setType(Type.INNER); // XXX just keep the interface now
      }
      return task;
   }

   // cluster API
   @RequestMapping(value = "/clusters", method = RequestMethod.POST, consumes = "application/json")
   @ResponseStatus(HttpStatus.ACCEPTED)
   public void createCluster(@RequestBody ClusterCreate createSpec,
         HttpServletRequest request, HttpServletResponse response)
         throws Exception {
      String clusterName = createSpec.getName();
      if (!CommonUtil.validateClusterName(clusterName)) {
         throw BddException.INVALID_PARAMETER("cluster name", clusterName);
      }
      long jobExecutionId = clusterMgr.createCluster(createSpec);
      redirectRequest(jobExecutionId, request, response);
   }

   // cluster API
   @RequestMapping(value = "/cluster/{clusterName}/config", method = RequestMethod.PUT, consumes = "application/json")
   @ResponseStatus(HttpStatus.ACCEPTED)
   public void configCluster(@PathVariable("clusterName") String clusterName,
         @RequestBody ClusterCreate createSpec, HttpServletRequest request,
         HttpServletResponse response) throws Exception {
      if (!CommonUtil.validateClusterName(clusterName)) {
         throw BddException.INVALID_PARAMETER("cluster name", clusterName);
      }
      Long taskId = clusterMgr.configCluster(clusterName, createSpec);
      redirectRequest(taskId, request, response);
   }

   private void redirectRequest(long taskId, HttpServletRequest request,
         HttpServletResponse response) {
      StringBuffer url = request.getRequestURL();
      String pathInfo = request.getPathInfo();
      if (!CommonUtil.validataPathInfo(pathInfo)) {
         throw BddException.INVALID_PARAMETER("requested path info", pathInfo);
      }
      int subLength = url.length() - pathInfo.length();
      url.setLength(subLength);
      url.append("/task/").append(Long.toString(taskId));
      response.setHeader("Location", url.toString());
   }

   @RequestMapping(value = "/cluster/{clusterName}", method = RequestMethod.DELETE)
   @ResponseStatus(HttpStatus.ACCEPTED)
   public void deleteCluster(@PathVariable("clusterName") String clusterName,
         HttpServletRequest request, HttpServletResponse response)
         throws Exception {
      // make sure cluster name is valid
      if (!CommonUtil.validateClusterName(clusterName)) {
         throw BddException.INVALID_PARAMETER("cluster name", clusterName);
      }
      Long taskId = clusterMgr.deleteClusterByName(clusterName);
      redirectRequest(taskId, request, response);
   }

   @RequestMapping(value = "/cluster/{clusterName}", method = RequestMethod.PUT)
   @ResponseStatus(HttpStatus.ACCEPTED)
   public void startStopResumeCluster(
         @PathVariable("clusterName") String clusterName,
         @RequestParam(value = "state", required = true) String state,
         HttpServletRequest request, HttpServletResponse response)
         throws Exception {
      if (CommonUtil.isBlank(clusterName)
            || !CommonUtil.validateClusterName(clusterName)) {
         throw BddException.INVALID_PARAMETER("cluster name", clusterName);
      }

      Long taskId;
      if (state.equals("stop")) {
         taskId = clusterMgr.stopCluster(clusterName);
         redirectRequest(taskId, request, response);
      } else if (state.equals("start")) {
         taskId = clusterMgr.startCluster(clusterName);
         redirectRequest(taskId, request, response);
      } else if (state.equals("resume")) {
         taskId = clusterMgr.resumeClusterCreation(clusterName);
         redirectRequest(taskId, request, response);
      } else {
         throw BddException.INVALID_PARAMETER("cluster state", state);
      }
   }

   @RequestMapping(value = "/cluster/{clusterName}/nodegroup/{groupName}/instancenum", method = RequestMethod.PUT)
   @ResponseStatus(HttpStatus.ACCEPTED)
   public void resizeCluster(@PathVariable("clusterName") String clusterName,
         @PathVariable("groupName") String groupName,
         @RequestBody int instanceNum, HttpServletRequest request,
         HttpServletResponse response) throws Exception {
      if (CommonUtil.isBlank(clusterName)
            || !CommonUtil.validateClusterName(clusterName)) {
         throw BddException.INVALID_PARAMETER("cluster name", clusterName);
      }

      if (CommonUtil.isBlank(groupName)
            || !CommonUtil.validateNodeGroupName(groupName)) {
         throw BddException.INVALID_PARAMETER("node group name", groupName);
      }

      if (instanceNum <= 0) {
         throw BddException.INVALID_PARAMETER("node group instance number",
               String.valueOf(instanceNum));
      }
      Long taskId =
            clusterMgr.resizeCluster(clusterName, groupName, instanceNum);
      redirectRequest(taskId, request, response);
   }

   @RequestMapping(value = "/cluster/{clusterName}/nodegroup/{groupName}/scale", method = RequestMethod.PUT)
   @ResponseStatus(HttpStatus.ACCEPTED)
   public void scale(@PathVariable("clusterName") String clusterName,
         @PathVariable("groupName") String groupName,
         @RequestBody ResourceScale scale, HttpServletRequest request,
         HttpServletResponse response) throws Exception {
      if (CommonUtil.isBlank(clusterName)
            || !CommonUtil.validateClusterName(clusterName)) {
         throw BddException.INVALID_PARAMETER("cluster name", clusterName);
      }

      if (CommonUtil.isBlank(groupName)
            || !CommonUtil.validateNodeGroupName(groupName)) {
         throw BddException.INVALID_PARAMETER("node group name", groupName);
      }

	  if (scale.getCpuNumber() <= 0
			&& scale.getMemory() < Constants.MIN_MEM_SIZE) {
		 throw BddException.INVALID_PARAMETER_WITHOUT_EQUALS_SIGN(
						"node group scale parameter. The number of CPUs must be greater than zero, and the memory size in MB must be greater than or equal to "
								+ Constants.MIN_MEM_SIZE + ":", scale);
		}
      logger.info("scale cluster: " + scale.toString());
      Long taskId =
            scaleMgr.scaleNodeGroupResource(scale);
      redirectRequest(taskId, request, response);
   }

   @RequestMapping(value = "/cluster/{clusterName}/param_wait_for_result", method = RequestMethod.PUT)
   @ResponseStatus(HttpStatus.ACCEPTED)
   public void asyncSetParam(@PathVariable("clusterName") String clusterName, @RequestBody ElasticityRequestBody requestBody, HttpServletRequest request,
         HttpServletResponse response) throws Exception {
      validateInput(clusterName, requestBody);
      ClusterRead cluster = clusterMgr.getClusterByName(clusterName, false);
      if (!cluster.needAsyncUpdateParam(requestBody)) {
            throw BddException.BAD_REST_CALL(null, "invalid input to cluster.");
      }

      Long taskId =
            clusterMgr.asyncSetParam(clusterName,
                  requestBody.getActiveComputeNodeNum(),
                  requestBody.getMinComputeNodeNum(),
                  requestBody.getMaxComputeNodeNum(),
                  requestBody.getEnableAuto(), requestBody.getIoPriority());
      redirectRequest(taskId, request, response);
   }

   @RequestMapping(value = "/cluster/{clusterName}/param", method = RequestMethod.PUT)
   @ResponseStatus(HttpStatus.OK)
   public void syncSetParam(@PathVariable("clusterName") String clusterName, @RequestBody ElasticityRequestBody requestBody, HttpServletRequest request,
         HttpServletResponse response) throws Exception {
      validateInput(clusterName, requestBody);

      clusterMgr.syncSetParam(clusterName,
            requestBody.getActiveComputeNodeNum(),
            requestBody.getMinComputeNodeNum(),
            requestBody.getMaxComputeNodeNum(),
            requestBody.getEnableAuto(),
            requestBody.getIoPriority());
   }

   private void validateInput(String clusterName, ElasticityRequestBody requestBody) {
      if (CommonUtil.isBlank(clusterName) || !CommonUtil.validateClusterName(clusterName)) {
         throw BddException.INVALID_PARAMETER("cluster name", clusterName);
      }

      Integer minComputeNodeNum = requestBody.getMinComputeNodeNum();
      if (minComputeNodeNum != null && minComputeNodeNum < -1) {
         throw BddException.INVALID_PARAMETER("min compute node num", minComputeNodeNum.toString());
      }

      Integer maxComputeNodeNum = requestBody.getMaxComputeNodeNum();
      if (maxComputeNodeNum != null && maxComputeNodeNum < -1) {
         throw BddException.INVALID_PARAMETER("max compute node num", maxComputeNodeNum.toString());
      }

      Integer activeComputeNodeNum = requestBody.getActiveComputeNodeNum();
      // The active compute node number must be a positive number or -1.
      if (activeComputeNodeNum != null && activeComputeNodeNum < -1) {
         logger.error("Invalid instance number: " + activeComputeNodeNum + " !");
         throw BddException.INVALID_PARAMETER("instance number", activeComputeNodeNum.toString());
      }
   }

   @RequestMapping(value = "/cluster/{clusterName}/fix/disk", method = RequestMethod.PUT)
   @ResponseStatus(HttpStatus.ACCEPTED)
   public void fixCluster(@PathVariable("clusterName") String clusterName,
         @RequestBody FixDiskRequestBody requestBody,
         HttpServletRequest request, HttpServletResponse response)
         throws Exception {
      Long taskId =
            clusterMgr.fixDiskFailures(clusterName,
                  requestBody.getNodeGroupName());
      redirectRequest(taskId, request, response);
   }

   @RequestMapping(value = "/cluster/{clusterName}", method = RequestMethod.GET, produces = "application/json")
   @ResponseBody
   public ClusterRead getCluster(
         @PathVariable("clusterName") final String clusterName,
         @RequestParam(value = "details", required = false) Boolean details) {
      if (CommonUtil.isBlank(clusterName)
            || !CommonUtil.validateClusterName(clusterName)) {
         throw BddException.INVALID_PARAMETER("cluster name", clusterName);
      }
      return clusterMgr.getClusterByName(clusterName, (details == null) ? false
            : details);
   }

   @RequestMapping(value = "/cluster/{clusterName}/spec", method = RequestMethod.GET, produces = "application/json")
   @ResponseBody
   public ClusterCreate getClusterSpec(
         @PathVariable("clusterName") final String clusterName) {
      if (CommonUtil.isBlank(clusterName)
            || !CommonUtil.validateClusterName(clusterName)) {
         throw BddException.INVALID_PARAMETER("cluster name", clusterName);
      }
      return clusterMgr.getClusterSpec(clusterName);
   }

   @RequestMapping(value = "/clusters", method = RequestMethod.GET, produces = "application/json")
   @ResponseBody
   public List<ClusterRead> getClusters(
         @RequestParam(value = "details", required = false) Boolean details) {
      return clusterMgr.getClusters((details == null) ? false : details);
   }

   // cloud provider API
   @RequestMapping(value = "/resourcepools", method = RequestMethod.POST, consumes = "application/json")
   @ResponseStatus(HttpStatus.OK)
   public void addResourcePool(@RequestBody ResourcePoolAdd rpSpec) {
      if (rpSpec == null) {
         throw BddException.INVALID_PARAMETER("rpSpec", null);
      }
      if (CommonUtil.isBlank(rpSpec.getName())
            || !CommonUtil.validateName(rpSpec.getName())) {
         throw BddException.INVALID_PARAMETER("resource pool name",
               rpSpec.getName());
      }
      if (CommonUtil.isBlank(rpSpec.getVcClusterName())
            || !CommonUtil.validateName(rpSpec.getVcClusterName())) {
         throw BddException.INVALID_PARAMETER("vCenter Server cluster name",
               rpSpec.getVcClusterName());
      }
      rpSpec.setResourcePoolName(CommonUtil.notNull(
            rpSpec.getResourcePoolName(), ""));
      if (!CommonUtil.isBlank(rpSpec.getResourcePoolName())
            && !CommonUtil.validateName(rpSpec.getResourcePoolName())) {
         throw BddException.INVALID_PARAMETER(
               "vCenter Server resource pool name",
               rpSpec.getResourcePoolName());
      }

      vcRpSvc.addResourcePool(rpSpec.getName(), rpSpec.getVcClusterName(),
            rpSpec.getResourcePoolName());
   }

   @RequestMapping(value = "/resourcepools", method = RequestMethod.GET, produces = "application/json")
   @ResponseBody
   public List<ResourcePoolRead> getResourcePools() {
      return vcRpSvc.getAllResourcePoolForRest();
   }

   @RequestMapping(value = "/resourcepool/{rpName}", method = RequestMethod.GET, produces = "application/json")
   @ResponseBody
   public ResourcePoolRead getResourcePool(
         @PathVariable("rpName") final String rpName) {
      if (CommonUtil.isBlank(rpName) || !CommonUtil.validateName(rpName)) {
         throw BddException.INVALID_PARAMETER("resource pool name", rpName);
      }
      ResourcePoolRead read = vcRpSvc.getResourcePoolForRest(rpName);
      if (read == null) {
         throw BddException.NOT_FOUND("Resource pool", rpName);
      }
      return read;
   }

   @RequestMapping(value = "/resourcepool/{rpName}", method = RequestMethod.DELETE)
   @ResponseStatus(HttpStatus.OK)
   public void deleteResourcePool(@PathVariable("rpName") final String rpName) {
      if (CommonUtil.isBlank(rpName) || !CommonUtil.validateName(rpName)) {
         throw BddException.INVALID_PARAMETER("resource pool name", rpName);
      }
      vcRpSvc.deleteResourcePool(rpName);
   }

   @RequestMapping(value = "/datastores", method = RequestMethod.POST, consumes = "application/json")
   @ResponseStatus(HttpStatus.OK)
   public void addDatastore(@RequestBody DatastoreAdd dsSpec) {
      if (dsSpec == null) {
         throw BddException.INVALID_PARAMETER("dsSpec", null);
      }
      if (CommonUtil.isBlank(dsSpec.getName())
            || !CommonUtil.validateName(dsSpec.getName())) {
         throw BddException.INVALID_PARAMETER("datestore name",
               dsSpec.getName());
      }
      if (!dsSpec.getRegex() && !CommonUtil.validateVcDataStoreNames(dsSpec.getSpec())) {
         throw BddException.INVALID_PARAMETER("vCenter Server datastore name",
               dsSpec.getSpec().toString());
      }
      datastoreSvc.addDatastores(dsSpec);
   }

   @RequestMapping(value = "/datastore/{dsName}", method = RequestMethod.GET, produces = "application/json")
   @ResponseBody
   public DatastoreRead getDatastore(@PathVariable("dsName") final String dsName) {
      if (CommonUtil.isBlank(dsName) || !CommonUtil.validateName(dsName)) {
         throw BddException.INVALID_PARAMETER("datestore name", dsName);
      }
      DatastoreRead read = datastoreSvc.getDatastoreRead(dsName);
      if (read == null) {
         throw BddException.NOT_FOUND("Data store", dsName);
      }
      return read;
   }

   @RequestMapping(value = "/datastores", method = RequestMethod.GET, produces = "application/json")
   @ResponseBody
   public List<DatastoreRead> getDatastores() {
      return datastoreSvc.getAllDatastoreReads();
   }

   @RequestMapping(value = "/datastore/{dsName}", method = RequestMethod.DELETE)
   @ResponseStatus(HttpStatus.OK)
   public void deleteDatastore(@PathVariable("dsName") String dsName) {
      if (CommonUtil.isBlank(dsName) || !CommonUtil.validateName(dsName)) {
         throw BddException.INVALID_PARAMETER("date store name", dsName);
      }
      datastoreSvc.deleteDatastore(dsName);
   }

   @RequestMapping(value = "/network/{networkName}", method = RequestMethod.DELETE)
   @ResponseStatus(HttpStatus.OK)
   public void deleteNetworkByName(
         @PathVariable("networkName") final String networkName) {
      if (CommonUtil.isBlank(networkName)
            || !CommonUtil.validateName(networkName)) {
         throw BddException.INVALID_PARAMETER("network name", networkName);
      }
      networkSvc.removeNetwork(networkName);
   }

   @RequestMapping(value = "/network/{networkName}", method = RequestMethod.GET, produces = "application/json")
   @ResponseBody
   public NetworkRead getNetworkByName(
         @PathVariable("networkName") final String networkName,
         @RequestParam(value = "details", required = false, defaultValue = "false") final Boolean details) {
      if (CommonUtil.isBlank(networkName)
            || !CommonUtil.validateName(networkName)) {
         throw BddException.INVALID_PARAMETER("network name", networkName);
      }
      NetworkRead network =
            networkSvc.getNetworkByName(networkName, details != null ? details
                  : false);
      if (network == null) {
         throw NetworkException.NOT_FOUND("Network", networkName);
      }
      return network;
   }

   @RequestMapping(value = "/networks", method = RequestMethod.GET, produces = "application/json")
   @ResponseBody
   public List<NetworkRead> getNetworks(
         @RequestParam(value = "details", required = false, defaultValue = "false") final Boolean details) {
      return networkSvc.getAllNetworks(details != null ? details : false);
   }

   @RequestMapping(value = "/networks", method = RequestMethod.POST, consumes = "application/json")
   @ResponseStatus(HttpStatus.OK)
   public void addNetworks(@RequestBody final NetworkAdd na) {
      if (CommonUtil.isBlank(na.getName())
            || !CommonUtil.validateName(na.getName())) {
         throw BddException.INVALID_PARAMETER("name", na.getName());
      }
      if (CommonUtil.isBlank(na.getPortGroup())
            || !CommonUtil.validatePortGroupName(na.getPortGroup())) {
         throw BddException.INVALID_PARAMETER("port group", na.getPortGroup());
      }

      if (na.isDhcp()) {
         networkSvc.addDhcpNetwork(na.getName(), na.getPortGroup());
      } else {
         if (!IpAddressUtil.isValidNetmask(na.getNetmask())) {
            throw BddException.INVALID_PARAMETER("netmask", na.getNetmask());
         }
         long netmask = IpAddressUtil.getAddressAsLong(na.getNetmask());
         if (na.getGateway() != null && !IpAddressUtil.isValidIp(netmask,
               IpAddressUtil.getAddressAsLong(na.getGateway()))) {
            throw BddException.INVALID_PARAMETER("gateway", na.getGateway());
         }
         if (na.getDns1() != null && !IpAddressUtil.isValidIp(na.getDns1())) {
            throw BddException.INVALID_PARAMETER("primary DNS", na.getDns1());
         }
         if (na.getDns2() != null && !IpAddressUtil.isValidIp(na.getDns2())) {
            throw BddException.INVALID_PARAMETER("secondary DNS", na.getDns2());
         }
         IpAddressUtil.verifyIPBlocks(na.getIp(), netmask);
         networkSvc.addIpPoolNetwork(na.getName(), na.getPortGroup(),
               na.getNetmask(), na.getGateway(), na.getDns1(), na.getDns2(),
               na.getIp());
      }
   }

   @RequestMapping(value = "/network/{networkName}", method = RequestMethod.PUT, consumes = "application/json")
   @ResponseStatus(HttpStatus.OK)
   public void increaseIPs(@PathVariable("networkName") String networkName,
         @RequestBody NetworkAdd network, HttpServletRequest request,
         HttpServletResponse response) {
      if (CommonUtil.isBlank(networkName)
            || !CommonUtil.validateName(networkName)) {
         throw BddException.INVALID_PARAMETER("network name", networkName);
      }
      networkSvc.increaseIPs(networkName, network.getIp());
   }

   @RequestMapping(value = "/racks", method = RequestMethod.PUT)
   @ResponseStatus(HttpStatus.OK)
   public void importRacks(@RequestBody final RackInfoList racksInfo)
         throws Exception {
      if (racksInfo == null || racksInfo.size() == 0) {
         throw BddException.INVALID_PARAMETER("rack list", "empty");
      }

      rackInfoManager.importRackInfo(racksInfo);
   }

   @RequestMapping(value = "/racks", method = RequestMethod.GET, produces = "application/json")
   @ResponseBody
   public List<RackInfo> exportRacks() throws Exception {
      return rackInfoManager.exportRackInfo();
   }

   @RequestMapping(value = "/distros", method = RequestMethod.GET, produces = "application/json")
   @ResponseBody
   public List<DistroRead> getDistros() {
      return distroManager.getDistros();
   }

   @RequestMapping(value = "/distro/{distroName}", method = RequestMethod.GET, produces = "application/json")
   @ResponseBody
   public DistroRead getDistroByName(
         @PathVariable("distroName") String distroName) {
      if (CommonUtil.isBlank(distroName)
            || !CommonUtil.validateName(distroName)) {
         throw BddException.INVALID_PARAMETER("distro name", distroName);
      }
      DistroRead distro = distroManager.getDistroByName(distroName);
      if (distro == null) {
         throw BddException.NOT_FOUND("Distro", distroName);
      }

      return distro;
   }

   @ExceptionHandler(Throwable.class)
   @ResponseBody
   public BddErrorMessage handleException(Throwable t,
         HttpServletResponse response) {
      if (t instanceof NestedRuntimeException) {
         t = BddException.BAD_REST_CALL(t, t.getMessage());
      }
      BddException ex =
            BddException.wrapIfNeeded(t, "REST API transport layer error.");
      logger.error("rest call error", ex);
      response.setStatus(getHttpErrorCode(ex.getFullErrorId()));
      return new BddErrorMessage(ex.getFullErrorId(), extractErrorMessage(ex));
   }

   /**
    * @param ex
    * @return
    */
   private String extractErrorMessage(BddException ex) {
      String msg = ex.getMessage();
      if (ex.getCause() instanceof DataAccessException) {
         msg = "Data access layer exception. See the detailed error in the log";
      }
      return msg;
   }
}

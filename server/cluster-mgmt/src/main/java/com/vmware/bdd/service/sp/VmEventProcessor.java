package com.vmware.bdd.service.sp;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;

import com.vmware.aurora.exception.AuroraException;
import com.vmware.aurora.global.Configuration;
import com.vmware.aurora.util.CmsWorker;
import com.vmware.aurora.util.CmsWorker.WorkQueue;
import com.vmware.aurora.vc.MoUtil;
import com.vmware.aurora.vc.VcCache;
import com.vmware.aurora.vc.VcHost;
import com.vmware.aurora.vc.VcUtil;
import com.vmware.aurora.vc.VcVirtualMachine;
import com.vmware.aurora.vc.vcevent.VcEventHandlers.IVcEventHandler;
import com.vmware.aurora.vc.vcevent.VcEventHandlers.VcEventType;
import com.vmware.aurora.vc.vcevent.VcEventListener;
import com.vmware.aurora.vc.vcservice.VcContext;
import com.vmware.aurora.vc.vcservice.VcSession;
import com.vmware.bdd.entity.NodeEntity;
import com.vmware.bdd.manager.intf.IClusterEntityManager;
import com.vmware.bdd.manager.intf.IConcurrentLockedClusterEntityManager;
import com.vmware.bdd.service.utils.VcResourceUtils;
import com.vmware.bdd.utils.AuAssert;
import com.vmware.bdd.utils.CommonUtil;
import com.vmware.bdd.utils.ConfigInfo;
import com.vmware.bdd.utils.Constants;
import com.vmware.vim.binding.vim.Folder;
import com.vmware.vim.binding.vim.event.Event;
import com.vmware.vim.binding.vim.event.EventEx;
import com.vmware.vim.binding.vim.event.HostEvent;
import com.vmware.vim.binding.vim.event.VmEvent;
import com.vmware.vim.binding.vim.vm.FaultToleranceSecondaryConfigInfo;
import com.vmware.vim.binding.vmodl.ManagedObjectReference;
import com.vmware.vim.binding.vmodl.fault.ManagedObjectNotFound;

public class VmEventProcessor extends Thread {
   private static class EventWrapper {
      private VcEventType type;
      private Event event;
      private boolean external;

      public EventWrapper(VcEventType type, Event event, boolean external) {
         this.type = type;
         this.event = event;
         this.external = external;
      }
   }

   private static final EnumSet<VcEventType> vmEvents = EnumSet.of(
         VcEventType.VmDasBeingResetWithScreenshot, VcEventType.VmDrsPoweredOn,
         VcEventType.VmMigrated, VcEventType.VmConnected,
         VcEventType.VmCreated, VcEventType.VmDasBeingReset,
         VcEventType.VmDasResetFailed, VcEventType.VmDisconnected,
         VcEventType.VmMessage, VcEventType.VmMessageError,
         VcEventType.VmMessageWarning, VcEventType.VmOrphaned,
         VcEventType.VmPoweredOn, VcEventType.VmPoweredOff,
         VcEventType.VmReconfigured, VcEventType.VmRegistered,
         VcEventType.VmRelocated, VcEventType.VmRemoved, VcEventType.VmRenamed,
         VcEventType.VmResourcePoolMoved, VcEventType.VmResuming,
         VcEventType.VmSuspended, VcEventType.VmAppHealthChanged,
         VcEventType.NotEnoughResourcesToStartVmEvent,
         VcEventType.VmMaxRestartCountReached, VcEventType.VmFailoverFailed,
         VcEventType.VmCloned, VcEventType.VhmError, VcEventType.VhmWarning,
         VcEventType.VhmInfo,
         VcEventType.VhmUser,

         // host event set
         VcEventType.HostConnected, VcEventType.HostRemoved,
         VcEventType.EnteredMaintenanceMode, VcEventType.ExitMaintenanceMode,
         VcEventType.HostDisconnected);

   private static final Logger logger = Logger
         .getLogger(VmEventProcessor.class);
   private BlockingQueue<EventWrapper> queue =
         new LinkedBlockingQueue<EventWrapper>();
   private boolean isTerminate = false;
   private IConcurrentLockedClusterEntityManager lockMgr;
   private IClusterEntityManager clusterEntityMgr;
   private Folder rootSerengetiFolder = null;

   public VmEventProcessor(IConcurrentLockedClusterEntityManager lockMgr) {
      super();
      this.lockMgr = lockMgr;
      this.clusterEntityMgr = lockMgr.getClusterEntityMgr();
   }

   private void initRootFolder() {
      String serverMobId =
            Configuration.getString(Constants.SERENGETI_SERVER_VM_MOBID);
      VcVirtualMachine serverVm = VcCache.get(serverMobId);
      String root = ConfigInfo.getSerengetiRootFolder();
      List<String> folderNames = new ArrayList<String>();
      folderNames.add(root);
      this.rootSerengetiFolder =
            VcResourceUtils.findFolderByNameList(serverVm.getDatacenter(),
                  folderNames);
      if (rootSerengetiFolder == null) {
         logger.info("VM root folder" + root
               + " is not created yet. Ignore external VM event.");
      }
   }

   public void installEventHandler() {
      VcEventListener.installExtEventHandler(vmEvents, new IVcEventHandler() {
         @Override
         public boolean eventHandler(VcEventType type, Event e)
               throws Exception {
            logger.debug("Received external VM event " + e);
            add(type, e, true);
            return false;
         }
      });
      VcEventListener.installEventHandler(vmEvents, new IVcEventHandler() {
         @Override
         public boolean eventHandler(VcEventType type, Event e)
               throws Exception {
            logger.debug("Received internal VM event " + e);
            add(type, e, false);
            return false;
         }
      });
   }

   public void add(VcEventType type, Event event, boolean external) {
      EventWrapper wrapper = new EventWrapper(type, event, external);
      queue.add(wrapper);
   }

   public void run() {
      while (!isTerminate) {
         Event event = null;
         try {
            final EventWrapper wrapper = queue.poll(1, TimeUnit.MINUTES);
            if (wrapper != null) {
               event = wrapper.event;
               VcContext.inVcSessionDo(new VcSession<Void>() {
                  @Override
                  protected boolean isTaskSession() {
                     return true;
                  }

                  @Override
                  protected Void body() throws Exception {
                     processEvent(wrapper.type, wrapper.event, wrapper.external);
                     return null;
                  }
               });
            }
         } catch (InterruptedException e) {
            logger.warn("Thread interrupt exception received, exit.");
            isTerminate = true;
         } catch (Exception e) {
            if (event != null) {
               logger.error("Failed to process event: " + event, e);
            } else {
               logger.error("Failed to process VM event.", e);
            }
         }
      }
   }

   public boolean isTerminate() {
      return isTerminate;
   }

   public void setTerminate(boolean isTerminate) {
      this.isTerminate = isTerminate;
   }

   private boolean processExternalEvent(VcEventType type, Event e, String moId)
         throws Exception {
      if (clusterEntityMgr.getNodeByMobId(moId) != null) {
         return true;
      }
      if (type != VcEventType.VmRemoved) {
         VcVirtualMachine vm = VcCache.getIgnoreMissing(e.getVm().getVm());
         if (vm == null) {
            return false;
         }
         logger.debug("Event received for VM not managed by Serengeti");
         if (rootSerengetiFolder == null) {
            initRootFolder();
         }
         if (rootSerengetiFolder == null) {
            return false;
         }
         if (clusterEntityMgr.getNodeByVmName(vm.getName()) != null
               && VcResourceUtils.insidedRootFolder(rootSerengetiFolder, vm)) {
            logger.info("VM " + vm.getName()
                  + " is Serengeti created VM, add it into meta-db");
            return true;
         }
      }
      return false;
   }

   private void processHostEvent(VcEventType type, Event e) throws Exception {
      logger.info("Received host event " + e);
      HostEvent he = (HostEvent) e;
      String hostName = he.getHost().getName();
      List<NodeEntity> nodes = clusterEntityMgr.getNodesByHost(hostName);
      switch (type) {
      case HostConnected:
      case EnteredMaintenanceMode:
      case ExitMaintenanceMode:
      case HostDisconnected: {
         sleep(2000);
         VcHost host = VcCache.getIgnoreMissing(he.getHost().getHost());
         host.update();
         for (NodeEntity node : nodes) {
            String moId = node.getMoId();
            if (moId == null) {
               continue;
            }
            VcVirtualMachine vm = VcCache.getIgnoreMissing(moId);
            if (vm == null) {
               continue;
            }
            logger.info("Process VM " + vm.getName()
                  + " for received host event " + type + " of " + hostName);
            if (logger.isDebugEnabled()) {
               logger.debug("host availability: "
                     + !vm.getHost().isUnavailbleForManagement());
               logger.debug("host connection: " + vm.getHost().isConnected());
               logger.debug("host maintenance: "
                     + vm.getHost().isInMaintenanceMode());
               logger.debug("vm connection: " + vm.isConnected());
            }
            String clusterName = CommonUtil.getClusterName(vm.getName());
            try {
               vm.updateRuntime();
               lockMgr.refreshNodeByVmName(clusterName, moId, vm.getName(),
                     true);
               if ((!vm.isConnected())
                     || vm.getHost().isUnavailbleForManagement()) {
                  logConnectionChangeEvent(vm.getName());
               }
            } catch (ManagedObjectNotFound me) {
               continue;
            }
         }
         break;
      }
      case HostRemoved: {
         for (NodeEntity node : nodes) {
            String moId = node.getMoId();
            if (moId == null) {
               continue;
            }
            logger.debug("Remove node " + node.getVmName()
                  + " for host is removed from VC.");
            String clusterName = CommonUtil.getClusterName(node.getVmName());
            lockMgr.removeVmReference(clusterName, moId);
         }
         break;
      }
      }
   }

   private void logConnectionChangeEvent(String vmName) {
      String message =
            "VM "
                  + vmName
                  + " connection state changed. "
                  + "For any operations you did on the cluster in the VM "
                  + "disconnected time, you need to repeat them for this VM manually.";
      logger.warn(message);
   }

   public void processEvent(VcEventType type, Event e, boolean external)
         throws Exception {
      if (e instanceof HostEvent) {
         processHostEvent(type, e);
         return;
      }
      // Event can be either VmEvent or EventEx (TODO: Explicitly check for
      // VM specific EventEx class usage? e.g. VcEventType.VmAppHealthChanged?)
      AuAssert.check(e instanceof VmEvent || e instanceof EventEx);
      ManagedObjectReference moRef = e.getVm().getVm();
      String moId = MoUtil.morefToString(moRef);
      String externalStr = external ? " external" : "";
      logger.debug("processed" + externalStr + " vm event: " + e);
      if (external) {
         if (processExternalEvent(type, e, moId)) {
            VcVirtualMachine vm = VcCache.getIgnoreMissing(e.getVm().getVm());
            String newId = switchMobId(moId, vm);
            processEvent(type, e, newId, true);
         }
      } else {
         processEvent(type, e, moId, false);
      }
   }

   private void processEvent(VcEventType type, Event e, String moId,
         boolean external) throws Exception {
      try {
         switch (type) {
         case VmRemoved: {
            logger.debug("received vm removed event for vm: " + moId);
            NodeEntity node = clusterEntityMgr.getNodeByMobId(moId);
            if (node != null) {
               String clusterName = CommonUtil.getClusterName(node.getVmName());
               lockMgr.refreshNodeByMobId(clusterName, moId, null, true);
            }
            break;
         }
         case VmDisconnected: {
            VcVirtualMachine vm = VcCache.getIgnoreMissing(moId);
            if (vm == null) {
               NodeEntity node = clusterEntityMgr.getNodeByMobId(moId);
               if (node != null) {
                  String clusterName =
                        CommonUtil.getClusterName(node.getVmName());
                  logger.debug("vm " + moId + " is already removed");
                  lockMgr.removeVmReference(clusterName, moId);
               }
               break;
            }
            if (clusterEntityMgr.getNodeByVmName(vm.getName()) != null) {
               vm.updateRuntime();
               if ((!vm.isConnected())
                     || vm.getHost().isUnavailbleForManagement()) {
                  String clusterName = CommonUtil.getClusterName(vm.getName());
                  lockMgr.setNodeConnectionState(clusterName, vm.getName());
                  logConnectionChangeEvent(vm.getName());
               }
            }
            break;
         }
         case VmPoweredOn: {
            refreshNodeWithAction(moId, true, Constants.NODE_ACTION_WAITING_IP,
                  "Powered On");
            if (external) {
               NodePowerOnRequest request =
                     new NodePowerOnRequest(lockMgr, moId);
               CmsWorker.addRequest(WorkQueue.VC_TASK_NO_DELAY, request);
            }
            break;
         }
         case VmCloned: {
            refreshNodeWithAction(moId, true,
                  Constants.NODE_ACTION_RECONFIGURE, "Cloned");
            break;
         }
         case VmSuspended: {
            refreshNodeWithAction(moId, true, null, "Suspended");
            break;
         }
         case VmPoweredOff: {
            refreshNodeWithAction(moId, true, null, "Powered Off");
            break;
         }
         case VmConnected: {
            try {
               refreshNodeWithAction(moId, false, null, type.name());
            } catch (AuroraException ex) {
               // vm is not able to be accessed immediately after it's created, 
               // ignore the exception here to continue other event processing
               logger.error("Catch aurora exception " + ex.getMessage()
                     + ", ignore it.");
            }
            break;
         }
         case VmMigrated: {
            refreshNodeWithAction(moId, false, null, type.name());
            break;
         }
         case VhmError:
         case VhmWarning: {
            EventEx event = (EventEx) e;
            VcVirtualMachine vm =
                  VcCache.getIgnoreMissing(event.getVm().getVm());
            if (vm == null) {
               break;
            }
            if (clusterEntityMgr.getNodeByVmName(vm.getName()) != null) {
               logger.info("received vhm event " + event.getEventTypeId()
                     + " for vm " + vm.getName() + ": " + event.getMessage());
               vm.updateRuntime();
               String clusterName = CommonUtil.getClusterName(vm.getName());
               lockMgr.refreshNodeByVmName(clusterName, moId, vm.getName(),
                     event.getMessage(), true);
            }
            break;
         }
         case VhmInfo: {
            EventEx event = (EventEx) e;
            VcVirtualMachine vm =
                  VcCache.getIgnoreMissing(event.getVm().getVm());
            if (vm == null) {
               break;
            }
            if (clusterEntityMgr.getNodeByVmName(vm.getName()) != null) {
               logger.info("received vhm event " + event.getEventTypeId()
                     + " for vm " + vm.getName() + ": " + event.getMessage());
               vm.updateRuntime();
               String clusterName = CommonUtil.getClusterName(vm.getName());
               lockMgr.refreshNodeByVmName(clusterName, moId, vm.getName(), "",
                     true);
            }
            break;
         }
         default: {
            if (external) {
               VcVirtualMachine vm = VcCache.getIgnoreMissing(moId);
               if (vm == null) {
                  break;
               }
               String clusterName = CommonUtil.getClusterName(vm.getName());
               lockMgr.refreshNodeByVmName(clusterName, moId, vm.getName(),
                     true);
            }
            break;
         }
         }
      } catch (ManagedObjectNotFound exp) {
         VcUtil.processNotFoundException(exp, moId, logger);
      }
   }

   private void refreshNodeWithAction(String moId, boolean setAction,
         String action, String eventName) throws Exception {
      VcVirtualMachine vm = VcCache.getIgnoreMissing(moId);
      if (vm == null) {
         return;
      }
      if (clusterEntityMgr.getNodeByVmName(vm.getName()) != null) {
         logger.info("received vm " + eventName + " event for vm: "
               + vm.getName());
         vm.updateRuntime();
         String clusterName = CommonUtil.getClusterName(vm.getName());
         if (setAction) {
            lockMgr.refreshNodeByVmName(clusterName, moId, vm.getName(),
                  action, true);
         } else {
            lockMgr.refreshNodeByVmName(clusterName, moId, vm.getName(), true);
         }
      }
      return;
   }

   private String switchMobId(String moId, VcVirtualMachine vm)
         throws Exception {
      // check if ft enabled
      if (vm.getConfig() != null && vm.getConfig().getFtInfo() != null) {
         // ft enabled
         vm.update();
         if (vm.getConfig().getFtInfo().getRole() != 1) {
            // not primary VM
            FaultToleranceSecondaryConfigInfo ftInfo =
                  (FaultToleranceSecondaryConfigInfo) vm.getConfig()
                        .getFtInfo();
            moId = MoUtil.morefToString(ftInfo.getPrimaryVM());
            logger.info("Received secondary VM event, switch to primary VM "
                  + moId);
         }
      }
      return moId;
   }

   public void shutdown() {
      this.interrupt();
   }
}

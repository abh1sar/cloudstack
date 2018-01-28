/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cloudstack.storage.motion;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.storage.CopyVolumeAnswer;
import com.cloud.agent.api.storage.CopyVolumeCommand;
import com.cloud.agent.api.MigrateAnswer;
import com.cloud.agent.api.MigrateCommand;
import com.cloud.agent.api.ModifyTargetsAnswer;
import com.cloud.agent.api.ModifyTargetsCommand;
import com.cloud.agent.api.PrepareForMigrationCommand;
import com.cloud.agent.api.storage.MigrateVolumeAnswer;
import com.cloud.agent.api.storage.MigrateVolumeCommand;
import com.cloud.agent.api.to.DataStoreTO;
import com.cloud.agent.api.to.DataTO;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.agent.api.to.NfsTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.configuration.Config;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.host.dao.HostDetailsDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.Snapshot;
import com.cloud.storage.SnapshotVO;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.StorageManager;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.VolumeDetailVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.GuestOSCategoryDao;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.SnapshotDetailsDao;
import com.cloud.storage.dao.SnapshotDetailsVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.VMInstanceDao;

import com.google.common.base.Preconditions;

import org.apache.cloudstack.engine.subsystem.api.storage.ChapInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.ClusterScope;
import org.apache.cloudstack.engine.subsystem.api.storage.CopyCommandResult;
import org.apache.cloudstack.engine.subsystem.api.storage.DataMotionStrategy;
import org.apache.cloudstack.engine.subsystem.api.storage.DataObject;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreCapabilities;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.EndPoint;
import org.apache.cloudstack.engine.subsystem.api.storage.EndPointSelector;
import org.apache.cloudstack.engine.subsystem.api.storage.HostScope;
import org.apache.cloudstack.engine.subsystem.api.storage.ObjectInDataStoreStateMachine.Event;
import org.apache.cloudstack.engine.subsystem.api.storage.Scope;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.StorageCacheManager;
import org.apache.cloudstack.engine.subsystem.api.storage.StrategyPriority;
import org.apache.cloudstack.engine.subsystem.api.storage.TemplateInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService.VolumeApiResult;
import org.apache.cloudstack.engine.subsystem.api.storage.ZoneScope;
import org.apache.cloudstack.framework.async.AsyncCallFuture;
import org.apache.cloudstack.framework.async.AsyncCompletionCallback;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.storage.command.CopyCmdAnswer;
import org.apache.cloudstack.storage.command.CopyCommand;
import org.apache.cloudstack.storage.command.ResignatureAnswer;
import org.apache.cloudstack.storage.command.ResignatureCommand;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.apache.log4j.Logger;

import org.springframework.stereotype.Component;

import javax.inject.Inject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class StorageSystemDataMotionStrategy implements DataMotionStrategy {
    private static final Logger LOGGER = Logger.getLogger(StorageSystemDataMotionStrategy.class);
    private static final Random RANDOM = new Random(System.nanoTime());
    private static final int LOCK_TIME_IN_SECONDS = 300;
    private static final String OPERATION_NOT_SUPPORTED = "This operation is not supported.";

    @Inject private AgentManager _agentMgr;
    @Inject private ConfigurationDao _configDao;
    @Inject private DataStoreManager dataStoreMgr;
    @Inject private DiskOfferingDao _diskOfferingDao;
    @Inject private GuestOSCategoryDao _guestOsCategoryDao;
    @Inject private GuestOSDao _guestOsDao;
    @Inject private ClusterDao clusterDao;
    @Inject private HostDao _hostDao;
    @Inject private HostDetailsDao hostDetailsDao;
    @Inject private PrimaryDataStoreDao _storagePoolDao;
    @Inject private SnapshotDao _snapshotDao;
    @Inject private SnapshotDataStoreDao _snapshotDataStoreDao;
    @Inject private SnapshotDetailsDao _snapshotDetailsDao;
    @Inject private VMInstanceDao _vmDao;
    @Inject private VMTemplateDao _vmTemplateDao;
    @Inject private VolumeDao _volumeDao;
    @Inject private VolumeDataFactory _volumeDataFactory;
    @Inject private VolumeDetailsDao volumeDetailsDao;
    @Inject private VolumeService _volumeService;
    @Inject private StorageCacheManager cacheMgr;
    @Inject private EndPointSelector selector;

    @Override
    public StrategyPriority canHandle(DataObject srcData, DataObject destData) {
        if (srcData instanceof SnapshotInfo) {
            if (canHandle(srcData) || canHandle(destData)) {
                return StrategyPriority.HIGHEST;
            }
        }

        if (srcData instanceof TemplateInfo && destData instanceof VolumeInfo &&
                (srcData.getDataStore().getId() == destData.getDataStore().getId()) &&
                (canHandle(srcData) || canHandle(destData))) {
            // Both source and dest are on the same storage, so just clone them.
            return StrategyPriority.HIGHEST;
        }

        if (srcData instanceof VolumeInfo && destData instanceof VolumeInfo) {
            VolumeInfo srcVolumeInfo = (VolumeInfo)srcData;

            if (isVolumeOnManagedStorage(srcVolumeInfo)) {
                return StrategyPriority.HIGHEST;
            }

            VolumeInfo destVolumeInfo = (VolumeInfo)destData;

            if (isVolumeOnManagedStorage(destVolumeInfo)) {
                return StrategyPriority.HIGHEST;
            }
        }

        if (srcData instanceof VolumeInfo && destData instanceof TemplateInfo) {
            VolumeInfo srcVolumeInfo = (VolumeInfo)srcData;

            if (isVolumeOnManagedStorage(srcVolumeInfo)) {
                return StrategyPriority.HIGHEST;
            }
        }

        return StrategyPriority.CANT_HANDLE;
    }

    private boolean isVolumeOnManagedStorage(VolumeInfo volumeInfo) {
        long storagePooldId = volumeInfo.getDataStore().getId();
        StoragePoolVO storagePoolVO = _storagePoolDao.findById(storagePooldId);

        return storagePoolVO.isManaged();
    }

    // canHandle returns true if the storage driver for the DataObject that's passed in can support certain features (what features we
    // care about during a particular invocation of this method depend on what type of DataObject was passed in (ex. VolumeInfo versus SnapshotInfo)).
    private boolean canHandle(DataObject dataObject) {
        Preconditions.checkArgument(dataObject != null, "Passing 'null' to dataObject of canHandle(DataObject) is not supported.");

        DataStore dataStore = dataObject.getDataStore();

        if (dataStore.getRole() == DataStoreRole.Primary) {
            Map<String, String> mapCapabilities = dataStore.getDriver().getCapabilities();

            if (mapCapabilities == null) {
                return false;
            }

            if (dataObject instanceof VolumeInfo || dataObject instanceof SnapshotInfo) {
                String value = mapCapabilities.get(DataStoreCapabilities.STORAGE_SYSTEM_SNAPSHOT.toString());
                Boolean supportsStorageSystemSnapshots = Boolean.valueOf(value);

                if (supportsStorageSystemSnapshots) {
                    LOGGER.info("Using 'StorageSystemDataMotionStrategy' (dataObject is a volume or snapshot and the storage system supports snapshots)");

                    return true;
                }
            } else if (dataObject instanceof TemplateInfo) {
                // If the storage system can clone volumes, we can cache templates on it.
                String value = mapCapabilities.get(DataStoreCapabilities.CAN_CREATE_VOLUME_FROM_VOLUME.toString());
                Boolean canCloneVolume = Boolean.valueOf(value);

                if (canCloneVolume) {
                    LOGGER.info("Using 'StorageSystemDataMotionStrategy' (dataObject is a template and the storage system can create a volume from a volume)");

                    return true;
                }
            }
        }

        return false;
    }

    @Override
    public StrategyPriority canHandle(Map<VolumeInfo, DataStore> volumeMap, Host srcHost, Host destHost) {
        if (HypervisorType.KVM.equals(srcHost.getHypervisorType())) {
            Set<VolumeInfo> volumeInfoSet = volumeMap.keySet();

            for (VolumeInfo volumeInfo : volumeInfoSet) {
                StoragePoolVO storagePoolVO = _storagePoolDao.findById(volumeInfo.getPoolId());

                if (storagePoolVO.isManaged()) {
                    return StrategyPriority.HIGHEST;
                }
            }

            Collection<DataStore> dataStores = volumeMap.values();

            for (DataStore dataStore : dataStores) {
                StoragePoolVO storagePoolVO = _storagePoolDao.findById(dataStore.getId());

                if (storagePoolVO.isManaged()) {
                    return StrategyPriority.HIGHEST;
                }
            }
        }

        return StrategyPriority.CANT_HANDLE;
    }

    @Override
    public void copyAsync(DataObject srcData, DataObject destData, Host destHost, AsyncCompletionCallback<CopyCommandResult> callback) {
        if (srcData instanceof SnapshotInfo) {
            SnapshotInfo srcSnapshotInfo = (SnapshotInfo)srcData;

            handleCopyAsyncForSnapshot(srcSnapshotInfo, destData, callback);
        } else if (srcData instanceof TemplateInfo && destData instanceof VolumeInfo) {
            TemplateInfo srcTemplateInfo = (TemplateInfo)srcData;
            VolumeInfo destVolumeInfo = (VolumeInfo)destData;

            handleCopyAsyncForTemplateAndVolume(srcTemplateInfo, destVolumeInfo, callback);
        } else if (srcData instanceof VolumeInfo && destData instanceof VolumeInfo) {
            VolumeInfo srcVolumeInfo = (VolumeInfo)srcData;
            VolumeInfo destVolumeInfo = (VolumeInfo)destData;

            handleCopyAsyncForVolumes(srcVolumeInfo, destVolumeInfo, callback);
        } else if (srcData instanceof VolumeInfo && destData instanceof TemplateInfo &&
                (destData.getDataStore().getRole() == DataStoreRole.Image || destData.getDataStore().getRole() == DataStoreRole.ImageCache)) {
            VolumeInfo srcVolumeInfo = (VolumeInfo)srcData;
            TemplateInfo destTemplateInfo = (TemplateInfo)destData;

            handleCreateTemplateFromVolume(srcVolumeInfo, destTemplateInfo, callback);
        }
        else {
            handleError(OPERATION_NOT_SUPPORTED, callback);
        }
    }

    private void handleCopyAsyncForSnapshot(SnapshotInfo srcSnapshotInfo, DataObject destData, AsyncCompletionCallback<CopyCommandResult> callback) {
        verifyFormat(srcSnapshotInfo);

        boolean canHandleSrc = canHandle(srcSnapshotInfo);

        if (canHandleSrc && (destData instanceof TemplateInfo || destData instanceof SnapshotInfo) &&
                (destData.getDataStore().getRole() == DataStoreRole.Image || destData.getDataStore().getRole() == DataStoreRole.ImageCache)) {
            handleCopyDataToSecondaryStorage(srcSnapshotInfo, destData, callback);
        } else if (destData instanceof VolumeInfo) {
            handleCopyAsyncForSnapshotToVolume(srcSnapshotInfo, (VolumeInfo)destData, callback);
        } else {
            handleError(OPERATION_NOT_SUPPORTED, callback);
        }
    }

    private void handleCopyAsyncForSnapshotToVolume(SnapshotInfo srcSnapshotInfo, VolumeInfo destVolumeInfo,
                                                    AsyncCompletionCallback<CopyCommandResult> callback) {
        boolean canHandleDest = canHandle(destVolumeInfo);

        if (!canHandleDest) {
            handleError(OPERATION_NOT_SUPPORTED, callback);
        }

        boolean canHandleSrc = canHandle(srcSnapshotInfo);

        if (!canHandleSrc) {
            handleCreateVolumeFromSnapshotOnSecondaryStorage(srcSnapshotInfo, destVolumeInfo, callback);
        }

        if (srcSnapshotInfo.getDataStore().getId() == destVolumeInfo.getDataStore().getId()) {
            handleCreateVolumeFromSnapshotBothOnStorageSystem(srcSnapshotInfo, destVolumeInfo, callback);
        } else {
            String errMsg = "To perform this operation, the source and destination primary storages must be the same.";

            handleError(errMsg, callback);
        }
    }

    private void handleCopyAsyncForTemplateAndVolume(TemplateInfo srcTemplateInfo, VolumeInfo destVolumeInfo, AsyncCompletionCallback<CopyCommandResult> callback) {
        boolean canHandleSrc = canHandle(srcTemplateInfo);

        if (!canHandleSrc) {
            handleError(OPERATION_NOT_SUPPORTED, callback);
        }

        handleCreateVolumeFromTemplateBothOnStorageSystem(srcTemplateInfo, destVolumeInfo, callback);
    }

    private void handleCopyAsyncForVolumes(VolumeInfo srcVolumeInfo, VolumeInfo destVolumeInfo, AsyncCompletionCallback<CopyCommandResult> callback) {
        if (srcVolumeInfo.getState() == Volume.State.Migrating) {
            if (isVolumeOnManagedStorage(srcVolumeInfo)) {
                if (destVolumeInfo.getDataStore().getRole() == DataStoreRole.Image || destVolumeInfo.getDataStore().getRole() == DataStoreRole.ImageCache) {
                    handleVolumeCopyFromManagedStorageToSecondaryStorage(srcVolumeInfo, destVolumeInfo, callback);
                } else if (!isVolumeOnManagedStorage(destVolumeInfo)) {
                    handleVolumeMigrationFromManagedStorageToNonManagedStorage(srcVolumeInfo, destVolumeInfo, callback);
                } else {
                    String errMsg = "The source volume to migrate and the destination volume are both on managed storage. " +
                            "Migration in this case is not yet supported.";

                    handleError(errMsg, callback);
                }
            } else if (!isVolumeOnManagedStorage(destVolumeInfo)) {
                String errMsg = "The 'StorageSystemDataMotionStrategy' does not support this migration use case.";

                handleError(errMsg, callback);
            } else {
                handleVolumeMigrationFromNonManagedStorageToManagedStorage(srcVolumeInfo, destVolumeInfo, callback);
            }
        } else if (srcVolumeInfo.getState() == Volume.State.Uploaded &&
                (srcVolumeInfo.getDataStore().getRole() == DataStoreRole.Image || srcVolumeInfo.getDataStore().getRole() == DataStoreRole.ImageCache) &&
                destVolumeInfo.getDataStore().getRole() == DataStoreRole.Primary) {
            ImageFormat imageFormat = destVolumeInfo.getFormat();

            if (!ImageFormat.QCOW2.equals(imageFormat)) {
                String errMsg = "The 'StorageSystemDataMotionStrategy' does not support this upload use case (non KVM).";

                handleError(errMsg, callback);
            }

            handleCreateVolumeFromVolumeOnSecondaryStorage(srcVolumeInfo, destVolumeInfo, destVolumeInfo.getDataCenterId(), HypervisorType.KVM, callback);
        } else {
            handleError(OPERATION_NOT_SUPPORTED, callback);
        }
    }

    private void handleError(String errMsg, AsyncCompletionCallback<CopyCommandResult> callback) {
        LOGGER.warn(errMsg);

        invokeCallback(errMsg, callback);

        throw new UnsupportedOperationException(errMsg);
    }

    private void invokeCallback(String errMsg, AsyncCompletionCallback<CopyCommandResult> callback) {
        CopyCmdAnswer copyCmdAnswer = new CopyCmdAnswer(errMsg);

        CopyCommandResult result = new CopyCommandResult(null, copyCmdAnswer);

        result.setResult(errMsg);

        callback.complete(result);
    }

    private void handleVolumeCopyFromManagedStorageToSecondaryStorage(VolumeInfo srcVolumeInfo, VolumeInfo destVolumeInfo,
                                                                      AsyncCompletionCallback<CopyCommandResult> callback) {
        String errMsg = null;
        String volumePath = null;

        try {
            if (!ImageFormat.QCOW2.equals(srcVolumeInfo.getFormat())) {
                throw new CloudRuntimeException("Currently, only the KVM hypervisor type is supported for the migration of a volume " +
                        "from managed storage to non-managed storage.");
            }

            HypervisorType hypervisorType = HypervisorType.KVM;
            VirtualMachine vm = srcVolumeInfo.getAttachedVM();

            if (vm != null && vm.getState() != VirtualMachine.State.Stopped) {
                throw new CloudRuntimeException("Currently, if a volume to copy from managed storage to secondary storage is attached to " +
                        "a VM, the VM must be in the Stopped state.");
            }

            long srcStoragePoolId = srcVolumeInfo.getPoolId();
            StoragePoolVO srcStoragePoolVO = _storagePoolDao.findById(srcStoragePoolId);

            HostVO hostVO;

            if (srcStoragePoolVO.getClusterId() != null) {
                hostVO = getHostInCluster(srcStoragePoolVO.getClusterId());
            }
            else {
                hostVO = getHost(srcVolumeInfo.getDataCenterId(), hypervisorType, false);
            }

            volumePath = copyVolumeToSecondaryStorage(srcVolumeInfo, destVolumeInfo, hostVO,
                    "Unable to copy the volume from managed storage to secondary storage");
        }
        catch (Exception ex) {
            errMsg = "Migration operation failed in 'StorageSystemDataMotionStrategy.handleVolumeCopyFromManagedStorageToSecondaryStorage': " +
                    ex.getMessage();

            throw new CloudRuntimeException(errMsg);
        }
        finally {
            CopyCmdAnswer copyCmdAnswer;

            if (errMsg != null) {
                copyCmdAnswer = new CopyCmdAnswer(errMsg);
            }
            else if (volumePath == null) {
                copyCmdAnswer = new CopyCmdAnswer("Unable to acquire a volume path");
            }
            else {
                VolumeObjectTO volumeObjectTO = (VolumeObjectTO)destVolumeInfo.getTO();

                volumeObjectTO.setPath(volumePath);

                copyCmdAnswer = new CopyCmdAnswer(volumeObjectTO);
            }

            CopyCommandResult result = new CopyCommandResult(null, copyCmdAnswer);

            result.setResult(errMsg);

            callback.complete(result);
        }
    }

    private void handleVolumeMigrationFromManagedStorageToNonManagedStorage(VolumeInfo srcVolumeInfo, VolumeInfo destVolumeInfo,
                                                                            AsyncCompletionCallback<CopyCommandResult> callback) {
        String errMsg = null;

        try {
            if (!ImageFormat.QCOW2.equals(srcVolumeInfo.getFormat())) {
                throw new CloudRuntimeException("Currently, only the KVM hypervisor type is supported for the migration of a volume " +
                        "from managed storage to non-managed storage.");
            }

            HypervisorType hypervisorType = HypervisorType.KVM;
            VirtualMachine vm = srcVolumeInfo.getAttachedVM();

            if (vm != null && vm.getState() != VirtualMachine.State.Stopped) {
                throw new CloudRuntimeException("Currently, if a volume to migrate from managed storage to non-managed storage is attached to " +
                        "a VM, the VM must be in the Stopped state.");
            }

            long destStoragePoolId = destVolumeInfo.getPoolId();
            StoragePoolVO destStoragePoolVO = _storagePoolDao.findById(destStoragePoolId);

            HostVO hostVO;

            if (destStoragePoolVO.getClusterId() != null) {
                hostVO = getHostInCluster(destStoragePoolVO.getClusterId());
            }
            else {
                hostVO = getHost(destVolumeInfo.getDataCenterId(), hypervisorType, false);
            }

            setCertainVolumeValuesNull(destVolumeInfo.getId());

            // migrate the volume via the hypervisor
            String path = migrateVolume(srcVolumeInfo, destVolumeInfo, hostVO, "Unable to migrate the volume from managed storage to non-managed storage");

            updateVolumePath(destVolumeInfo.getId(), path);
        }
        catch (Exception ex) {
            errMsg = "Migration operation failed in 'StorageSystemDataMotionStrategy.handleVolumeMigrationFromManagedStorageToNonManagedStorage': " +
                    ex.getMessage();

            throw new CloudRuntimeException(errMsg);
        }
        finally {
            CopyCmdAnswer copyCmdAnswer;

            if (errMsg != null) {
                copyCmdAnswer = new CopyCmdAnswer(errMsg);
            }
            else {
                destVolumeInfo = _volumeDataFactory.getVolume(destVolumeInfo.getId(), destVolumeInfo.getDataStore());

                DataTO dataTO = destVolumeInfo.getTO();

                copyCmdAnswer = new CopyCmdAnswer(dataTO);
            }

            CopyCommandResult result = new CopyCommandResult(null, copyCmdAnswer);

            result.setResult(errMsg);

            callback.complete(result);
        }
    }

    private void verifyFormat(ImageFormat imageFormat) {
        if (imageFormat != ImageFormat.VHD && imageFormat != ImageFormat.OVA && imageFormat != ImageFormat.QCOW2) {
            throw new CloudRuntimeException("Only the following image types are currently supported: " +
                    ImageFormat.VHD.toString() + ", " + ImageFormat.OVA.toString() + ", and " + ImageFormat.QCOW2);
        }
    }

    private void verifyFormat(SnapshotInfo snapshotInfo) {
        long volumeId = snapshotInfo.getVolumeId();

        VolumeVO volumeVO = _volumeDao.findByIdIncludingRemoved(volumeId);

        verifyFormat(volumeVO.getFormat());
    }

    private boolean usingBackendSnapshotFor(SnapshotInfo snapshotInfo) {
        String property = getSnapshotProperty(snapshotInfo.getId(), "takeSnapshot");

        return Boolean.parseBoolean(property);
    }

    private boolean needCacheStorage(DataObject srcData, DataObject destData) {
        DataTO srcTO = srcData.getTO();
        DataStoreTO srcStoreTO = srcTO.getDataStore();
        DataTO destTO = destData.getTO();
        DataStoreTO destStoreTO = destTO.getDataStore();

        // both snapshot and volume are on primary datastore - no need for a cache storage as hypervisor will copy directly
        if (srcStoreTO instanceof PrimaryDataStoreTO && destStoreTO instanceof PrimaryDataStoreTO) {
            return false;
        }

        if (srcStoreTO instanceof NfsTO || srcStoreTO.getRole() == DataStoreRole.ImageCache) {
            return false;
        }

        if (destStoreTO instanceof NfsTO || destStoreTO.getRole() == DataStoreRole.ImageCache) {
            return false;
        }

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("needCacheStorage true; dest at " + destTO.getPath() + ", dest role " + destStoreTO.getRole().toString() + "; src at " +
                    srcTO.getPath() + ", src role " + srcStoreTO.getRole().toString());
        }

        return true;
    }

    private Scope pickCacheScopeForCopy(DataObject srcData, DataObject destData) {
        Scope srcScope = srcData.getDataStore().getScope();
        Scope destScope = destData.getDataStore().getScope();

        Scope selectedScope = null;

        if (srcScope.getScopeId() != null) {
            selectedScope = getZoneScope(srcScope);
        } else if (destScope.getScopeId() != null) {
            selectedScope = getZoneScope(destScope);
        } else {
            LOGGER.warn("Cannot find a zone-wide scope for movement that needs a cache storage");
        }

        return selectedScope;
    }

    private Scope getZoneScope(Scope scope) {
        ZoneScope zoneScope;

        if (scope instanceof ClusterScope) {
            ClusterScope clusterScope = (ClusterScope)scope;

            zoneScope = new ZoneScope(clusterScope.getZoneId());
        } else if (scope instanceof HostScope) {
            HostScope hostScope = (HostScope)scope;

            zoneScope = new ZoneScope(hostScope.getZoneId());
        } else {
            zoneScope = (ZoneScope)scope;
        }

        return zoneScope;
    }

    private void handleVolumeMigrationFromNonManagedStorageToManagedStorage(VolumeInfo srcVolumeInfo, VolumeInfo destVolumeInfo,
                                                                            AsyncCompletionCallback<CopyCommandResult> callback) {
        String errMsg = null;

        try {
            HypervisorType hypervisorType = srcVolumeInfo.getHypervisorType();

            if (!HypervisorType.KVM.equals(hypervisorType)) {
                throw new CloudRuntimeException("Currently, only the KVM hypervisor type is supported for the migration of a volume " +
                        "from non-managed storage to managed storage.");
            }

            VirtualMachine vm = srcVolumeInfo.getAttachedVM();

            if (vm != null && vm.getState() != VirtualMachine.State.Stopped) {
                throw new CloudRuntimeException("Currently, if a volume to migrate from non-managed storage to managed storage is attached to " +
                        "a VM, the VM must be in the Stopped state.");
            }

            destVolumeInfo.getDataStore().getDriver().createAsync(destVolumeInfo.getDataStore(), destVolumeInfo, null);

            VolumeVO volumeVO = _volumeDao.findById(destVolumeInfo.getId());

            volumeVO.setPath(volumeVO.get_iScsiName());

            _volumeDao.update(volumeVO.getId(), volumeVO);

            destVolumeInfo = _volumeDataFactory.getVolume(destVolumeInfo.getId(), destVolumeInfo.getDataStore());

            long srcStoragePoolId = srcVolumeInfo.getPoolId();
            StoragePoolVO srcStoragePoolVO = _storagePoolDao.findById(srcStoragePoolId);

            HostVO hostVO;

            if (srcStoragePoolVO.getClusterId() != null) {
                hostVO = getHostInCluster(srcStoragePoolVO.getClusterId());
            }
            else {
                hostVO = getHost(destVolumeInfo.getDataCenterId(), hypervisorType, false);
            }

            // migrate the volume via the hypervisor
            migrateVolume(srcVolumeInfo, destVolumeInfo, hostVO, "Unable to migrate the volume from non-managed storage to managed storage");

            volumeVO = _volumeDao.findById(destVolumeInfo.getId());

            volumeVO.setFormat(ImageFormat.QCOW2);

            _volumeDao.update(volumeVO.getId(), volumeVO);
        }
        catch (Exception ex) {
            errMsg = "Migration operation failed in 'StorageSystemDataMotionStrategy.handleVolumeMigrationFromNonManagedStorageToManagedStorage': " +
                    ex.getMessage();

            throw new CloudRuntimeException(errMsg);
        }
        finally {
            CopyCmdAnswer copyCmdAnswer;

            if (errMsg != null) {
                copyCmdAnswer = new CopyCmdAnswer(errMsg);
            }
            else {
                destVolumeInfo = _volumeDataFactory.getVolume(destVolumeInfo.getId(), destVolumeInfo.getDataStore());

                DataTO dataTO = destVolumeInfo.getTO();

                copyCmdAnswer = new CopyCmdAnswer(dataTO);
            }

            CopyCommandResult result = new CopyCommandResult(null, copyCmdAnswer);

            result.setResult(errMsg);

            callback.complete(result);
        }
    }

    /**
     * This function is responsible for copying a snapshot from managed storage to secondary storage. This is used in the following two cases:
     * 1) When creating a template from a snapshot
     * 2) When createSnapshot is called with location=SECONDARY
     *
     * @param snapshotInfo source snapshot
     * @param destData destination (can be template or snapshot)
     * @param callback callback for async
     */
    private void handleCopyDataToSecondaryStorage(SnapshotInfo snapshotInfo, DataObject destData, AsyncCompletionCallback<CopyCommandResult> callback) {
        String errMsg = null;
        CopyCmdAnswer copyCmdAnswer = null;
        boolean usingBackendSnapshot = false;

        try {
            snapshotInfo.processEvent(Event.CopyingRequested);

            HostVO hostVO = getHost(snapshotInfo);

            boolean needCache = needCacheStorage(snapshotInfo, destData);

            DataObject destOnStore = destData;

            if (needCache) {
                // creates an object in the DB for data to be cached
                Scope selectedScope = pickCacheScopeForCopy(snapshotInfo, destData);

                destOnStore = cacheMgr.getCacheObject(snapshotInfo, selectedScope);

                destOnStore.processEvent(Event.CreateOnlyRequested);
            }

            usingBackendSnapshot = usingBackendSnapshotFor(snapshotInfo);

            if (usingBackendSnapshot) {
                final boolean computeClusterSupportsVolumeClone;

                // only XenServer, VMware, and KVM are currently supported
                if (HypervisorType.XenServer.equals(snapshotInfo.getHypervisorType())) {
                    computeClusterSupportsVolumeClone = clusterDao.getSupportsResigning(hostVO.getClusterId());
                }
                else if (HypervisorType.VMware.equals(snapshotInfo.getHypervisorType()) || HypervisorType.KVM.equals(snapshotInfo.getHypervisorType())) {
                    computeClusterSupportsVolumeClone = true;
                }
                else {
                    throw new CloudRuntimeException("Unsupported hypervisor type");
                }

                if (!computeClusterSupportsVolumeClone) {
                    String noSupportForResignErrMsg = "Unable to locate an applicable host with which to perform a resignature operation : Cluster ID = " +
                            hostVO.getClusterId();

                    LOGGER.warn(noSupportForResignErrMsg);

                    throw new CloudRuntimeException(noSupportForResignErrMsg);
                }
            }

            String vmdk = null;
            String uuid = null;
            boolean keepGrantedAccess = false;

            DataStore srcDataStore = snapshotInfo.getDataStore();

            if (usingBackendSnapshot) {
                createVolumeFromSnapshot(snapshotInfo);

                if (HypervisorType.XenServer.equals(snapshotInfo.getHypervisorType()) || HypervisorType.VMware.equals(snapshotInfo.getHypervisorType())) {
                    keepGrantedAccess = HypervisorType.XenServer.equals(snapshotInfo.getHypervisorType());

                    Map<String, String> extraDetails = null;

                    if (HypervisorType.VMware.equals(snapshotInfo.getHypervisorType())) {
                        extraDetails = new HashMap<>();

                        String extraDetailsVmdk = getSnapshotProperty(snapshotInfo.getId(), DiskTO.VMDK);

                        extraDetails.put(DiskTO.VMDK, extraDetailsVmdk);
                        extraDetails.put(DiskTO.TEMPLATE_RESIGN, Boolean.TRUE.toString());
                    }

                    copyCmdAnswer = performResignature(snapshotInfo, hostVO, extraDetails, keepGrantedAccess);

                    // If using VMware, have the host rescan its software HBA if dynamic discovery is in use.
                    if (HypervisorType.VMware.equals(snapshotInfo.getHypervisorType())) {
                        String iqn = getSnapshotProperty(snapshotInfo.getId(), DiskTO.IQN);

                        disconnectHostFromVolume(hostVO, srcDataStore.getId(), iqn);
                    }

                    if (copyCmdAnswer == null || !copyCmdAnswer.getResult()) {
                        if (copyCmdAnswer != null && !StringUtils.isEmpty(copyCmdAnswer.getDetails())) {
                            throw new CloudRuntimeException(copyCmdAnswer.getDetails());
                        } else {
                            throw new CloudRuntimeException("Unable to create volume from snapshot");
                        }
                    }

                    vmdk = copyCmdAnswer.getNewData().getPath();
                    uuid = UUID.randomUUID().toString();
                }
            }

            String value = _configDao.getValue(Config.PrimaryStorageDownloadWait.toString());
            int primaryStorageDownloadWait = NumbersUtil.parseInt(value, Integer.parseInt(Config.PrimaryStorageDownloadWait.getDefaultValue()));
            CopyCommand copyCommand = new CopyCommand(snapshotInfo.getTO(), destOnStore.getTO(), primaryStorageDownloadWait,
                    VirtualMachineManager.ExecuteInSequence.value());

            try {
                if (!keepGrantedAccess) {
                    _volumeService.grantAccess(snapshotInfo, hostVO, srcDataStore);
                }

                Map<String, String> srcDetails = getSnapshotDetails(snapshotInfo);

                if (isForVMware(destData)) {
                    srcDetails.put(DiskTO.VMDK, vmdk);
                    srcDetails.put(DiskTO.UUID, uuid);

                    if (destData instanceof TemplateInfo) {
                        VMTemplateVO templateDataStoreVO = _vmTemplateDao.findById(destData.getId());

                        templateDataStoreVO.setUniqueName(uuid);

                        _vmTemplateDao.update(destData.getId(), templateDataStoreVO);
                    }
                }

                copyCommand.setOptions(srcDetails);

                copyCmdAnswer = (CopyCmdAnswer)_agentMgr.send(hostVO.getId(), copyCommand);

                if (!copyCmdAnswer.getResult()) {
                    // We were not able to copy. Handle it.
                    errMsg = copyCmdAnswer.getDetails();

                    throw new CloudRuntimeException(errMsg);
                }

                if (needCache) {
                    // If cached storage was needed (in case of object store as secondary
                    // storage), at this point, the data has been copied from the primary
                    // to the NFS cache by the hypervisor. We now invoke another copy
                    // command to copy this data from cache to secondary storage. We
                    // then clean up the cache.

                    destOnStore.processEvent(Event.OperationSuccessed, copyCmdAnswer);

                    CopyCommand cmd = new CopyCommand(destOnStore.getTO(), destData.getTO(), primaryStorageDownloadWait,
                            VirtualMachineManager.ExecuteInSequence.value());
                    EndPoint ep = selector.select(destOnStore, destData);

                    if (ep == null) {
                        errMsg = "No remote endpoint to send command, check if host or SSVM is down";

                        LOGGER.error(errMsg);

                        copyCmdAnswer = new CopyCmdAnswer(errMsg);
                    } else {
                        copyCmdAnswer = (CopyCmdAnswer)ep.sendMessage(cmd);
                    }

                    // clean up snapshot copied to staging
                    cacheMgr.deleteCacheObject(destOnStore);
                }
            } catch (CloudRuntimeException | AgentUnavailableException | OperationTimedoutException ex) {
                String msg = "Failed to create template from snapshot (Snapshot ID = " + snapshotInfo.getId() + ") : ";

                LOGGER.warn(msg, ex);

                throw new CloudRuntimeException(msg + ex.getMessage(), ex);
            } finally {
                _volumeService.revokeAccess(snapshotInfo, hostVO, srcDataStore);

                // If using VMware, have the host rescan its software HBA if dynamic discovery is in use.
                if (HypervisorType.VMware.equals(snapshotInfo.getHypervisorType())) {
                    String iqn = getSnapshotProperty(snapshotInfo.getId(), DiskTO.IQN);

                    disconnectHostFromVolume(hostVO, srcDataStore.getId(), iqn);
                }

                if (copyCmdAnswer == null || !copyCmdAnswer.getResult()) {
                    if (copyCmdAnswer != null && !StringUtils.isEmpty(copyCmdAnswer.getDetails())) {
                        errMsg = copyCmdAnswer.getDetails();

                        if (needCache) {
                            cacheMgr.deleteCacheObject(destOnStore);
                        }
                    }
                    else {
                        errMsg = "Unable to create template from snapshot";
                    }
                }

                try {
                    if (StringUtils.isEmpty(errMsg)) {
                        snapshotInfo.processEvent(Event.OperationSuccessed);
                    }
                    else {
                        snapshotInfo.processEvent(Event.OperationFailed);
                    }
                }
                catch (Exception ex) {
                    LOGGER.warn("Error processing snapshot event: " + ex.getMessage(), ex);
                }
            }
        }
        catch (Exception ex) {
            errMsg = ex.getMessage();

            throw new CloudRuntimeException(errMsg);
        }
        finally {
            if (usingBackendSnapshot) {
                deleteVolumeFromSnapshot(snapshotInfo);
            }

            if (copyCmdAnswer == null) {
                copyCmdAnswer = new CopyCmdAnswer(errMsg);
            }

            CopyCommandResult result = new CopyCommandResult(null, copyCmdAnswer);

            result.setResult(errMsg);

            callback.complete(result);
        }
    }

    /**
     * Creates a volume on the storage from a snapshot that resides on the secondary storage (archived snapshot).
     * @param snapshotInfo snapshot on secondary
     * @param volumeInfo volume to be created on the storage
     * @param callback for async
     */
    private void handleCreateVolumeFromSnapshotOnSecondaryStorage(SnapshotInfo snapshotInfo, VolumeInfo volumeInfo,
                                                                  AsyncCompletionCallback<CopyCommandResult> callback) {
        String errMsg = null;
        CopyCmdAnswer copyCmdAnswer = null;

        try {
            // at this point, the snapshotInfo and volumeInfo should have the same disk offering ID (so either one should be OK to get a DiskOfferingVO instance)
            DiskOfferingVO diskOffering = _diskOfferingDao.findByIdIncludingRemoved(volumeInfo.getDiskOfferingId());
            SnapshotVO snapshot = _snapshotDao.findById(snapshotInfo.getId());

            // update the volume's hv_ss_reserve (hypervisor snapshot reserve) from a disk offering (used for managed storage)
            _volumeService.updateHypervisorSnapshotReserveForVolume(diskOffering, volumeInfo.getId(), snapshot.getHypervisorType());

            HostVO hostVO;

            // create a volume on the storage
            AsyncCallFuture<VolumeApiResult> future = _volumeService.createVolumeAsync(volumeInfo, volumeInfo.getDataStore());
            VolumeApiResult result = future.get();

            if (result.isFailed()) {
                LOGGER.error("Failed to create a volume: " + result.getResult());

                throw new CloudRuntimeException(result.getResult());
            }

            volumeInfo = _volumeDataFactory.getVolume(volumeInfo.getId(), volumeInfo.getDataStore());
            volumeInfo.processEvent(Event.MigrationRequested);
            volumeInfo = _volumeDataFactory.getVolume(volumeInfo.getId(), volumeInfo.getDataStore());

            hostVO = getHost(snapshotInfo.getDataCenterId(), snapshotInfo.getHypervisorType(), false);

            // copy the volume from secondary via the hypervisor
            if (HypervisorType.XenServer.equals(snapshotInfo.getHypervisorType())) {
                copyCmdAnswer = performCopyOfVdi(volumeInfo, snapshotInfo, hostVO);
            }
            else {
                copyCmdAnswer = copyImageToVolume(snapshotInfo, volumeInfo, hostVO);
            }

            if (copyCmdAnswer == null || !copyCmdAnswer.getResult()) {
                if (copyCmdAnswer != null && !StringUtils.isEmpty(copyCmdAnswer.getDetails())) {
                    throw new CloudRuntimeException(copyCmdAnswer.getDetails());
                }
                else {
                    throw new CloudRuntimeException("Unable to create volume from snapshot");
                }
            }
        }
        catch (Exception ex) {
            errMsg = "Copy operation failed in 'StorageSystemDataMotionStrategy.handleCreateVolumeFromSnapshotOnSecondaryStorage': " +
                    ex.getMessage();

            throw new CloudRuntimeException(errMsg);
        }
        finally {
            if (copyCmdAnswer == null) {
                copyCmdAnswer = new CopyCmdAnswer(errMsg);
            }

            CopyCommandResult result = new CopyCommandResult(null, copyCmdAnswer);

            result.setResult(errMsg);

            callback.complete(result);
        }
    }

    /**
     * Clones a template present on the storage to a new volume and resignatures it.
     *
     * @param templateInfo source template
     * @param volumeInfo destination ROOT volume
     * @param callback for async
     */
    private void handleCreateVolumeFromTemplateBothOnStorageSystem(TemplateInfo templateInfo, VolumeInfo volumeInfo, AsyncCompletionCallback<CopyCommandResult> callback) {
        String errMsg = null;
        CopyCmdAnswer copyCmdAnswer = null;

        try {
            Preconditions.checkArgument(templateInfo != null, "Passing 'null' to templateInfo of " +
                            "handleCreateVolumeFromTemplateBothOnStorageSystem is not supported.");
            Preconditions.checkArgument(volumeInfo != null, "Passing 'null' to volumeInfo of " +
                            "handleCreateVolumeFromTemplateBothOnStorageSystem is not supported.");

            verifyFormat(templateInfo.getFormat());

            HostVO hostVO = null;

            final boolean computeClusterSupportsVolumeClone;

            // only XenServer, VMware, and KVM are currently supported
            // Leave host equal to null for KVM since we don't need to perform a resignature when using that hypervisor type.
            if (volumeInfo.getFormat() == ImageFormat.VHD) {
                hostVO = getHost(volumeInfo.getDataCenterId(), HypervisorType.XenServer, true);

                if (hostVO == null) {
                    throw new CloudRuntimeException("Unable to locate a host capable of resigning in the zone with the following ID: " +
                            volumeInfo.getDataCenterId());
                }

                computeClusterSupportsVolumeClone = clusterDao.getSupportsResigning(hostVO.getClusterId());

                if (!computeClusterSupportsVolumeClone) {
                    String noSupportForResignErrMsg = "Unable to locate an applicable host with which to perform a resignature operation : Cluster ID = " +
                            hostVO.getClusterId();

                    LOGGER.warn(noSupportForResignErrMsg);

                    throw new CloudRuntimeException(noSupportForResignErrMsg);
                }
            }
            else if (volumeInfo.getFormat() == ImageFormat.OVA) {
                // all VMware hosts support resigning
                hostVO = getHost(volumeInfo.getDataCenterId(), HypervisorType.VMware, false);

                if (hostVO == null) {
                    throw new CloudRuntimeException("Unable to locate a host capable of resigning in the zone with the following ID: " +
                            volumeInfo.getDataCenterId());
                }
            }

            VolumeDetailVO volumeDetail = new VolumeDetailVO(volumeInfo.getId(),
                    "cloneOfTemplate",
                    String.valueOf(templateInfo.getId()),
                    false);

            volumeDetail = volumeDetailsDao.persist(volumeDetail);

            AsyncCallFuture<VolumeApiResult> future = _volumeService.createVolumeAsync(volumeInfo, volumeInfo.getDataStore());

            int storagePoolMaxWaitSeconds = NumbersUtil.parseInt(_configDao.getValue(Config.StoragePoolMaxWaitSeconds.key()), 3600);

            VolumeApiResult result = future.get(storagePoolMaxWaitSeconds, TimeUnit.SECONDS);

            if (volumeDetail != null) {
                volumeDetailsDao.remove(volumeDetail.getId());
            }

            if (result.isFailed()) {
                LOGGER.warn("Failed to create a volume: " + result.getResult());

                throw new CloudRuntimeException(result.getResult());
            }

            volumeInfo = _volumeDataFactory.getVolume(volumeInfo.getId(), volumeInfo.getDataStore());
            volumeInfo.processEvent(Event.MigrationRequested);
            volumeInfo = _volumeDataFactory.getVolume(volumeInfo.getId(), volumeInfo.getDataStore());

            if (hostVO != null) {
                Map<String, String> extraDetails = null;

                if (HypervisorType.VMware.equals(templateInfo.getHypervisorType())) {
                    extraDetails = new HashMap<>();

                    String extraDetailsVmdk = templateInfo.getUniqueName() + ".vmdk";

                    extraDetails.put(DiskTO.VMDK, extraDetailsVmdk);
                    extraDetails.put(DiskTO.EXPAND_DATASTORE, Boolean.TRUE.toString());
                }

                copyCmdAnswer = performResignature(volumeInfo, hostVO, extraDetails);

                if (copyCmdAnswer == null || !copyCmdAnswer.getResult()) {
                    if (copyCmdAnswer != null && !StringUtils.isEmpty(copyCmdAnswer.getDetails())) {
                        throw new CloudRuntimeException(copyCmdAnswer.getDetails());
                    } else {
                        throw new CloudRuntimeException("Unable to create a volume from a template");
                    }
                }

                // If using VMware, have the host rescan its software HBA if dynamic discovery is in use.
                if (HypervisorType.VMware.equals(templateInfo.getHypervisorType())) {
                    disconnectHostFromVolume(hostVO, volumeInfo.getPoolId(), volumeInfo.get_iScsiName());
                }
            }
            else {
                VolumeObjectTO newVolume = new VolumeObjectTO();

                newVolume.setSize(volumeInfo.getSize());
                newVolume.setPath(volumeInfo.getPath());
                newVolume.setFormat(volumeInfo.getFormat());

                copyCmdAnswer = new CopyCmdAnswer(newVolume);
            }
        } catch (Exception ex) {
            try {
                volumeInfo.getDataStore().getDriver().deleteAsync(volumeInfo.getDataStore(), volumeInfo, null);
            }
            catch (Exception exc) {
                LOGGER.warn("Failed to delete volume", exc);
            }

            if (templateInfo != null) {
                errMsg = "Create volume from template (ID = " + templateInfo.getId() + ") failed: " + ex.getMessage();
            }
            else {
                errMsg = "Create volume from template failed: " + ex.getMessage();
            }

            throw new CloudRuntimeException(errMsg);
        }
        finally {
            if (copyCmdAnswer == null) {
                copyCmdAnswer = new CopyCmdAnswer(errMsg);
            }

            CopyCommandResult result = new CopyCommandResult(null, copyCmdAnswer);

            result.setResult(errMsg);

            callback.complete(result);
        }
    }

    private void handleCreateVolumeFromSnapshotBothOnStorageSystem(SnapshotInfo snapshotInfo, VolumeInfo volumeInfo,
                                                                   AsyncCompletionCallback<CopyCommandResult> callback) {
        String errMsg = null;
        CopyCmdAnswer copyCmdAnswer = null;

        try {
            verifyFormat(snapshotInfo);

            HostVO hostVO = getHost(snapshotInfo);

            boolean usingBackendSnapshot = usingBackendSnapshotFor(snapshotInfo);
            boolean computeClusterSupportsVolumeClone = true;

            if (HypervisorType.XenServer.equals(snapshotInfo.getHypervisorType())) {
                computeClusterSupportsVolumeClone = clusterDao.getSupportsResigning(hostVO.getClusterId());

                if (usingBackendSnapshot && !computeClusterSupportsVolumeClone) {
                    String noSupportForResignErrMsg = "Unable to locate an applicable host with which to perform a resignature operation : Cluster ID = " +
                            hostVO.getClusterId();

                    LOGGER.warn(noSupportForResignErrMsg);

                    throw new CloudRuntimeException(noSupportForResignErrMsg);
                }
            }

            boolean canStorageSystemCreateVolumeFromVolume = canStorageSystemCreateVolumeFromVolume(snapshotInfo);
            boolean useCloning = usingBackendSnapshot || (canStorageSystemCreateVolumeFromVolume && computeClusterSupportsVolumeClone);

            VolumeDetailVO volumeDetail = null;

            if (useCloning) {
                volumeDetail = new VolumeDetailVO(volumeInfo.getId(),
                    "cloneOfSnapshot",
                    String.valueOf(snapshotInfo.getId()),
                    false);

                volumeDetail = volumeDetailsDao.persist(volumeDetail);
            }

            // at this point, the snapshotInfo and volumeInfo should have the same disk offering ID (so either one should be OK to get a DiskOfferingVO instance)
            DiskOfferingVO diskOffering = _diskOfferingDao.findByIdIncludingRemoved(volumeInfo.getDiskOfferingId());
            SnapshotVO snapshot = _snapshotDao.findById(snapshotInfo.getId());

            // update the volume's hv_ss_reserve (hypervisor snapshot reserve) from a disk offering (used for managed storage)
            _volumeService.updateHypervisorSnapshotReserveForVolume(diskOffering, volumeInfo.getId(), snapshot.getHypervisorType());

            AsyncCallFuture<VolumeApiResult> future = _volumeService.createVolumeAsync(volumeInfo, volumeInfo.getDataStore());
            VolumeApiResult result = future.get();

            if (volumeDetail != null) {
                volumeDetailsDao.remove(volumeDetail.getId());
            }

            if (result.isFailed()) {
                LOGGER.warn("Failed to create a volume: " + result.getResult());

                throw new CloudRuntimeException(result.getResult());
            }

            volumeInfo = _volumeDataFactory.getVolume(volumeInfo.getId(), volumeInfo.getDataStore());
            volumeInfo.processEvent(Event.MigrationRequested);
            volumeInfo = _volumeDataFactory.getVolume(volumeInfo.getId(), volumeInfo.getDataStore());

            if (HypervisorType.XenServer.equals(snapshotInfo.getHypervisorType()) || HypervisorType.VMware.equals(snapshotInfo.getHypervisorType())) {
                if (useCloning) {
                    Map<String, String> extraDetails = null;

                    if (HypervisorType.VMware.equals(snapshotInfo.getHypervisorType())) {
                        extraDetails = new HashMap<>();

                        String extraDetailsVmdk = getSnapshotProperty(snapshotInfo.getId(), DiskTO.VMDK);

                        extraDetails.put(DiskTO.VMDK, extraDetailsVmdk);
                    }

                    copyCmdAnswer = performResignature(volumeInfo, hostVO, extraDetails);

                    // If using VMware, have the host rescan its software HBA if dynamic discovery is in use.
                    if (HypervisorType.VMware.equals(snapshotInfo.getHypervisorType())) {
                        disconnectHostFromVolume(hostVO, volumeInfo.getPoolId(), volumeInfo.get_iScsiName());
                    }
                } else {
                    // asking for a XenServer host here so we don't always prefer to use XenServer hosts that support resigning
                    // even when we don't need those hosts to do this kind of copy work
                    hostVO = getHost(snapshotInfo.getDataCenterId(), snapshotInfo.getHypervisorType(), false);

                    copyCmdAnswer = performCopyOfVdi(volumeInfo, snapshotInfo, hostVO);
                }

                if (copyCmdAnswer == null || !copyCmdAnswer.getResult()) {
                    if (copyCmdAnswer != null && !StringUtils.isEmpty(copyCmdAnswer.getDetails())) {
                        throw new CloudRuntimeException(copyCmdAnswer.getDetails());
                    } else {
                        throw new CloudRuntimeException("Unable to create volume from snapshot");
                    }
                }
            }
            else if (HypervisorType.KVM.equals(snapshotInfo.getHypervisorType())) {
                VolumeObjectTO newVolume = new VolumeObjectTO();

                newVolume.setSize(volumeInfo.getSize());
                newVolume.setPath(volumeInfo.get_iScsiName());
                newVolume.setFormat(volumeInfo.getFormat());

                copyCmdAnswer = new CopyCmdAnswer(newVolume);
            }
            else {
                throw new CloudRuntimeException("Unsupported hypervisor type");
            }
        }
        catch (Exception ex) {
            errMsg = "Copy operation failed in 'StorageSystemDataMotionStrategy.handleCreateVolumeFromSnapshotBothOnStorageSystem': " +
                    ex.getMessage();

            throw new CloudRuntimeException(errMsg);
        }
        finally {
            if (copyCmdAnswer == null) {
                copyCmdAnswer = new CopyCmdAnswer(errMsg);
            }

            CopyCommandResult result = new CopyCommandResult(null, copyCmdAnswer);

            result.setResult(errMsg);

            callback.complete(result);
        }
    }

    private void handleCreateVolumeFromVolumeOnSecondaryStorage(VolumeInfo srcVolumeInfo, VolumeInfo destVolumeInfo,
                                                                long dataCenterId, HypervisorType hypervisorType,
                                                                AsyncCompletionCallback<CopyCommandResult> callback) {
        String errMsg = null;
        CopyCmdAnswer copyCmdAnswer = null;

        try {
            // create a volume on the storage
            destVolumeInfo.getDataStore().getDriver().createAsync(destVolumeInfo.getDataStore(), destVolumeInfo, null);

            destVolumeInfo = _volumeDataFactory.getVolume(destVolumeInfo.getId(), destVolumeInfo.getDataStore());

            HostVO hostVO = getHost(dataCenterId, hypervisorType, false);

            // copy the volume from secondary via the hypervisor
            copyCmdAnswer = copyImageToVolume(srcVolumeInfo, destVolumeInfo, hostVO);

            if (copyCmdAnswer == null || !copyCmdAnswer.getResult()) {
                if (copyCmdAnswer != null && !StringUtils.isEmpty(copyCmdAnswer.getDetails())) {
                    throw new CloudRuntimeException(copyCmdAnswer.getDetails());
                }
                else {
                    throw new CloudRuntimeException("Unable to create volume from volume");
                }
            }
        }
        catch (Exception ex) {
            errMsg = "Copy operation failed in 'StorageSystemDataMotionStrategy.handleCreateVolumeFromVolumeOnSecondaryStorage': " +
                    ex.getMessage();

            throw new CloudRuntimeException(errMsg);
        }
        finally {
            if (copyCmdAnswer == null) {
                copyCmdAnswer = new CopyCmdAnswer(errMsg);
            }

            CopyCommandResult result = new CopyCommandResult(null, copyCmdAnswer);

            result.setResult(errMsg);

            callback.complete(result);
        }
    }

    private CopyCmdAnswer copyImageToVolume(DataObject srcDataObject, VolumeInfo destVolumeInfo, HostVO hostVO) {
        String value = _configDao.getValue(Config.PrimaryStorageDownloadWait.toString());
        int primaryStorageDownloadWait = NumbersUtil.parseInt(value, Integer.parseInt(Config.PrimaryStorageDownloadWait.getDefaultValue()));

        CopyCommand copyCommand = new CopyCommand(srcDataObject.getTO(), destVolumeInfo.getTO(), primaryStorageDownloadWait,
                VirtualMachineManager.ExecuteInSequence.value());

        CopyCmdAnswer copyCmdAnswer;

        try {
            _volumeService.grantAccess(destVolumeInfo, hostVO, destVolumeInfo.getDataStore());

            Map<String, String> destDetails = getVolumeDetails(destVolumeInfo);

            copyCommand.setOptions2(destDetails);

            copyCmdAnswer = (CopyCmdAnswer)_agentMgr.send(hostVO.getId(), copyCommand);
        }
        catch (CloudRuntimeException | AgentUnavailableException | OperationTimedoutException ex) {
            String msg = "Failed to copy image : ";

            LOGGER.warn(msg, ex);

            throw new CloudRuntimeException(msg + ex.getMessage(), ex);
        }
        finally {
            _volumeService.revokeAccess(destVolumeInfo, hostVO, destVolumeInfo.getDataStore());
        }

        VolumeObjectTO volumeObjectTO = (VolumeObjectTO)copyCmdAnswer.getNewData();

        volumeObjectTO.setFormat(ImageFormat.QCOW2);

        return copyCmdAnswer;
    }

    /**
     * If the underlying storage system is making use of read-only snapshots, this gives the storage system the opportunity to
     * create a volume from the snapshot so that we can copy the VHD file that should be inside of the snapshot to secondary storage.
     *
     * The resultant volume must be writable because we need to resign the SR and the VDI that should be inside of it before we copy
     * the VHD file to secondary storage.
     *
     * If the storage system is using writable snapshots, then nothing need be done by that storage system here because we can just
     * resign the SR and the VDI that should be inside of the snapshot before copying the VHD file to secondary storage.
     */
    private void createVolumeFromSnapshot(SnapshotInfo snapshotInfo) {
        SnapshotDetailsVO snapshotDetails = handleSnapshotDetails(snapshotInfo.getId(), "create");

        try {
            snapshotInfo.getDataStore().getDriver().createAsync(snapshotInfo.getDataStore(), snapshotInfo, null);
        }
        finally {
            _snapshotDetailsDao.remove(snapshotDetails.getId());
        }
    }

    /**
     * If the underlying storage system needed to create a volume from a snapshot for createVolumeFromSnapshot(SnapshotInfo), then
     * this is its opportunity to delete that temporary volume and restore properties in snapshot_details to the way they were before the
     * invocation of createVolumeFromSnapshot(SnapshotInfo).
     */
    private void deleteVolumeFromSnapshot(SnapshotInfo snapshotInfo) {
        SnapshotDetailsVO snapshotDetails = handleSnapshotDetails(snapshotInfo.getId(), "delete");

        try {
            snapshotInfo.getDataStore().getDriver().createAsync(snapshotInfo.getDataStore(), snapshotInfo, null);
        }
        finally {
            _snapshotDetailsDao.remove(snapshotDetails.getId());
        }
    }

    private SnapshotDetailsVO handleSnapshotDetails(long csSnapshotId, String value) {
        String name = "tempVolume";

        _snapshotDetailsDao.removeDetail(csSnapshotId, name);

        SnapshotDetailsVO snapshotDetails = new SnapshotDetailsVO(csSnapshotId, name, value, false);

        return _snapshotDetailsDao.persist(snapshotDetails);
    }

    /**
     * For each disk to migrate:
     *   Create a volume on the target storage system.
     *   Make the newly created volume accessible to the target KVM host.
     *   Send a command to the target KVM host to connect to the newly created volume.
     * Send a command to the source KVM host to migrate the VM and its storage.
     */
    @Override
    public void copyAsync(Map<VolumeInfo, DataStore> volumeDataStoreMap, VirtualMachineTO vmTO, Host srcHost, Host destHost, AsyncCompletionCallback<CopyCommandResult> callback) {
        String errMsg = null;

        try {
            if (srcHost.getHypervisorType() != HypervisorType.KVM) {
                throw new CloudRuntimeException("Invalid hypervisor type (only KVM supported for this operation at the time being)");
            }

            verifyLiveMigrationMapForKVM(volumeDataStoreMap);

            Map<String, MigrateCommand.MigrateDiskInfo> migrateStorage = new HashMap<>();
            Map<VolumeInfo, VolumeInfo> srcVolumeInfoToDestVolumeInfo = new HashMap<>();

            for (Map.Entry<VolumeInfo, DataStore> entry : volumeDataStoreMap.entrySet()) {
                VolumeInfo srcVolumeInfo = entry.getKey();
                DataStore destDataStore = entry.getValue();

                VolumeVO srcVolume = _volumeDao.findById(srcVolumeInfo.getId());
                StoragePoolVO destStoragePool = _storagePoolDao.findById(destDataStore.getId());

                VolumeVO destVolume = duplicateVolumeOnAnotherStorage(srcVolume, destStoragePool);
                VolumeInfo destVolumeInfo = _volumeDataFactory.getVolume(destVolume.getId(), destDataStore);

                // move the volume from Allocated to Creating
                destVolumeInfo.processEvent(Event.MigrationCopyRequested);
                // move the volume from Creating to Ready
                destVolumeInfo.processEvent(Event.MigrationCopySucceeded);
                // move the volume from Ready to Migrating
                destVolumeInfo.processEvent(Event.MigrationRequested);

                // create a volume on the destination storage
                destDataStore.getDriver().createAsync(destDataStore, destVolumeInfo, null);

                destVolume = _volumeDao.findById(destVolume.getId());

                destVolume.setPath(destVolume.get_iScsiName());

                _volumeDao.update(destVolume.getId(), destVolume);

                destVolumeInfo = _volumeDataFactory.getVolume(destVolume.getId(), destDataStore);

                _volumeService.grantAccess(destVolumeInfo, destHost, destDataStore);

                String connectedPath = connectHostToVolume(destHost, destVolumeInfo.getPoolId(), destVolumeInfo.get_iScsiName());

                MigrateCommand.MigrateDiskInfo migrateDiskInfo = new MigrateCommand.MigrateDiskInfo(srcVolumeInfo.getPath(),
                        MigrateCommand.MigrateDiskInfo.DiskType.BLOCK,
                        MigrateCommand.MigrateDiskInfo.DriverType.RAW,
                        MigrateCommand.MigrateDiskInfo.Source.DEV,
                        connectedPath);

                migrateStorage.put(srcVolumeInfo.getPath(), migrateDiskInfo);

                srcVolumeInfoToDestVolumeInfo.put(srcVolumeInfo, destVolumeInfo);
            }

            PrepareForMigrationCommand pfmc = new PrepareForMigrationCommand(vmTO);

            try {
                Answer pfma = _agentMgr.send(destHost.getId(), pfmc);

                if (pfma == null || !pfma.getResult()) {
                    String details = pfma != null ? pfma.getDetails() : "null answer returned";
                    String msg = "Unable to prepare for migration due to the following: " + details;

                    throw new AgentUnavailableException(msg, destHost.getId());
                }
            }
            catch (final OperationTimedoutException e) {
                throw new AgentUnavailableException("Operation timed out", destHost.getId());
            }

            VMInstanceVO vm = _vmDao.findById(vmTO.getId());
            boolean isWindows = _guestOsCategoryDao.findById(_guestOsDao.findById(vm.getGuestOSId()).getCategoryId()).getName().equalsIgnoreCase("Windows");

            MigrateCommand migrateCommand = new MigrateCommand(vmTO.getName(), destHost.getPrivateIpAddress(), isWindows, vmTO, true);

            migrateCommand.setWait(StorageManager.KvmStorageOnlineMigrationWait.value());

            migrateCommand.setMigrateStorage(migrateStorage);

            String autoConvergence = _configDao.getValue(Config.KvmAutoConvergence.toString());
            boolean kvmAutoConvergence = Boolean.parseBoolean(autoConvergence);

            migrateCommand.setAutoConvergence(kvmAutoConvergence);

            MigrateAnswer migrateAnswer = (MigrateAnswer)_agentMgr.send(srcHost.getId(), migrateCommand);

            boolean success = migrateAnswer != null && migrateAnswer.getResult();

            handlePostMigration(success, srcVolumeInfoToDestVolumeInfo, vmTO, destHost);

            if (migrateAnswer == null) {
                throw new CloudRuntimeException("Unable to get an answer to the migrate command");
            }

            if (!migrateAnswer.getResult()) {
                errMsg = migrateAnswer.getDetails();

                throw new CloudRuntimeException(errMsg);
            }
        }
        catch (Exception ex) {
            errMsg = "Copy operation failed in 'StorageSystemDataMotionStrategy.copyAsync': " + ex.getMessage();

            throw new CloudRuntimeException(errMsg);
        }
        finally {
            CopyCmdAnswer copyCmdAnswer = new CopyCmdAnswer(errMsg);

            CopyCommandResult result = new CopyCommandResult(null, copyCmdAnswer);

            result.setResult(errMsg);

            callback.complete(result);
        }
    }

    private void handlePostMigration(boolean success, Map<VolumeInfo, VolumeInfo> srcVolumeInfoToDestVolumeInfo, VirtualMachineTO vmTO, Host destHost) {
        if (!success) {
            try {
                PrepareForMigrationCommand pfmc = new PrepareForMigrationCommand(vmTO);

                pfmc.setRollback(true);

                Answer pfma = _agentMgr.send(destHost.getId(), pfmc);

                if (pfma == null || !pfma.getResult()) {
                    String details = pfma != null ? pfma.getDetails() : "null answer returned";
                    String msg = "Unable to rollback prepare for migration due to the following: " + details;

                    throw new AgentUnavailableException(msg, destHost.getId());
                }
            }
            catch (Exception e) {
                LOGGER.debug("Failed to disconnect one or more (original) dest volumes", e);
            }
        }

        for (Map.Entry<VolumeInfo, VolumeInfo> entry : srcVolumeInfoToDestVolumeInfo.entrySet()) {
            VolumeInfo srcVolumeInfo = entry.getKey();
            VolumeInfo destVolumeInfo = entry.getValue();

            if (success) {
                srcVolumeInfo.processEvent(Event.OperationSuccessed);
                destVolumeInfo.processEvent(Event.OperationSuccessed);

                _volumeDao.updateUuid(srcVolumeInfo.getId(), destVolumeInfo.getId());

                VolumeVO volumeVO = _volumeDao.findById(destVolumeInfo.getId());

                volumeVO.setFormat(ImageFormat.QCOW2);

                _volumeDao.update(volumeVO.getId(), volumeVO);

                try {
                    _volumeService.destroyVolume(srcVolumeInfo.getId());

                    srcVolumeInfo = _volumeDataFactory.getVolume(srcVolumeInfo.getId());

                    AsyncCallFuture<VolumeApiResult> destroyFuture = _volumeService.expungeVolumeAsync(srcVolumeInfo);

                    if (destroyFuture.get().isFailed()) {
                        LOGGER.debug("Failed to clean up source volume on storage");
                    }
                } catch (Exception e) {
                    LOGGER.debug("Failed to clean up source volume on storage", e);
                }

                // Update the volume ID for snapshots on secondary storage
                if (!_snapshotDao.listByVolumeId(srcVolumeInfo.getId()).isEmpty()) {
                    _snapshotDao.updateVolumeIds(srcVolumeInfo.getId(), destVolumeInfo.getId());
                    _snapshotDataStoreDao.updateVolumeIds(srcVolumeInfo.getId(), destVolumeInfo.getId());
                }
            }
            else {
                try {
                    disconnectHostFromVolume(destHost, destVolumeInfo.getPoolId(), destVolumeInfo.get_iScsiName());
                }
                catch (Exception e) {
                    LOGGER.debug("Failed to disconnect (new) dest volume", e);
                }

                try {
                    _volumeService.revokeAccess(destVolumeInfo, destHost, destVolumeInfo.getDataStore());
                }
                catch (Exception e) {
                    LOGGER.debug("Failed to revoke access from dest volume", e);
                }

                destVolumeInfo.processEvent(Event.OperationFailed);
                srcVolumeInfo.processEvent(Event.OperationFailed);

                try {
                    _volumeService.destroyVolume(destVolumeInfo.getId());

                    destVolumeInfo = _volumeDataFactory.getVolume(destVolumeInfo.getId());

                    AsyncCallFuture<VolumeApiResult> destroyFuture = _volumeService.expungeVolumeAsync(destVolumeInfo);

                    if (destroyFuture.get().isFailed()) {
                        LOGGER.debug("Failed to clean up dest volume on storage");
                    }
                } catch (Exception e) {
                    LOGGER.debug("Failed to clean up dest volume on storage", e);
                }
            }
        }
    }

    private VolumeVO duplicateVolumeOnAnotherStorage(Volume volume, StoragePoolVO storagePoolVO) {
        Long lastPoolId = volume.getPoolId();

        VolumeVO newVol = new VolumeVO(volume);

        newVol.setInstanceId(null);
        newVol.setChainInfo(null);
        newVol.setPath(null);
        newVol.setFolder(null);
        newVol.setPodId(storagePoolVO.getPodId());
        newVol.setPoolId(storagePoolVO.getId());
        newVol.setLastPoolId(lastPoolId);

        return _volumeDao.persist(newVol);
    }

    private String connectHostToVolume(Host host, long storagePoolId, String iqn) {
        ModifyTargetsCommand modifyTargetsCommand = getModifyTargetsCommand(storagePoolId, iqn, true);

        return sendModifyTargetsCommand(modifyTargetsCommand, host.getId()).get(0);
    }

    private void disconnectHostFromVolume(Host host, long storagePoolId, String iqn) {
        ModifyTargetsCommand modifyTargetsCommand = getModifyTargetsCommand(storagePoolId, iqn, false);

        sendModifyTargetsCommand(modifyTargetsCommand, host.getId());
    }

    private ModifyTargetsCommand getModifyTargetsCommand(long storagePoolId, String iqn, boolean add) {
        StoragePoolVO storagePool = _storagePoolDao.findById(storagePoolId);

        Map<String, String> details = new HashMap<>();

        details.put(ModifyTargetsCommand.IQN, iqn);
        details.put(ModifyTargetsCommand.STORAGE_TYPE, storagePool.getPoolType().name());
        details.put(ModifyTargetsCommand.STORAGE_UUID, storagePool.getUuid());
        details.put(ModifyTargetsCommand.STORAGE_HOST, storagePool.getHostAddress());
        details.put(ModifyTargetsCommand.STORAGE_PORT, String.valueOf(storagePool.getPort()));

        ModifyTargetsCommand modifyTargetsCommand = new ModifyTargetsCommand();

        List<Map<String, String>> targets = new ArrayList<>();

        targets.add(details);

        modifyTargetsCommand.setTargets(targets);
        modifyTargetsCommand.setApplyToAllHostsInCluster(true);
        modifyTargetsCommand.setAdd(add);
        modifyTargetsCommand.setTargetTypeToRemove(ModifyTargetsCommand.TargetTypeToRemove.DYNAMIC);

        return modifyTargetsCommand;
    }

    private List<String> sendModifyTargetsCommand(ModifyTargetsCommand cmd, long hostId) {
        ModifyTargetsAnswer modifyTargetsAnswer = (ModifyTargetsAnswer)_agentMgr.easySend(hostId, cmd);

        if (modifyTargetsAnswer == null) {
            throw new CloudRuntimeException("Unable to get an answer to the modify targets command");
        }

        if (!modifyTargetsAnswer.getResult()) {
            String msg = "Unable to modify targets on the following host: " + hostId;

            throw new CloudRuntimeException(msg);
        }

        return modifyTargetsAnswer.getConnectedPaths();
    }

    /*
    * At a high level: The source storage cannot be managed and the destination storage must be managed.
    */
    private void verifyLiveMigrationMapForKVM(Map<VolumeInfo, DataStore> volumeDataStoreMap) {
        for (Map.Entry<VolumeInfo, DataStore> entry : volumeDataStoreMap.entrySet()) {
            VolumeInfo volumeInfo = entry.getKey();

            Long storagePoolId = volumeInfo.getPoolId();
            StoragePoolVO srcStoragePoolVO = _storagePoolDao.findById(storagePoolId);

            if (srcStoragePoolVO == null) {
                throw new CloudRuntimeException("Volume with ID " + volumeInfo.getId() + " is not associated with a storage pool.");
            }

            if (srcStoragePoolVO.isManaged()) {
                throw new CloudRuntimeException("Migrating a volume online with KVM from managed storage is not currently supported.");
            }

            DataStore dataStore = entry.getValue();
            StoragePoolVO destStoragePoolVO = _storagePoolDao.findById(dataStore.getId());

            if (destStoragePoolVO == null) {
                throw new CloudRuntimeException("Destination storage pool with ID " + dataStore.getId() + " was not located.");
            }

            if (!destStoragePoolVO.isManaged()) {
                throw new CloudRuntimeException("Migrating a volume online with KVM can currently only be done when moving to managed storage.");
            }
        }
    }

    private boolean canStorageSystemCreateVolumeFromVolume(SnapshotInfo snapshotInfo) {
        boolean supportsCloningVolumeFromVolume = false;

        DataStore dataStore = dataStoreMgr.getDataStore(snapshotInfo.getDataStore().getId(), DataStoreRole.Primary);

        Map<String, String> mapCapabilities = dataStore.getDriver().getCapabilities();

        if (mapCapabilities != null) {
            String value = mapCapabilities.get(DataStoreCapabilities.CAN_CREATE_VOLUME_FROM_VOLUME.toString());

            supportsCloningVolumeFromVolume = Boolean.valueOf(value);
        }

        return supportsCloningVolumeFromVolume;
    }

    private String getVolumeProperty(long volumeId, String property) {
        VolumeDetailVO volumeDetails = volumeDetailsDao.findDetail(volumeId, property);

        if (volumeDetails != null) {
            return volumeDetails.getValue();
        }

        return null;
    }

    private String getSnapshotProperty(long snapshotId, String property) {
        SnapshotDetailsVO snapshotDetails = _snapshotDetailsDao.findDetail(snapshotId, property);

        if (snapshotDetails != null) {
            return snapshotDetails.getValue();
        }

        return null;
    }

    private void handleCreateTemplateFromVolume(VolumeInfo volumeInfo, TemplateInfo templateInfo, AsyncCompletionCallback<CopyCommandResult> callback) {
        boolean srcVolumeDetached = volumeInfo.getAttachedVM() == null;

        String errMsg = null;
        CopyCmdAnswer copyCmdAnswer = null;

        try {
            if (!ImageFormat.QCOW2.equals(volumeInfo.getFormat())) {
                throw new CloudRuntimeException("When using managed storage, you can only create a template from a volume on KVM currently.");
            }

            volumeInfo.processEvent(Event.MigrationRequested);

            HostVO hostVO = getHost(volumeInfo.getDataCenterId(), HypervisorType.KVM, false);
            DataStore srcDataStore = volumeInfo.getDataStore();

            String value = _configDao.getValue(Config.PrimaryStorageDownloadWait.toString());
            int primaryStorageDownloadWait = NumberUtils.toInt(value, Integer.parseInt(Config.PrimaryStorageDownloadWait.getDefaultValue()));
            CopyCommand copyCommand = new CopyCommand(volumeInfo.getTO(), templateInfo.getTO(), primaryStorageDownloadWait, VirtualMachineManager.ExecuteInSequence.value());

            try {
                if (srcVolumeDetached) {
                    _volumeService.grantAccess(volumeInfo, hostVO, srcDataStore);
                }

                Map<String, String> srcDetails = getVolumeDetails(volumeInfo);

                copyCommand.setOptions(srcDetails);

                copyCmdAnswer = (CopyCmdAnswer)_agentMgr.send(hostVO.getId(), copyCommand);

                if (!copyCmdAnswer.getResult()) {
                    // We were not able to copy. Handle it.
                    errMsg = copyCmdAnswer.getDetails();
                    throw new CloudRuntimeException(errMsg);
                }

                VMTemplateVO vmTemplateVO = _vmTemplateDao.findById(templateInfo.getId());

                vmTemplateVO.setHypervisorType(HypervisorType.KVM);

                _vmTemplateDao.update(vmTemplateVO.getId(), vmTemplateVO);
            }
            catch (CloudRuntimeException | AgentUnavailableException | OperationTimedoutException ex) {
                String msg = "Failed to create template from volume (Volume ID = " + volumeInfo.getId() + ") : ";

                LOGGER.warn(msg, ex);

                throw new CloudRuntimeException(msg + ex.getMessage(), ex);
            }
            finally {
                try {
                    if (srcVolumeDetached) {
                        _volumeService.revokeAccess(volumeInfo, hostVO, srcDataStore);
                    }
                }
                catch (Exception ex) {
                    LOGGER.warn("Error revoking access to volume (Volume ID = " + volumeInfo.getId() + "): " + ex.getMessage(), ex);
                }
                if (copyCmdAnswer == null || !copyCmdAnswer.getResult()) {
                    if (copyCmdAnswer != null && !StringUtils.isEmpty(copyCmdAnswer.getDetails())) {
                        errMsg = copyCmdAnswer.getDetails();
                    }
                    else {
                        errMsg = "Unable to create template from volume";
                    }
                }

                try {
                    if (StringUtils.isEmpty(errMsg)) {
                        volumeInfo.processEvent(Event.OperationSuccessed);
                    }
                    else {
                        volumeInfo.processEvent(Event.OperationFailed);
                    }
                }
                catch (Exception ex) {
                    LOGGER.warn("Error processing snapshot event: " + ex.getMessage(), ex);
                }
            }
        }
        catch (Exception ex) {
            errMsg = ex.getMessage();

            throw new CloudRuntimeException(errMsg);
        }
        finally {
            if (copyCmdAnswer == null) {
                copyCmdAnswer = new CopyCmdAnswer(errMsg);
            }

            CopyCommandResult result = new CopyCommandResult(null, copyCmdAnswer);

            result.setResult(errMsg);

            callback.complete(result);
        }
    }

    private Map<String, String> getVolumeDetails(VolumeInfo volumeInfo) {
        long storagePoolId = volumeInfo.getPoolId();
        StoragePoolVO storagePoolVO = _storagePoolDao.findById(storagePoolId);

        if (!storagePoolVO.isManaged()) {
            return null;
        }

        Map<String, String> volumeDetails = new HashMap<>();

        VolumeVO volumeVO = _volumeDao.findById(volumeInfo.getId());

        volumeDetails.put(DiskTO.STORAGE_HOST, storagePoolVO.getHostAddress());
        volumeDetails.put(DiskTO.STORAGE_PORT, String.valueOf(storagePoolVO.getPort()));
        volumeDetails.put(DiskTO.IQN, volumeVO.get_iScsiName());

        volumeDetails.put(DiskTO.VOLUME_SIZE, String.valueOf(volumeVO.getSize()));
        volumeDetails.put(DiskTO.SCSI_NAA_DEVICE_ID, getVolumeProperty(volumeInfo.getId(), DiskTO.SCSI_NAA_DEVICE_ID));

        ChapInfo chapInfo = _volumeService.getChapInfo(volumeInfo, volumeInfo.getDataStore());

        if (chapInfo != null) {
            volumeDetails.put(DiskTO.CHAP_INITIATOR_USERNAME, chapInfo.getInitiatorUsername());
            volumeDetails.put(DiskTO.CHAP_INITIATOR_SECRET, chapInfo.getInitiatorSecret());
            volumeDetails.put(DiskTO.CHAP_TARGET_USERNAME, chapInfo.getTargetUsername());
            volumeDetails.put(DiskTO.CHAP_TARGET_SECRET, chapInfo.getTargetSecret());
        }

        return volumeDetails;
    }

    private Map<String, String> getSnapshotDetails(SnapshotInfo snapshotInfo) {
        Map<String, String> snapshotDetails = new HashMap<>();

        long storagePoolId = snapshotInfo.getDataStore().getId();
        StoragePoolVO storagePoolVO = _storagePoolDao.findById(storagePoolId);

        snapshotDetails.put(DiskTO.STORAGE_HOST, storagePoolVO.getHostAddress());
        snapshotDetails.put(DiskTO.STORAGE_PORT, String.valueOf(storagePoolVO.getPort()));

        long snapshotId = snapshotInfo.getId();

        snapshotDetails.put(DiskTO.IQN, getSnapshotProperty(snapshotId, DiskTO.IQN));
        snapshotDetails.put(DiskTO.VOLUME_SIZE, String.valueOf(snapshotInfo.getSize()));
        snapshotDetails.put(DiskTO.SCSI_NAA_DEVICE_ID, getSnapshotProperty(snapshotId, DiskTO.SCSI_NAA_DEVICE_ID));

        snapshotDetails.put(DiskTO.CHAP_INITIATOR_USERNAME, getSnapshotProperty(snapshotId, DiskTO.CHAP_INITIATOR_USERNAME));
        snapshotDetails.put(DiskTO.CHAP_INITIATOR_SECRET, getSnapshotProperty(snapshotId, DiskTO.CHAP_INITIATOR_SECRET));
        snapshotDetails.put(DiskTO.CHAP_TARGET_USERNAME, getSnapshotProperty(snapshotId, DiskTO.CHAP_TARGET_USERNAME));
        snapshotDetails.put(DiskTO.CHAP_TARGET_SECRET, getSnapshotProperty(snapshotId, DiskTO.CHAP_TARGET_SECRET));

        return snapshotDetails;
    }

    private HostVO getHost(SnapshotInfo snapshotInfo) {
        HypervisorType hypervisorType = snapshotInfo.getHypervisorType();

        if (HypervisorType.XenServer.equals(hypervisorType)) {
            HostVO hostVO = getHost(snapshotInfo.getDataCenterId(), hypervisorType, true);

            if (hostVO == null) {
                hostVO = getHost(snapshotInfo.getDataCenterId(), hypervisorType, false);

                if (hostVO == null) {
                    throw new CloudRuntimeException("Unable to locate an applicable host in data center with ID = " + snapshotInfo.getDataCenterId());
                }
            }

            return hostVO;
        }

        if (HypervisorType.VMware.equals(hypervisorType) || HypervisorType.KVM.equals(hypervisorType)) {
            return getHost(snapshotInfo.getDataCenterId(), hypervisorType, false);
        }

        throw new CloudRuntimeException("Unsupported hypervisor type");
    }

    private HostVO getHostInCluster(long clusterId) {
        List<HostVO> hosts = _hostDao.findByClusterId(clusterId);

        if (hosts != null && hosts.size() > 0) {
            Collections.shuffle(hosts, RANDOM);

            return hosts.get(0);
        }

        throw new CloudRuntimeException("Unable to locate a host");
    }

    private HostVO getHost(Long zoneId, HypervisorType hypervisorType, boolean computeClusterMustSupportResign) {
        Preconditions.checkArgument(zoneId != null, "Zone ID cannot be null.");
        Preconditions.checkArgument(hypervisorType != null, "Hypervisor type cannot be null.");

        List<HostVO> hosts = _hostDao.listByDataCenterIdAndHypervisorType(zoneId, hypervisorType);

        if (hosts == null) {
            return null;
        }

        List<Long> clustersToSkip = new ArrayList<>();

        Collections.shuffle(hosts, RANDOM);

        for (HostVO host : hosts) {
            if (computeClusterMustSupportResign) {
                long clusterId = host.getClusterId();

                if (clustersToSkip.contains(clusterId)) {
                    continue;
                }

                if (clusterDao.getSupportsResigning(clusterId)) {
                    return host;
                }
                else {
                    clustersToSkip.add(clusterId);
                }
            }
            else {
                return host;
            }
        }

        return null;
    }

    private Map<String, String> getDetails(DataObject dataObj) {
        if (dataObj instanceof VolumeInfo) {
            return getVolumeDetails((VolumeInfo)dataObj);
        }
        else if (dataObj instanceof SnapshotInfo) {
            return getSnapshotDetails((SnapshotInfo)dataObj);
        }

        throw new CloudRuntimeException("'dataObj' must be of type 'VolumeInfo' or 'SnapshotInfo'.");
    }

    private boolean isForVMware(DataObject dataObj) {
        if (dataObj instanceof VolumeInfo) {
            return ImageFormat.OVA.equals(((VolumeInfo)dataObj).getFormat());
        }

        if (dataObj instanceof SnapshotInfo) {
            return ImageFormat.OVA.equals(((SnapshotInfo)dataObj).getBaseVolume().getFormat());
        }

        return dataObj instanceof TemplateInfo && HypervisorType.VMware.equals(((TemplateInfo)dataObj).getHypervisorType());
    }

    private CopyCmdAnswer performResignature(DataObject dataObj, HostVO hostVO, Map<String, String> extraDetails) {
        return performResignature(dataObj, hostVO, extraDetails, false);
    }

    private CopyCmdAnswer performResignature(DataObject dataObj, HostVO hostVO, Map<String, String> extraDetails, boolean keepGrantedAccess) {
        long storagePoolId = dataObj.getDataStore().getId();
        DataStore dataStore = dataStoreMgr.getDataStore(storagePoolId, DataStoreRole.Primary);

        Map<String, String> details = getDetails(dataObj);

        if (extraDetails != null) {
            details.putAll(extraDetails);
        }

        ResignatureCommand command = new ResignatureCommand(details);

        ResignatureAnswer answer;

        GlobalLock lock = GlobalLock.getInternLock(dataStore.getUuid());

        if (!lock.lock(LOCK_TIME_IN_SECONDS)) {
            String errMsg = "Couldn't lock the DB (in performResignature) on the following string: " + dataStore.getUuid();

            LOGGER.warn(errMsg);

            throw new CloudRuntimeException(errMsg);
        }

        try {
            _volumeService.grantAccess(dataObj, hostVO, dataStore);

            answer = (ResignatureAnswer)_agentMgr.send(hostVO.getId(), command);
        }
        catch (CloudRuntimeException | AgentUnavailableException | OperationTimedoutException ex) {
            keepGrantedAccess = false;

            String msg = "Failed to resign the DataObject with the following ID: " + dataObj.getId();

            LOGGER.warn(msg, ex);

            throw new CloudRuntimeException(msg + ex.getMessage());
        }
        finally {
            lock.unlock();
            lock.releaseRef();

            if (!keepGrantedAccess) {
                _volumeService.revokeAccess(dataObj, hostVO, dataStore);
            }
        }

        if (answer == null || !answer.getResult()) {
            final String errMsg;

            if (answer != null && answer.getDetails() != null && !answer.getDetails().isEmpty()) {
                errMsg = answer.getDetails();
            }
            else {
                errMsg = "Unable to perform resignature operation in 'StorageSystemDataMotionStrategy.performResignature'";
            }

            throw new CloudRuntimeException(errMsg);
        }

        VolumeObjectTO newVolume = new VolumeObjectTO();

        newVolume.setSize(answer.getSize());
        newVolume.setPath(answer.getPath());
        newVolume.setFormat(answer.getFormat());

        return new CopyCmdAnswer(newVolume);
    }

    private DataObject cacheSnapshotChain(SnapshotInfo snapshot, Scope scope) {
        DataObject leafData = null;
        DataStore store = cacheMgr.getCacheStorage(snapshot, scope);

        while (snapshot != null) {
            DataObject cacheData = cacheMgr.createCacheObject(snapshot, store);

            if (leafData == null) {
                leafData = cacheData;
            }

            snapshot = snapshot.getParent();
        }

        return leafData;
    }

    private String migrateVolume(VolumeInfo srcVolumeInfo, VolumeInfo destVolumeInfo, HostVO hostVO, String errMsg) {
        boolean srcVolumeDetached = srcVolumeInfo.getAttachedVM() == null;

        try {
            Map<String, String> srcDetails = getVolumeDetails(srcVolumeInfo);
            Map<String, String> destDetails = getVolumeDetails(destVolumeInfo);

            MigrateVolumeCommand migrateVolumeCommand = new MigrateVolumeCommand(srcVolumeInfo.getTO(), destVolumeInfo.getTO(),
                    srcDetails, destDetails, StorageManager.KvmStorageOfflineMigrationWait.value());

            if (srcVolumeDetached) {
                _volumeService.grantAccess(srcVolumeInfo, hostVO, srcVolumeInfo.getDataStore());
            }

            _volumeService.grantAccess(destVolumeInfo, hostVO, destVolumeInfo.getDataStore());

            MigrateVolumeAnswer migrateVolumeAnswer = (MigrateVolumeAnswer)_agentMgr.send(hostVO.getId(), migrateVolumeCommand);

            if (migrateVolumeAnswer == null || !migrateVolumeAnswer.getResult()) {
                if (migrateVolumeAnswer != null && !StringUtils.isEmpty(migrateVolumeAnswer.getDetails())) {
                    throw new CloudRuntimeException(migrateVolumeAnswer.getDetails());
                }
                else {
                    throw new CloudRuntimeException(errMsg);
                }
            }

            if (srcVolumeDetached) {
                _volumeService.revokeAccess(destVolumeInfo, hostVO, destVolumeInfo.getDataStore());
            }

            try {
                _volumeService.revokeAccess(srcVolumeInfo, hostVO, srcVolumeInfo.getDataStore());
            }
            catch (Exception e) {
                // This volume should be deleted soon, so just log a warning here.
                LOGGER.warn(e.getMessage(), e);
            }

            return migrateVolumeAnswer.getVolumePath();
        }
        catch (Exception ex) {
            try {
                _volumeService.revokeAccess(destVolumeInfo, hostVO, destVolumeInfo.getDataStore());
            }
            catch (Exception e) {
                // This volume should be deleted soon, so just log a warning here.
                LOGGER.warn(e.getMessage(), e);
            }

            if (srcVolumeDetached) {
                _volumeService.revokeAccess(srcVolumeInfo, hostVO, srcVolumeInfo.getDataStore());
            }

            String msg = "Failed to perform volume migration : ";

            LOGGER.warn(msg, ex);

            throw new CloudRuntimeException(msg + ex.getMessage(), ex);
        }
    }

    private String copyVolumeToSecondaryStorage(VolumeInfo srcVolumeInfo, VolumeInfo destVolumeInfo, HostVO hostVO, String errMsg) {
        boolean srcVolumeDetached = srcVolumeInfo.getAttachedVM() == null;

        try {
            StoragePoolVO storagePoolVO = _storagePoolDao.findById(srcVolumeInfo.getPoolId());
            Map<String, String> srcDetails = getVolumeDetails(srcVolumeInfo);

            CopyVolumeCommand copyVolumeCommand = new CopyVolumeCommand(srcVolumeInfo.getId(), destVolumeInfo.getPath(), storagePoolVO,
                    destVolumeInfo.getDataStore().getUri(), true, StorageManager.KvmStorageOfflineMigrationWait.value(), true);

            copyVolumeCommand.setSrcData(srcVolumeInfo.getTO());
            copyVolumeCommand.setSrcDetails(srcDetails);

            if (srcVolumeDetached) {
                _volumeService.grantAccess(srcVolumeInfo, hostVO, srcVolumeInfo.getDataStore());
            }

            CopyVolumeAnswer copyVolumeAnswer = (CopyVolumeAnswer)_agentMgr.send(hostVO.getId(), copyVolumeCommand);

            if (copyVolumeAnswer == null || !copyVolumeAnswer.getResult()) {
                if (copyVolumeAnswer != null && !StringUtils.isEmpty(copyVolumeAnswer.getDetails())) {
                    throw new CloudRuntimeException(copyVolumeAnswer.getDetails());
                }
                else {
                    throw new CloudRuntimeException(errMsg);
                }
            }

            return copyVolumeAnswer.getVolumePath();
        }
        catch (Exception ex) {
            String msg = "Failed to perform volume copy to secondary storage : ";

            LOGGER.warn(msg, ex);

            throw new CloudRuntimeException(msg + ex.getMessage());
        }
        finally {
            if (srcVolumeDetached) {
                _volumeService.revokeAccess(srcVolumeInfo, hostVO, srcVolumeInfo.getDataStore());
            }
        }
    }

    private void setCertainVolumeValuesNull(long volumeId) {
        VolumeVO volumeVO = _volumeDao.findById(volumeId);

        volumeVO.set_iScsiName(null);
        volumeVO.setMinIops(null);
        volumeVO.setMaxIops(null);
        volumeVO.setHypervisorSnapshotReserve(null);

        _volumeDao.update(volumeId, volumeVO);
    }

    private void updateVolumePath(long volumeId, String path) {
        VolumeVO volumeVO = _volumeDao.findById(volumeId);

        volumeVO.setPath(path);

        _volumeDao.update(volumeId, volumeVO);
    }

    /**
     * Copies data from secondary storage to a primary volume
     * @param volumeInfo The primary volume
     * @param snapshotInfo  destination of the copy
     * @param hostVO the host used to copy the data
     * @return result of the copy
     */
    private CopyCmdAnswer performCopyOfVdi(VolumeInfo volumeInfo, SnapshotInfo snapshotInfo, HostVO hostVO) {
        Snapshot.LocationType locationType = snapshotInfo.getLocationType();

        String value = _configDao.getValue(Config.PrimaryStorageDownloadWait.toString());
        int primaryStorageDownloadWait = NumbersUtil.parseInt(value, Integer.parseInt(Config.PrimaryStorageDownloadWait.getDefaultValue()));

        DataObject srcData = snapshotInfo;
        CopyCmdAnswer copyCmdAnswer = null;
        DataObject cacheData = null;

        boolean needCacheStorage = needCacheStorage(snapshotInfo, volumeInfo);

        if (needCacheStorage) {
            cacheData = cacheSnapshotChain(snapshotInfo, new ZoneScope(volumeInfo.getDataCenterId()));
            srcData = cacheData;
        }

        CopyCommand copyCommand = new CopyCommand(srcData.getTO(), volumeInfo.getTO(), primaryStorageDownloadWait, VirtualMachineManager.ExecuteInSequence.value());

        try {
            if (Snapshot.LocationType.PRIMARY.equals(locationType)) {
                _volumeService.grantAccess(snapshotInfo, hostVO, snapshotInfo.getDataStore());

                Map<String, String> srcDetails = getSnapshotDetails(snapshotInfo);

                copyCommand.setOptions(srcDetails);
            }

            _volumeService.grantAccess(volumeInfo, hostVO, volumeInfo.getDataStore());

            Map<String, String> destDetails = getVolumeDetails(volumeInfo);

            copyCommand.setOptions2(destDetails);

            copyCmdAnswer = (CopyCmdAnswer)_agentMgr.send(hostVO.getId(), copyCommand);
        }
        catch (CloudRuntimeException | AgentUnavailableException | OperationTimedoutException ex) {
            String msg = "Failed to perform VDI copy : ";

            LOGGER.warn(msg, ex);

            throw new CloudRuntimeException(msg + ex.getMessage(), ex);
        }
        finally {
            if (Snapshot.LocationType.PRIMARY.equals(locationType)) {
                _volumeService.revokeAccess(snapshotInfo, hostVO, snapshotInfo.getDataStore());
            }

            _volumeService.revokeAccess(volumeInfo, hostVO, volumeInfo.getDataStore());

            if (needCacheStorage && copyCmdAnswer != null && copyCmdAnswer.getResult()) {
                cacheMgr.deleteCacheObject(cacheData);
            }
        }

        return copyCmdAnswer;
    }
}
/*
 * Copyright (c) 2016 Ericsson India Global Services Pvt Ltd. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.vpnservice.natservice.internal;

import java.math.BigInteger;
import java.util.List;

import org.opendaylight.controller.md.sal.binding.api.DataBroker;
import org.opendaylight.controller.md.sal.binding.api.DataChangeListener;
import org.opendaylight.controller.md.sal.common.api.data.AsyncDataBroker;
import org.opendaylight.controller.md.sal.common.api.data.LogicalDatastoreType;
import org.opendaylight.vpnservice.mdsalutil.AbstractDataChangeListener;
import org.opendaylight.vpnservice.mdsalutil.*;
import org.opendaylight.vpnservice.mdsalutil.interfaces.IMdsalApiManager;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.yang.types.rev130715.Uuid;
import org.opendaylight.yang.gen.v1.urn.opendaylight.group.types.rev131018.GroupTypes;
import org.opendaylight.yang.gen.v1.urn.opendaylight.vpnservice.idmanager.rev150403.IdManagerService;
import org.opendaylight.yang.gen.v1.urn.opendaylight.vpnservice.natservice.rev160111.ext.routers.Routers;
import org.opendaylight.yang.gen.v1.urn.opendaylight.l3vpn.rev130911.NeutronRouterDpns;
import org.opendaylight.yang.gen.v1.urn.opendaylight.l3vpn.rev130911.neutron.router.dpns.RouterDpnList;
import org.opendaylight.yang.gen.v1.urn.opendaylight.l3vpn.rev130911.neutron.router.dpns.router.dpn.list.DpnVpninterfacesList;
import org.opendaylight.yangtools.concepts.ListenerRegistration;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;

public class RouterDpnChangeListener extends AbstractDataChangeListener<DpnVpninterfacesList> implements AutoCloseable{
    private static final Logger LOG = LoggerFactory.getLogger(RouterDpnChangeListener.class);
    private ListenerRegistration<DataChangeListener> listenerRegistration;
    private final DataBroker dataBroker;
    private SNATDefaultRouteProgrammer defaultRouteProgrammer;
    private NaptSwitchHA naptSwitchHA;
    private IMdsalApiManager mdsalManager;
    private IdManagerService idManager;

    public RouterDpnChangeListener (final DataBroker db) {
        super(DpnVpninterfacesList.class);
        dataBroker = db;
        registerListener(db);
    }

    void setDefaultProgrammer(SNATDefaultRouteProgrammer defaultRouteProgrammer) {
        this.defaultRouteProgrammer = defaultRouteProgrammer;
    }

    void setNaptSwitchHA(NaptSwitchHA switchHA) {
        naptSwitchHA = switchHA;
    }

    void setMdsalManager(IMdsalApiManager mdsalManager) {
        this.mdsalManager = mdsalManager;
    }

    public void setIdManager(IdManagerService idManager) {
        this.idManager = idManager;
    }

    @Override
    public void close() throws Exception {
        if (listenerRegistration != null) {
            try {
                listenerRegistration.close();
            } catch (final Exception e) {
                LOG.error("Error when cleaning up DataChangeListener.", e);
            }
            listenerRegistration = null;
        }
        LOG.info("Router ports Listener Closed");
    }

    private void registerListener(final DataBroker db) {
        try {
            listenerRegistration = db.registerDataChangeListener(LogicalDatastoreType.CONFIGURATION,
                    getWildCardPath(), RouterDpnChangeListener.this, AsyncDataBroker.DataChangeScope.SUBTREE);
        } catch (final Exception e) {
            LOG.error("RouterPorts DataChange listener registration fail!", e);
            throw new IllegalStateException("RouterPorts Listener registration Listener failed.", e);
        }
    }

    private InstanceIdentifier<DpnVpninterfacesList> getWildCardPath() {
        return InstanceIdentifier.create(NeutronRouterDpns.class).child(RouterDpnList.class).child(DpnVpninterfacesList.class);
    }

    @Override
    protected void add(final InstanceIdentifier<DpnVpninterfacesList> identifier, final DpnVpninterfacesList dpnInfo) {
        LOG.trace("Add event - key: {}, value: {}", identifier, dpnInfo);
        final String routerId = identifier.firstKeyOf(RouterDpnList.class).getRouterId();
        BigInteger dpnId = dpnInfo.getDpnId();
        //check router is associated to external network
        InstanceIdentifier<Routers> id = NatUtil.buildRouterIdentifier(routerId);
        Optional<Routers> routerData = NatUtil.read(dataBroker, LogicalDatastoreType.CONFIGURATION, id);
        if (routerData.isPresent()) {
            Uuid networkId = routerData.get().getNetworkId();
            if(networkId != null) {
                LOG.debug("Router {} is associated with ext nw {}", routerId, networkId);
                Uuid vpnName = NatUtil.getVpnForRouter(dataBroker,routerId);
                Long vpnId;
                if (vpnName == null) {
                    LOG.debug("Internal vpn associated to router {}",routerId);
                    vpnId = NatUtil.getVpnId(dataBroker,routerId);
                    if (vpnId == NatConstants.INVALID_ID) {
                        LOG.error("Invalid vpnId returned for routerName {}",routerId);
                        return;
                    }
                    LOG.debug("Retrieved vpnId {} for router {}",vpnId,routerId);
                    //Install default entry in FIB to SNAT table
                    LOG.debug("Installing default route in FIB on dpn {} for router {} with vpn {}...", dpnId,routerId,vpnId);
                    defaultRouteProgrammer.installDefNATRouteInDPN(dpnId, vpnId);
                } else {
                    LOG.debug("External BGP vpn associated to router {}",routerId);
                    vpnId = NatUtil.getVpnId(dataBroker, vpnName.getValue());
                    if (vpnId == NatConstants.INVALID_ID) {
                        LOG.error("Invalid vpnId returned for routerName {}", routerId);
                        return;
                    }
                    Long routId = NatUtil.getVpnId(dataBroker, routerId);
                    if (routId == NatConstants.INVALID_ID) {
                        LOG.error("Invalid routId returned for routerName {}",routerId);
                        return;
                    }
                    LOG.debug("Retrieved vpnId {} for router {}",vpnId,routerId);
                    //Install default entry in FIB to SNAT table
                    LOG.debug("Installing default route in FIB on dpn {} for routerId {} with vpnId {}...", dpnId,routerId,vpnId);
                    defaultRouteProgrammer.installDefNATRouteInDPN(dpnId, vpnId, routId);
                }

                if (routerData.get().isEnableSnat()) {
                    LOG.info("SNAT enabled for router {}", routerId);
                    handleSNATForDPN(dpnId, routerId ,vpnId);
                } else {
                    LOG.info("SNAT is not enabled for router {} to handle addDPN event {}", routerId, dpnId);
                }
            }
        } else {
            LOG.debug("Router {} is not associated with External network", routerId);
        }
    }

    @Override
    protected void remove(InstanceIdentifier<DpnVpninterfacesList> identifier, DpnVpninterfacesList dpnInfo) {
        LOG.trace("Remove event - key: {}, value: {}", identifier, dpnInfo);
        final String routerId = identifier.firstKeyOf(RouterDpnList.class).getRouterId();
        BigInteger dpnId = dpnInfo.getDpnId();
        //check router is associated to external network
        InstanceIdentifier<Routers> id = NatUtil.buildRouterIdentifier(routerId);
        Optional<Routers> routerData = NatUtil.read(dataBroker, LogicalDatastoreType.CONFIGURATION, id);
        if (routerData.isPresent()) {
            Uuid networkId = routerData.get().getNetworkId();
            if (networkId != null) {
                LOG.debug("Router {} is associated with ext nw {}", routerId, networkId);
                Uuid vpnName = NatUtil.getVpnForRouter(dataBroker, routerId);
                Long vpnId;
                if (vpnName == null) {
                    LOG.debug("Internal vpn associated to router {}", routerId);
                    vpnId = NatUtil.getVpnId(dataBroker, routerId);
                    if (vpnId == NatConstants.INVALID_ID) {
                        LOG.error("Invalid vpnId returned for routerName {}", routerId);
                        return;
                    }
                    LOG.debug("Retrieved vpnId {} for router {}",vpnId,routerId);
                    //Remove default entry in FIB
                    LOG.debug("Removing default route in FIB on dpn {} for vpn {} ...", dpnId, vpnName);
                    defaultRouteProgrammer.removeDefNATRouteInDPN(dpnId, vpnId);
                } else {
                    LOG.debug("External vpn associated to router {}", routerId);
                    vpnId = NatUtil.getVpnId(dataBroker, vpnName.getValue());
                    if (vpnId == NatConstants.INVALID_ID) {
                        LOG.error("Invalid vpnId returned for routerName {}", routerId);
                        return;
                    }
                    Long routId = NatUtil.getVpnId(dataBroker, routerId);
                    if (routId == NatConstants.INVALID_ID) {
                        LOG.error("Invalid routId returned for routerName {}",routerId);
                        return;
                    }
                    LOG.debug("Retrieved vpnId {} for router {}",vpnId,routerId);
                    //Remove default entry in FIB
                    LOG.debug("Removing default route in FIB on dpn {} for vpn {} ...", dpnId, vpnName);
                    defaultRouteProgrammer.removeDefNATRouteInDPN(dpnId,vpnId,routId);
                }

                if (routerData.get().isEnableSnat()) {
                    LOG.info("SNAT enabled for router {}", routerId);
                    removeSNATFromDPN(dpnId, routerId, vpnId);
                } else {
                    LOG.info("SNAT is not enabled for router {} to handle removeDPN event {}", routerId, dpnId);
                }
            }
        }
    }

    @Override
    protected void update(InstanceIdentifier<DpnVpninterfacesList> identifier, DpnVpninterfacesList original, DpnVpninterfacesList update) {
        LOG.trace("Update event - key: {}, original: {}, update: {}", identifier, original, update);
    }
    void handleSNATForDPN(BigInteger dpnId, String routerName,Long routerVpnId) {
        //Check if primary and secondary switch are selected, If not select the role
        //Install select group to NAPT switch
        //Install default miss entry to NAPT switch
        BigInteger naptSwitch;
        try {
            Long routerId = NatUtil.getVpnId(dataBroker, routerName);
            if (routerId == NatConstants.INVALID_ID) {
                LOG.error("Invalid routerId returned for routerName {}", routerName);
                return;
            }
            BigInteger naptId = NatUtil.getPrimaryNaptfromRouterId(dataBroker, routerId);
            if (naptId == null || naptId.equals(BigInteger.ZERO)) {
                LOG.debug("No NaptSwitch is selected for router {}", routerName);

                naptSwitch = dpnId;
                boolean naptstatus = naptSwitchHA.updateNaptSwitch(routerName, naptSwitch);
                if (!naptstatus) {
                    LOG.error("Failed to update newNaptSwitch {} for routername {}", naptSwitch, routerName);
                    return;
                }
                LOG.debug("Switch {} is elected as NaptSwitch for router {}", dpnId, routerName);

                //installing group
                List<BucketInfo> bucketInfo = naptSwitchHA.handleGroupInPrimarySwitch();
                naptSwitchHA.installSnatGroupEntry(naptSwitch, bucketInfo, routerName);

                naptSwitchHA.installSnatFlows(routerName, routerId, naptSwitch, routerVpnId);

            } else {
                LOG.debug("Napt switch with Id {} is already elected for router {}", naptId, routerName);
                naptSwitch = naptId;

                //installing group
                List<BucketInfo> bucketInfo = naptSwitchHA.handleGroupInNeighborSwitches(dpnId, routerName, naptSwitch);
                if (bucketInfo == null) {
                    LOG.debug("Failed to populate bucketInfo for dpnId {} routername {} naptSwitch {}", dpnId, routerName,
                            naptSwitch);
                    return;
                }
                naptSwitchHA.installSnatGroupEntry(dpnId, bucketInfo, routerName);
            }
            // Install miss entry (table 26) pointing to group
            long groupId = NatUtil.createGroupId(NatUtil.getGroupIdKey(routerName), idManager);
            FlowEntity flowEntity = naptSwitchHA.buildSnatFlowEntity(dpnId, routerName, groupId, routerVpnId, NatConstants.ADD_FLOW);
            if (flowEntity == null) {
                LOG.debug("Failed to populate flowentity for router {} with dpnId {} groupId {}", routerName, dpnId, groupId);
                return;
            }
            LOG.debug("Successfully installed flow for dpnId {} router {} group {}", dpnId, routerName, groupId);
            mdsalManager.installFlow(flowEntity);
        } catch (Exception ex) {
            LOG.error("Exception in handleSNATForDPN method : {}", ex);
        }
    }

    void removeSNATFromDPN(BigInteger dpnId, String routerName, long routerVpnId) {
        //irrespective of naptswitch or non-naptswitch, SNAT default miss entry need to be removed
        //remove miss entry to NAPT switch
        //if naptswitch elect new switch and install Snat flows and remove those flows in oldnaptswitch

        Long routerId = NatUtil.getVpnId(dataBroker, routerName);
        if (routerId == NatConstants.INVALID_ID) {
            LOG.error("Invalid routerId returned for routerName {}",routerName);
            return;
        }
        BigInteger naptSwitch = NatUtil.getPrimaryNaptfromRouterId(dataBroker, routerId);
        if (naptSwitch == null || naptSwitch.equals(BigInteger.ZERO)) {
            LOG.debug("No naptSwitch is selected for router {}", routerName);
            return;
        }
        try {
            boolean naptStatus = naptSwitchHA.isNaptSwitchDown(routerName,dpnId,naptSwitch,routerVpnId);
            if (!naptStatus) {
                LOG.debug("NaptSwitchDown: Switch with DpnId {} is not naptSwitch for router {}",
                        dpnId, routerName);
            } else {
                naptSwitchHA.removeSnatFlowsInOldNaptSwitch(routerName, naptSwitch);
            }
        } catch (Exception ex) {
            LOG.debug("Exception while handling naptSwitch down for router {} : {}",routerName,ex);
        }

        long groupId = NatUtil.createGroupId(NatUtil.getGroupIdKey(routerName), idManager);
        FlowEntity flowEntity = null;
        try {
            flowEntity = naptSwitchHA.buildSnatFlowEntity(dpnId, routerName, groupId, routerVpnId, NatConstants.DEL_FLOW);
            if (flowEntity == null) {
                LOG.debug("Failed to populate flowentity for router {} with dpnId {} groupIs {}",routerName,dpnId,groupId);
                return;
            }
            LOG.debug("NAT Service : Removing default SNAT miss entry flow entity {}",flowEntity);
            mdsalManager.removeFlow(flowEntity);

        } catch (Exception ex) {
            LOG.debug("NAT Service : Failed to remove default SNAT miss entry flow entity {} : {}",flowEntity,ex);
            return;
        }
        LOG.debug("NAT Service : Removed default SNAT miss entry flow for dpnID {} with routername {}", dpnId, routerName);

        //remove group
        GroupEntity groupEntity = null;
        try {
            groupEntity = MDSALUtil.buildGroupEntity(dpnId, groupId, routerName,
                    GroupTypes.GroupAll, null);
            LOG.info("NAT Service : Removing NAPT GroupEntity:{}", groupEntity);
            mdsalManager.removeGroup(groupEntity);
        } catch (Exception ex) {
            LOG.debug("NAT Service : Failed to remove group entity {} : {}",groupEntity,ex);
            return;
        }
        LOG.debug("NAT Service : Removed default SNAT miss entry flow for dpnID {} with routerName {}", dpnId, routerName);
    }
}

/*
 * Copyright (c) 2016 Ericsson India Global Services Pvt Ltd. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.vpnservice.elan.internal;

import java.math.BigInteger;

import org.opendaylight.controller.liblldp.NetUtils;
import org.opendaylight.controller.md.sal.binding.api.DataBroker;
import org.opendaylight.controller.md.sal.common.api.data.LogicalDatastoreType;
import org.opendaylight.vpnservice.elan.l2gw.utils.ElanL2GatewayUtils;
import org.opendaylight.vpnservice.elan.utils.ElanConstants;
import org.opendaylight.vpnservice.elan.utils.ElanUtils;
import org.opendaylight.vpnservice.interfacemgr.globals.InterfaceInfo;
import org.opendaylight.vpnservice.interfacemgr.interfaces.IInterfaceManager;
import org.opendaylight.vpnservice.mdsalutil.MDSALUtil;
import org.opendaylight.vpnservice.mdsalutil.MetaDataUtil;
import org.opendaylight.vpnservice.mdsalutil.NWUtil;
import org.opendaylight.vpnservice.mdsalutil.packet.Ethernet;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.yang.types.rev130715.PhysAddress;
import org.opendaylight.yang.gen.v1.urn.opendaylight.packet.service.rev130709.NoMatch;
import org.opendaylight.yang.gen.v1.urn.opendaylight.packet.service.rev130709.PacketInReason;
import org.opendaylight.yang.gen.v1.urn.opendaylight.packet.service.rev130709.PacketProcessingListener;
import org.opendaylight.yang.gen.v1.urn.opendaylight.packet.service.rev130709.PacketReceived;
import org.opendaylight.yang.gen.v1.urn.opendaylight.vpnservice.elan.rev150602.elan.instances.ElanInstance;
import org.opendaylight.yang.gen.v1.urn.opendaylight.vpnservice.elan.rev150602.elan.tag.name.map.ElanTagName;
import org.opendaylight.yang.gen.v1.urn.opendaylight.vpnservice.elan.rev150602.forwarding.entries.MacEntry;
import org.opendaylight.yang.gen.v1.urn.opendaylight.vpnservice.elan.rev150602.forwarding.entries.MacEntryBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.vpnservice.elan.rev150602.forwarding.entries.MacEntryKey;
import org.opendaylight.yang.gen.v1.urn.opendaylight.vpnservice.interfacemgr.meta.rev151007._if.indexes._interface.map.IfIndexInterface;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;

import java.util.Arrays;

@SuppressWarnings("deprecation")
public class ElanPacketInHandler implements PacketProcessingListener {

    private final DataBroker broker;
    private IInterfaceManager interfaceManager;

    private static final Logger logger = LoggerFactory.getLogger(ElanPacketInHandler.class);

    public ElanPacketInHandler(DataBroker dataBroker) {
        broker = dataBroker;
    }

    public void setInterfaceManager(IInterfaceManager interfaceManager) {
        this.interfaceManager = interfaceManager;
    }

    @Override
    public void onPacketReceived(PacketReceived notification) {
        Class<? extends PacketInReason>  pktInReason =  notification.getPacketInReason();
        short tableId = notification.getTableId().getValue();
        if(pktInReason == NoMatch.class && tableId == ElanConstants.ELAN_SMAC_TABLE) {
            try {
                byte[] data = notification.getPayload();
                Ethernet res = new Ethernet();

                res.deserialize(data, 0, data.length * NetUtils.NumBitsInAByte);

                byte[] srcMac = res.getSourceMACAddress();
                String macAddress = NWUtil.toStringMacAddress(srcMac);
                PhysAddress physAddress = new PhysAddress(macAddress);
                BigInteger metadata = notification.getMatch().getMetadata().getMetadata();
                long elanTag = MetaDataUtil.getElanTagFromMetadata(metadata);

                long portTag = MetaDataUtil.getLportFromMetadata(metadata).intValue();

                Optional<IfIndexInterface> interfaceInfoOp = ElanUtils.getInterfaceInfoByInterfaceTag(portTag);
                if (!interfaceInfoOp.isPresent()) {
                    logger.warn("There is no interface for given portTag {}", portTag);
                    return;
                }
                String interfaceName = interfaceInfoOp.get().getInterfaceName();
                ElanTagName elanTagName = ElanUtils.getElanInfoByElanTag(elanTag);
                if (elanTagName == null) {
                    logger.warn("not able to find elanTagName in elan-tag-name-map for elan tag {}", elanTag);
                    return;
                }
                String elanName = elanTagName.getName();
                MacEntry macEntry = ElanUtils.getInterfaceMacEntriesOperationalDataPath(interfaceName, physAddress);
                if(macEntry != null && macEntry.getInterface() == interfaceName) {
                    BigInteger macTimeStamp = macEntry.getControllerLearnedForwardingEntryTimestamp();
                    if (System.currentTimeMillis() > macTimeStamp.longValue()+2000) {
                        /*
                         * Protection time expired. Even though the MAC has been learnt (it is in the cache)
                         * the packets are punted to controller. Which means, the the flows were not successfully
                         * created in the DPN, but the MAC entry has been added successfully in the cache.
                         *
                         * So, the cache has to be cleared and the flows and cache should be recreated (clearing
                         * of cache is required so that the timestamp is updated).
                         */
                        InstanceIdentifier<MacEntry> macEntryId =  ElanUtils.getInterfaceMacEntriesIdentifierOperationalDataPath(interfaceName, physAddress);
                        ElanUtils.delete(broker, LogicalDatastoreType.OPERATIONAL, macEntryId);
                    } else {
                        // Protection time running. Ignore packets for 2 seconds
                        return;
                    }
                } else if(macEntry != null) {
                    // MAC address has moved. Overwrite the mapping and replace MAC flows
                    long macTimeStamp = macEntry.getControllerLearnedForwardingEntryTimestamp().longValue();
                    if (System.currentTimeMillis() > macTimeStamp+1000) {

                        InstanceIdentifier<MacEntry> macEntryId =  ElanUtils.getInterfaceMacEntriesIdentifierOperationalDataPath(interfaceName, physAddress);
                        ElanUtils.delete(broker, LogicalDatastoreType.OPERATIONAL, macEntryId);
                        tryAndRemoveInvalidMacEntry(elanName, macEntry);
                    } else {
                        // New FEs flood their packets on all interfaces. This can lead
                        // to many contradicting packet_ins. Ignore all packets received
                        // within 1s after the first packet_in
                        return;
                    }
                }
                BigInteger timeStamp = new BigInteger(String.valueOf((long)System.currentTimeMillis()));
                macEntry = new MacEntryBuilder().setInterface(interfaceName).setMacAddress(physAddress).setKey(new MacEntryKey(physAddress)).setControllerLearnedForwardingEntryTimestamp(timeStamp).setIsStaticAddress(false).build();
                InstanceIdentifier<MacEntry> macEntryId = ElanUtils.getInterfaceMacEntriesIdentifierOperationalDataPath(interfaceName, physAddress);
                MDSALUtil.syncWrite(broker, LogicalDatastoreType.OPERATIONAL, macEntryId, macEntry);
                InstanceIdentifier<MacEntry> elanMacEntryId = ElanUtils.getMacEntryOperationalDataPath(elanName, physAddress);
                MDSALUtil.syncWrite(broker, LogicalDatastoreType.OPERATIONAL, elanMacEntryId, macEntry);
                ElanInstance elanInstance = ElanUtils.getElanInstanceByName(elanName);
                ElanUtils.setupMacFlows(elanInstance, interfaceManager.getInterfaceInfo(interfaceName), elanInstance.getMacTimeout(), macAddress);

                BigInteger dpId = interfaceManager.getDpnForInterface(interfaceName);
                ElanL2GatewayUtils.scheduleAddDpnMacInExtDevices(elanInstance.getElanInstanceName(), dpId,
                        Arrays.asList(physAddress));
            } catch (Exception e) {
                logger.trace("Failed to decode packet: {}", e);
            }
        }

    }


    /*
 * Though this method is a little costlier because it uses try-catch construct, it is used
 * only in rare scenarios like MAC movement or invalid Static MAC having been added on a
 * wrong ELAN.
 */
    private void tryAndRemoveInvalidMacEntry(String elanName, MacEntry macEntry) {
        ElanInstance elanInfo = ElanUtils.getElanInstanceByName(elanName);
        if (elanInfo == null) {
            logger.warn(String.format("MAC %s is been added (either statically or dynamically) for an invalid Elan %s. "
                    + "Manual cleanup may be necessary", macEntry.getMacAddress(), elanName));
            return;
        }

        InterfaceInfo oldInterfaceLport = interfaceManager.getInterfaceInfo(macEntry.getInterface());
        if (oldInterfaceLport == null) {
            logger.warn(String.format("MAC %s is been added (either statically or dynamically) on an invalid Logical Port %s. "
                    + "Manual cleanup may be necessary", macEntry.getMacAddress(), macEntry.getInterface()));
            return;
        }
        ElanUtils.deleteMacFlows(elanInfo, oldInterfaceLport, macEntry);
        ElanL2GatewayUtils.removeMacsFromElanExternalDevices(elanInfo, Arrays.asList(macEntry.getMacAddress()));
    }

}

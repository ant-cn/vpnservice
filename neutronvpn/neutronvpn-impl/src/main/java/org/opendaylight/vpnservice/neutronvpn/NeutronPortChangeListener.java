/*
 * Copyright (c) 2015 - 2016 Ericsson India Global Services Pvt Ltd. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.vpnservice.neutronvpn;


import com.google.common.base.Optional;

import org.opendaylight.controller.md.sal.binding.api.DataBroker;
import org.opendaylight.controller.md.sal.binding.api.DataChangeListener;
import org.opendaylight.controller.md.sal.binding.api.NotificationPublishService;
import org.opendaylight.controller.md.sal.binding.api.NotificationService;
import org.opendaylight.controller.md.sal.common.api.data.AsyncDataBroker.DataChangeScope;
import org.opendaylight.controller.md.sal.common.api.data.LogicalDatastoreType;
import org.opendaylight.vpnservice.mdsalutil.AbstractDataChangeListener;
import org.opendaylight.vpnservice.mdsalutil.MDSALUtil;
import org.opendaylight.vpnservice.neutronvpn.api.utils.NeutronUtils;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.iana._if.type.rev140508.L2vlan;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.interfaces.rev140508.interfaces.Interface;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.interfaces.rev140508.interfaces.InterfaceBuilder;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.yang.types.rev130715.PhysAddress;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.yang.types.rev130715.Uuid;
import org.opendaylight.yang.gen.v1.urn.opendaylight.neutron.ports.rev150712.port.attributes.FixedIps;
import org.opendaylight.yang.gen.v1.urn.opendaylight.neutron.ports.rev150712.ports.attributes.Ports;
import org.opendaylight.yang.gen.v1.urn.opendaylight.neutron.ports.rev150712.ports.attributes.ports.Port;
import org.opendaylight.yang.gen.v1.urn.opendaylight.neutron.rev150712.Neutron;
import org.opendaylight.yang.gen.v1.urn.opendaylight.vpnservice.elan.rev150602.ElanInterfaces;
import org.opendaylight.yang.gen.v1.urn.opendaylight.vpnservice.elan.rev150602.elan.interfaces.ElanInterface;
import org.opendaylight.yang.gen.v1.urn.opendaylight.vpnservice.elan.rev150602.elan.interfaces.ElanInterfaceBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.vpnservice.elan.rev150602.elan.interfaces.ElanInterfaceKey;
import org.opendaylight.yang.gen.v1.urn.opendaylight.vpnservice.interfacemgr.rev150331.IfL2vlan;
import org.opendaylight.yang.gen.v1.urn.opendaylight.vpnservice.interfacemgr.rev150331.IfL2vlanBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.vpnservice.interfacemgr.rev150331.ParentRefs;
import org.opendaylight.yang.gen.v1.urn.opendaylight.vpnservice.interfacemgr.rev150331.ParentRefsBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.vpnservice.lockmanager.rev150819.LockManagerService;
import org.opendaylight.yang.gen.v1.urn.opendaylight.vpnservice.neutronvpn.rev150602.PortAddedToSubnetBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.vpnservice.neutronvpn.rev150602.PortRemovedFromSubnetBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.vpnservice.neutronvpn.rev150602.neutron.port.data
        .PortFixedipToPortNameBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.vpnservice.neutronvpn.rev150602.subnetmaps.Subnetmap;
import org.opendaylight.yangtools.concepts.ListenerRegistration;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;


public class NeutronPortChangeListener extends AbstractDataChangeListener<Port> implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(NeutronPortChangeListener.class);

    private ListenerRegistration<DataChangeListener> listenerRegistration;
    private final DataBroker broker;
    private NeutronvpnManager nvpnManager;
    private LockManagerService lockManager;
    private NotificationPublishService notificationPublishService;
    private NotificationService notificationService;


    public NeutronPortChangeListener(final DataBroker db, NeutronvpnManager nVpnMgr,NotificationPublishService notiPublishService, NotificationService notiService) {
        super(Port.class);
        broker = db;
        nvpnManager = nVpnMgr;
        notificationPublishService = notiPublishService;
        notificationService = notiService;
        registerListener(db);
    }

    public void setLockManager(LockManagerService lockManager) {
        this.lockManager = lockManager;
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
        LOG.info("N_Port listener Closed");
    }


    private void registerListener(final DataBroker db) {
        try {
            listenerRegistration = db.registerDataChangeListener(LogicalDatastoreType.CONFIGURATION,
                    InstanceIdentifier.create(Neutron.class).child(Ports.class).child(Port.class),
                    NeutronPortChangeListener.this, DataChangeScope.SUBTREE);
        } catch (final Exception e) {
            LOG.error("Neutron Manager Port DataChange listener registration fail!", e);
            throw new IllegalStateException("Neutron Manager Port DataChange listener registration failed.", e);
        }
    }

    @Override
    protected void add(InstanceIdentifier<Port> identifier, Port input) {
        if (LOG.isTraceEnabled()) {
            LOG.trace("Adding Port : key: " + identifier + ", value=" + input);
        }
        handleNeutronPortCreated(input);

    }

    @Override
    protected void remove(InstanceIdentifier<Port> identifier, Port input) {
        if (LOG.isTraceEnabled()) {
            LOG.trace("Removing Port : key: " + identifier + ", value=" + input);
        }
        handleNeutronPortDeleted(input);

    }

    @Override
    protected void update(InstanceIdentifier<Port> identifier, Port original, Port update) {
        if (LOG.isTraceEnabled()) {
            LOG.trace("Updating Port : key: " + identifier + ", original value=" + original + ", update value=" +
                    update);
        }
        List<FixedIps> oldIPs = (original.getFixedIps() != null) ? original.getFixedIps() : new ArrayList<FixedIps>();
        List<FixedIps> newIPs = (update.getFixedIps() != null) ? update.getFixedIps() : new ArrayList<FixedIps>();

        if (!oldIPs.equals(newIPs)) {
            Iterator<FixedIps> iterator = newIPs.iterator();
            while (iterator.hasNext()) {
                FixedIps ip = iterator.next();
                if (oldIPs.remove(ip)) {
                    iterator.remove();
                }
            }
            handleNeutronPortUpdated(original, update);
        }
    }

    private void handleNeutronPortCreated(Port port) {
        if (!NeutronUtils.isPortVnicTypeNormal(port)) {
            LOG.info("Port {} is not a NORMAL VNIC Type port; OF Port interfaces are not created",
                    port.getUuid().getValue());
            return;
        }
        LOG.info("Of-port-interface creation");
        // Create of-port interface for this neutron port
        String portInterfaceName = createOfPortInterface(port);
        LOG.debug("Creating ELAN Interface");
        createElanInterface(port, portInterfaceName);
        LOG.debug("Add port to subnet");
        // add port to local Subnets DS
        Uuid vpnId = addPortToSubnets(port);

        if (vpnId != null) {
            // create vpn-interface on this neutron port
            LOG.debug("Adding VPN Interface");
            nvpnManager.createVpnInterface(vpnId, port);
            Uuid routerId = NeutronvpnUtils.getVpnMap(broker, vpnId).getRouterId();
            if(routerId != null) {
                nvpnManager.addToNeutronRouterInterfacesMap(routerId, port.getUuid().getValue());
            }
        }
    }

    private void handleNeutronPortDeleted(Port port) {
        LOG.debug("Of-port-interface removal");
        LOG.debug("Remove port from subnet");
        // remove port from local Subnets DS
        Uuid vpnId = removePortFromSubnets(port);

        if (vpnId != null) {
            // remove vpn-interface for this neutron port
            LOG.debug("removing VPN Interface");
            nvpnManager.deleteVpnInterface(port);
        }
        // Remove of-port interface for this neutron port
        // ELAN interface is also implicitly deleted as part of this operation
        deleteOfPortInterface(port);

        Uuid routerId = NeutronvpnUtils.getVpnMap(broker, vpnId).getRouterId();
        if(routerId != null) {
            nvpnManager.removeFromNeutronRouterInterfacesMap(routerId, port.getUuid().getValue());
        }

    }

    private void handleNeutronPortUpdated(Port portoriginal, Port portupdate) {
        LOG.debug("Add port to subnet");
        Uuid vpnIdup = addPortToSubnets(portupdate);
        Uuid vpnIdor = removePortFromSubnets(portoriginal);

        // add port FixedIP to local Subnets DS
        if (vpnIdup != null) {
            nvpnManager.createVpnInterface(vpnIdup, portupdate);
            Uuid routerId = NeutronvpnUtils.getVpnMap(broker, vpnIdup).getRouterId();
            if(routerId != null) {
                nvpnManager.addToNeutronRouterInterfacesMap(routerId, portupdate.getUuid().getValue());
            }
            if ((vpnIdor != vpnIdup ||
                 !portoriginal.getDeviceOwner().equals("network:router_interface")) &&
                portupdate.getDeviceOwner().equals("network:router_interface")) {
                Set<Uuid> subnetUuids = new HashSet<>();
                for (FixedIps fixedIps : portupdate.getFixedIps()) {
                    subnetUuids.add(fixedIps.getSubnetId());
                }
                for (Uuid subnetUuid : subnetUuids) {
                    nvpnManager.addSubnetToVpn(vpnIdup, subnetUuid);
                }
            }
        }

        // remove port FixedIP from local Subnets DS
        if (vpnIdor != null) {
            nvpnManager.deleteVpnInterface(portoriginal);
            Uuid routerId = NeutronvpnUtils.getVpnMap(broker, vpnIdor).getRouterId();
            if(routerId != null) {
                nvpnManager.removeFromNeutronRouterInterfacesMap(routerId, portoriginal.getUuid().getValue());
            }
            if ((vpnIdor != vpnIdup ||
                 !portupdate.getDeviceOwner().equals("network:router_interface")) &&
                portoriginal.getDeviceOwner().equals("network:router_interface")) {
                Set<Uuid> subnetUuids = new HashSet<>();
                for (FixedIps fixedIps : portoriginal.getFixedIps()) {
                    subnetUuids.add(fixedIps.getSubnetId());
                }
                for (Uuid subnetUuid : subnetUuids) {
                    nvpnManager.removeSubnetFromVpn(vpnIdor, subnetUuid);
                }
            }
        }
    }

    private String createOfPortInterface(Port port) {
        Interface inf = createInterface(port);
        String infName = inf.getName();

        LOG.debug("Creating OFPort Interface {}", infName);
        InstanceIdentifier interfaceIdentifier = NeutronvpnUtils.buildVlanInterfaceIdentifier(infName);
        try {
            Optional<Interface> optionalInf = NeutronvpnUtils.read(broker, LogicalDatastoreType.CONFIGURATION,
                    interfaceIdentifier);
            if (!optionalInf.isPresent()) {
                MDSALUtil.syncWrite(broker, LogicalDatastoreType.CONFIGURATION, interfaceIdentifier, inf);
            } else {
                LOG.error("Interface {} is already present", infName);
            }
        } catch (Exception e) {
            LOG.error("failed to create interface {} due to the exception {} ", infName, e.getMessage());
        }
        return infName;
    }

    private Interface createInterface(Port port) {
        String parentRefName = NeutronvpnUtils.uuidToTapPortName(port.getUuid());;
        String interfaceName = port.getUuid().getValue();
        IfL2vlan.L2vlanMode l2VlanMode = IfL2vlan.L2vlanMode.Trunk;
        InterfaceBuilder interfaceBuilder = new InterfaceBuilder();
        IfL2vlanBuilder ifL2vlanBuilder = new IfL2vlanBuilder();
        ifL2vlanBuilder.setL2vlanMode(l2VlanMode);
        ParentRefsBuilder parentRefsBuilder = new ParentRefsBuilder().setParentInterface(parentRefName);
        interfaceBuilder.setEnabled(true).setName(interfaceName).setType(L2vlan.class).addAugmentation(IfL2vlan
                .class, ifL2vlanBuilder.build()).addAugmentation(ParentRefs.class, parentRefsBuilder.build());
        return interfaceBuilder.build();
    }

    private void deleteOfPortInterface(Port port) {
        String name = port.getUuid().getValue();
        LOG.debug("Removing OFPort Interface {}", name);
        InstanceIdentifier interfaceIdentifier = NeutronvpnUtils.buildVlanInterfaceIdentifier(name);
        try {
            Optional<Interface> optionalInf = NeutronvpnUtils.read(broker, LogicalDatastoreType.CONFIGURATION,
                    interfaceIdentifier);
            if (optionalInf.isPresent()) {
                MDSALUtil.syncDelete(broker, LogicalDatastoreType.CONFIGURATION, interfaceIdentifier);
            } else {
                LOG.error("Interface {} is not present", name);
            }
        } catch (Exception e) {
            LOG.error("Failed to delete interface {} due to the exception {}", name, e.getMessage());
        }
    }

    private void createElanInterface(Port port, String name) {
        String elanInstanceName = port.getNetworkId().getValue();
        List<PhysAddress> physAddresses = new ArrayList<>();
        physAddresses.add(new PhysAddress(String.valueOf(port.getMacAddress().getValue())));

        InstanceIdentifier<ElanInterface> id = InstanceIdentifier.builder(ElanInterfaces.class).child(ElanInterface
                .class, new ElanInterfaceKey(name)).build();
        ElanInterface elanInterface = new ElanInterfaceBuilder().setElanInstanceName(elanInstanceName)
                .setName(name).setStaticMacEntries(physAddresses).setKey(new ElanInterfaceKey(name)).build();
        MDSALUtil.syncWrite(broker, LogicalDatastoreType.CONFIGURATION, id, elanInterface);
        LOG.debug("Creating new ELan Interface {}", elanInterface);
    }

    // adds port to subnet list and creates vpnInterface
    private Uuid addPortToSubnets(Port port) {
        Uuid subnetId = null;
        Uuid vpnId = null;
        Subnetmap subnetmap = null;
        String infName = port.getUuid().getValue();
        boolean isLockAcquired = false;
        String lockName = port.getUuid().getValue();

        // find the subnet to which this port is associated
        FixedIps ip = port.getFixedIps().get(0);
        String ipValue = ip.getIpAddress().getIpv4Address().getValue();
        InstanceIdentifier id = NeutronvpnUtils.buildFixedIpToPortNameIdentifier(ipValue);
        PortFixedipToPortNameBuilder builder = new PortFixedipToPortNameBuilder().setPortFixedip(ipValue)
                .setPortName(infName);
        MDSALUtil.syncWrite(broker, LogicalDatastoreType.CONFIGURATION, id, builder.build());
        LOG.debug("fixedIp-name map for neutron port with fixedIp: {}, name: {} added to NeutronPortData DS",
                ipValue, infName);
        subnetId = ip.getSubnetId();
        subnetmap = nvpnManager.updateSubnetNode(subnetId, null, null, null, null, null, port.getUuid());
        if (subnetmap != null) {
            vpnId = subnetmap.getVpnId();
        }
        if(vpnId != null) {
            try {
                isLockAcquired = NeutronvpnUtils.lock(lockManager, lockName);
                checkAndPublishPortAddNotification(subnetmap.getSubnetIp(), subnetId, port.getUuid());
                LOG.debug("Port added to subnet notification sent");
            } catch (Exception e) {
                LOG.error("Port added to subnet notification failed", e);
            } finally {
                if (isLockAcquired) {
                    NeutronvpnUtils.unlock(lockManager, lockName);
                }
            }
            if (port.getDeviceOwner().equals("network:router_interface")) {
                Set<Uuid> subnetUuids = new HashSet<>();
                for (FixedIps fixedIps : port.getFixedIps()) {
                    subnetUuids.add(fixedIps.getSubnetId());
                }
                for (Uuid subnetUuid : subnetUuids) {
                    nvpnManager.addSubnetToVpn(vpnId, subnetUuid);
                }
            }
        }
        return vpnId;
    }

    private Uuid removePortFromSubnets(Port port) {
        Uuid subnetId = null;
        Uuid vpnId = null;
        Subnetmap subnetmap = null;
        boolean isLockAcquired = false;
        String lockName = port.getUuid().getValue();

        // find the subnet to which this port is associated
        FixedIps ip = port.getFixedIps().get(0);
        String ipValue = ip.getIpAddress().getIpv4Address().getValue();
        InstanceIdentifier id = NeutronvpnUtils.buildFixedIpToPortNameIdentifier(ipValue);
        MDSALUtil.syncDelete(broker, LogicalDatastoreType.CONFIGURATION, id);
        LOG.debug("fixedIp-name map for neutron port with fixedIp: {} deleted from NeutronPortData DS", ipValue);
        subnetId = ip.getSubnetId();
        subnetmap = nvpnManager.removeFromSubnetNode(subnetId, null, null, null, port.getUuid());
        if (subnetmap != null) {
            vpnId = subnetmap.getVpnId();
        }
        if(vpnId != null) {
            try {
                isLockAcquired = NeutronvpnUtils.lock(lockManager, lockName);
                checkAndPublishPortRemoveNotification(subnetmap.getSubnetIp(), subnetId, port.getUuid());
                LOG.debug("Port removed from subnet notification sent");
            } catch (Exception e) {
                LOG.error("Port removed from subnet notification failed", e);
            } finally {
                if (isLockAcquired) {
                    NeutronvpnUtils.unlock(lockManager, lockName);
                }
            }
            if (port.getDeviceOwner().equals("network:router_interface")) {
                Set<Uuid> subnetUuids = new HashSet<>();
                for (FixedIps fixedIps : port.getFixedIps()) {
                    subnetUuids.add(fixedIps.getSubnetId());
                }
                for (Uuid subnetUuid : subnetUuids) {
                    nvpnManager.removeSubnetFromVpn(vpnId, subnetUuid);
                }
            }
        }
        return vpnId;
    }

    private void checkAndPublishPortAddNotification(String subnetIp, Uuid subnetId, Uuid portId)throws InterruptedException{
        PortAddedToSubnetBuilder builder = new PortAddedToSubnetBuilder();

        LOG.info("publish notification called");

        builder.setSubnetIp(subnetIp);
        builder.setSubnetId(subnetId);
        builder.setPortId(portId);

        notificationPublishService.putNotification(builder.build());
    }

    private void checkAndPublishPortRemoveNotification(String subnetIp, Uuid subnetId, Uuid portId)throws InterruptedException{
        PortRemovedFromSubnetBuilder builder = new PortRemovedFromSubnetBuilder();

        LOG.info("publish notification called");

        builder.setPortId(portId);
        builder.setSubnetIp(subnetIp);
        builder.setSubnetId(subnetId);

        notificationPublishService.putNotification(builder.build());
    }
}

/*
 * Copyright (c) 2015 - 2016 Ericsson India Global Services Pvt Ltd. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.vpnservice.neutronvpn;


import org.opendaylight.controller.md.sal.binding.api.DataBroker;
import org.opendaylight.controller.md.sal.binding.api.DataChangeListener;
import org.opendaylight.controller.md.sal.common.api.data.AsyncDataBroker.DataChangeScope;
import org.opendaylight.controller.md.sal.common.api.data.LogicalDatastoreType;
import org.opendaylight.vpnservice.mdsalutil.AbstractDataChangeListener;
import org.opendaylight.yang.gen.v1.urn.opendaylight.neutron.networks.rev150712.networks.attributes.Networks;
import org.opendaylight.yang.gen.v1.urn.opendaylight.neutron.networks.rev150712.networks.attributes.networks.Network;
import org.opendaylight.yang.gen.v1.urn.opendaylight.neutron.rev150712.Neutron;
import org.opendaylight.yangtools.concepts.ListenerRegistration;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class NeutronNetworkChangeListener extends AbstractDataChangeListener<Network> implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(NeutronNetworkChangeListener.class);

    private ListenerRegistration<DataChangeListener> listenerRegistration;
    private final DataBroker broker;
    private NeutronvpnManager nvpnManager;


    public NeutronNetworkChangeListener(final DataBroker db, NeutronvpnManager nVpnMgr) {
        super(Network.class);
        broker = db;
        nvpnManager = nVpnMgr;
        registerListener(db);
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
        LOG.info("N_Network listener Closed");
    }


    private void registerListener(final DataBroker db) {
        try {
            listenerRegistration = db.registerDataChangeListener(LogicalDatastoreType.CONFIGURATION,
                    InstanceIdentifier.create(Neutron.class).
                            child(Networks.class).child(Network.class),
                    NeutronNetworkChangeListener.this, DataChangeScope.SUBTREE);
            LOG.info("Neutron Manager Network DataChange listener registration Success!");
        } catch (final Exception e) {
            LOG.error("Neutron Manager Network DataChange listener registration fail!", e);
            throw new IllegalStateException("Neutron Manager Network DataChange listener registration failed.", e);
        }
    }

    @Override
    protected void add(InstanceIdentifier<Network> identifier, Network input) {
        if (LOG.isTraceEnabled()) {
            LOG.trace("Adding Network : key: " + identifier + ", value=" + input);
        }
    }

    @Override
    protected void remove(InstanceIdentifier<Network> identifier, Network input) {
        if (LOG.isTraceEnabled()) {
            LOG.trace("Removing Network : key: " + identifier + ", value=" + input);
        }
    }

    @Override
    protected void update(InstanceIdentifier<Network> identifier, Network original, Network update) {
        if (LOG.isTraceEnabled()) {
            LOG.trace("Updating Network : key: " + identifier + ", original value=" + original + ", update value=" +
                    update);
        }
    }

}
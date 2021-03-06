/*
 * Copyright (c) 2016 Ericsson India Global Services Pvt Ltd. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.vpnservice.elan.l2gw.jobs;

import com.google.common.util.concurrent.ListenableFuture;

import org.opendaylight.controller.md.sal.binding.api.DataBroker;
import org.opendaylight.vpnservice.utils.hwvtep.HwvtepUtils;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.NodeId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * The Class LogicalSwitchDeletedJob.
 */
public class LogicalSwitchDeletedJob implements Callable<List<ListenableFuture<Void>>> {
    private DataBroker broker;

    /** The logical switch name. */
    private String logicalSwitchName;

    /** The physical device. */
    private NodeId hwvtepNodeId;

    private static final Logger LOG = LoggerFactory.getLogger(LogicalSwitchDeletedJob.class);

    public LogicalSwitchDeletedJob(DataBroker broker, NodeId hwvtepNodeId, String logicalSwitchName) {
        this.broker = broker;
        this.hwvtepNodeId = hwvtepNodeId;
        this.logicalSwitchName = logicalSwitchName;
        LOG.debug("created logical switch deleted job for {} on {}", logicalSwitchName, hwvtepNodeId);
    }

    public String getJobKey() {
        return logicalSwitchName;
    }

    /*
     * (non-Javadoc)
     *
     * @see java.util.concurrent.Callable#call()
     */
    @Override
    public List<ListenableFuture<Void>> call() throws Exception {
        try {
            LOG.debug("running logical switch deleted job for {} in {}", logicalSwitchName, hwvtepNodeId);
            List<ListenableFuture<Void>> futures = new ArrayList<>();
            futures.add(HwvtepUtils.deleteLogicalSwitch(broker, hwvtepNodeId, logicalSwitchName));
            return futures;
        } catch (Throwable e) {
            LOG.error("failed to delete ls ", e);
            return null;
        }
    }
}

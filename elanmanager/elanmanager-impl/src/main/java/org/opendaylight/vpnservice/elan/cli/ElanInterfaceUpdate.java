/*
 * Copyright (c) 2016 Ericsson India Global Services Pvt Ltd. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.vpnservice.elan.cli;

import java.math.BigInteger;
import java.util.List;

import org.apache.karaf.shell.console.OsgiCommandSupport;
import org.apache.karaf.shell.commands.Argument;
import org.apache.karaf.shell.commands.Command;
import org.apache.karaf.shell.commands.Option;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.Futures;
import org.opendaylight.elanmanager.api.IElanService;

@Command(scope = "elanInterface", name = "update", description = "updating Elan Interface")
public class ElanInterfaceUpdate extends OsgiCommandSupport {

    @Argument(index = 0, name = "elanName", description = "ELAN-NAME", required = true, multiValued = false)
    private String elanName;
    @Argument(index = 1, name = "interfaceName", description = "InterfaceName", required = true, multiValued = false)
    private String interfaceName;
    @Argument(index = 2, name = "staticMacAddresses", description = "StaticMacAddresses", required = false, multiValued = true)
    private List<String> staticMacAddresses;
    @Argument(index = 3, name = "elanInterfaceDescr", description = "ELAN Interface-Description", required = false, multiValued = false)
    private String elanInterfaceDescr;
    private static final Logger logger = LoggerFactory.getLogger(ElanInterfaceUpdate.class);
    private IElanService elanProvider;

    public void setElanProvider(IElanService elanServiceProvider) {
        this.elanProvider = elanServiceProvider;
    }

    @Override
    protected Object doExecute() {
        try {
            logger.debug("Executing updating ElanInterface command" + "\t" + elanName + "\t" + interfaceName + "\t" + staticMacAddresses + "\t" +
                    elanInterfaceDescr + "\t");
            elanProvider.updateElanInterface(elanName, interfaceName, staticMacAddresses, elanInterfaceDescr);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}


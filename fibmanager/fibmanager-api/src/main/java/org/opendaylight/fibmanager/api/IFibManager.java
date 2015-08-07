/*
 * Copyright (c) 2015 Ericsson India Global Services Pvt Ltd. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.fibmanager.api;

import java.math.BigInteger;

public interface IFibManager {
    void populateFibOnNewDpn(BigInteger dpnId, long vpnId, String rd);
    void cleanUpDpnForVpn(BigInteger dpnId, long vpnId, String rd);
}

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.network.vpc.dao;

import com.cloud.network.vpc.VpcOfferingVO;
import com.cloud.utils.db.GenericDao;
import com.cloud.utils.net.NetUtils;

public interface VpcOfferingDao extends GenericDao<VpcOfferingVO, Long> {
    /**
     * Returns the VPC offering that matches the unique name.
     *
     * @param uniqueName
     *            name
     * @return VpcOfferingVO
     */
    VpcOfferingVO findByUniqueName(String uniqueName);

    NetUtils.InternetProtocol getVpcOfferingInternetProtocol(long offeringId);

    boolean isIpv6Supported(long offeringId);

    boolean isRoutedVpc(long offeringId);
}

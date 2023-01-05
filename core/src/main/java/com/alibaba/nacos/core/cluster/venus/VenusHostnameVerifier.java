package com.alibaba.nacos.core.cluster.venus;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;

public class VenusHostnameVerifier implements HostnameVerifier {

    @Override
    public boolean verify(String hostname, SSLSession session) {
        return true;
    }


}

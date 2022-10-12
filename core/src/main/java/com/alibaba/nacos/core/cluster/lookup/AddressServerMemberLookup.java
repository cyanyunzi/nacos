/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.nacos.core.cluster.lookup;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.common.http.HttpClientBeanHolder;
import com.alibaba.nacos.common.http.client.NacosRestTemplate;
import com.alibaba.nacos.common.utils.ExceptionUtil;
import com.alibaba.nacos.common.utils.StringUtils;
import com.alibaba.nacos.core.cluster.AbstractMemberLookup;
import com.alibaba.nacos.core.cluster.MemberUtil;
import com.alibaba.nacos.core.utils.GenericType;
import com.alibaba.nacos.core.utils.GlobalExecutor;
import com.alibaba.nacos.core.utils.Loggers;
import com.alibaba.nacos.sys.env.EnvUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.*;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;

/**
 * Cluster member addressing mode for the address server.
 *
 * @author <a href="mailto:liaochuntao@live.com">liaochuntao</a>
 */
public class AddressServerMemberLookup extends AbstractMemberLookup {
    
    private final GenericType<String> genericType = new GenericType<String>() { };
    
    public String domainName;
    
    public String addressPort;
    
    public String addressUrl;
    
    public String envIdUrl;
    
    public String addressServerUrl;
    
    private volatile boolean isAddressServerHealth = true;
    
    private int addressServerFailCount = 0;
    
    private int maxFailCount = 12;
    
    private final NacosRestTemplate restTemplate = HttpClientBeanHolder.getNacosRestTemplate(Loggers.CORE);
    
    private volatile boolean shutdown = false;
    
    private static final String HEALTH_CHECK_FAIL_COUNT_PROPERTY = "maxHealthCheckFailCount";
    
    private static final String DEFAULT_HEALTH_CHECK_FAIL_COUNT = "12";
    
    private static final String DEFAULT_SERVER_DOMAIN = "jmenv.tbsite.net";
    
    private static final String DEFAULT_SERVER_POINT = "8080";
    
    private static final int DEFAULT_SERVER_RETRY_TIME = 5;
    
    private static final long DEFAULT_SYNC_TASK_DELAY_MS = 5_000L;
    
    private static final String ADDRESS_SERVER_DOMAIN_ENV = "address_server_domain";
    
    private static final String ADDRESS_SERVER_DOMAIN_PROPERTY = "address.server.domain";
    
    private static final String ADDRESS_SERVER_PORT_ENV = "address_server_port";
    
    private static final String ADDRESS_SERVER_PORT_PROPERTY = "address.server.port";
    
    private static final String ADDRESS_SERVER_URL_ENV = "address_server_url";
    
    private static final String ADDRESS_SERVER_URL_PROPERTY = "address.server.url";
    
    private static final String ADDRESS_SERVER_RETRY_PROPERTY = "nacos.core.address-server.retry";

    private static final String ADDRESS_HTTP_PROTOCOL = "address.http.protocol";
    private static final int HTTP_200 = 200;


    @Override
    public void doStart() throws NacosException {
        this.maxFailCount = Integer.parseInt(EnvUtil.getProperty(HEALTH_CHECK_FAIL_COUNT_PROPERTY, DEFAULT_HEALTH_CHECK_FAIL_COUNT));
        initAddressSys();
        run();
    }
    
    @Override
    public boolean useAddressServer() {
        return true;
    }
    
    private void initAddressSys() {
        String envDomainName = System.getenv(ADDRESS_SERVER_DOMAIN_ENV);
        if (StringUtils.isBlank(envDomainName)) {
            domainName = EnvUtil.getProperty(ADDRESS_SERVER_DOMAIN_PROPERTY, DEFAULT_SERVER_DOMAIN);
        } else {
            domainName = envDomainName;
        }
        String envAddressPort = System.getenv(ADDRESS_SERVER_PORT_ENV);
        if (StringUtils.isBlank(envAddressPort)) {
            addressPort = EnvUtil.getProperty(ADDRESS_SERVER_PORT_PROPERTY, DEFAULT_SERVER_POINT);
        } else {
            addressPort = envAddressPort;
        }
        String envAddressUrl = System.getenv(ADDRESS_SERVER_URL_ENV);
        if (StringUtils.isBlank(envAddressUrl)) {
            addressUrl = EnvUtil.getProperty(ADDRESS_SERVER_URL_PROPERTY, EnvUtil.getContextPath() + "/" + "serverlist");
        } else {
            addressUrl = envAddressUrl;
        }
        addressServerUrl =  EnvUtil.getProperty(ADDRESS_HTTP_PROTOCOL) + domainName + ":" + addressPort + addressUrl;
        envIdUrl =  EnvUtil.getProperty(ADDRESS_HTTP_PROTOCOL) + domainName + ":" + addressPort + "/env";
        
        Loggers.CORE.info("ServerListService address-server port:" + addressPort);
        Loggers.CORE.info("ADDRESS_SERVER_URL:" + addressServerUrl);
    }
    
    @SuppressWarnings("PMD.UndefineMagicConstantRule")
    private void run() throws NacosException {
        // With the address server, you need to perform a synchronous member node pull at startup
        // Repeat three times, successfully jump out
        boolean success = false;
        Throwable ex = null;
        int maxRetry = EnvUtil.getProperty(ADDRESS_SERVER_RETRY_PROPERTY, Integer.class, DEFAULT_SERVER_RETRY_TIME);
        for (int i = 0; i < maxRetry; i++) {
            try {
                syncFromAddressUrl();
                success = true;
                break;
            } catch (Throwable e) {
                ex = e;
                Loggers.CLUSTER.error("[serverlist] exception, error : {}", ExceptionUtil.getAllExceptionMsg(ex));
            }
        }
        if (!success) {
            throw new NacosException(NacosException.SERVER_ERROR, ex);
        }
        
        GlobalExecutor.scheduleByCommon(new AddressServerSyncTask(), DEFAULT_SYNC_TASK_DELAY_MS);
    }
    
    @Override
    protected void doDestroy() throws NacosException {
        shutdown = true;
    }
    
    @Override
    public Map<String, Object> info() {
        Map<String, Object> info = new HashMap<>(4);
        info.put("addressServerHealth", isAddressServerHealth);
        info.put("addressServerUrl", addressServerUrl);
        info.put("envIdUrl", envIdUrl);
        info.put("addressServerFailCount", addressServerFailCount);
        return info;
    }
    
    private void syncFromAddressUrl() throws Exception {
        RestTemplate myRestTemplate = MyRestTemplate.getMyRestTemplate();
        ResponseEntity<String> responseEntity = myRestTemplate.getForEntity(addressServerUrl, String.class);
        if (responseEntity.getStatusCode().value()==HTTP_200) {
            isAddressServerHealth = true;
            Reader reader = new StringReader(responseEntity.getBody());
            try {
                afterLookup(MemberUtil.readServerConf(EnvUtil.analyzeClusterConf(reader)));
            } catch (Throwable e) {
                Loggers.CLUSTER.error("[serverlist] exception for analyzeClusterConf, error : {}",
                        ExceptionUtil.getAllExceptionMsg(e));
            }
            addressServerFailCount = 0;
        } else {
            addressServerFailCount++;
            if (addressServerFailCount >= maxFailCount) {
                isAddressServerHealth = false;
            }
            Loggers.CLUSTER.error("[serverlist] failed to get serverlist, error code {}", responseEntity.getStatusCode().value());
        }
    }
    
    class AddressServerSyncTask implements Runnable {
        
        @Override
        public void run() {
            if (shutdown) {
                return;
            }
            try {
                syncFromAddressUrl();
            } catch (Throwable ex) {
                addressServerFailCount++;
                if (addressServerFailCount >= maxFailCount) {
                    isAddressServerHealth = false;
                }
                Loggers.CLUSTER.error("[serverlist] exception, error : {}", ExceptionUtil.getAllExceptionMsg(ex));
            } finally {
                GlobalExecutor.scheduleByCommon(this, DEFAULT_SYNC_TASK_DELAY_MS);
            }
        }
    }

    public static class MyRestTemplate {
        private static RestTemplate restTemplate = new RestTemplate(new Ssl());

        public static RestTemplate getMyRestTemplate(){
            return restTemplate;
        }

    }

    public static class SkipHostNameVerifier implements HostnameVerifier {
        @Override
        public boolean verify(String s, SSLSession sslSession) {
            return true;
        }
    }

    public static class SkipX509TrustManager implements X509TrustManager {
        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }
    }

    public static class Ssl extends SimpleClientHttpRequestFactory {
        protected void prepareConnection(HttpURLConnection connection, String httpMethod)
                throws IOException {
            if (connection instanceof HttpsURLConnection) {
                prepareHttpsConnection((HttpsURLConnection) connection);
            }
            super.prepareConnection(connection, httpMethod);
        }

        private void prepareHttpsConnection(HttpsURLConnection connection) {
            connection.setHostnameVerifier(new SkipHostNameVerifier());
            try {
                connection.setSSLSocketFactory(createSslSocketFactory());
            } catch (Exception ex) {
                // Ignore
            }
        }

        private SSLSocketFactory createSslSocketFactory() throws Exception {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new SkipX509TrustManager()},
                    new SecureRandom());
            return context.getSocketFactory();
        }
    }
}

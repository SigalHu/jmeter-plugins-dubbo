/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.ningyu.jmeter.plugin.dubbo.sample;

import com.google.gson.Gson;
import io.github.ningyu.jmeter.plugin.util.ClassUtils;
import io.github.ningyu.jmeter.plugin.util.Constants;
import io.github.ningyu.jmeter.plugin.util.ErrorCode;
import io.github.ningyu.jmeter.plugin.util.JsonUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.utils.ReferenceConfigCache;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.service.GenericService;
import org.apache.jmeter.samplers.AbstractSampler;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.Interruptible;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jorphan.logging.LoggingManager;
import org.apache.log.Logger;

import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * DubboSample
 */
public class DubboSample extends AbstractSampler implements Interruptible {
    
    private static final Logger log = LoggingManager.getLoggerForClass();
    private static final long serialVersionUID = -6794913295411458705L;

    public static ApplicationConfig application = new ApplicationConfig("DubboSample");

    private Map<String, GenericService> serviceMap = new ConcurrentHashMap<>();

    @SuppressWarnings("deprecation")
	@Override
    public SampleResult sample(Entry entry) {
        SampleResult res = new SampleResult();
        res.setSampleLabel(getName());
        //构造请求数据
        res.setSamplerData(getSampleData());
        //调用dubbo
        res.setResponseData(JsonUtils.toJson(callDubbo(res)), StandardCharsets.UTF_8.name());
        //构造响应数据
        res.setDataType(SampleResult.TEXT);
        res.setResponseCodeOK();
        res.setResponseMessageOK();
        res.sampleEnd();
        return res;
    }

    /**
     * Construct request data
     */
    private String getSampleData() {
        Map<String, Object> reqs = new HashMap<>(16);
        reqs.put("registryProtocol", Constants.getRegistryProtocol(this));
        reqs.put("address", Constants.getAddress(this));
        reqs.put("rpcProtocol", Constants.getRpcProtocol(this));
        reqs.put("timeout", Constants.getTimeout(this));
        reqs.put("version", Constants.getVersion(this));
        reqs.put("retries", Constants.getRetries(this));
        reqs.put("cluster", Constants.getCluster(this));
        reqs.put("group", Constants.getGroup(this));
        reqs.put("connections", Constants.getConnections(this));
        reqs.put("loadBalance", Constants.getLoadbalance(this));
        reqs.put("async", Constants.getAsync(this));
        reqs.put("interface", Constants.getInterface(this));
        reqs.put("method", Constants.getMethod(this));
        reqs.put("methodArgs", Constants.getMethodArgs(this));
        reqs.put("attachment", Constants.getAttachmentArgs(this));
        return JsonUtils.toJson(reqs);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object callDubbo(SampleResult res) {
        // This instance is heavy, encapsulating the connection to the registry and the connection to the provider,
        // so please cache yourself, otherwise memory and connection leaks may occur.
        ReferenceConfig reference = new ReferenceConfig();
        // set application
        reference.setApplication(application);
        RegistryConfig registry = null;
        // check address
        String address = Constants.getAddress(this);
        if (StringUtils.isBlank(address)) {
            res.setSuccessful(false);
            return ErrorCode.MISS_ADDRESS.getMessage();
        }
        // get rpc protocol
        String rpcProtocol = Constants.getRpcProtocol(this).replaceAll("://", "");
        // get registry protocol
        String protocol = Constants.getRegistryProtocol(this);
        // get registry group
        String registryGroup = Constants.getRegistryGroup(this);
		switch (protocol) {
		case Constants.REGISTRY_ZOOKEEPER:
			registry = new RegistryConfig();
			registry.setProtocol(Constants.REGISTRY_ZOOKEEPER);
            registry.setGroup(registryGroup);
			registry.setAddress(address);
			reference.setRegistry(registry);
			reference.setProtocol(rpcProtocol);
			break;
		case Constants.REGISTRY_MULTICAST:
			registry = new RegistryConfig();
			registry.setProtocol(Constants.REGISTRY_MULTICAST);
            registry.setGroup(registryGroup);
			registry.setAddress(address);
			reference.setRegistry(registry);
			reference.setProtocol(rpcProtocol);
			break;
		case Constants.REGISTRY_REDIS:
			registry = new RegistryConfig();
			registry.setProtocol(Constants.REGISTRY_REDIS);
            registry.setGroup(registryGroup);
			registry.setAddress(address);
			reference.setRegistry(registry);
			reference.setProtocol(rpcProtocol);
			break;
		case Constants.REGISTRY_SIMPLE:
			registry = new RegistryConfig();
			registry.setAddress(address);
			reference.setRegistry(registry);
			reference.setProtocol(rpcProtocol);
			break;
		default:
			// direct invoke provider
			StringBuilder sb = new StringBuilder();
			sb.append(Constants.getRpcProtocol(this))
                    .append(Constants.getAddress(this))
                    .append("/").append(Constants.getInterface(this));
			//# fix dubbo 2.7.3 Generic bug https://github.com/apache/dubbo/pull/4787
            String version = Constants.getVersion(this);
            if (!StringUtils.isBlank(version)) {
                sb.append(":").append(version);
            }
			log.debug("rpc invoker url : " + sb.toString());
			reference.setUrl(sb.toString());
		}
        try {
		    // set interface
		    String interfaceName = Constants.getInterface(this);
		    if (StringUtils.isBlank(interfaceName)) {
                res.setSuccessful(false);
                return ErrorCode.MISS_INTERFACE.getMessage();
            }
            reference.setInterface(interfaceName);

		    // set retries
            Integer retries = null;
            try {
                if (!StringUtils.isBlank(Constants.getRetries(this))) {
                    retries = Integer.valueOf(Constants.getRetries(this));
                }
            } catch (NumberFormatException e) {
                res.setSuccessful(false);
                return ErrorCode.RETRIES_ERROR.getMessage();
            }
            if (retries != null) {
                reference.setRetries(retries);
            }

            // set cluster
            String cluster = Constants.getCluster(this);
            if (!StringUtils.isBlank(cluster)) {
                reference.setCluster(Constants.getCluster(this));
            }

            // set version
            String version = Constants.getVersion(this);
            if (!StringUtils.isBlank(version)) {
                reference.setVersion(version);
            }

            // set timeout
            Integer timeout = null;
            try {
                if (!StringUtils.isBlank(Constants.getTimeout(this))) {
                    timeout = Integer.valueOf(Constants.getTimeout(this));
                }
            } catch (NumberFormatException e) {
                res.setSuccessful(false);
                return ErrorCode.TIMEOUT_ERROR.getMessage();
            }
            if (timeout != null) {
                reference.setTimeout(timeout);
            }

            // set group
            String group = Constants.getGroup(this);
            if (!StringUtils.isBlank(group)) {
                reference.setGroup(group);
            }

            // set connections
            Integer connections = null;
            try {
                if (!StringUtils.isBlank(Constants.getConnections(this))) {
                    connections = Integer.valueOf(Constants.getConnections(this));
                }
            } catch (NumberFormatException e) {
                res.setSuccessful(false);
                return ErrorCode.CONNECTIONS_ERROR.getMessage();
            }
            if (connections != null) {
                reference.setConnections(connections);
            }

            // set loadBalance
            String loadBalance = Constants.getLoadbalance(this);
            if (!StringUtils.isBlank(loadBalance)) {
                reference.setLoadbalance(loadBalance);
            }

            // set async
            String async = Constants.getAsync(this);
            if (!StringUtils.isBlank(async)) {
                reference.setAsync(Constants.ASYNC.equals(async));
            }

            // set generic
            reference.setGeneric(true);

            String methodName = Constants.getMethod(this);
            if (StringUtils.isBlank(methodName)) {
                res.setSuccessful(false);
                return ErrorCode.MISS_METHOD.getMessage();
            }

            // The registry's address is to generate the ReferenceConfigCache key
            ReferenceConfigCache.KeyGenerator keyGenerator = new ReferenceConfigCache.KeyGenerator() {
                @Override
                public String generateKey(org.apache.dubbo.config.ReferenceConfig<?> referenceConfig) {
                    return referenceConfig.toString();
                }
            };
            GenericService genericService = serviceMap.computeIfAbsent(keyGenerator.generateKey(reference), key -> {
                ReferenceConfigCache cache = ReferenceConfigCache.getCache(Constants.getAddress(this), keyGenerator);
                return (GenericService) cache.get(reference);
            });
            if (genericService == null) {
                res.setSuccessful(false);
                return MessageFormat.format(ErrorCode.GENERIC_SERVICE_IS_NULL.getMessage(), interfaceName);
            }
            String[] parameterTypes = null;
            Object[] parameterValues = null;
            List<MethodArgument> args = Constants.getMethodArgs(this);
            List<String> paramterTypeList =  new ArrayList<String>();;
            List<Object> parameterValuesList = new ArrayList<Object>();;
            for(MethodArgument arg : args) {
            	ClassUtils.parseParameter(paramterTypeList, parameterValuesList, arg);
            }
            parameterTypes = paramterTypeList.toArray(new String[paramterTypeList.size()]);
            parameterValues = parameterValuesList.toArray(new Object[parameterValuesList.size()]);

            List<MethodArgument> attachmentArgs = Constants.getAttachmentArgs(this);
            if (attachmentArgs != null && !attachmentArgs.isEmpty()) {
                RpcContext.getContext().setAttachments(attachmentArgs.stream().collect(Collectors.toMap(MethodArgument::getParamType, MethodArgument::getParamValue)));
            }

            res.sampleStart();
            Object result = null;
			try {
				result = genericService.$invoke(methodName, parameterTypes, parameterValues);
				res.setSuccessful(true);
			} catch (Exception e) {
				log.error("RpcException：", e);
				//TODO
				//当接口返回异常时，sample标识为successful，通过响应内容做断言来判断是否标识sample错误，因为sample的错误会统计到用例的error百分比内。
				//比如接口有一些校验性质的异常，不代表这个操作是错误的，这样就可以灵活的判断，不至于正常的校验返回导致测试用例error百分比的不真实
				res.setSuccessful(true);
				result = e;
			}
            return result;
        } catch (Exception e) {
            log.error("UnknownException：", e);
            res.setSuccessful(false);
            return e;
        } finally {
        	//TODO 不能在sample结束时destroy
//            if (registry != null) {
//                registry.destroyAll();
//            }
//            reference.destroy();
        }
    }

    @Override
    public boolean interrupt() {
        Thread.currentThread().interrupt();
        return true;
    }
}

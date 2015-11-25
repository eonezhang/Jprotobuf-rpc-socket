/*
 * Copyright 2002-2014 the original author or authors.
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

package com.baidu.jprotobuf.pbrpc.client;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.baidu.bjf.remoting.protobuf.utils.FieldUtils;
import com.baidu.jprotobuf.pbrpc.ClientAttachmentHandler;
import com.baidu.jprotobuf.pbrpc.ErrorDataException;
import com.baidu.jprotobuf.pbrpc.ProtobufRPC;
import com.baidu.jprotobuf.pbrpc.data.RpcDataPackage;
import com.baidu.jprotobuf.pbrpc.data.RpcResponseMeta;
import com.baidu.jprotobuf.pbrpc.transport.BlockingRpcCallback;
import com.baidu.jprotobuf.pbrpc.transport.Connection;
import com.baidu.jprotobuf.pbrpc.transport.RpcChannel;
import com.baidu.jprotobuf.pbrpc.transport.RpcClient;
import com.baidu.jprotobuf.pbrpc.transport.handler.ErrorCodes;
import com.baidu.jprotobuf.pbrpc.utils.ServiceSignatureUtils;
import com.baidu.jprotobuf.pbrpc.utils.StringUtils;

/**
 * Protobuf RPC proxy utility class.
 * 
 * @author xiemalin
 * @since 1.0
 * @see ProxyFactory
 */
public class ProtobufRpcProxy<T> implements InvocationHandler {

    /**
     * Logger for this class
     */
    private static final Logger LOGGER = Logger.getLogger(ProtobufRpcProxy.class.getName());
    
    /**
     * Logger for this class
     */
    private static final Logger PERFORMANCE_LOGGER = Logger.getLogger("performance-log");

    /**
     * key name for shared RPC channel
     * 
     * @see RpcChannel
     */
    private static final String SHARE_KEY = "___share_key";

    private Map<String, RpcMethodInfo> cachedRpcMethods = new HashMap<String, RpcMethodInfo>();
    
    /**
     * RPC client.
     */
    private final RpcClient rpcClient;
    private Map<String, RpcChannel> rpcChannelMap = new HashMap<String, RpcChannel>();

    private String host;
    private int port;

    private boolean lookupStubOnStartup = true;

    private T instance;

    private ServiceLocatorCallback serviceLocatorCallback;
    
    private String serviceUrl;

    /**
     * set serviceLocatorCallback value to serviceLocatorCallback
     * 
     * @param serviceLocatorCallback the serviceLocatorCallback to set
     */
    public void setServiceLocatorCallback(ServiceLocatorCallback serviceLocatorCallback) {
        this.serviceLocatorCallback = serviceLocatorCallback;
    }

    /**
     * get the lookupStubOnStartup
     * 
     * @return the lookupStubOnStartup
     */
    public boolean isLookupStubOnStartup() {
        return lookupStubOnStartup;
    }

    /**
     * set lookupStubOnStartup value to lookupStubOnStartup
     * 
     * @param lookupStubOnStartup the lookupStubOnStartup to set
     */
    public void setLookupStubOnStartup(boolean lookupStubOnStartup) {
        this.lookupStubOnStartup = lookupStubOnStartup;
    }

    /**
     * set host value to host
     * 
     * @param host the host to set
     */
    public void setHost(String host) {
        this.host = host;
    }

    public Set<String> getServiceSignatures() {
        if (!cachedRpcMethods.isEmpty()) {
            return new HashSet<String>(cachedRpcMethods.keySet());
        }

        Set<String> serviceSignatures = new HashSet<String>();
        Method[] methods = interfaceClass.getMethods();
        for (Method method : methods) {
            ProtobufRPC protobufPRC = method.getAnnotation(ProtobufRPC.class);
            if (protobufPRC != null) {
                String serviceName = protobufPRC.serviceName();
                String methodName = protobufPRC.methodName();
                if (StringUtils.isEmpty(methodName)) {
                    methodName = method.getName();
                }

                String methodSignature = ServiceSignatureUtils.makeSignature(serviceName, methodName);
                serviceSignatures.add(methodSignature);
            }
        }
        // if not protobufRpc method defined throw exception
        if (serviceSignatures.isEmpty()) {
            throw new IllegalArgumentException(
                    "This no protobufRpc method in interface class:" + interfaceClass.getName());
        }
        return serviceSignatures;
    }

    /**
     * set port value to port
     * 
     * @param port the port to set
     */
    public void setPort(int port) {
        this.port = port;
    }

    /**
     * target interface class
     */
    private final Class<T> interfaceClass;

    /**
     * @param rpcClient
     */
    public ProtobufRpcProxy(RpcClient rpcClient, Class<T> interfaceClass) {
        this.interfaceClass = interfaceClass;
        if (rpcClient == null) {
            throw new IllegalArgumentException("Param 'rpcClient'  is null.");
        }
        if (interfaceClass == null) {
            throw new IllegalArgumentException("Param 'interfaceClass'  is null.");
        }
        this.rpcClient = rpcClient;
    }

    public synchronized T proxy() {

        if (instance != null) {
            return instance;
        }

        // to parse interface
        Method[] methods = interfaceClass.getMethods();
        for (Method method : methods) {
            ProtobufRPC protobufPRC = method.getAnnotation(ProtobufRPC.class);
            if (protobufPRC != null) {
                String serviceName = protobufPRC.serviceName();
                String methodName = protobufPRC.methodName();
                if (StringUtils.isEmpty(methodName)) {
                    methodName = method.getName();
                }

                String methodSignature = ServiceSignatureUtils.makeSignature(serviceName, methodName);
                if (cachedRpcMethods.containsKey(methodSignature)) {
                    throw new IllegalArgumentException(
                            "Method with annotation ProtobufPRC already defined service name [" + serviceName
                                    + "] method name [" + methodName + "]");
                }

                RpcMethodInfo methodInfo;
                if (!RpcMethodInfo.isMessageType(method)) {
                    // using POJO
                    methodInfo = new PojoRpcMethodInfo(method, protobufPRC);

                } else {
                    // support google protobuf GeneratedMessage
                    methodInfo = new GeneratedMessageRpcMethodInfo(method, protobufPRC);
                }
                methodInfo.setOnceTalkTimeout(protobufPRC.onceTalkTimeout());
                methodInfo.setServiceName(serviceName);
                methodInfo.setMethodName(methodName);

                cachedRpcMethods.put(methodSignature, methodInfo);

                // do create rpc channal
                String eHost = host;
                int ePort = port;
                if (serviceLocatorCallback != null) {
                    InetSocketAddress address = serviceLocatorCallback.fetchAddress(methodSignature);
                    if (address == null) {
                        throw new RuntimeException("fetch a null address from serviceLocatorCallback"
                                + " by serviceSignature '" + methodSignature + "'");
                    }
                    eHost = address.getHostName();
                    port = address.getPort();
                }

                String channelKey = methodSignature;

                if (rpcClient.getRpcClientOptions().isShareThreadPoolUnderEachProxy()) {
                    channelKey = SHARE_KEY;
                }

                if (!rpcChannelMap.containsKey(channelKey)) {
                    RpcChannel rpcChannel = new RpcChannel(rpcClient, eHost, ePort);
                    if (lookupStubOnStartup) {
                        rpcChannel.testChannlConnect();
                    }

                    rpcChannelMap.put(channelKey, rpcChannel);
                }

                serviceUrl = eHost + ":" + ePort;
            }
        }

        // if not protobufRpc method defined throw exception
        if (cachedRpcMethods.isEmpty()) {
            throw new IllegalArgumentException(
                    "This no protobufRpc method in interface class:" + interfaceClass.getName());
        }

        Class[] clazz = { interfaceClass, ServiceUrlAccessible.class };
        instance = ProxyFactory.createProxy(clazz, interfaceClass.getClassLoader(), this);
        return instance;
    }

    protected RpcDataPackage buildRequestDataPackage(RpcMethodInfo rpcMethodInfo, Object[] args) throws IOException {
        RpcDataPackage rpcDataPackage = RpcDataPackage.buildRpcDataPackage(rpcMethodInfo, args);
        return rpcDataPackage;
    }

    public void close() {
        Collection<RpcChannel> rpcChannels = rpcChannelMap.values();
        for (RpcChannel rpcChann : rpcChannels) {
            try {
                rpcChann.close();
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, e.getMessage(), e.getCause());
            }
        }

    }

    /*
     * (non-Javadoc)
     * 
     * @see java.lang.reflect.InvocationHandler#invoke(java.lang.Object, java.lang.reflect.Method, java.lang.Object[])
     */
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        
        String mName = method.getName();
        if ("getServiceUrl".equals(mName)) {
            
            // return directly from local due to call ServiceUrlAccessible
            return serviceUrl;
        }
        

        long time = System.currentTimeMillis();

        ProtobufRPC protobufPRC = method.getAnnotation(ProtobufRPC.class);
        if (protobufPRC == null) {
            throw new IllegalAccessError("Target method is not marked annotation @ProtobufPRC. method name :"
                    + method.getDeclaringClass().getName() + "." + method.getName());
        }

        String serviceName = protobufPRC.serviceName();
        String methodName = protobufPRC.methodName();
        if (StringUtils.isEmpty(methodName)) {
            methodName = mName;
        }
        String methodSignature = ServiceSignatureUtils.makeSignature(serviceName, methodName);
        RpcMethodInfo rpcMethodInfo = cachedRpcMethods.get(methodSignature);
        if (rpcMethodInfo == null) {
            throw new IllegalAccessError(
                    "Can not invoke method '" + method.getName() + "' due to not a protbufRpc method.");
        }

        long onceTalkTimeout = rpcMethodInfo.getOnceTalkTimeout();
        if (onceTalkTimeout <= 0) {
            // use default once talk timeout
            onceTalkTimeout = rpcClient.getRpcClientOptions().getOnceTalkTimeout();
        }

        BlockingRpcCallback callback = new BlockingRpcCallback();
        RpcDataPackage rpcDataPackage = buildRequestDataPackage(rpcMethodInfo, args);
        // set correlationId
        rpcDataPackage.getRpcMeta().setCorrelationId(rpcClient.getNextCorrelationId());

        String channelKey = methodSignature;
        if (rpcClient.getRpcClientOptions().isShareThreadPoolUnderEachProxy()) {
            channelKey = SHARE_KEY;
        }

        RpcChannel rpcChannel = rpcChannelMap.get(channelKey);
        if (rpcChannel == null) {
            throw new RuntimeException("No rpcChannel bind with serviceSignature '" + channelKey + "'");
        }

        Connection connection = rpcChannel.getConnection();
        
        try {
            rpcChannel.doTransport(connection, rpcDataPackage, callback, onceTalkTimeout);
            
            if (!callback.isDone()) {
                synchronized (callback) {
                    while (!callback.isDone()) {
                        try {
                            callback.wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
        } finally {
            rpcChannel.releaseConnection(connection);
        }

        RpcDataPackage message = callback.getMessage();

        RpcResponseMeta response = message.getRpcMeta().getResponse();
        if (response != null) {
            Integer errorCode = response.getErrorCode();
            if (!ErrorCodes.isSuccess(errorCode)) {
                String error = message.getRpcMeta().getResponse().getErrorText();
                throw new ErrorDataException("A error occurred: errorCode=" + errorCode + " errorMessage:" + error,
                        errorCode);
            }
        }

        byte[] attachment = message.getAttachment();
        if (attachment != null) {
            ClientAttachmentHandler attachmentHandler = rpcMethodInfo.getClientAttachmentHandler();
            if (attachmentHandler != null) {
                attachmentHandler.handleResponse(attachment, serviceName, methodName, args);
            }
        }

        // handle response data
        byte[] data = message.getData();
        if (data == null) {
            return null;
        }

        PERFORMANCE_LOGGER.info("RPC client invoke method '" + method.getName() + "' time took:"
                + (System.currentTimeMillis() - time) + " ms");

        return rpcMethodInfo.outputDecode(data);
    }

}

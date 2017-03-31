/*
 * Copyright (c) Microsoft. All rights reserved.
 * Licensed under the MIT license. See LICENSE file in the project root for full license information.
 */

package tests.integration.com.microsoft.azure.sdk.iot.service;

import com.microsoft.azure.sdk.iot.device.DeviceClient;
import com.microsoft.azure.sdk.iot.device.DeviceTwin.DeviceMethodData;
import com.microsoft.azure.sdk.iot.device.DeviceTwin.DeviceMethodCallback;
import com.microsoft.azure.sdk.iot.device.IotHubClientProtocol;
import com.microsoft.azure.sdk.iot.device.IotHubEventCallback;
import com.microsoft.azure.sdk.iot.device.IotHubStatusCode;
import com.microsoft.azure.sdk.iot.service.*;
import com.microsoft.azure.sdk.iot.service.devicetwin.DeviceMethod;
import com.microsoft.azure.sdk.iot.service.devicetwin.MethodResult;
import com.microsoft.azure.sdk.iot.service.exceptions.IotHubException;
import com.microsoft.azure.sdk.iot.service.exceptions.IotHubGatewayTimeoutException;
import com.sun.xml.internal.ws.policy.privateutil.PolicyUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/**
 * Integration E2E test for Device Method on the service client.
 */
public class DeviceMethodIT
{
    private static String iotHubConnectionStringEnvVarName = "IOTHUB_CONNECTION_STRING";
    private static String iotHubConnectionString = "";
    private static String deviceConnectionString = "";
    private static RegistryManager registryManager;
    private static IotHubClientProtocol deviceClientProtocol = IotHubClientProtocol.MQTT;
    private static DeviceClient deviceClient;
    private static DeviceMethod methodServiceClient;

    private static DeviceMethodStatusCallback deviceMethodStatusCallback = new DeviceMethodStatusCallback();

    private static class DeviceStatus
    {
        IotHubStatusCode status;
    }
    private static DeviceStatus deviceMethodStatusCallbackResult = new DeviceStatus();
    private static final AtomicBoolean succeed = new AtomicBoolean();


    private static String DEVICE_ID_NAME = "java-e2e-test";
    private static final String METHOD_LOOPBACK = "loopback";
    private static final String METHOD_DELAY_IN_MILLISECONDS = "delayInMilliseconds";
    private static final String METHOD_UNKNOWN = "blah";

    private static final Long RESPONSE_TIMEOUT = TimeUnit.SECONDS.toSeconds(200);
    private static final Long CONNECTION_TIMEOUT = TimeUnit.SECONDS.toSeconds(5);
    private static final String PAYLOAD_STRING = "This is a valid payload";

    private static final int METHOD_SUCCESS = 200;
    private static final int METHOD_THROWS = 403;
    private static final int METHOD_NOT_DEFINED = 404;

    private static final int NUMBER_INVOKES_PARALLEL = 100;


    @BeforeClass
    public static void setUp() throws URISyntaxException, IOException, Exception
    {
        Map<String, String> env = System.getenv();
        for (String envName : env.keySet())
        {
            if (envName.equals(iotHubConnectionStringEnvVarName.toString()))
            {
                iotHubConnectionString = env.get(envName);
                break;
            }
        }

        if ((iotHubConnectionString == null) || iotHubConnectionString.isEmpty())
        {
            throw new IllegalArgumentException("Environment variable is not set: " + iotHubConnectionStringEnvVarName);
        }

        methodServiceClient = DeviceMethod.createFromConnectionString(iotHubConnectionString);

        registryManager = RegistryManager.createFromConnectionString(iotHubConnectionString);

        deviceConnectionString = provisioningDevice();

        deviceClient = new DeviceClient(deviceConnectionString, deviceClientProtocol);
        try
        {
            deviceClient.open();
            deviceClient.subscribeToDeviceMethod(new TestDeviceMethodCallback(), null, deviceMethodStatusCallback, deviceMethodStatusCallbackResult);
        }
        catch (Exception e)
        {
            removeDevice();
            throw new IOException(e);
        }
    }

    @Before
    public void cleanToStart()
    {
        deviceMethodStatusCallbackResult.status = IotHubStatusCode.ERROR;
    }

    protected static void removeDevice() throws Exception
    {
        deviceClient.close();
        registryManager.removeDevice(DEVICE_ID_NAME);
    }

    protected static String provisioningDevice() throws Exception
    {
        String uuid = UUID.randomUUID().toString();
        DEVICE_ID_NAME = DEVICE_ID_NAME.concat("-" + System.getProperty("user.name") + "-" + uuid);

        // Check if device exists with the given name
        Boolean deviceExist = false;
        try
        {
            registryManager.getDevice(DEVICE_ID_NAME);
            deviceExist = true;
        }
        catch (IotHubException e)
        {
        }
        if (deviceExist)
        {
            try
            {
                registryManager.removeDevice(DEVICE_ID_NAME);
            } catch (IotHubException|IOException e)
            {
                System.out.println("Initialization failed, could not remove device: " + DEVICE_ID_NAME);
                e.printStackTrace();
            }
        }

        Device deviceAdded = Device.createFromId(DEVICE_ID_NAME, null, null);
        registryManager.addDevice(deviceAdded);

        try{
            Thread.sleep(5000);
        }
        catch (Exception e){}

        String[] connectionStrings = iotHubConnectionString.split(";");

        return connectionStrings[0] + ";DeviceId=" + deviceAdded.getDeviceId() + ";SharedAccessKey=" + deviceAdded.getPrimaryKey();
    }

    protected static class DeviceMethodStatusCallback implements IotHubEventCallback
    {
        public void execute(IotHubStatusCode status, Object context)
        {
            DeviceStatus deviceStatus = (DeviceStatus)context;
            deviceStatus.status = status;
        }
    }

    protected static class RunnableInvoke implements Runnable
    {
        String name;
        CountDownLatch latch;

        public RunnableInvoke(String name, CountDownLatch latch)
        {
            this.name = name;
            this.latch = latch;
        }

        @Override
        public void run()
        {
            // Arrange
            MethodResult result = null;

            // Act
            try
            {
                result = methodServiceClient.invoke(DEVICE_ID_NAME, METHOD_LOOPBACK, RESPONSE_TIMEOUT, CONNECTION_TIMEOUT, name);
            }
            catch (Exception e)
            {
            }

            // Assert
            if((result == null) ||
                    ((long)result.getStatus() != (long)METHOD_SUCCESS) ||
                    !result.getPayload().equals(METHOD_LOOPBACK + ":" + name))
            {
                succeed.set(false);
            }
            latch.countDown();
        }
    }

    protected static class TestDeviceMethodCallback implements DeviceMethodCallback
    {
        @Override
        public DeviceMethodData call(String methodName, Object methodData, Object context)
        {
            DeviceMethodData deviceMethodData;
            switch (methodName)
            {
                case METHOD_LOOPBACK:
                {
                    int status;
                    String result;
                    try
                    {
                        result = loopback(methodData);
                        status = METHOD_SUCCESS;
                    }
                    catch (Exception e)
                    {
                        result = e.toString();
                        status = METHOD_THROWS;
                    }
                    deviceMethodData = new DeviceMethodData(status, result);
                    break;
                }
                case METHOD_DELAY_IN_MILLISECONDS:
                {
                    int status;
                    String result;
                    try
                    {
                        result = delayInMilliseconds(methodData);
                        status = METHOD_SUCCESS;
                    }
                    catch (Exception e)
                    {
                        result = e.toString();
                        status = METHOD_THROWS;
                    }
                    deviceMethodData = new DeviceMethodData(status, result);
                    break;
                }
                default:
                {
                    deviceMethodData = new DeviceMethodData(METHOD_NOT_DEFINED, "unknown:" + methodName);
                }
            }

            return deviceMethodData;
        }
    }

    private static String loopback(Object methodData) throws UnsupportedEncodingException
    {
        String payload = new String((byte[])methodData, "UTF-8").replace("\"", "");
        return METHOD_LOOPBACK + ":" + payload;
    }

    private static String delayInMilliseconds(Object methodData) throws UnsupportedEncodingException, InterruptedException
    {
        String payload = new String((byte[])methodData, "UTF-8").replace("\"", "");
        long delay = Long.parseLong(payload);
        Thread.sleep(delay);
        return METHOD_DELAY_IN_MILLISECONDS + ":succeed";
    }

    @AfterClass
    public static void tearDown() throws Exception
    {
        removeDevice();
    }

    @Test
    public void invokeMethod_succeed() throws Exception
    {
        // Act
        MethodResult result = methodServiceClient.invoke(DEVICE_ID_NAME, METHOD_LOOPBACK, RESPONSE_TIMEOUT, CONNECTION_TIMEOUT, PAYLOAD_STRING);

        // Assert
        assertNotNull(result);
        assertEquals((long)METHOD_SUCCESS, (long)result.getStatus());
        assertEquals(METHOD_LOOPBACK + ":" + PAYLOAD_STRING, result.getPayload());
        assertEquals(IotHubStatusCode.OK_EMPTY, deviceMethodStatusCallbackResult.status);
    }

    @Test
    public void invokeMethod_invokeParallel_succeed() throws Exception
    {
        List<Thread> threads = new ArrayList<>(NUMBER_INVOKES_PARALLEL);
        CountDownLatch cdl = new CountDownLatch(NUMBER_INVOKES_PARALLEL);
        succeed.set(true);

        for (int i = 0; i < NUMBER_INVOKES_PARALLEL; i++)
        {
            Thread thread = new Thread(
                    new RunnableInvoke(
                            "Thread" + i,
                            cdl));
            thread.start();
            threads.add(thread);
        }

        cdl.await();

        assertTrue("Invoking device method in parallel failed", succeed.get());
    }

    @Test
    public void invokeMethod_standardTimeout_succeed() throws Exception
    {
        // Act
        MethodResult result = methodServiceClient.invoke(DEVICE_ID_NAME, METHOD_LOOPBACK, null, null, PAYLOAD_STRING);

        // Assert
        assertNotNull(result);
        assertEquals((long)METHOD_SUCCESS, (long)result.getStatus());
        assertEquals(METHOD_LOOPBACK + ":" + PAYLOAD_STRING, result.getPayload());
        assertEquals(IotHubStatusCode.OK_EMPTY, deviceMethodStatusCallbackResult.status);
    }

    @Test
    public void invokeMethod_nullPayload_succeed() throws Exception
    {
        // Act
        MethodResult result = methodServiceClient.invoke(DEVICE_ID_NAME, METHOD_LOOPBACK, RESPONSE_TIMEOUT, CONNECTION_TIMEOUT, null);

        // Assert
        assertNotNull(result);
        assertEquals((long)METHOD_SUCCESS, (long)result.getStatus());
        assertEquals(METHOD_LOOPBACK + ":null", result.getPayload());
        assertEquals(IotHubStatusCode.OK_EMPTY, deviceMethodStatusCallbackResult.status);
    }

    @Test
    public void invokeMethod_number_succeed() throws Exception
    {
        // Act
        MethodResult result = methodServiceClient.invoke(DEVICE_ID_NAME, METHOD_DELAY_IN_MILLISECONDS, RESPONSE_TIMEOUT, CONNECTION_TIMEOUT, "100");

        // Assert
        assertNotNull(result);
        assertEquals((long)METHOD_SUCCESS, (long)result.getStatus());
        assertEquals(METHOD_DELAY_IN_MILLISECONDS + ":succeed", result.getPayload());
        assertEquals(IotHubStatusCode.OK_EMPTY, deviceMethodStatusCallbackResult.status);
    }

    @Test
    public void invokeMethod_throws_NumberFormatException_failed() throws Exception
    {
        // Act
        MethodResult result = methodServiceClient.invoke(DEVICE_ID_NAME, METHOD_DELAY_IN_MILLISECONDS, RESPONSE_TIMEOUT, CONNECTION_TIMEOUT, PAYLOAD_STRING);

        // Assert
        assertNotNull(result);
        assertEquals((long)METHOD_THROWS, (long)result.getStatus());
        assertEquals("java.lang.NumberFormatException: For input string: \"" + PAYLOAD_STRING + "\"", result.getPayload());
        assertEquals(IotHubStatusCode.OK_EMPTY, deviceMethodStatusCallbackResult.status);
    }

    @Test
    public void invokeMethod_unknown_failed() throws Exception
    {
        // Act
        MethodResult result = methodServiceClient.invoke(DEVICE_ID_NAME, METHOD_UNKNOWN, RESPONSE_TIMEOUT, CONNECTION_TIMEOUT, PAYLOAD_STRING);

        // Assert
        assertNotNull(result);
        assertEquals((long)METHOD_NOT_DEFINED, (long)result.getStatus());
        assertEquals("unknown:" + METHOD_UNKNOWN, result.getPayload());
        assertEquals(IotHubStatusCode.OK_EMPTY, deviceMethodStatusCallbackResult.status);
    }

    @Test
    public void invokeMethod_recoverFromTimeout_succeed() throws Exception
    {
        try
        {
            methodServiceClient.invoke(DEVICE_ID_NAME, METHOD_DELAY_IN_MILLISECONDS, (long)5, CONNECTION_TIMEOUT, "7000");
             assert true;
        }
        catch(IotHubGatewayTimeoutException expected)
        {
            //Don't do anything. Expected throw.
        }

        // Act
        MethodResult result = methodServiceClient.invoke(DEVICE_ID_NAME, METHOD_DELAY_IN_MILLISECONDS, RESPONSE_TIMEOUT, CONNECTION_TIMEOUT, "100");

        // Assert
        assertNotNull(result);
        assertEquals((long)METHOD_SUCCESS, (long)result.getStatus());
        assertEquals(METHOD_DELAY_IN_MILLISECONDS + ":succeed", result.getPayload());
        assertEquals(IotHubStatusCode.OK_EMPTY, deviceMethodStatusCallbackResult.status);
    }


    @Test (expected = IotHubGatewayTimeoutException.class)
    public void invokeMethod_timeout_failed() throws Exception
    {
        // Act
        MethodResult result = methodServiceClient.invoke(DEVICE_ID_NAME, METHOD_DELAY_IN_MILLISECONDS, (long)5, CONNECTION_TIMEOUT, "7000");
    }

}

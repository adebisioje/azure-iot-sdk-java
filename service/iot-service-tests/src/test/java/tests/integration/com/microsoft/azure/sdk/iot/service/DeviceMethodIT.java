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
import org.junit.*;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/**
 * Integration E2E test for Device Method on the service client.
 */
public class DeviceMethodIT
{
    private static final Integer MAX_DEVICE_PARALLEL = 2;
    private static final int NUMBER_INVOKES_PARALLEL = 100;

    private static final String iotHubConnectionStringEnvVarName = "IOTHUB_CONNECTION_STRING";
    private static String iotHubConnectionString = "";
    private static RegistryManager registryManager;
    private static IotHubClientProtocol deviceClientProtocol = IotHubClientProtocol.MQTT;
    private static DeviceClient deviceClient;
    private static DeviceMethod methodServiceClient;
    private static Device[] testDeviceArray = new Device[MAX_DEVICE_PARALLEL];

    private static TestDeviceMethodCallback testDeviceMethodCallback = new TestDeviceMethodCallback();
    private static DeviceMethodStatusCallback deviceMethodStatusCallback = new DeviceMethodStatusCallback();

    private static class DeviceStatus
    {
        IotHubStatusCode status;
    }
    private static DeviceStatus deviceMethodStatusCallbackResult = new DeviceStatus();
    private static AtomicBoolean succeed = new AtomicBoolean();
    private static ConcurrentSkipListSet<String> failReason = new ConcurrentSkipListSet<>();
    private static String uuid = UUID.randomUUID().toString();

    private static String baseDeviceId = "E2EJavaMQTT";
    private static Device testDevice;
    private static final String METHOD_LOOPBACK = "loopback";
    private static final String METHOD_DELAY_IN_MILLISECONDS = "delayInMilliseconds";
    private static final String METHOD_UNKNOWN = "unknown";

    private static final Long RESPONSE_TIMEOUT = TimeUnit.SECONDS.toSeconds(200);
    private static final Long CONNECTION_TIMEOUT = TimeUnit.SECONDS.toSeconds(5);
    private static final String PAYLOAD_STRING = "This is a valid payload";

    private static final int METHOD_SUCCESS = 200;
    private static final int METHOD_THROWS = 403;
    private static final int METHOD_NOT_DEFINED = 404;


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

        try
        {
            testDevice = provisioningDevice(baseDeviceId);
            deviceClient = new DeviceClient(getConnectionString(iotHubConnectionString, testDevice), deviceClientProtocol);
            deviceClient.open();
            deviceClient.subscribeToDeviceMethod(testDeviceMethodCallback, null, deviceMethodStatusCallback, deviceMethodStatusCallbackResult);
        }
        catch (Exception e)
        {
            removeDevices();
            throw e;
        }
    }

    protected static Device provisioningDevice(String deviceId) throws Exception
    {
        String fullDeviceId = deviceId.concat("-" + uuid);

        Device deviceAdded = Device.createFromId(fullDeviceId, null, null);
        registryManager.addDevice(deviceAdded);

        try{
            Thread.sleep(5000);
        }
        catch (Exception e){}

        return deviceAdded;
    }
    @Before
    public void cleanToStart() throws InterruptedException
    {
        deviceMethodStatusCallbackResult.status = IotHubStatusCode.ERROR;
        succeed.set(true);
        failReason.clear();
    }

    private static void removeDevices() throws Exception
    {
        deviceClient.close();
        registryManager.removeDevice(testDevice.getDeviceId());
    }

    private static String getConnectionString(String iotHubConnectionString, Device device)
    {
        StringBuilder stringBuilder = new StringBuilder();
        String[] tokenArray = iotHubConnectionString.split(";");
        String hostName = "";
        for (String token:tokenArray)
        {
            String[] keyValueArray = token.split("=");
            if (keyValueArray[0].equals("HostName"))
            {
                hostName =  token + ';';
                break;
            }
        }

        stringBuilder.append(hostName);
        stringBuilder.append(String.format("DeviceId=%s", device.getDeviceId()));
        stringBuilder.append(String.format(";SharedAccessKey=%s", device.getPrimaryKey()));
        return stringBuilder.toString();
    }

    protected static class DeviceMethodStatusCallback implements IotHubEventCallback
    {
        public synchronized void execute(IotHubStatusCode status, Object context)
        {
            DeviceStatus device = (DeviceStatus)context;
            deviceMethodStatusCallbackResult.status = status;
        }
    }

    protected static class RunnableInvoke implements Runnable
    {
        String deviceId;
        String method;
        Long responseTimeout;
        Long connectionTimeout;
        Object payload;
        Integer expectedStatus;
        String expectedPayload;
        CountDownLatch latch;

        public RunnableInvoke(String deviceId, String method, Long responseTimeout, Long connectionTimeout, Object payload, Integer expectedStatus, String expectedPayload, CountDownLatch latch)
        {
            this.deviceId = deviceId;
            this.method = method;
            this.responseTimeout = responseTimeout;
            this.connectionTimeout = connectionTimeout;
            this.payload = payload;
            this.expectedStatus = expectedStatus;
            this.expectedPayload = expectedPayload;
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
                result = methodServiceClient.invoke(deviceId, method, responseTimeout, connectionTimeout, payload);

                // Assert
                if(result == null)
                {
                    succeed.set(false);
                    failReason.add("invoke device " + deviceId + " returns null");
                }
                else if((long)result.getStatus() != (long)expectedStatus)
                {
                    succeed.set(false);
                    failReason.add("invoke device " + deviceId + " returns status:" + ((long) result.getStatus()) + "  expected:" + expectedStatus);
                }
                else if(!result.getPayload().equals(expectedPayload))
                {
                    succeed.set(false);
                    failReason.add("invoke device " + deviceId + " returns payload:" + result.getPayload().toString() + "  expected:" + expectedPayload);
                }
            }
            catch (Exception e)
            {
                succeed.set(false);
                failReason.add("invoke device " + deviceId + " throws:" + e.toString());
            }
            finally
            {
                latch.countDown();
            }
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
 //       removeDevices();
    }

    @Test
    public void invokeMethod_succeed() throws Exception
    {
        // Act
        MethodResult result = methodServiceClient.invoke(testDevice.getDeviceId(), METHOD_LOOPBACK, RESPONSE_TIMEOUT, CONNECTION_TIMEOUT, PAYLOAD_STRING);

        // Assert
        assertNotNull(result);
        assertEquals((long) METHOD_SUCCESS, (long) result.getStatus());
        assertEquals(METHOD_LOOPBACK + ":" + PAYLOAD_STRING, result.getPayload());
        assertEquals(IotHubStatusCode.OK_EMPTY, deviceMethodStatusCallbackResult.status);
    }

    @Test
    public void invokeMethod_invokeParallel_singleDevice_succeed() throws Exception
    {
        CountDownLatch cdl = new CountDownLatch(NUMBER_INVOKES_PARALLEL);
        succeed.set(true);

        for (int i = 0; i < NUMBER_INVOKES_PARALLEL; i++)
        {
            String threadName = "Thread" + i;
            new Thread(
                    new RunnableInvoke(
                            testDevice.getDeviceId(), METHOD_LOOPBACK, RESPONSE_TIMEOUT, CONNECTION_TIMEOUT, threadName,
                            METHOD_SUCCESS, METHOD_LOOPBACK + ":" + threadName,
                            cdl)).start();
        }

        cdl.await();

        for (String fail:failReason)
        {
            System.out.println(fail);
        }
        assertTrue("Invoking device method in parallel failed", succeed.get());
    }

    @Ignore
    @Test
    public void invokeMethod_invokeParallel_multipleDevice_succeed() throws Exception
    {
        int numberOfDevices = testDeviceArray.length;
        List<DeviceClient> deviceClientArray = new LinkedList<>();
        CountDownLatch cdl = new CountDownLatch(NUMBER_INVOKES_PARALLEL*numberOfDevices);
        succeed.set(true);

        for(Device device:testDeviceArray)
        {
            DeviceClient newDeviceClient = new DeviceClient(getConnectionString(iotHubConnectionString, device), deviceClientProtocol);
            deviceClientArray.add(newDeviceClient);
            newDeviceClient.open();
            for (int i = 0; i < NUMBER_INVOKES_PARALLEL; i++)
            {
                String threadName = "Thread" + i;
                new Thread(
                        new RunnableInvoke(
                                device.getDeviceId(), METHOD_LOOPBACK, RESPONSE_TIMEOUT, CONNECTION_TIMEOUT, threadName,
                                METHOD_SUCCESS, METHOD_LOOPBACK + ":" + threadName,
                                cdl)).start();
            }
        }

        cdl.await();

        for(DeviceClient deviceClient:deviceClientArray)
        {
            deviceClient.close();
        }

        for (String fail:failReason)
        {
            System.out.println(fail);
        }
        assertTrue("Invoking device method in parallel failed", succeed.get());

    }

    @Test
    public void invokeMethod_standardTimeout_succeed() throws Exception
    {
        // Act
        MethodResult result = methodServiceClient.invoke(testDevice.getDeviceId(), METHOD_LOOPBACK, null, null, PAYLOAD_STRING);

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
        MethodResult result = methodServiceClient.invoke(testDevice.getDeviceId(), METHOD_LOOPBACK, RESPONSE_TIMEOUT, CONNECTION_TIMEOUT, null);

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
        MethodResult result = methodServiceClient.invoke(testDevice.getDeviceId(), METHOD_DELAY_IN_MILLISECONDS, RESPONSE_TIMEOUT, CONNECTION_TIMEOUT, "100");

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
        MethodResult result = methodServiceClient.invoke(testDevice.getDeviceId(), METHOD_DELAY_IN_MILLISECONDS, RESPONSE_TIMEOUT, CONNECTION_TIMEOUT, PAYLOAD_STRING);

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
        MethodResult result = methodServiceClient.invoke(testDevice.getDeviceId(), METHOD_UNKNOWN, RESPONSE_TIMEOUT, CONNECTION_TIMEOUT, PAYLOAD_STRING);

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
            methodServiceClient.invoke(testDevice.getDeviceId(), METHOD_DELAY_IN_MILLISECONDS, (long)5, CONNECTION_TIMEOUT, "7000");
             assert true;
        }
        catch(IotHubGatewayTimeoutException expected)
        {
            //Don't do anything. Expected throw.
        }

        // Act
        MethodResult result = methodServiceClient.invoke(testDevice.getDeviceId(), METHOD_DELAY_IN_MILLISECONDS, RESPONSE_TIMEOUT, CONNECTION_TIMEOUT, "100");

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
        MethodResult result = methodServiceClient.invoke(testDevice.getDeviceId(), METHOD_DELAY_IN_MILLISECONDS, (long)5, CONNECTION_TIMEOUT, "7000");
    }

}

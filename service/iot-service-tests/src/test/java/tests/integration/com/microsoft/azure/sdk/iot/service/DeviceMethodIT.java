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
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Integration E2E test for Device Method on the service client.
 */
public class DeviceMethodIT
{
    private static String iotHubConnectionStringEnvVarName = "IOTHUB_CONNECTION_STRING";
    private static String iotHubConnectionString = "";
    private static String deviceConnectionString = "";
    private static String deviceId = "java-crud-e2e-test";
    private static RegistryManager registryManager;

    private static final String validMethodName = "commandABC";
    private static final Long responseTimeout = TimeUnit.SECONDS.toSeconds(200);
    private static final Long connectTimeout = TimeUnit.SECONDS.toSeconds(5);
    private static final String payload = "This is a valid payload";

    private static final int METHOD_SUCCESS = 200;
    private static final int METHOD_HUNG = 300;
    private static final int METHOD_THROWS = 403;
    private static final int METHOD_NOT_DEFINED = 404;

    private static IotHubClientProtocol deviceClientProtocol = IotHubClientProtocol.MQTT;
    private static DeviceClient deviceClient;

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

        registryManager = RegistryManager.createFromConnectionString(iotHubConnectionString);

        deviceConnectionString = provisioningDevice();

        deviceClient = new DeviceClient(deviceConnectionString, deviceClientProtocol);
        try
        {
            deviceClient.open();
            deviceClient.subscribeToDeviceMethod(new TestDeviceMethodCallback(), null, new DeviceMethodStatusCallback(), null);
        }
        catch (Exception e)
        {
            removeDevice();
            throw new IOException(e);
        }
    }

    protected static void removeDevice() throws Exception
    {
        deviceClient.close();
        registryManager.removeDevice(deviceId);
    }

    protected static String provisioningDevice() throws Exception
    {
        String uuid = UUID.randomUUID().toString();
        deviceId = deviceId.concat("-" + uuid);

        // Check if device exists with the given name
        Boolean deviceExist = false;
        try
        {
            registryManager.getDevice(deviceId);
            deviceExist = true;
        }
        catch (IotHubException e)
        {
        }
        if (deviceExist)
        {
            try
            {
                registryManager.removeDevice(deviceId);
            } catch (IotHubException|IOException e)
            {
                System.out.println("Initialization failed, could not remove device: " + deviceId);
                e.printStackTrace();
            }
        }

        Device deviceAdded = Device.createFromId(deviceId, null, null);
        registryManager.addDevice(deviceAdded);

        String[] connectionStrings = iotHubConnectionString.split(";");

        return connectionStrings[0] + ";DeviceId=" + deviceAdded.getDeviceId() + ";SharedAccessKey=" + deviceAdded.getPrimaryKey();
    }

    protected static class DeviceMethodStatusCallback implements IotHubEventCallback
    {
        public void execute(IotHubStatusCode status, Object context)
        {
            System.out.println("IoT Hub responded to device method operation with status " + status.name());
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
                case validMethodName:
                {
                    int status;
                    String result;
                    try
                    {
                        result = commandABC(methodData);
                        status = METHOD_SUCCESS;
                    }
                    catch (Exception e)
                    {
                        result = e.getMessage();
                        status = METHOD_THROWS;
                    }
                    deviceMethodData = new DeviceMethodData(status, result);
                    break;
                }
                default:
                {
                    int status = method_default(methodData);
                    deviceMethodData = new DeviceMethodData(status, "executed " + methodName);
                }
            }

            return deviceMethodData;
        }
    }

    private static String commandABC(Object methodData) throws UnsupportedEncodingException
    {
        return "executed commandABC(" + new String((byte[])methodData, "UTF-8") + ")";
    }

    private static int method_default(Object data)
    {
        System.out.println("default method for this device");
        // Insert device specific code here
        return METHOD_NOT_DEFINED;
    }

    @AfterClass
    public static void tearDown() throws Exception
    {
        removeDevice();
    }

    @Test
    public void invokeMethod() throws Exception
    {
        // Arrange
        DeviceMethod methodClient = DeviceMethod.createFromConnectionString(iotHubConnectionString);

        // Act
        MethodResult result = methodClient.invoke(deviceId, validMethodName, responseTimeout, connectTimeout, payload);

        // Assert
        assertNotNull(result);
        assertEquals((long)result.getStatus(), (long)METHOD_SUCCESS);
        assertEquals(result.getPayload(), "executed " + validMethodName + "(\"" + payload + "\")");
    }
}

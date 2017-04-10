/*
* Copyright (c) Microsoft. All rights reserved.
* Licensed under the MIT license. See LICENSE file in the project root for full license information.
*/

package tests.integration.com.microsoft.azure.sdk.iot.service;

import com.microsoft.azure.sdk.iot.device.DeviceClient;
import com.microsoft.azure.sdk.iot.device.DeviceTwin.DeviceMethodCallback;
import com.microsoft.azure.sdk.iot.device.DeviceTwin.DeviceMethodData;
import com.microsoft.azure.sdk.iot.device.IotHubClientProtocol;
import com.microsoft.azure.sdk.iot.device.IotHubEventCallback;
import com.microsoft.azure.sdk.iot.device.IotHubStatusCode;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.util.concurrent.CountDownLatch;

/**
 * Implement a fake device to the end to end test.
 */
public class DeviceEmulator  implements Runnable
{
    private static final String METHOD_LOOPBACK = "loopback";
    private static final String METHOD_DELAY_IN_MILLISECONDS = "delayInMilliseconds";
    private static final String METHOD_UNKNOWN = "unknown";

    private static final int METHOD_SUCCESS = 200;
    private static final int METHOD_THROWS = 403;
    private static final int METHOD_NOT_DEFINED = 404;

    private String connectionString;
    private IotHubClientProtocol protocol;
    private CountDownLatch latch;
    private DeviceClient deviceClient;
    private DeviceStatus deviceStatus;
    private Boolean stopDevice = false;

    public DeviceEmulator(String connectionString, IotHubClientProtocol protocol, CountDownLatch latch) throws URISyntaxException, IOException
    {
        this.connectionString = connectionString;
        this.protocol = protocol;
        this.latch = latch;
        System.out.println("Emulate device: " + this.connectionString);
    }

    @Override
    public void run()
    {
        try
        {
            System.out.println("OPEN:" + this.connectionString);
            this.deviceClient = new DeviceClient(connectionString, protocol);
            deviceClient.open();
            deviceClient.subscribeToDeviceMethod(
                    new TestDeviceMethodCallback(), null,
                    new DeviceMethodStatusCallback(), deviceStatus);
        }
        catch (Exception e)
        {

        }

        while(!stopDevice)
        {
        }

        latch.countDown();
    }

    public void stop() throws IOException
    {
        deviceClient.close();
        stopDevice = true;
    }

    private static class DeviceStatus
    {
        IotHubStatusCode status;
    }

    private static class DeviceMethodStatusCallback implements IotHubEventCallback
    {
        public void execute(IotHubStatusCode status, Object context)
        {
            DeviceStatus deviceStatus = (DeviceStatus)context;
            deviceStatus.status = status;
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


}

package com.skogsrud.halvard.jpasskit.spike;

class DeviceRegistration {
    private final String deviceLibraryIdentifier;
    private final String serialNumber;
    private final String pushToken;

    public DeviceRegistration(String deviceLibraryIdentifier, String serialNumber, String pushToken) {
        this.deviceLibraryIdentifier = deviceLibraryIdentifier;
        this.serialNumber = serialNumber;
        this.pushToken = pushToken;
    }

    public String getDeviceLibraryIdentifier() {
        return deviceLibraryIdentifier;
    }

    public String getSerialNumber() {
        return serialNumber;
    }

    public String getPushToken() {
        return pushToken;
    }
}

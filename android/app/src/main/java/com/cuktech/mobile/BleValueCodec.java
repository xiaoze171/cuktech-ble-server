package com.cuktech.mobile;

final class BleValueCodec {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    static String hex(byte[] value) {
        char[] encoded = new char[value.length * 2];
        for (int index = 0; index < value.length; index++) {
            int unsigned = value[index] & 0xff;
            encoded[index * 2] = HEX[unsigned >>> 4];
            encoded[index * 2 + 1] = HEX[unsigned & 15];
        }
        return new String(encoded);
    }
}

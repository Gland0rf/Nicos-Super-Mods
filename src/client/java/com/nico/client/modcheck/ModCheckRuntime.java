package com.nico.client.modcheck;

import com.nico.client.modcheck.scan.ScanReport;

public final class ModCheckRuntime {
    private static volatile ScanReport report;
    private static volatile boolean acknowledged;

    private ModCheckRuntime() { }

    public static ScanReport report() {
        return report;
    }

    public static void setReport(ScanReport scanReport) {
        report = scanReport;
        acknowledged = false;
    }

    public static boolean acknowledged() {
        return acknowledged;
    }

    public static void acknowledge() {
        acknowledged = true;
    }
}

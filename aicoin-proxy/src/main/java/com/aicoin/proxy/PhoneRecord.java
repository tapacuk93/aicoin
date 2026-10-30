package com.aicoin.proxy;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One wallet's hosted phone, as {@link PhoneStore} keeps it. Plain fields: it is a row, read and
 * written whole by the store, changed only on {@link HostedPhones}' thread.
 */
final class PhoneRecord {

    static final String STARTING = "starting";
    static final String RUNNING = "running";
    static final String SUSPENDING = "suspending";

    String wallet;
    String phoneId;
    String tier;
    String instanceType;
    long coinsPerMinute;
    String state = STARTING;
    /** "" until RunInstances has answered. */
    String instanceId = "";
    /** The instance's public IP, "" until it has one. */
    String host = "";
    /** The anyphoned access token. Only ever shown to this wallet. */
    String token;
    /** When metering started: just before RunInstances was called. */
    long launchedAt;
    /** Minutes charged so far — written only by {@link PhoneStore#charge}. */
    long billedMinutes;
    long chargedCoins;
    /** When the low-balance warning was pushed, 0 if it has not been (or the balance recovered). */
    long warnedAt;
    /** When the balance first could not pay the next minute, 0 if it can. Force-suspend is due a grace period later. */
    long exhaustedAt;
    long version;

    PhoneRecord copy() {
        PhoneRecord c = new PhoneRecord();
        c.wallet = wallet;
        c.phoneId = phoneId;
        c.tier = tier;
        c.instanceType = instanceType;
        c.coinsPerMinute = coinsPerMinute;
        c.state = state;
        c.instanceId = instanceId;
        c.host = host;
        c.token = token;
        c.launchedAt = launchedAt;
        c.billedMinutes = billedMinutes;
        c.chargedCoins = chargedCoins;
        c.warnedAt = warnedAt;
        c.exhaustedAt = exhaustedAt;
        c.version = version;
        return c;
    }

    /** The fields {@link PhoneStore#save} may change. */
    Map<String, String> mutableFields() {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("state", state);
        f.put("instanceId", instanceId);
        f.put("host", host);
        f.put("warnedAt", String.valueOf(warnedAt));
        f.put("exhaustedAt", String.valueOf(exhaustedAt));
        return f;
    }

    Map<String, String> toFields() {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("wallet", wallet);
        f.put("phoneId", phoneId);
        f.put("tier", tier);
        f.put("instanceType", instanceType);
        f.put("coinsPerMinute", String.valueOf(coinsPerMinute));
        f.put("token", token);
        f.put("launchedAt", String.valueOf(launchedAt));
        f.put("billedMinutes", String.valueOf(billedMinutes));
        f.put("chargedCoins", String.valueOf(chargedCoins));
        f.put("version", String.valueOf(version));
        f.putAll(mutableFields());
        return f;
    }

    static PhoneRecord fromFields(Map<String, String> f) {
        PhoneRecord r = new PhoneRecord();
        r.wallet = f.getOrDefault("wallet", "");
        r.phoneId = f.getOrDefault("phoneId", "");
        r.tier = f.getOrDefault("tier", "");
        r.instanceType = f.getOrDefault("instanceType", "");
        r.coinsPerMinute = num(f.get("coinsPerMinute"));
        r.state = f.getOrDefault("state", STARTING);
        r.instanceId = f.getOrDefault("instanceId", "");
        r.host = f.getOrDefault("host", "");
        r.token = f.getOrDefault("token", "");
        r.launchedAt = num(f.get("launchedAt"));
        r.billedMinutes = num(f.get("billedMinutes"));
        r.chargedCoins = num(f.get("chargedCoins"));
        r.warnedAt = num(f.get("warnedAt"));
        r.exhaustedAt = num(f.get("exhaustedAt"));
        r.version = num(f.get("version"));
        return r;
    }

    private static long num(String value) {
        if (value == null || value.isEmpty()) {
            return 0L;
        }
        // HINCRBY leaves integers, but tolerate a float written by hand.
        return value.contains(".") ? (long) Double.parseDouble(value) : Long.parseLong(value);
    }
}

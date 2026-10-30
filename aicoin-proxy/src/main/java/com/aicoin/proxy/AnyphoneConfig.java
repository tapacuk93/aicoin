package com.aicoin.proxy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code anyphone} block: hosted Anyphone phones, launched in the operator's AWS account and
 * paid for by the minute in aicoin. See {@link HostedPhones}.
 *
 * <p>Off by default. With {@code enabled: false} the {@code /phone/*} endpoints answer 404, no AWS
 * client is built and nothing runs on a timer.
 */
final class AnyphoneConfig {

    /** One kind of phone a wallet can start: an instance type and what an hour of it costs. */
    static final class Tier {
        private final String id;
        private final String instanceType;
        private final double usdPerHour;
        private final boolean spot;
        private final boolean available;
        private final boolean nestedVirtualization;
        private final String amiId;

        Tier(String id, String instanceType, double usdPerHour, boolean spot, boolean available,
             boolean nestedVirtualization, String amiId) {
            this.id = id;
            this.instanceType = instanceType;
            this.usdPerHour = usdPerHour;
            this.spot = spot;
            this.available = available;
            this.nestedVirtualization = nestedVirtualization;
            this.amiId = amiId == null ? "" : amiId;
        }

        String getId() {
            return id;
        }

        String getInstanceType() {
            return instanceType;
        }

        /**
         * What an hour of this instance costs the operator: the on-demand price, or for a spot
         * tier the most the operator will pay (it is also sent as the spot request's max price, so
         * AWS can never bill more than the coins were priced from).
         */
        double getUsdPerHour() {
            return usdPerHour;
        }

        boolean isSpot() {
            return spot;
        }

        /** False while the account cannot launch this type (e.g. no GPU quota yet). */
        boolean isAvailable() {
            return available;
        }

        /** The {@code c8i} family runs the emulator under KVM inside the VM, which needs this. */
        boolean isNestedVirtualization() {
            return nestedVirtualization;
        }

        /** This tier's own AMI, or "" to use the block's {@code amiId}. */
        String getAmiId() {
            return amiId;
        }
    }

    private final boolean enabled;
    private final String region;
    private final String amiId;
    private final String subnetId;
    private final String securityGroupId;
    private final String instanceProfile;
    private final String version;
    private final String repo;
    private final String userDataTemplate;
    private final String pushTopic;
    private final double coinValueUsd;
    private final double feeMargin;
    private final int minimumMinutes;
    private final int warnMinutes;
    private final int graceSeconds;
    private final int meterIntervalSeconds;
    private final int startTimeoutSeconds;
    private final Map<String, Tier> tiers;

    AnyphoneConfig(boolean enabled, String region, String amiId, String subnetId, String securityGroupId,
                   String instanceProfile, String version, String repo, String userDataTemplate, String pushTopic,
                   double coinValueUsd, double feeMargin, int minimumMinutes, int warnMinutes, int graceSeconds,
                   int meterIntervalSeconds, int startTimeoutSeconds, Map<String, Tier> tiers) {
        this.enabled = enabled;
        this.region = region;
        this.amiId = amiId;
        this.subnetId = subnetId;
        this.securityGroupId = securityGroupId;
        this.instanceProfile = instanceProfile;
        this.version = version;
        this.repo = repo;
        this.userDataTemplate = userDataTemplate;
        this.pushTopic = pushTopic;
        this.coinValueUsd = coinValueUsd;
        this.feeMargin = feeMargin;
        this.minimumMinutes = minimumMinutes;
        this.warnMinutes = warnMinutes;
        this.graceSeconds = graceSeconds;
        this.meterIntervalSeconds = meterIntervalSeconds;
        this.startTimeoutSeconds = startTimeoutSeconds;
        this.tiers = Collections.unmodifiableMap(new LinkedHashMap<>(tiers));
    }

    /** The bundled defaults, disabled — what a config built without the block gets. */
    static AnyphoneConfig disabled(double coinValueUsd) {
        return new AnyphoneConfig(false, "us-east-1", "", "", "", "", "main", "tapacuk93/anyphone", "",
                "com.oeaio.anyphone", coinValueUsd, AppStorePriceRounding.FEE_MARGIN, 10, 5, 180, 15, 300,
                defaultTiers());
    }

    static Map<String, Tier> defaultTiers() {
        Map<String, Tier> tiers = new LinkedHashMap<>();
        tiers.put("gpu", new Tier("gpu", "g4dn.metal", 7.82, false, false, false, ""));
        tiers.put("cpu", new Tier("cpu", "c8i.2xlarge", 0.38, false, true, true, ""));
        return tiers;
    }

    boolean isEnabled() {
        return enabled;
    }

    String getRegion() {
        return region;
    }

    String getAmiId() {
        return amiId;
    }

    String getSubnetId() {
        return subnetId;
    }

    String getSecurityGroupId() {
        return securityGroupId;
    }

    /** Instance profile name or ARN for the phone, or "" for none (a hosted phone needs no AWS access). */
    String getInstanceProfile() {
        return instanceProfile;
    }

    /** {@code ANYPHONE_VERSION}: the anyphone git ref the bootstrap installs. */
    String getVersion() {
        return version;
    }

    /** {@code ANYPHONE_REPO}: owner/name on GitHub. */
    String getRepo() {
        return repo;
    }

    /** Path of a user-data template file, or "" for the bundled {@code anyphone/user-data.sh}. */
    String getUserDataTemplate() {
        return userDataTemplate;
    }

    /** The Anyphone app's bundle id: low-balance warnings go to devices registered under it. */
    String getPushTopic() {
        return pushTopic;
    }

    /** What one coin is taken to be worth — {@code pricing.coinValueUsd} unless set here. */
    double getCoinValueUsd() {
        return coinValueUsd;
    }

    /** Margin over the AWS cost — {@link AppStorePriceRounding#FEE_MARGIN} unless set here. */
    double getFeeMargin() {
        return feeMargin;
    }

    /** A phone starts only when the balance covers this many minutes. */
    int getMinimumMinutes() {
        return minimumMinutes;
    }

    /** A warning is pushed when the balance covers fewer than this many minutes. */
    int getWarnMinutes() {
        return warnMinutes;
    }

    /** How long a phone keeps running once the balance cannot pay the next minute. */
    int getGraceSeconds() {
        return graceSeconds;
    }

    int getMeterIntervalSeconds() {
        return meterIntervalSeconds;
    }

    /** A record that has no instance this long after it was created is abandoned. */
    int getStartTimeoutSeconds() {
        return startTimeoutSeconds;
    }

    Map<String, Tier> getTiers() {
        return tiers;
    }

    Tier tier(String id) {
        return id == null ? null : tiers.get(id);
    }

    /** The AMI a tier launches from. */
    String amiFor(Tier tier) {
        return tier.getAmiId().isEmpty() ? amiId : tier.getAmiId();
    }
}

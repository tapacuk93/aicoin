package com.aicoin.proxy;

import java.util.List;

/**
 * The EC2 calls a hosted phone needs: launch one, list them, terminate one. {@link Ec2PhoneCloud}
 * in the proxy, a fake in tests — nothing in a test ever reaches AWS.
 *
 * <p>Calls block; {@link HostedPhones} makes them on its own thread.
 */
interface PhoneCloud {

    /** Tags every hosted instance carries. The reaper finds instances by the first. */
    String TAG_HOSTED = "anyphone:hosted";
    String TAG_WALLET = "anyphone:wallet";
    String TAG_PHONE_ID = "anyphone:phoneId";

    /** What to launch. */
    record Launch(String wallet, String phoneId, AnyphoneConfig.Tier tier, String userData) {
    }

    /** One hosted instance as EC2 reports it. {@code publicIp} is "" until it has one. */
    record Instance(String instanceId, String state, String publicIp, String wallet, String phoneId) {
        boolean isRunning() {
            return "running".equals(state);
        }

        /** Terminated, or on its way: nothing left to do with it, and it bills no further. */
        boolean isGone() {
            return "terminated".equals(state) || "shutting-down".equals(state);
        }
    }

    /** Launches one instance and returns its id. Throws with AWS's message when it cannot. */
    String launch(Launch launch);

    /** Every instance tagged {@link #TAG_HOSTED}, terminated ones excluded. */
    List<Instance> list();

    void terminate(String instanceId);
}

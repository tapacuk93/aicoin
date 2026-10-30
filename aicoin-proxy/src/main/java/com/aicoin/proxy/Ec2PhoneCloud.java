package com.aicoin.proxy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CpuOptionsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.HttpTokensState;
import software.amazon.awssdk.services.ec2.model.IamInstanceProfileSpecification;
import software.amazon.awssdk.services.ec2.model.InstanceInterruptionBehavior;
import software.amazon.awssdk.services.ec2.model.InstanceMarketOptionsRequest;
import software.amazon.awssdk.services.ec2.model.InstanceMetadataOptionsRequest;
import software.amazon.awssdk.services.ec2.model.InstanceNetworkInterfaceSpecification;
import software.amazon.awssdk.services.ec2.model.MarketType;
import software.amazon.awssdk.services.ec2.model.NestedVirtualizationSpecification;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.RunInstancesResponse;
import software.amazon.awssdk.services.ec2.model.ShutdownBehavior;
import software.amazon.awssdk.services.ec2.model.SpotInstanceType;
import software.amazon.awssdk.services.ec2.model.SpotMarketOptions;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;
import software.amazon.awssdk.services.ec2.model.TerminateInstancesRequest;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;

/**
 * {@link PhoneCloud} on EC2, AWS SDK v2. Credentials come from the SDK's default chain — in
 * production the {@code AWS_ACCESS_KEY_ID} / {@code AWS_SECRET_ACCESS_KEY} of an IAM user limited
 * to the permissions in the README's "Hosted Anyphone" section.
 */
final class Ec2PhoneCloud implements PhoneCloud {

    private final Ec2Client ec2;
    private final AnyphoneConfig config;

    Ec2PhoneCloud(Ec2Client ec2, AnyphoneConfig config) {
        this.ec2 = ec2;
        this.config = config;
    }

    static Ec2PhoneCloud create(AnyphoneConfig config) {
        Ec2Client client = Ec2Client.builder()
                .region(Region.of(config.getRegion()))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
        return new Ec2PhoneCloud(client, config);
    }

    /** The RunInstances request for a launch — separate so a test can read it without a client. */
    RunInstancesRequest request(Launch launch) {
        AnyphoneConfig.Tier tier = launch.tier();
        List<Tag> tags = List.of(
                Tag.builder().key("Name").value("anyphone-" + launch.phoneId()).build(),
                Tag.builder().key(TAG_HOSTED).value("true").build(),
                Tag.builder().key(TAG_WALLET).value(launch.wallet()).build(),
                Tag.builder().key(TAG_PHONE_ID).value(launch.phoneId()).build(),
                Tag.builder().key("anyphone:tier").value(tier.getId()).build());
        RunInstancesRequest.Builder b = RunInstancesRequest.builder()
                .imageId(config.amiFor(tier))
                .instanceType(tier.getInstanceType())
                .minCount(1)
                .maxCount(1)
                // A public address on the interface rather than relying on the subnet's default:
                // the phone is reached directly on port 443.
                .networkInterfaces(InstanceNetworkInterfaceSpecification.builder()
                        .deviceIndex(0)
                        .subnetId(config.getSubnetId())
                        .groups(config.getSecurityGroupId())
                        .associatePublicIpAddress(true)
                        .deleteOnTermination(true)
                        .build())
                .userData(Base64.getEncoder().encodeToString(launch.userData().getBytes(StandardCharsets.UTF_8)))
                // A phone that powers itself off is finished, not parked: a stopped instance
                // would keep its volume (and the user's data) in the operator's account.
                .instanceInitiatedShutdownBehavior(ShutdownBehavior.TERMINATE)
                .metadataOptions(InstanceMetadataOptionsRequest.builder()
                        .httpTokens(HttpTokensState.REQUIRED)
                        .httpPutResponseHopLimit(1)
                        .build())
                // Idempotent per phone: a retried call can never start a second instance.
                .clientToken(launch.phoneId())
                .tagSpecifications(
                        TagSpecification.builder().resourceType(ResourceType.INSTANCE).tags(tags).build(),
                        TagSpecification.builder().resourceType(ResourceType.VOLUME).tags(tags).build());
        if (!config.getInstanceProfile().isEmpty()) {
            IamInstanceProfileSpecification.Builder profile = IamInstanceProfileSpecification.builder();
            if (config.getInstanceProfile().startsWith("arn:")) {
                profile.arn(config.getInstanceProfile());
            } else {
                profile.name(config.getInstanceProfile());
            }
            b.iamInstanceProfile(profile.build());
        }
        if (tier.isNestedVirtualization()) {
            b.cpuOptions(CpuOptionsRequest.builder()
                    .nestedVirtualization(NestedVirtualizationSpecification.ENABLED).build());
        }
        if (tier.isSpot()) {
            b.instanceMarketOptions(InstanceMarketOptionsRequest.builder()
                    .marketType(MarketType.SPOT)
                    .spotOptions(SpotMarketOptions.builder()
                            .spotInstanceType(SpotInstanceType.ONE_TIME)
                            .instanceInterruptionBehavior(InstanceInterruptionBehavior.TERMINATE)
                            // Never more than the coins were priced from.
                            .maxPrice(String.format(java.util.Locale.ROOT, "%.4f", tier.getUsdPerHour()))
                            .build())
                    .build());
        }
        return b.build();
    }

    @Override
    public String launch(Launch launch) {
        RunInstancesResponse response = ec2.runInstances(request(launch));
        if (response.instances().isEmpty()) {
            throw new IllegalStateException("RunInstances returned no instance");
        }
        return response.instances().get(0).instanceId();
    }

    @Override
    public List<Instance> list() {
        List<Instance> out = new ArrayList<>();
        String next = null;
        do {
            DescribeInstancesResponse page = ec2.describeInstances(DescribeInstancesRequest.builder()
                    .filters(Filter.builder().name("tag:" + TAG_HOSTED).values("true").build(),
                            Filter.builder().name("instance-state-name")
                                    .values("pending", "running", "shutting-down", "stopping", "stopped").build())
                    .nextToken(next)
                    .build());
            for (Reservation reservation : page.reservations()) {
                for (software.amazon.awssdk.services.ec2.model.Instance i : reservation.instances()) {
                    String wallet = "";
                    String phoneId = "";
                    for (Tag tag : i.tags()) {
                        if (TAG_WALLET.equals(tag.key())) {
                            wallet = tag.value();
                        } else if (TAG_PHONE_ID.equals(tag.key())) {
                            phoneId = tag.value();
                        }
                    }
                    out.add(new Instance(i.instanceId(), i.state() == null ? "" : i.state().nameAsString(),
                            i.publicIpAddress() == null ? "" : i.publicIpAddress(), wallet, phoneId));
                }
            }
            next = page.nextToken();
        } while (next != null && !next.isEmpty());
        return out;
    }

    @Override
    public void terminate(String instanceId) {
        ec2.terminateInstances(TerminateInstancesRequest.builder().instanceIds(instanceId).build());
    }
}

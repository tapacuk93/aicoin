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
import software.amazon.awssdk.services.ec2.model.BlockDeviceMapping;
import software.amazon.awssdk.services.ec2.model.DescribeImagesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeImagesResponse;
import software.amazon.awssdk.services.ec2.model.EbsBlockDevice;
import software.amazon.awssdk.services.ec2.model.Image;
import software.amazon.awssdk.services.ec2.model.VolumeType;
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
                .imageId(launch.imageId())
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
                // The phone's data lives on the root volume: encrypted, and gone with the instance.
                .blockDeviceMappings(BlockDeviceMapping.builder()
                        .deviceName("/dev/sda1")
                        .ebs(EbsBlockDevice.builder()
                                .volumeType(VolumeType.GP3)
                                .volumeSize(config.getRootVolumeGb())
                                .encrypted(true)
                                .deleteOnTermination(true)
                                .build())
                        .build())
                .userData(Base64.getEncoder().encodeToString(launch.userData().getBytes(StandardCharsets.UTF_8)))
                // Stop, not terminate, as anyphone's hosted contract asks: a poweroff must never
                // destroy a phone that was not backed up. The meter treats a stopped phone as
                // ended and terminates it itself (HostedPhones).
                .instanceInitiatedShutdownBehavior(ShutdownBehavior.STOP)
                .metadataOptions(InstanceMetadataOptionsRequest.builder()
                        .httpTokens(HttpTokensState.REQUIRED)
                        .httpPutResponseHopLimit(1)
                        .build())
                // Idempotent per phone: a retried call can never start a second instance.
                .clientToken(launch.phoneId())
                .tagSpecifications(
                        TagSpecification.builder().resourceType(ResourceType.INSTANCE).tags(tags).build(),
                        TagSpecification.builder().resourceType(ResourceType.VOLUME).tags(tags).build());
        // No instance profile: a hosted phone calls no AWS API.
        // Nested virtualization for the c8i family only; never on metal (g4dn.metal), where the
        // option does not exist.
        if (tier.isNestedVirtualization() && !tier.getInstanceType().endsWith(".metal")) {
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
    public String newestBakedImage(String version, String gpu) {
        DescribeImagesResponse response = ec2.describeImages(DescribeImagesRequest.builder()
                .owners("self")
                .filters(Filter.builder().name("tag:anyphone:baked").values("1").build(),
                        Filter.builder().name("tag:anyphone:version").values(version).build(),
                        Filter.builder().name("tag:anyphone:gpu").values(gpu).build(),
                        Filter.builder().name("state").values("available").build())
                .build());
        Image newest = null;
        for (Image image : response.images()) {
            // ISO-8601 timestamps compare correctly as strings.
            if (newest == null || String.valueOf(image.creationDate()).compareTo(String.valueOf(newest.creationDate())) > 0) {
                newest = image;
            }
        }
        return newest == null ? null : newest.imageId();
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

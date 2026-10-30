package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceStateName;
import software.amazon.awssdk.services.ec2.model.MarketType;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.RunInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;
import software.amazon.awssdk.services.ec2.model.TerminateInstancesRequest;
import software.amazon.awssdk.services.ec2.model.TerminateInstancesResponse;

/** {@link Ec2PhoneCloud} against an {@link Ec2Client} stand-in that records requests and never reaches AWS. */
class Ec2PhoneCloudTest {

    private final List<Object> requests = new ArrayList<>();
    private final List<DescribeInstancesResponse> pages = new ArrayList<>();

    /**
     * Every Ec2Client operation is a default method that throws, so overriding the three used is a
     * complete stand-in. (A java.lang.reflect.Proxy cannot be used: the interface has too many
     * methods for a generated proxy class.)
     */
    private Ec2Client client() {
        return new Ec2Client() {
            @Override
            public String serviceName() {
                return "ec2";
            }

            @Override
            public void close() {
            }

            @Override
            public RunInstancesResponse runInstances(RunInstancesRequest request) {
                requests.add(request);
                return RunInstancesResponse.builder().instances(Instance.builder().instanceId("i-0abc").build()).build();
            }

            @Override
            public DescribeInstancesResponse describeInstances(DescribeInstancesRequest request) {
                requests.add(request);
                return pages.remove(0);
            }

            @Override
            public TerminateInstancesResponse terminateInstances(TerminateInstancesRequest request) {
                requests.add(request);
                return TerminateInstancesResponse.builder().build();
            }
        };
    }

    private static AnyphoneConfig config(String instanceProfile) {
        return new AnyphoneConfig(true, "us-east-1", "ami-default", "subnet-9", "sg-9", instanceProfile, "v1.2",
                "tapacuk93/anyphone", "", "com.oeaio.anyphone", 0.009, 0.5, 10, 5, 180, 15, 300,
                AnyphoneConfig.defaultTiers());
    }

    private static Map<String, String> tags(TagSpecification spec) {
        return spec.tags().stream().collect(Collectors.toMap(Tag::key, Tag::value));
    }

    @Test
    void launchesTheCpuTierOnDemandWithNestedVirtualizationTagsAndUserData() {
        AnyphoneConfig config = config("anyphone-phone");
        Ec2PhoneCloud cloud = new Ec2PhoneCloud(client(), config);
        String id = cloud.launch(new PhoneCloud.Launch("wallet1", "ph-42", config.tier("cpu"), "#!/bin/bash\necho hi\n"));
        assertEquals("i-0abc", id);

        RunInstancesRequest r = (RunInstancesRequest) requests.get(0);
        assertEquals("ami-default", r.imageId());
        assertEquals("c8i.2xlarge", r.instanceTypeAsString());
        assertEquals(1, r.minCount());
        assertEquals(1, r.maxCount());
        assertEquals("ph-42", r.clientToken());
        assertEquals("terminate", r.instanceInitiatedShutdownBehaviorAsString());
        assertEquals("required", r.metadataOptions().httpTokensAsString());
        assertEquals("enabled", r.cpuOptions().nestedVirtualizationAsString());
        assertNull(r.instanceMarketOptions(), "on-demand");
        assertEquals("anyphone-phone", r.iamInstanceProfile().name());
        assertEquals("#!/bin/bash\necho hi\n",
                new String(Base64.getDecoder().decode(r.userData()), StandardCharsets.UTF_8));

        assertEquals(1, r.networkInterfaces().size());
        assertEquals("subnet-9", r.networkInterfaces().get(0).subnetId());
        assertEquals(List.of("sg-9"), r.networkInterfaces().get(0).groups());
        assertTrue(r.networkInterfaces().get(0).associatePublicIpAddress());

        assertEquals(2, r.tagSpecifications().size());
        assertEquals(ResourceType.INSTANCE, r.tagSpecifications().get(0).resourceType());
        assertEquals(ResourceType.VOLUME, r.tagSpecifications().get(1).resourceType());
        for (TagSpecification spec : r.tagSpecifications()) {
            Map<String, String> tags = tags(spec);
            assertEquals("true", tags.get("anyphone:hosted"));
            assertEquals("wallet1", tags.get("anyphone:wallet"));
            assertEquals("ph-42", tags.get("anyphone:phoneId"));
            assertEquals("cpu", tags.get("anyphone:tier"));
            assertEquals("anyphone-ph-42", tags.get("Name"));
        }
    }

    @Test
    void aSpotTierCapsThePriceAtWhatTheCoinsWerePricedFrom() {
        AnyphoneConfig base = config("");
        AnyphoneConfig.Tier spot = new AnyphoneConfig.Tier("gpu", "g4dn.metal", 4.5, true, true, false, "ami-gpu");
        Ec2PhoneCloud cloud = new Ec2PhoneCloud(client(), base);
        cloud.launch(new PhoneCloud.Launch("w", "ph-1", spot, "x"));
        RunInstancesRequest r = (RunInstancesRequest) requests.get(0);
        assertEquals("ami-gpu", r.imageId(), "a tier's own AMI wins");
        assertEquals(MarketType.SPOT, r.instanceMarketOptions().marketType());
        assertEquals("4.5000", r.instanceMarketOptions().spotOptions().maxPrice());
        assertEquals("one-time", r.instanceMarketOptions().spotOptions().spotInstanceTypeAsString());
        assertEquals("terminate", r.instanceMarketOptions().spotOptions().instanceInterruptionBehaviorAsString());
        assertNull(r.cpuOptions(), "metal needs no nested virtualization");
        assertNull(r.iamInstanceProfile(), "no instance profile configured");
    }

    @Test
    void listsHostedInstancesAcrossPagesWithTheirOwners() {
        pages.add(DescribeInstancesResponse.builder()
                .reservations(Reservation.builder().instances(Instance.builder().instanceId("i-1")
                        .state(s -> s.name(InstanceStateName.RUNNING)).publicIpAddress("203.0.113.5")
                        .tags(Tag.builder().key("anyphone:wallet").value("wA").build(),
                                Tag.builder().key("anyphone:phoneId").value("ph-a").build()).build()).build())
                .nextToken("page2").build());
        pages.add(DescribeInstancesResponse.builder()
                .reservations(Reservation.builder().instances(Instance.builder().instanceId("i-2")
                        .state(s -> s.name(InstanceStateName.PENDING)).build()).build())
                .build());
        List<PhoneCloud.Instance> list = new Ec2PhoneCloud(client(), config("")).list();
        assertEquals(List.of(new PhoneCloud.Instance("i-1", "running", "203.0.113.5", "wA", "ph-a"),
                new PhoneCloud.Instance("i-2", "pending", "", "", "")), list);

        DescribeInstancesRequest first = (DescribeInstancesRequest) requests.get(0);
        assertEquals("tag:anyphone:hosted", first.filters().get(0).name());
        assertEquals(List.of("true"), first.filters().get(0).values());
        assertNull(first.nextToken());
        assertEquals("page2", ((DescribeInstancesRequest) requests.get(1)).nextToken());
    }

    @Test
    void terminatesByInstanceId() {
        new Ec2PhoneCloud(client(), config("")).terminate("i-9");
        assertEquals(List.of("i-9"), ((TerminateInstancesRequest) requests.get(0)).instanceIds());
    }
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.broker.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;
import java.io.Closeable;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.Cleanup;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.api.ClientBuilder;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.ProducerConsumerBase;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.PulsarClientException.NotAllowedException;
import org.apache.pulsar.client.impl.LookupService;
import org.apache.pulsar.client.impl.LookupTopicResult;
import org.apache.pulsar.client.impl.PulsarClientImpl;
import org.apache.pulsar.common.naming.NamespaceName;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.common.partition.PartitionedTopicMetadata;
import org.apache.pulsar.common.policies.data.AutoTopicCreationOverride;
import org.apache.pulsar.common.policies.data.TopicType;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

@Test(groups = "broker-admin")
@Slf4j
public class TopicAutoCreationTest extends ProducerConsumerBase {

    @Override
    @BeforeClass
    protected void setup() throws Exception {
        conf.setAllowAutoTopicCreationType(TopicType.PARTITIONED);
        conf.setAllowAutoTopicCreation(true);
        conf.setDefaultNumPartitions(3);
        conf.setForceDeleteNamespaceAllowed(true);
        super.internalSetup();
        super.producerBaseSetup();
    }

    @Override
    protected void customizeNewPulsarClientBuilder(ClientBuilder clientBuilder) {
        clientBuilder.operationTimeout(2, TimeUnit.SECONDS);
    }

    @Override
    @AfterClass(alwaysRun = true)
    protected void cleanup() throws Exception {
        super.internalCleanup();
    }

    @Test
    public void testPartitionedTopicAutoCreation() throws PulsarAdminException, PulsarClientException {
        final String namespaceName = "my-property/my-ns";
        final String topic = "persistent://" + namespaceName + "/test-partitioned-topi-auto-creation-"
                + UUID.randomUUID().toString();

        Producer<byte[]> producer = pulsarClient.newProducer()
                .topic(topic)
                .create();

        List<String> partitionedTopics = admin.topics().getPartitionedTopicList(namespaceName);
        assertTrue(partitionedTopics.contains(topic));
        List<String> topics = admin.topics().getList(namespaceName);
        for (int i = 0; i < conf.getDefaultNumPartitions(); i++) {
            assertTrue(topics.contains(topic + TopicName.PARTITIONED_TOPIC_SUFFIX + i));
        }

        producer.close();
        for (String t : topics) {
            admin.topics().delete(t);
        }

        admin.topics().deletePartitionedTopic(topic);


        final String partition = "persistent://" + namespaceName + "/test-partitioned-topi-auto-creation-partition-0";

        // The Pulsar doesn't automatically create the metadata for the single partition, so the producer creation
        // will fail.
        assertThrows(NotAllowedException.class, () -> {
            @Cleanup
            Producer<byte[]> ignored = pulsarClient.newProducer()
                    .topic(partition)
                    .create();
        });
    }


    @Test
    public void testPartitionedTopicAutoCreationForbiddenDuringNamespaceDeletion()
            throws Exception {
        final String namespaceName = "my-property/my-ns";
        final String topic = "persistent://" + namespaceName + "/test-partitioned-topi-auto-creation-"
                + UUID.randomUUID().toString();

        pulsar.getPulsarResources().getNamespaceResources()
                .setPolicies(NamespaceName.get(namespaceName), old -> {
            old.deleted = true;
            return old;
        });


        LookupService original = ((PulsarClientImpl) pulsarClient).getLookup();
        try {

            // we want to skip the "lookup" phase, because it is blocked by the HTTP API
            LookupService mockLookup = mock(LookupService.class);
            ((PulsarClientImpl) pulsarClient).setLookup(mockLookup);
            when(mockLookup.getPartitionedTopicMetadata(any(), anyBoolean())).thenAnswer(
                    i -> CompletableFuture.completedFuture(new PartitionedTopicMetadata(0)));
            when(mockLookup.getPartitionedTopicMetadata(any(), anyBoolean(), anyBoolean())).thenAnswer(
                    i -> CompletableFuture.completedFuture(new PartitionedTopicMetadata(0)));
            when(mockLookup.getBroker(any())).thenAnswer(ignored -> {
                InetSocketAddress brokerAddress =
                        new InetSocketAddress(pulsar.getAdvertisedAddress(), pulsar.getBrokerListenPort().get());
                return CompletableFuture.completedFuture(new LookupTopicResult(brokerAddress, brokerAddress, false));
            });
            final String topicPoliciesServiceInitException =
                    "Topic creation encountered an exception by initialize topic policies service";

            // Creating a producer and creating a Consumer may trigger automatic topic
            // creation, let's try to create a Producer and a Consumer
            try (Producer<byte[]> ignored = pulsarClient.newProducer()
                    .sendTimeout(1, TimeUnit.SECONDS)
                    .topic(topic)
                    .create()) {
            } catch (PulsarClientException.TopicDoesNotExistException expected) {
                // Since the "policies.deleted" is "true", the value of "isAllowAutoTopicCreationAsync" will be false,
                // so the "TopicDoesNotExistException" is expected.
                log.info("Expected error", expected);
                assertTrue(expected.getMessage().contains(topic)
                        || expected.getMessage().contains(topicPoliciesServiceInitException));
            }

            try (Consumer<byte[]> ignored = pulsarClient.newConsumer()
                    .topic(topic)
                    .subscriptionName("test")
                    .subscribe()) {
            } catch (PulsarClientException.TopicDoesNotExistException expected) {
                // Since the "policies.deleted" is "true", the value of "isAllowAutoTopicCreationAsync" will be false,
                // so the "TopicDoesNotExistException" is expected.
                log.info("Expected error", expected);
                assertTrue(expected.getMessage().contains(topic)
                        || expected.getMessage().contains(topicPoliciesServiceInitException));
            }


            // verify that the topic does not exist
            pulsar.getPulsarResources().getNamespaceResources()
                    .setPolicies(NamespaceName.get(namespaceName), old -> {
                        old.deleted = false;
                        return old;
                    });

            admin.topics().getList(namespaceName).isEmpty();

            // create now the topic using auto creation
            ((PulsarClientImpl) pulsarClient).setLookup(original);
            try (Consumer<byte[]> ignored = pulsarClient.newConsumer()
                    .topic(topic)
                    .subscriptionName("test")
                    .subscribe()) {
            }

            admin.topics().getList(namespaceName).contains(topic);
        } finally {
            ((PulsarClientImpl) pulsarClient).setLookup(original);
        }

    }

    @Test
    public void testClientWithAutoCreationGotNotFoundException() throws PulsarAdminException, PulsarClientException {
        final String namespace = "public/test_1";
        final String topicName = "persistent://public/test_1/test_auto_creation_got_not_found"
                + System.currentTimeMillis();
        final int retryTimes = 30;
        admin.namespaces().createNamespace(namespace);
        admin.namespaces().setAutoTopicCreation(namespace, AutoTopicCreationOverride.builder()
                .allowAutoTopicCreation(true)
                .topicType("non-partitioned")
                .build());

        @Cleanup("shutdown")
        final ExecutorService executor1 = Executors.newSingleThreadExecutor();

        @Cleanup("shutdown")
        final ExecutorService executor2 = Executors.newSingleThreadExecutor();

        for (int i = 0; i < retryTimes; i++) {
            final CompletableFuture<Void> adminListSub = CompletableFuture.runAsync(() -> {
                try {
                    admin.topics().getSubscriptions(topicName);
                } catch (PulsarAdminException e) {
                    throw new RuntimeException(e);
                }
            }, executor1);

            final CompletableFuture<Consumer<byte[]>> consumerSub = CompletableFuture.supplyAsync(() -> {
                try {
                    return pulsarClient.newConsumer()
                            .topic(topicName)
                            .subscriptionName("sub-1")
                            .subscribe();
                } catch (PulsarClientException e) {
                    throw new RuntimeException(e);
                }
            }, executor2);

            try {
                adminListSub.join();
            } catch (Throwable ex) {
                // we don't care the exception.
            }

            consumerSub.join().close();
            admin.topics().delete(topicName, true);
        }

        admin.namespaces().deleteNamespace(namespace, true);
    }

    @Test
    public void testPartitionsNotCreatedAfterDeletion() throws Exception {
        @Cleanup final var client = PulsarClient.builder().serviceUrl(pulsar.getBrokerServiceUrl()).build();
        final var topicName = TopicName.get("my-property/my-ns/testPartitionsNotCreatedAfterDeletion");
        final var topic = topicName.toString();
        final var interval = Duration.ofSeconds(1);
        final ThrowableConsumer<ThrowableSupplier<Closeable>> verifier = creator -> {
            admin.topics().createPartitionedTopic(topic, 1);
            try (final var ignored = creator.get()) {
                admin.topics().terminatePartitionedTopic(topic);
                admin.topics().deletePartitionedTopic(topic, true);
                Thread.sleep(interval.toMillis() + 500); // wait until the auto update partitions task has run

                final var topics = admin.topics().getList(topicName.getNamespace()).stream()
                        .filter(__ -> __.contains(topicName.getLocalName())).toList();
                assertTrue(topics.isEmpty(), "topics are " + topics);
            }
        };
        verifier.accept(() -> client.newProducer().topic(topic)
                .autoUpdatePartitionsInterval(interval.toSecondsPart(), TimeUnit.SECONDS).create());
        verifier.accept(() -> client.newConsumer().topic(topic).subscriptionName("sub")
                .autoUpdatePartitionsInterval(interval.toSecondsPart(), TimeUnit.SECONDS).subscribe());
        verifier.accept(() -> client.newReader().topic(topic).startMessageId(MessageId.earliest)
                .autoUpdatePartitionsInterval(interval.toSecondsPart(), TimeUnit.SECONDS).create());
    }

    private interface ThrowableConsumer<T> {

        void accept(T value) throws Exception;
    }

    public interface ThrowableSupplier<T> {

        T get() throws Exception;
    }
}

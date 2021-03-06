/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package storm.kafka.bolt;

import backtype.storm.Config;
import backtype.storm.Constants;
import backtype.storm.task.GeneralTopologyContext;
import backtype.storm.task.IOutputCollector;
import backtype.storm.task.OutputCollector;
import backtype.storm.topology.TopologyBuilder;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Tuple;
import backtype.storm.tuple.TupleImpl;
import backtype.storm.tuple.Values;
import backtype.storm.utils.TupleUtils;
import backtype.storm.utils.Utils;
import kafka.api.OffsetRequest;
import kafka.javaapi.consumer.SimpleConsumer;
import kafka.javaapi.message.ByteBufferMessageSet;
import kafka.message.Message;
import kafka.message.MessageAndOffset;
import org.junit.*;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import storm.kafka.*;
import storm.kafka.trident.GlobalPartitionInformation;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class KafkaBoltTest {

    private static final String TEST_TOPIC = "test-topic";
    private KafkaTestBroker broker;
    private KafkaBolt bolt;
    private Config config = new Config();
    private KafkaConfig kafkaConfig;
    private SimpleConsumer simpleConsumer;

    @Mock
    private IOutputCollector collector;

    @Before
    public void initMocks() {
        MockitoAnnotations.initMocks(this);
        broker = new KafkaTestBroker();
        setupKafkaConsumer();
        config.put(KafkaBolt.TOPIC, TEST_TOPIC);
        bolt = generateStringSerializerBolt();
    }

    @After
    public void shutdown() {
        simpleConsumer.close();
        broker.shutdown();
        bolt.cleanup();
    }

    private void setupKafkaConsumer() {
        GlobalPartitionInformation globalPartitionInformation = new GlobalPartitionInformation();
        globalPartitionInformation.addPartition(0, Broker.fromString(broker.getBrokerConnectionString()));
        BrokerHosts brokerHosts = new StaticHosts(globalPartitionInformation);
        kafkaConfig = new KafkaConfig(brokerHosts, TEST_TOPIC);
        simpleConsumer = new SimpleConsumer("localhost", broker.getPort(), 60000, 1024, "testClient");
    }

    @Test
    public void shouldAcknowledgeTickTuples() throws Exception {
        // Given
        Tuple tickTuple = mockTickTuple();

        // When
        bolt.execute(tickTuple);

        // Then
        verify(collector).ack(tickTuple);
    }

    @Test
    public void executeWithKey() throws Exception {
        String message = "value-123";
        String key = "key-123";
        Tuple tuple = generateTestTuple(key, message);
        bolt.execute(tuple);
        verify(collector).ack(tuple);
        verifyMessage(key, message);
    }

    /* test synchronous sending */
    @Test
    public void executeWithByteArrayKeyAndMessageSync() {
        boolean async = false;
        boolean fireAndForget = false;
        bolt = generateDefaultSerializerBolt(async, fireAndForget);
        String keyString = "test-key";
        String messageString = "test-message";
        byte[] key = keyString.getBytes();
        byte[] message = messageString.getBytes();
        Tuple tuple = generateTestTuple(key, message);
        bolt.execute(tuple);
        verify(collector).ack(tuple);
        verifyMessage(keyString, messageString);
    }

    /* test asynchronous sending (default) */
    @Test
    public void executeWithByteArrayKeyAndMessageAsync() {
        boolean async = true;
        boolean fireAndForget = false;
        bolt = generateDefaultSerializerBolt(async, fireAndForget);
        String keyString = "test-key";
        String messageString = "test-message";
        byte[] key = keyString.getBytes();
        byte[] message = messageString.getBytes();
        Tuple tuple = generateTestTuple(key, message);
        bolt.execute(tuple);
        try {
            Thread.sleep(1000);                 
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        verify(collector).ack(tuple);
        verifyMessage(keyString, messageString);
    }

    /* test with fireAndForget option enabled */
    @Test
    public void executeWithByteArrayKeyAndMessageFire() {
        boolean async = true;
        boolean fireAndForget = true;
        bolt = generateDefaultSerializerBolt(async, fireAndForget);
        String keyString = "test-key";
        String messageString = "test-message";
        byte[] key = keyString.getBytes();
        byte[] message = messageString.getBytes();
        Tuple tuple = generateTestTuple(key, message);
        bolt.execute(tuple);
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        verify(collector).ack(tuple);
        verifyMessage(keyString, messageString);
    }

    /* test bolt specified properties */
    @Test
    public void executeWithBoltSpecifiedProperties() {
        boolean async = false;
        boolean fireAndForget = false;
        bolt = defaultSerializerBoltWithSpecifiedProperties(async, fireAndForget);
        String keyString = "test-key";
        String messageString = "test-message";
        byte[] key = keyString.getBytes();
        byte[] message = messageString.getBytes();
        Tuple tuple = generateTestTuple(key, message);
        bolt.execute(tuple);
        verify(collector).ack(tuple);
        verifyMessage(keyString, messageString);
    }

    private KafkaBolt generateStringSerializerBolt() {
        KafkaBolt bolt = new KafkaBolt();
        Properties props = new Properties();
        props.put("request.required.acks", "1");
        props.put("serializer.class", "kafka.serializer.StringEncoder");
        props.put("bootstrap.servers", broker.getBrokerConnectionString());
        props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("metadata.fetch.timeout.ms", 1000);
        config.put(KafkaBolt.KAFKA_BROKER_PROPERTIES, props);
        bolt.prepare(config, null, new OutputCollector(collector));
        bolt.setAsync(false);
        return bolt;
    }

    private KafkaBolt generateDefaultSerializerBolt(boolean async, boolean fireAndForget) {
        KafkaBolt bolt = new KafkaBolt();
        Properties props = new Properties();
        props.put("request.required.acks", "1");
        props.put("serializer.class", "kafka.serializer.StringEncoder");
        props.put("bootstrap.servers", broker.getBrokerConnectionString());
        props.put("key.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer");
        props.put("value.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer");
        props.put("metadata.fetch.timeout.ms", 1000);
        props.put("linger.ms", 0);
        config.put(KafkaBolt.KAFKA_BROKER_PROPERTIES, props);
        bolt.prepare(config, null, new OutputCollector(collector));
        bolt.setAsync(async);
        bolt.setFireAndForget(fireAndForget);
        return bolt;
    }

    private KafkaBolt defaultSerializerBoltWithSpecifiedProperties(boolean async, boolean fireAndForget) {
        Properties props = new Properties();
        props.put("request.required.acks", "1");
        props.put("serializer.class", "kafka.serializer.StringEncoder");
        props.put("bootstrap.servers", broker.getBrokerConnectionString());
        props.put("key.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer");
        props.put("value.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer");
        props.put("metadata.fetch.timeout.ms", 1000);
        props.put("linger.ms", 0);
        KafkaBolt bolt = new KafkaBolt().withProducerProperties(props);
        bolt.prepare(config, null, new OutputCollector(collector));
        bolt.setAsync(async);
        bolt.setFireAndForget(fireAndForget);
        return bolt;
    }

    @Test
    public void executeWithoutKey() throws Exception {
        String message = "value-234";
        Tuple tuple = generateTestTuple(message);
        bolt.execute(tuple);
        verify(collector).ack(tuple);
        verifyMessage(null, message);
    }


    @Test
    public void executeWithBrokerDown() throws Exception {
        broker.shutdown();
        String message = "value-234";
        Tuple tuple = generateTestTuple(message);
        bolt.execute(tuple);
        verify(collector).fail(tuple);
    }

    private boolean verifyMessage(String key, String message) {
        long lastMessageOffset = KafkaUtils.getOffset(simpleConsumer, kafkaConfig.topic, 0, OffsetRequest.LatestTime()) - 1;
        ByteBufferMessageSet messageAndOffsets = KafkaUtils.fetchMessages(kafkaConfig, simpleConsumer,
                new Partition(Broker.fromString(broker.getBrokerConnectionString()), 0), lastMessageOffset);
        MessageAndOffset messageAndOffset = messageAndOffsets.iterator().next();
        Message kafkaMessage = messageAndOffset.message();
        ByteBuffer messageKeyBuffer = kafkaMessage.key();
        String keyString = null;
        String messageString = new String(Utils.toByteArray(kafkaMessage.payload()));
        if (messageKeyBuffer != null) {
            keyString = new String(Utils.toByteArray(messageKeyBuffer));
        }
        assertEquals(key, keyString);
        assertEquals(message, messageString);
        return true;
    }

    private Tuple generateTestTuple(Object key, Object message) {
        TopologyBuilder builder = new TopologyBuilder();
        GeneralTopologyContext topologyContext = new GeneralTopologyContext(builder.createTopology(), new Config(), new HashMap(), new HashMap(), new HashMap(), "") {
            @Override
            public Fields getComponentOutputFields(String componentId, String streamId) {
                return new Fields("key", "message");
            }
        };
        return new TupleImpl(topologyContext, new Values(key, message), 1, "");
    }

    private Tuple generateTestTuple(Object message) {
        TopologyBuilder builder = new TopologyBuilder();
        GeneralTopologyContext topologyContext = new GeneralTopologyContext(builder.createTopology(), new Config(), new HashMap(), new HashMap(), new HashMap(), "") {
            @Override
            public Fields getComponentOutputFields(String componentId, String streamId) {
                return new Fields("message");
            }
        };
        return new TupleImpl(topologyContext, new Values(message), 1, "");
    }

    private Tuple mockTickTuple() {
        Tuple tuple = mock(Tuple.class);
        when(tuple.getSourceComponent()).thenReturn(Constants.SYSTEM_COMPONENT_ID);
        when(tuple.getSourceStreamId()).thenReturn(Constants.SYSTEM_TICK_STREAM_ID);
        // Sanity check
        assertTrue(TupleUtils.isTick(tuple));
        return tuple;
    }
}

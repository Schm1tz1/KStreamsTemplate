package com.github.schm1tz1;

import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamsPipelineTest {

    private static final String INPUT_TOPIC = "input-topic";
    private static final String OUTPUT_TOPIC = "output-topic";

    private TopologyTestDriver testDriver;
    private TestInputTopic<String, String> inputTopic;
    private TestOutputTopic<String, String> outputTopic;

    @BeforeEach
    void setUp() {
        Properties properties = PipelineConfigTools.configureStreamsProperties();
        properties.setProperty("streamsApp.inputTopic", INPUT_TOPIC);
        properties.setProperty("streamsApp.outputTopic", OUTPUT_TOPIC);

        Topology topology = new StreamsPipeline(properties).createStreamsTopology();
        testDriver = new TopologyTestDriver(topology, properties);

        inputTopic = testDriver.createInputTopic(INPUT_TOPIC, Serdes.String().serializer(), Serdes.String().serializer());
        outputTopic = testDriver.createOutputTopic(OUTPUT_TOPIC, Serdes.String().deserializer(), Serdes.String().deserializer());
    }

    @AfterEach
    void tearDown() {
        testDriver.close();
    }

    @Test
    void passesRecordsThroughUnchanged() {
        inputTopic.pipeInput("key1", "value1");

        assertEquals("value1", outputTopic.readValue());
        assertTrue(outputTopic.isEmpty());
    }

    @Test
    void preservesKeyAndOrderingAcrossMultipleRecords() {
        inputTopic.pipeInput("key1", "value1");
        inputTopic.pipeInput("key2", "value2");

        var first = outputTopic.readKeyValue();
        var second = outputTopic.readKeyValue();

        assertEquals("key1", first.key);
        assertEquals("value1", first.value);
        assertEquals("key2", second.key);
        assertEquals("value2", second.value);
        assertTrue(outputTopic.isEmpty());
    }
}

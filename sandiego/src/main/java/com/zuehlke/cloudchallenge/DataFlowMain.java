package com.zuehlke.cloudchallenge;

import com.google.api.services.bigquery.model.TableFieldSchema;
import com.google.api.services.bigquery.model.TableSchema;
import com.zuehlke.cloudchallenge.dataFlow.*;
import org.apache.beam.runners.dataflow.options.DataflowPipelineOptions;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubIO;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.windowing.*;
import org.apache.beam.sdk.values.PCollection;
import org.joda.time.Duration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DataFlowMain {

    public interface DataFlowOptions extends DataflowPipelineOptions {
        @Description("the topic to consume messages from")
        @Default.String("request-t1-europe-north1")
        String getRequestTopic();

        void setRequestTopic(String requestTopic);

        @Description("the topic to push messages to")
        @Default.String("response-t1-europe-north1")
        String getResponseTopic();

        void setResponseTopic(String responseTopic);
    }

    public static void main(String[] args) {
        for (int i = 0; i < args.length; i++) {
            System.out.println("argument " + i + " : " + args[i]);
        }

        DataFlowOptions options = PipelineOptionsFactory.fromArgs(args).withValidation().as(DataFlowOptions.class);
        PipelineOptionsFactory.register(DataFlowOptions.class);

        options.setStreaming(true);
       // options.setFilesToStage(Collections.emptyList());

        Pipeline p = Pipeline.create(options);

        String requestTopic = "projects/" + options.getProject() + "/topics/" + options.getRequestTopic();
        String responseTopic = "projects/" + options.getProject() + "/topics/" + options.getResponseTopic();
        System.out.println("RequestTopic: " + requestTopic);

        PCollection<FlightMessageDto> currentFlightMessages = p
                .apply("GetMessages", PubsubIO.readStrings().fromTopic(requestTopic))
               // .apply("SetWindowing", assignMessageWindowFn())
                .apply("ExtractData", ParDo.of(new DataExtractor()));

        /*
        currentFlightMessages.apply("Add word count", ParDo.of(new WordCount()))
                .apply("WriteToPubSub", PubsubIO.writeAvros(ProcessedFlightMessageDto.class).to(responseTopic));
*/
        String table = options.getProject() + ":flight_messages.raw_flight_messages";
        List<TableFieldSchema> fields = new ArrayList<>();
        fields.add(new TableFieldSchema().setName("timestamp").setType("TIMESTAMP"));
        fields.add(new TableFieldSchema().setName("flight_number").setType("STRING"));
        fields.add(new TableFieldSchema().setName("airport").setType("STRING"));
        fields.add(new TableFieldSchema().setName("message").setType("STRING"));

        TableSchema schema = new TableSchema().setFields(fields);

        currentFlightMessages
                .apply("WriteBigQueryRow", ParDo.of(new BigQueryRowWriter()))
                .apply(BigQueryIO.writeTableRows().to(table)//
                        .withSchema(schema)//
                        .withWriteDisposition(BigQueryIO.Write.WriteDisposition.WRITE_APPEND)
                        .withCreateDisposition(BigQueryIO.Write.CreateDisposition.CREATE_IF_NEEDED));

        PipelineResult result = p.run();
        result.waitUntilFinish();
    }

    private static Window<String> assignMessageWindowFn() {
        return Window.<String>into(FixedWindows.of(Duration.standardSeconds(5)))
                .withAllowedLateness(Duration.standardDays(1))
                .triggering(AfterWatermark.pastEndOfWindow()
                        .withEarlyFirings(AfterPane.elementCountAtLeast(1))
                        .withLateFirings(AfterFirst.of(
                                AfterPane.elementCountAtLeast(1),
                                AfterProcessingTime.pastFirstElementInPane()
                                        .plusDelayOf(Duration.standardSeconds(1))
                        )))
                .discardingFiredPanes();
    }

}

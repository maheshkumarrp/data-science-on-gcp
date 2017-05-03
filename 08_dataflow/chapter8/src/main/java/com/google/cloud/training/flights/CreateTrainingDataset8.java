/*
 * Copyright (C) 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.training.flights;

import java.util.Arrays;
import java.util.Map;

import org.apache.beam.runners.dataflow.options.DataflowPipelineOptions;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.Mean;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.View;
import org.apache.beam.sdk.transforms.windowing.SlidingWindows;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionView;
import org.joda.time.Duration;

import com.google.cloud.training.flights.Flight.INPUTCOLS;

/**
 * Adds in average arrival delays
 * 
 * @author vlakshmanan
 *
 */
public class CreateTrainingDataset8 {

  public static interface MyOptions extends DataflowPipelineOptions {
    @Description("Path of the file to read from")
    @Default.String("/Users/vlakshmanan/data/flights/small.csv")
    String getInput();

    void setInput(String s);

    @Description("Path of the output directory")
    @Default.String("/tmp/output/")
    String getOutput();

    void setOutput(String s);

    @Description("Path of trainday.csv")
    @Default.String("gs://cloud-training-demos-ml/flights/trainday.csv")
    String getTraindayCsvPath();

    void setTraindayCsvPath(String s);
  }

  @SuppressWarnings("serial")
  public static void main(String[] args) {
    MyOptions options = PipelineOptionsFactory.fromArgs(args).withValidation().as(MyOptions.class);
    options.setStreaming(true);
    Pipeline p = Pipeline.create(options);

    // read traindays.csv into memory for use as a side-input
    PCollectionView<Map<String, String>> traindays = getTrainDays(p, options.getTraindayCsvPath());

    String[] events = {
        "2015-09-21,AA,19805,AA,1572,13303,1330303,32467,MIA,12889,1288903,32211,LAS,2015-09-20T22:50:00,2015-09-20T04:03:00,313.00,19.00,2015-09-20T04:22:00,,,2015-09-21T04:08:00,,,0.00,,,2174.00,25.79527778,-80.29000000,-14400.0,36.08000000,-115.15222222,-25200.0,wheelsoff,2015-09-20T04:22:00",
        "2015-09-21,AA,19805,AA,2495,11298,1129804,30194,DFW,12892,1289203,32575,LAX,2015-09-21T01:25:00,2015-09-20T06:04:00,279.00,15.00,2015-09-20T06:19:00,,,2015-09-21T04:55:00,,,0.00,,,1235.00,32.89722222,-97.03777778,-18000.0,33.94250000,-118.40805556,-25200.0,wheelsoff,2015-09-20T06:19:00",
        "2015-09-21,AA,19805,AA,2495,11298,1129804,30194,DFW,12892,1289203,32575,LAX,2015-09-21T01:25:00,2015-09-20T06:04:00,279.00,15.00,2015-09-20T06:19:00,2015-09-20T09:10:00,5.00,2015-09-21T04:55:00,2015-09-20T09:15:00,260.00,0.00,,0.00,1235.00,32.89722222,-97.03777778,-18000.0,33.94250000,-118.40805556,-25200.0,arrived,2015-09-20T09:15:00" };

    PCollection<Flight> flights = p //
        .apply("ReadLines",
            // TextIO.Read.from(options.getInput())) //
            Create.of(Arrays.asList(events))) //
        .apply("ParseFlights", ParDo.of(new DoFn<String, Flight>() {
          @ProcessElement
          public void processElement(ProcessContext c) throws Exception {
            String line = c.element();
            Flight f = Flight.fromCsv(line);
            if (f != null) {
              c.outputWithTimestamp(f, f.getEventTimestamp());
            }
          }
        })) //
        .apply("TrainOnly", ParDo.withSideInputs(traindays).of(new DoFn<Flight, Flight>() {
          @ProcessElement
          public void processElement(ProcessContext c) throws Exception {
            Flight f = c.element();
            String date = f.getField(Flight.INPUTCOLS.FL_DATE);
            boolean isTrainDay = c.sideInput(traindays).containsKey(date);
            if (isTrainDay) {
              c.output(f);
            }
          }
        })) //
        .apply("GoodFlights", ParDo.of(new DoFn<Flight, Flight>() {
          @ProcessElement
          public void processElement(ProcessContext c) throws Exception {
            Flight f = c.element();
            if (f.isNotCancelled() && f.isNotDiverted()) {
              c.output(f);
            }
          }
        }));

    PCollection<Flight> lastHourFlights = //
        flights.apply(Window.into(SlidingWindows//
        .of(Duration.standardHours(1))//
        .every(Duration.standardMinutes(5))));

    
    PCollection<KV<String, Double>> depDelays = flights
        .apply("airport:hour->depdelay", ParDo.of(new DoFn<Flight, KV<String, Double>>() {

          @ProcessElement
          public void processElement(ProcessContext c) throws Exception {
            Flight f = c.element();
            if (f.getField(Flight.INPUTCOLS.EVENT).equals("wheelsoff")) {
              String key = f.getField(Flight.INPUTCOLS.ORIGIN) + ":" + f.getDepartureHour();
              double value = f.getFieldAsFloat(Flight.INPUTCOLS.DEP_DELAY)
                  + f.getFieldAsFloat(Flight.INPUTCOLS.TAXI_OUT);
              c.output(KV.of(key, value));
            }
          }

        })) //
        .apply("avgDepDelay", Mean.perKey());
    
    PCollection<KV<String, Double>> arrDelays = lastHourFlights
        .apply("airport->arrdelay", ParDo.of(new DoFn<Flight, KV<String, Double>>() {

          @ProcessElement
          public void processElement(ProcessContext c) throws Exception {
            Flight f = c.element();
            if (f.getField(Flight.INPUTCOLS.EVENT).equals("arrived")) {
              String key = f.getField(Flight.INPUTCOLS.DEST);
              double value = f.getFieldAsFloat(Flight.INPUTCOLS.ARR_DELAY);
              c.output(KV.of(key, value));
            }
          }

        })) //
        .apply("avgArrDelay", Mean.perKey());

    PCollectionView<Map<String, Double>> avgDepDelay = depDelays.apply("depdelay->map", View.asMap());
    PCollectionView<Map<String, Double>> avgArrDelay = arrDelays.apply("arrdelay->map", View.asMap());
    
    flights = lastHourFlights.apply("AddDelayInfo", ParDo.withSideInputs(avgDepDelay, avgArrDelay).of(new DoFn<Flight, Flight>() {

      @ProcessElement
      public void processElement(ProcessContext c) throws Exception {
        Flight f = c.element().newCopy();
        String depKey = f.getField(Flight.INPUTCOLS.ORIGIN) + ":" + f.getDepartureHour();
        Double depDelay = c.sideInput(avgDepDelay).get(depKey);
        String arrKey = f.getField(Flight.INPUTCOLS.DEST);
        Double arrDelay = c.sideInput(avgArrDelay).get(arrKey);
        f.avgDepartureDelay = (float) ((depDelay == null) ? 0 : depDelay);
        f.avgArrivalDelay = (float) ((arrDelay == null) ? 0 : arrDelay);
        c.output(f);
      }

    }));
    
    depDelays.apply("DepDelayToCsv", ParDo.of(new DoFn<KV<String, Double>, String>() {
      @ProcessElement
      public void processElement(ProcessContext c) throws Exception {
        KV<String, Double> kv = c.element();
        c.output(kv.getKey() + "," + kv.getValue());
      }
    })) //
        .apply("WriteDepDelays", TextIO.Write.to(options.getOutput() + "delays8").withSuffix(".csv").withoutSharding()); 

    flights.apply("ToCsv", ParDo.of(new DoFn<Flight, String>() {
      @ProcessElement
      public void processElement(ProcessContext c) throws Exception {
        Flight f = c.element();
        if (f.getField(INPUTCOLS.EVENT).equals("arrived")) {
          c.output(f.toTrainingCsv());
        }
      }
    })) //
        .apply("WriteFlights", TextIO.Write.to(options.getOutput() + "flights8").withSuffix(".csv").withoutSharding());

    p.run();
  }

  @SuppressWarnings("serial")
  private static PCollectionView<Map<String, String>> getTrainDays(Pipeline p, String path) {
    return p.apply("Read trainday.csv", TextIO.Read.from(path)) //
        .apply("Parse trainday.csv", ParDo.of(new DoFn<String, KV<String, String>>() {
          @ProcessElement
          public void processElement(ProcessContext c) throws Exception {
            String line = c.element();
            String[] fields = line.split(",");
            if (fields.length > 1 && "True".equals(fields[1])) {
              c.output(KV.of(fields[0], "")); // ignore value
            }
          }
        })) //
        .apply("toView", View.asMap());
  }
}

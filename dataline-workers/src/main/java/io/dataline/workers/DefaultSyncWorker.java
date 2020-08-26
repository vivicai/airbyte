/*
 * MIT License
 *
 * Copyright (c) 2020 Dataline
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.dataline.workers;

import io.dataline.config.SingerProtocol;
import io.dataline.config.StandardSyncInput;
import io.dataline.config.StandardSyncOutput;
import io.dataline.config.StandardSyncSummary;
import io.dataline.config.StandardTapConfig;
import io.dataline.config.StandardTargetConfig;
import io.dataline.config.State;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.UUID;
import java.util.function.Consumer;
import org.apache.commons.lang3.mutable.MutableLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultSyncWorker implements SyncWorker {
  private static final Logger LOGGER = LoggerFactory.getLogger(DefaultSyncWorker.class);

  public static final String TAP_ERR_LOG = "tap_err.log";
  public static final String TARGET_ERR_LOG = "target_err.log";

  private final SyncTap<SingerProtocol> tap;
  private final SyncTarget<SingerProtocol> target;

  public DefaultSyncWorker(SyncTap<SingerProtocol> tap, SyncTarget<SingerProtocol> target) {
    this.tap = tap;
    this.target = target;
  }

  @Override
  public OutputAndStatus<StandardSyncOutput> run(StandardSyncInput syncInput, Path workspacePath)
      throws InvalidCredentialsException, InvalidCatalogException {
    long startTime = System.currentTimeMillis();

    final StandardTapConfig tapConfig = new StandardTapConfig();
    tapConfig.setSourceConnectionImplementation(syncInput.getSourceConnectionImplementation());
    tapConfig.setStandardSync(syncInput.getStandardSync());

    final StandardTargetConfig targetConfig = new StandardTargetConfig();
    targetConfig.setDestinationConnectionImplementation(
        syncInput.getDestinationConnectionImplementation());
    targetConfig.setStandardSync(syncInput.getStandardSync());

    final MutableLong recordCount = new MutableLong();
    Consumer<SingerProtocol> counter =
        record -> {
          if (record.getType().equals(SingerProtocol.Type.RECORD)) {
            recordCount.increment();
          }
        };

    final State state;
    try (tap) {
      final Iterator<SingerProtocol> iterator = tap.run(tapConfig, workspacePath);

      Iterator<SingerProtocol> countingIterator = new ConsumerIterator<>(iterator, counter);
      try (target) {
        state = target.run(countingIterator, targetConfig, workspacePath);
      }

    } catch (Exception e) {
      LOGGER.debug(
          "Sync worker failed. Tap error log: {}.\n Target error log: {}",
          WorkerUtils.readFileFromWorkspace(workspacePath, TAP_ERR_LOG),
          WorkerUtils.readFileFromWorkspace(workspacePath, TARGET_ERR_LOG));

      return new OutputAndStatus<>(JobStatus.FAILED, null);
    }

    StandardSyncSummary summary = new StandardSyncSummary();
    summary.setRecordsSynced(recordCount.getValue());
    summary.setStartTime(startTime);
    summary.setEndTime(System.currentTimeMillis());
    summary.setJobId(UUID.randomUUID()); // TODO this is not input anywhere
    // TODO set logs

    final StandardSyncOutput output = new StandardSyncOutput();
    output.setStandardSyncSummary(summary);
    output.setState(state);

    return new OutputAndStatus<>(JobStatus.SUCCESSFUL, output);
  }

  @Override
  public void cancel() {
    try {
      tap.close();
      target.close();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
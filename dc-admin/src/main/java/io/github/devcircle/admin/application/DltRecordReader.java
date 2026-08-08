package io.github.devcircle.admin.application;

interface DltRecordReader {
    DltRecord read(String sourceTopic, int partition, long offset);

    record DltRecord(String dltTopic, int partition, long offset, String originalTopic,
                     String key, String value) {
    }
}

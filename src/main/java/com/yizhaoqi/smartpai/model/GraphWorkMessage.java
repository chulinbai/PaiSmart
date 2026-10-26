package com.yizhaoqi.smartpai.model;

/** Kafka contains identities only. The immutable work payload remains in MySQL. */
public record GraphWorkMessage(String eventId, String jobId) {}


package com.cheatbreaker.client.diagnostics;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticLogTest {
   @TempDir
   Path directory;

   @Test
   void rotatesWithinFourFilesAndRetainsNewestRecords() throws Exception {
      Path path = this.directory.resolve("connection.jsonl");
      String payload = String.join("", Collections.nCopies(200, "x"));
      try (DiagnosticLog log = new DiagnosticLog(path, 512)) {
         for (int index = 0; index < 12; index++) log.offer(ConnectionDiagnostics.fields("sequence", index, "padding", payload));
      }
      assertTrue(Files.exists(path.resolveSibling("connection.jsonl.3")));
      assertFalse(Files.exists(path.resolveSibling("connection.jsonl.4")));
      for (int index = 0; index < 4; index++) {
         Path segment = path;
         if (index > 0) segment = path.resolveSibling("connection.jsonl." + index);
         assertTrue(Files.size(segment) <= 512);
         for (String line : Files.readAllLines(segment, StandardCharsets.UTF_8)) {
            assertTrue(com.google.gson.JsonParser.parseString(line).isJsonObject());
         }
      }
      String latest = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
      assertTrue(latest.contains("\"sequence\":11"));
      assertFalse(latest.contains("\"sequence\":0,"));
   }

   @Test
   void boundsBacklogAndCountsDroppedRecords() {
      Path path = this.directory.resolve("connection.jsonl");
      String payload = String.join("", Collections.nCopies(8192, "x"));
      Map<String, Object> record = ConnectionDiagnostics.fields("padding", payload);
      try (DiagnosticLog log = new DiagnosticLog(path)) {
         for (int index = 0; index < 8000; index++) log.offer(record);
         assertTrue(log.pending() <= 1024);
         assertTrue(log.dropped.get() > 0, "A full logging queue must drop records instead of blocking the network thread");
      }
   }

   @Test
   void countsFileFailuresWithoutThrowingIntoTheProducer() throws Exception {
      Path occupied = this.directory.resolve("not-a-directory");
      Files.write(occupied, new byte[]{1});
      DiagnosticLog log = new DiagnosticLog(occupied.resolve("connection.jsonl"));
      log.offer(ConnectionDiagnostics.fields("event", "test"));
      log.close();
      assertEquals(1, log.failures.get());
      assertEquals(1, log.dropped.get());
   }
}

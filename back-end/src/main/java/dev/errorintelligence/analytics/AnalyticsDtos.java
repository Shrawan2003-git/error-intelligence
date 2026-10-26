package dev.errorintelligence.analytics;

import java.util.List;

public final class AnalyticsDtos {
  private AnalyticsDtos() {}

  public record Overview(
      int days,
      long eventCount,
      long incidentCount,
      long openCount,
      long acknowledgedCount,
      long resolvedCount,
      double resolutionRate,
      List<DailyCount> dailyEvents,
      List<TopIncident> topIncidents) {}

  public record DailyCount(String date, long count) {}

  public record TopIncident(String title, long occurrenceCount, String status) {}
}

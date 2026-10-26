-- Keep per-organization daily admission checks bounded as analysis history grows.
CREATE INDEX incident_ai_analysis_daily_quota
    ON intelligence.incident_ai_analyses (organization_id, created_at);

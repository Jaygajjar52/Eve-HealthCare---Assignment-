-- ===========================================================================
--  V3__city_index_case_insensitive.sql
--
--  V1 created a plain btree index on diagnostic_centres(city), but the query
--  behind GET /centres?city= is case-insensitive. Spring Data turns
--  findByCityIgnoreCase into:
--
--      WHERE lower(city) = lower(?)
--
--  and the planner cannot use a plain index for that, because the value in the
--  index is the raw city, not lower(city). Verified with EXPLAIN: the old index
--  was never chosen.
--
--  An expression index over lower(city) matches the expression in the query, so
--  the planner can use it. Verified with EXPLAIN after enable_seqscan=off:
--
--      Index Scan using idx_diagnostic_centres_city_lower on diagnostic_centres
--        Index Cond: (lower((city)::text) = 'bengaluru'::text)
--
--  At three seeded rows PostgreSQL will still choose a sequential scan, which
--  is correct: scanning three rows beats any index. The index is here so the
--  plan stays right once the table actually grows.
-- ===========================================================================

DROP INDEX idx_diagnostic_centres_city;

CREATE INDEX idx_diagnostic_centres_city_lower ON diagnostic_centres (lower(city));
-- ===========================================================================
--  V2__seed_data.sql  -  catalogue data plus one ADMIN account
--
--  Runs once, on an empty database. Fixed UUIDs are used on purpose so the
--  README curl examples and the tests can refer to a known centre id.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- Diagnostic tests (catalogue)
-- ---------------------------------------------------------------------------
INSERT INTO diagnostic_tests (id, name, description) VALUES
('a1111111-1111-1111-1111-111111111111', 'Complete Blood Count (CBC)',
 'Haemoglobin, WBC and platelet count. Used to screen for anaemia and infection.'),

('a2222222-2222-2222-2222-222222222222', 'Lipid Profile',
 'Total cholesterol, HDL, LDL and triglycerides. Screens for cardiovascular risk.'),

('a3333333-3333-3333-3333-333333333333', 'Thyroid Function Test',
 'TSH, T3 and T4 levels. Screens for hypo- and hyper-thyroidism.'),

('a4444444-4444-4444-4444-111111111111', 'MRI - Brain (Plain)',
 'Magnetic resonance imaging of the brain without contrast.'),

('a5555555-5555-5555-5555-111111111111', 'Chest X-Ray',
 'Single view radiograph of the chest. Screens for lung and heart conditions.');

-- ---------------------------------------------------------------------------
-- Diagnostic centres (two in Bengaluru so ?city= has something to filter)
-- ---------------------------------------------------------------------------
INSERT INTO diagnostic_centres (id, name, city, address) VALUES
('11111111-1111-1111-1111-111111111111', 'EVE Health - Andheri Diagnostics Centre',
 'Mumbai', 'Andheri West, Mumbai, Maharashtra 400058'),

('22222222-2222-2222-2222-222222222222', 'EVE Health - Koramangala Imaging Centre',
 'Bengaluru', '4th Block, Koramangala, Bengaluru, Karnataka 560034'),

('33333333-3333-3333-3333-333333333333', 'EVE Health - HSR Layout Scan Centre',
 'Bengaluru', 'Sector 2, HSR Layout, Bengaluru, Karnataka 560102');

-- ---------------------------------------------------------------------------
-- Which centre offers which test, and at what price.
--
-- Note that the same test has a different price per centre (CBC is 350.00 in
-- Andheri and 300.00 in Koramangala). That is exactly why a booking must store
-- its own amount instead of joining to the price row at read time.
-- ---------------------------------------------------------------------------
INSERT INTO centre_tests (id, centre_id, test_id, price) VALUES
-- Andheri, Mumbai
('c1111111-1111-1111-1111-111111111111', '11111111-1111-1111-1111-111111111111',
 'a1111111-1111-1111-1111-111111111111', 350.00),
('c2222222-2222-2222-2222-111111111111', '11111111-1111-1111-1111-111111111111',
 'a2222222-2222-2222-2222-222222222222', 650.00),
('c3333333-3333-3333-3333-111111111111', '11111111-1111-1111-1111-111111111111',
 'a5555555-5555-5555-5555-111111111111', 450.00),

-- Koramangala, Bengaluru
('c4444444-4444-4444-4444-222222222222', '22222222-2222-2222-2222-222222222222',
 'a1111111-1111-1111-1111-111111111111', 300.00),
('c5555555-5555-5555-5555-222222222222', '22222222-2222-2222-2222-222222222222',
 'a2222222-2222-2222-2222-222222222222', 700.00),
('c6666666-6666-6666-6666-222222222222', '22222222-2222-2222-2222-222222222222',
 'a4444444-4444-4444-4444-111111111111', 4500.00),

-- HSR Layout, Bengaluru
('c7777777-7777-7777-7777-333333333333', '33333333-3333-3333-3333-333333333333',
 'a3333333-3333-3333-3333-333333333333', 500.00),
('c8888888-8888-8888-8888-333333333333', '33333333-3333-3333-3333-333333333333',
 'a5555555-5555-5555-5555-111111111111', 400.00);

-- ---------------------------------------------------------------------------
-- Admin account
--
--   email    : admin@evehealth.com
--   password : Admin@123
--
-- The password was hashed with Spring Security's BCryptPasswordEncoder, the
-- same encoder the application uses, and the hash below was verified to match.
-- Credentials are only safe because this is a local demo database - in a real
-- deployment this row would come from a migration, an admin bootstrap endpoint,
-- or an identity provider instead.
-- ---------------------------------------------------------------------------
INSERT INTO users (id, name, email, password_hash, role) VALUES
('d0000000-0000-0000-0000-000000000001', 'EVE Admin',
 'admin@evehealth.com',
 '$2a$10$8zvwlK2o7VehrBK5/m5kaeEWTmISj/xHIUn2BiCw1q3Tw6GTtzSFe',
 'ADMIN');